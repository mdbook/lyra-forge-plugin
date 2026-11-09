package mtgai.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.item.PaperCard;
import forge.util.MyRandom;
import mtgai.controller.Control;
import mtgai.controller.LobbyPlayerExternal;
import mtgai.controller.PlayerControllerExternal;
import mtgai.controller.SeatStats;
import mtgai.controller.WatchedAiController;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionRequest;
import mtgai.decision.RandomOracle;
import mtgai.encode.ObservationEncoder;
import mtgai.match.GameRecord;
import mtgai.match.MatchAbort;
import mtgai.match.Watchdog;

import static mtgai.search.PimcOracle.STATS;
import static mtgai.search.PimcOracle.mix;

/**
 * The per-simulation machinery of a search seat (one per seat per game), shared by the search oracles: the copy point
 * ({@link Anchor}: new at a priority window / declaration, kept while the seat's answers since it are replayed) and one
 * sim = copy the copy point, {@link Determinizer} deal, replay trail + root ({@link RolloutOracle} forced steps), roll
 * out to the horizon, leaf value. Counters are kept in {@link PimcOracle#STATS}.
 */
final class SimRunner {
    /** The copy point the current decision sequence replays from. */
    static final class Anchor {
        DecisionKind kind;
        GameCopies.Rewind rewind;
        GameCopies.PhaseState ps;
        Game[] games;
        /**
         * Network rollouts: the live seat's encoder at the copy point, one per copy (bound to that copy's seat); null when
         * the seat has no encoder. Each sim's copy seat adopts its state ({@link ObservationEncoder#adoptState}).
         */
        ObservationEncoder[] encoders;
        int turn;
        forge.game.phase.PhaseType phase;
        Set<Integer> pinned;
        /** The seat's answers since the copy point, replayed before the root. */
        final List<RolloutOracle.Step> trail = new ArrayList<>();
    }

    private final PimcOracle.Config cfg;
    private final MaterialEval eval = new MaterialEval();
    private PlayerControllerExternal ctl;
    private Determinizer determinizer;
    Anchor anchor;

    SimRunner(PimcOracle.Config cfg) {
        this.cfg = cfg;
    }

    void attach(PlayerControllerExternal controller) {
        if (ctl == null) {
            ctl = controller;
        }
    }

    boolean attached() {
        return ctl != null;
    }

    void onGameStart() {
        anchor = null;
        determinizer = null;
        gateSkippedBlockTurn = -1;
        gateSkippedBlockPhase = null;
    }

    /** The seat answered {@code chosen} to {@code req}: replayed before the root of later sims from this copy point. */
    void recordAnswer(DecisionRequest req, int chosen) {
        if (anchor != null) {
            anchor.trail.add(new RolloutOracle.Step(req.kind(), req.candidates().get(chosen).toString()));
        }
    }

    // ------------------------------------------------------------------ worker pool (shared)

    private static final Map<Integer, ExecutorService> POOLS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_N = new AtomicInteger();

    private static ExecutorService pool(int threads) {
        return POOLS.computeIfAbsent(threads, n -> Executors.newFixedThreadPool(n, r -> {
            // "Game" prefix: ThreadUtil.isGameThread(); the engine needs the game threads' deep stack
            final Thread t = new Thread(null, r, "Game-pimc-" + THREAD_N.incrementAndGet(), 8L << 20);
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Runs the tasks ({candidate, j}) round-robin over the anchor's copies (one worker per copy, on a shared pool when
     * there is more than one); one {candidate, value-or-NaN, j} per sim, worker by worker. A sim is a pure function of
     * (decision point, candidate, j, dseed), so the values do not depend on the thread count.
     */
    List<double[]> runTasks(DecisionRequest req, Anchor a, List<int[]> tasks, long dseed, Map<String, Integer> fails) {
        final int workers = Math.min(a.games.length, tasks.size());
        final List<List<int[]>> perWorker = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            perWorker.add(new ArrayList<>());
        }
        for (int t = 0; t < tasks.size(); t++) {
            perWorker.get(t % workers).add(tasks.get(t));
        }
        final List<double[]> results = new ArrayList<>();
        if (workers == 1) {
            results.addAll(runWorker(req, a, a.games[0], perWorker.get(0), dseed, fails));
        } else {
            final ExecutorService pool = pool(cfg.threads);
            final List<Future<List<double[]>>> fut = new ArrayList<>();
            final List<Map<String, Integer>> wf = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final Game g = a.games[w];
                final List<int[]> mine = perWorker.get(w);
                final Map<String, Integer> f = new HashMap<>();
                wf.add(f);
                fut.add(pool.submit(() -> runWorker(req, a, g, mine, dseed, f)));
            }
            final long timeoutMs = 30_000L + cfg.rolloutWallMs * (tasks.size() / workers + 1);
            for (int w = 0; w < workers; w++) {
                try {
                    results.addAll(fut.get(w).get(timeoutMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    fut.get(w).cancel(true);
                    STATS.fail("timeout", e);
                    fails.merge("timeout", perWorker.get(w).size(), Integer::sum);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (java.util.concurrent.ExecutionException e) {
                    STATS.fail("worker", e.getCause());
                    fails.merge("worker", perWorker.get(w).size(), Integer::sum);
                }
                wf.get(w).forEach((k, v) -> fails.merge(k, v, Integer::sum));
            }
        }
        return results;
    }

    /** Runs one worker's sims on its own copy of the decision point; one {candidate, value-or-NaN, j} per sim. */
    List<double[]> runWorker(DecisionRequest req, Anchor a, Game base, List<int[]> mine, long dseed,
                             Map<String, Integer> fails) {
        final List<double[]> out = new ArrayList<>(mine.size());
        ObservationEncoder snapshot = null; // the live seat's encoder at the copy point, on this worker's copy of it
        if (a.encoders != null) {
            for (int i = 0; i < a.games.length; i++) {
                if (a.games[i] == base) {
                    snapshot = a.encoders[i];
                }
            }
        }
        final Random saved = MyRandom.getRandom();
        try {
            for (int[] t : mine) {
                double v;
                try {
                    v = sim(req, a, base, snapshot, t[0], t[1], dseed, fails);
                } catch (OutOfMemoryError e) {
                    throw e;
                } catch (Throwable e) {
                    STATS.fail("exception", e);
                    fails.merge("exception", 1, Integer::sum);
                    v = Double.NaN;
                }
                out.add(new double[] {t[0], v, t[1]});
            }
        } finally {
            MyRandom.setRandom(saved);
        }
        return out;
    }

    /** One rollout: copy, determinise, replay the trail + root, roll out, evaluate. NaN = failed. */
    private double sim(DecisionRequest req, Anchor a, Game base, ObservationEncoder snapshot, int cand, int j, long dseed,
                       Map<String, Integer> fails) {
        final long seed = mix(dseed, j, 0x51L);
        final int mySeat = ctl.seat();
        final List<RolloutOracle.Step> forced = new ArrayList<>(a.trail);
        forced.add(new RolloutOracle.Step(req.kind(), req.candidates().get(cand).toString()));
        final boolean forgeAi = cfg.rollout == PimcOracle.Rollout.FORGE_AI;
        final int decisionCap = cfg.depthDecisions > 0 ? forced.size() + cfg.depthDecisions : 0;
        final boolean net = cfg.rollout == PimcOracle.Rollout.NETWORK;
        final int turnCap = a.turn + cfg.depthTurns;
        // NETWORK: the horizon is NetLeaf's (it forwards the first request at / after it, then stops the rollout); the
        // watchdog's own caps are only a backstop a few turns / decisions later, for a game with no such request
        final Watchdog wd = net ? new Watchdog(turnCap + 3, decisionCap > 0 ? decisionCap + 100 : 0, cfg.rolloutWallMs)
                : new Watchdog(turnCap, decisionCap, cfg.rolloutWallMs);
        final NetLeaf leaf = net ? new NetLeaf(cfg.net, wd, turnCap, decisionCap, mySeat) : null;
        final boolean[] root = {false};
        final PlayerControllerExternal[] myCtl = new PlayerControllerExternal[1];
        final RolloutOracle mine = new RolloutOracle(forced, forgeAi, seed * 31 + 1, () -> {
            root[0] = true;
            if (forgeAi) { // after the root: the stock AI, minus the seam's per-window enumeration and hint mapping
                myCtl[0].playStockFromNextWindow();
            }
        }, net ? leaf.seat(mySeat, seed * 31 + 1) : null);
        final java.util.function.BiConsumer<DecisionRequest, DecisionRequest> firstHook = PimcOracle.firstRequestHook;
        if (firstHook != null && a.trail.isEmpty()) { // the copy's first request is the live request (an anchor's own)
            mine.firstRequest = first -> firstHook.accept(req, first);
        }
        final PlayerControllerExternal.Exposure exposure = ctl.exposure();
        // The deciding seat owns the live seat's encoder 8 groups (Control): the copy must ask the same decision set, or
        // the trail / root cannot be replayed (with `mana` on, the live seat's MANA_SOURCE picks for its priority pick
        // never came up on a Control.NONE copy: every such search failed with mismatch / beforeRoot). After the root the
        // groups are answered by the rollout policy (Forge's hint until the stock hand-over, or random): consistently.
        // The opponent seat is never replayed, so it keeps Control.NONE: Forge's AI pays its mana, assigns its damage
        // and orders its triggers (cheaper, and stronger than random answers in the random rollout; irrelevant for the
        // stock Forge-AI opponent of the forge rollout).
        final Control control = ctl.control();
        final SeatStats[] simStats = {new SeatStats(), new SeatStats()}; // {deciding seat, NETWORK opponent}
        final LobbyPlayerExternal.CopyControllers ctrls = (p, lobby, s) -> {
            if (s == mySeat) {
                myCtl[0] = new PlayerControllerExternal(p.getGame(), p, lobby, mine, s, exposure, wd, new GameRecord(null),
                        simStats[0], control);
                return myCtl[0];
            }
            if (forgeAi) {
                return new WatchedAiController(p.getGame(), p, lobby, wd, new SeatStats());
            }
            if (net) { // the network was trained with the live groups on: its opponent seat owns them too. Its encoder
                // starts empty (the live opponent's memory is not known to us; see the NetLeaf doc)
                return new PlayerControllerExternal(p.getGame(), p, lobby, leaf.seat(s, seed * 31 + 2 + s),
                        s, exposure, wd, new GameRecord(null), simStats[1], control);
            }
            return new PlayerControllerExternal(p.getGame(), p, lobby, new RandomOracle(0.3, 0.85, seed * 31 + 2 + s),
                    s, exposure, wd, new GameRecord(null), new SeatStats(), Control.NONE);
        };
        final long c0 = System.nanoTime();
        final Game g;
        try {
            final Runnable hook = PimcOracle.simHook;
            if (hook != null) {
                hook.run();
            }
            g = GameCopies.copy(base, a.ps, ctrls, a.rewind);
            final long c1 = System.nanoTime();
            STATS.copyNanos.add(c1 - c0);
            final Determinizer.Result dr = determinizer.determinise(g, ctl.getPlayer().getId(), a.pinned, new Random(seed));
            STATS.determinised.increment();
            if (dr.mismatches > 0) {
                STATS.deckMismatches.add(dr.mismatches);
            }
            STATS.detNanos.add(System.nanoTime() - c1);
            if (snapshot != null && myCtl[0].encoder() != null) { // the copy seat's encoder = the live one at the copy point
                myCtl[0].encoder().adoptState(snapshot);
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            STATS.fail(e instanceof GameCopies.StackCopyException ? "stack" : "copy", e);
            fails.merge("copy", 1, Integer::sum);
            return Double.NaN;
        }
        MyRandom.setRandom(new Random(seed ^ 0x9E3779B97F4A7C15L));
        final long r0 = System.nanoTime();
        try {
            while (!g.isGameOver()) {
                g.getPhaseHandler().mainLoopStep();
            }
            STATS.simsGameOver.increment();
        } catch (MatchAbort stop) {
            if (stop.reason() == MatchAbort.Reason.WALL_CAP) {
                STATS.fail("wallCap", null);
                fails.merge("wallCap", 1, Integer::sum);
                // still a leaf: the state is valid, just less deep
            }
        } catch (RolloutOracle.Mismatch m) {
            STATS.fail("mismatch", m);
            fails.merge("mismatch", 1, Integer::sum);
            return Double.NaN;
        }
        if (!root[0]) {
            STATS.fail("beforeRoot", null);
            fails.merge("beforeRoot", 1, Integer::sum);
            return Double.NaN;
        }
        final long r1 = System.nanoTime();
        STATS.rolloutNanos.add(r1 - r0);
        STATS.simEnumNanos.add(simStats[0].enumNanos + simStats[1].enumNanos);
        STATS.simEncodeNanos.add(simStats[0].encodeNanos + simStats[1].encodeNanos);
        STATS.simsOk.increment();
        STATS.rolloutDecisions.add(wd.decisions());
        double v = eval.value(g, g.getPlayer(ctl.getPlayer().getId()));
        if (net && !g.isGameOver() && leaf.has()) {
            // 0.5 x material + 0.5 x the value head AT the horizon (NetLeaf); a finished game is its result
            v = leaf.mix(v);
            STATS.netLeaves.increment();
        }
        STATS.evalNanos.add(System.nanoTime() - r1);
        return v;
    }

    // ------------------------------------------------------------------ anchors

    /** Sets {@link #anchor} for this request (new copy point or the current one), or returns why it cannot. */
    String prepareAnchor(DecisionRequest req) {
        return prepareAnchor(req, null);
    }

    /** {@link #prepareAnchor(DecisionRequest, BooleanSupplier)}'s answer when the gate skipped a copy point. */
    static final String GATE = "gate";

    /** The declare-blockers step whose copy point the gate skipped (turn, phase): its later blocks are skipped too. */
    private int gateSkippedBlockTurn = -1;
    private forge.game.phase.PhaseType gateSkippedBlockPhase;

    /**
     * {@link #prepareAnchor(DecisionRequest)} with a gate (search targets, training mode): at a new copy point (PRIORITY,
     * ATTACK first pick, BLOCK_ATTACKER) the copy is made only if {@code gate} says yes; otherwise the anchor is cleared
     * (a later sub-choice never reuses a stale one) and {@link #GATE} returned. The later blocks of a skipped
     * declare-blockers step are skipped too (they would be new copy points with an empty trail: every sim a mismatch).
     * Sub-choices of a copy point the gate let through follow its anchor as usual. {@code gate} null = always.
     */
    String prepareAnchor(DecisionRequest req, java.util.function.BooleanSupplier gate) {
        final Game game = ctl.getGame();
        final Player me = ctl.getPlayer();
        final int turn = game.getPhaseHandler().getTurn();
        final DecisionKind k = req.kind();
        switch (k) {
            case MULLIGAN, MULLIGAN_BOTTOM -> {
                anchor = null;
                return "pre-game";
            }
            case PRIORITY -> {
                return copyPoint(k, GameCopies.Rewind.NONE, gate);
            }
            case ATTACK -> {
                final Combat c = game.getPhaseHandler().getCombat();
                final int declared = c == null ? -1 : c.getAttackers().size();
                if (anchor != null && anchor.kind == DecisionKind.ATTACK && anchor.turn == turn
                        && declared == anchor.trail.size()) {
                    return null;
                }
                if (declared != 0) {
                    anchor = null;
                    return "attack: no copy point";
                }
                return copyPoint(k, GameCopies.Rewind.TO_DECLARE_ATTACKERS, gate);
            }
            case BLOCK_ATTACKER, BLOCK_BLOCKER -> {
                final Combat c = game.getPhaseHandler().getCombat();
                if (anchor != null && anchor.kind == DecisionKind.BLOCK_ATTACKER && anchor.turn == turn
                        && anchor.phase == game.getPhaseHandler().getPhase()) {
                    return null;
                }
                boolean mine = false;
                if (c != null) {
                    for (Card b : c.getAllBlockers()) {
                        mine |= b.getController() == me;
                    }
                }
                if (k != DecisionKind.BLOCK_ATTACKER || c == null || mine) {
                    anchor = null;
                    return "block: no copy point";
                }
                final forge.game.phase.PhaseType phase = game.getPhaseHandler().getPhase();
                if (gate != null && gateSkippedBlockTurn == turn && gateSkippedBlockPhase == phase) {
                    anchor = null;
                    return GATE;
                }
                final String why = copyPoint(DecisionKind.BLOCK_ATTACKER, GameCopies.Rewind.TO_DECLARE_BLOCKERS, gate);
                if (GATE.equals(why)) {
                    gateSkippedBlockTurn = turn;
                    gateSkippedBlockPhase = phase;
                }
                return why;
            }
            default -> {
                if (anchor != null && anchor.kind == DecisionKind.PRIORITY && ctl.inPriorityPlay() && anchor.turn == turn
                        && !anchor.trail.isEmpty()) {
                    return null; // a target / mode / X / cost choice of the seat's own priority pick
                }
                anchor = null;
                return "not replayable (" + (ctl.inPriorityPlay() ? "in play" : "resolution/trigger") + ")";
            }
        }
    }

    /** A new copy point, unless {@code gate} (non-null) skips it: then the anchor is cleared and {@link #GATE} returned. */
    private String copyPoint(DecisionKind kind, GameCopies.Rewind rewind, java.util.function.BooleanSupplier gate) {
        if (gate != null && !gate.getAsBoolean()) {
            anchor = null;
            return GATE;
        }
        return newAnchor(kind, rewind);
    }

    private String newAnchor(DecisionKind kind, GameCopies.Rewind rewind) {
        anchor = null;
        final Game game = ctl.getGame();
        final String stack = GameCopies.stackProblem(game);
        if (stack != null) {
            return "stack: " + stack;
        }
        if (determinizer == null) {
            final Map<Integer, Map<String, Integer>> lists = new HashMap<>();
            final Map<String, PaperCard> paper = new HashMap<>();
            for (Player p : game.getPlayers()) {
                if (p != ctl.getPlayer() && p.getRegisteredPlayer() != null && p.getRegisteredPlayer().getDeck() != null) {
                    lists.put(p.getId(), Determinizer.decklist(p.getRegisteredPlayer().getDeck()));
                    paper.putAll(Determinizer.printings(p.getRegisteredPlayer().getDeck()));
                }
            }
            determinizer = new Determinizer(lists, paper);
        }
        final Anchor a = new Anchor();
        a.kind = kind;
        a.rewind = rewind;
        a.turn = game.getPhaseHandler().getTurn();
        a.phase = game.getPhaseHandler().getPhase();
        a.ps = GameCopies.PhaseState.capture(game);
        a.pinned = PimcOracle.pinned(game, ctl.getPlayer());
        final ObservationEncoder live = ctl.encoder();
        if (live != null) { // the seat knows where these own library cards are (scry / surveil): the deal keeps them there
            a.pinned.addAll(live.knownLibrary());
        }
        final int n = Math.max(1, Math.min(cfg.threads, cfg.budget));
        a.games = new Game[n];
        a.encoders = live == null ? null : new ObservationEncoder[n];
        final long c0 = System.nanoTime();
        try {
            for (int i = 0; i < n; i++) {
                // the copy point itself never runs: inert controllers (a random seat, never asked)
                a.games[i] = GameCopies.copy(game, a.ps, GameCopies.inert(), GameCopies.Rewind.NONE);
                if (live != null) {
                    a.encoders[i] = live.copyFor(a.games[i].getPlayer(ctl.getPlayer().getId()));
                }
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            STATS.fail(e instanceof GameCopies.StackCopyException ? "stack" : "copy", e);
            return e instanceof GameCopies.StackCopyException ? "stack: copy failed" : "copy failed";
        } finally {
            STATS.anchorNanos.add(System.nanoTime() - c0);
        }
        anchor = a;
        return null;
    }
}
