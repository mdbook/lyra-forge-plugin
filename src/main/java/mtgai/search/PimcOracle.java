package mtgai.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.ai.AiCache;
import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import mtgai.controller.PlayerControllerExternal;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.decision.RandomOracle;
import mtgai.encode.ObservationEncoder;

/**
 * The no-learning search baseline. Flat Monte Carlo over the root's legal candidates with perfect-information
 * rollouts on determinised game copies (PIMC): for each sim, a fresh {@link Determinizer} deal of the hidden zones, a
 * {@link GameCopies} copy whose seats are driven by rollout controllers, the root candidate applied by replaying the
 * request on the copy ({@link RolloutOracle}), a rollout of {@code depthTurns} turns (or {@code depthDecisions}
 * decisions) under the rollout policy, and a leaf value ({@link MaterialEval}: the result if the game ended, else
 * material). The candidate with the best mean value is played.
 *
 * <p>Which requests are searched: PRIORITY (copy point = the priority window); ATTACK and BLOCK_* (the copy is rewound
 * to the end of the previous step and the picks already made in this declaration are replayed); the targets, modes, X
 * and cost choices of the seat's own priority pick (replayed from that window's copy). Everything else (mulligans,
 * choices during resolution, triggers' targets) and every failed search falls back: to the best candidate so far if any
 * sim completed, else to Forge's own choice (the request's AI hint, as {@code DelegatingOracle}).
 *
 * <p>Budget = total sims per decision, split evenly over the candidates (Forge's choice first, then a seeded order, so a
 * budget below the candidate count still sims the AI's pick); sim {@code j} uses the same deal and rollout seed for
 * every candidate (common random numbers). With {@code threads > 1} the sims of one decision run on a shared pool, each
 * worker on its own copy of the decision point; a sim is a pure function of (decision point, candidate, seed), so the
 * choice does not depend on the thread count.
 */
public final class PimcOracle implements DecisionOracle {
    /** Rollout policy after the root. */
    public enum Rollout { RANDOM, FORGE_AI, /** Prototype: the ONNX policy plays both seats. */ NETWORK }

    public static final class Config {
        public int budget = 64;
        /** Forge's AI: much stronger per ms than random rollouts. */
        public Rollout rollout = Rollout.FORGE_AI;
        /**
         * Rollout horizon: stop at the first decision of turn (root turn + depthTurns + 1); 0 = the rest of this turn (as
         * strong as 1 in our measurements at half the cost).
         */
        public int depthTurns = 0;
        /** Rollout horizon in decisions after the root (both seats), 0 = off (turns only). */
        public int depthDecisions = 0;
        public int threads = 1;
        /** Per-rollout wall cap (cooperative, at decision points). */
        public long rolloutWallMs = 10_000;
        /** Fallback when nothing was searched: Forge's choice (hint) or uniform random. */
        public boolean fallbackHint = true;
        /**
         * {@link Rollout#NETWORK} (prototype): a fresh policy oracle per
         * rollout seat (the bench's {@code onnx} agent: shared session); leaf = 0.5 material + 0.5 value head.
         */
        public java.util.function.Supplier<DecisionOracle> net;

        public Config copy() {
            final Config c = new Config();
            c.budget = budget;
            c.rollout = rollout;
            c.depthTurns = depthTurns;
            c.depthDecisions = depthDecisions;
            c.threads = threads;
            c.rolloutWallMs = rolloutWallMs;
            c.fallbackHint = fallbackHint;
            c.net = net;
            return c;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "budget=%d rollout=%s depthTurns=%d depthDecisions=%d threads=%d fallback=%s",
                    budget, rollout.name().toLowerCase(Locale.ROOT), depthTurns, depthDecisions, threads,
                    fallbackHint ? "hint" : "random");
        }
    }

    private static final Pattern NAME = Pattern.compile("pimc-(\\d+)(?:-(random|forge|forge-ai|ai|net))?");

    /** True for an agent name {@code pimc-<budget>[-random|-forge]}. */
    public static boolean isPimcAgent(String name) {
        return NAME.matcher(name).matches();
    }

    /** The config of agent {@code name} on top of {@code base} (budget and, when given, rollout from the name). */
    public static Config configFor(String name, Config base) {
        final Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a pimc agent: " + name);
        }
        final Config c = (base == null ? new Config() : base).copy();
        c.budget = Integer.parseInt(m.group(1));
        if (m.group(2) != null) {
            c.rollout = m.group(2).equals("random") ? Rollout.RANDOM
                    : m.group(2).equals("net") ? Rollout.NETWORK : Rollout.FORGE_AI;
        }
        return c;
    }

    // ------------------------------------------------------------------ stats (JVM-wide, bench prints them)

    /** JVM-wide counters over every PimcOracle (all games, all seats). */
    public static final class Stats {
        public final LongAdder requests = new LongAdder();
        public final LongAdder searched = new LongAdder();
        public final LongAdder simsOk = new LongAdder();
        public final LongAdder simsGameOver = new LongAdder();
        public final LongAdder rolloutDecisions = new LongAdder();
        public final LongAdder searchNanos = new LongAdder();
        public final LongAdder fallbackNanos = new LongAdder();
        public final LongAdder copyNanos = new LongAdder();
        public final LongAdder anchorNanos = new LongAdder();
        public final LongAdder detNanos = new LongAdder();
        public final LongAdder rolloutNanos = new LongAdder();
        public final LongAdder evalNanos = new LongAdder();
        public final LongAdder determinised = new LongAdder();
        public final LongAdder deckMismatches = new LongAdder();
        public final LongAdder changedChoice = new LongAdder();
        /** NETWORK rollouts: policy forwards in rollouts, their ns, leaves scored with the value head. */
        public final LongAdder netForwards = new LongAdder();
        public final LongAdder netNanos = new LongAdder();
        public final LongAdder netLeaves = new LongAdder();
        /** Rollout seats' legal-action enumeration and observation encoding (ok sims). */
        public final LongAdder simEnumNanos = new LongAdder();
        public final LongAdder simEncodeNanos = new LongAdder();
        /** failure kind -> count (copy, stack, mismatch, exception, timeout, beforeRoot, wallCap). */
        public final Map<String, LongAdder> failures = new ConcurrentHashMap<>();
        /** fallback reason -> count. */
        public final Map<String, LongAdder> fallbacks = new ConcurrentHashMap<>();
        /** kind -> {searched, fallback}. */
        public final Map<DecisionKind, LongAdder[]> byKind = new ConcurrentHashMap<>();
        /** per searched decision, ms (for percentiles). */
        public final List<Long> searchMs = Collections.synchronizedList(new ArrayList<>());
        /** a few failure messages, for diagnosis. */
        public final List<String> notes = Collections.synchronizedList(new ArrayList<>());

        void fail(String kind, Throwable t) {
            failures.computeIfAbsent(kind, k -> new LongAdder()).increment();
            if (notes.size() < 30) {
                notes.add(kind + ": " + (t == null ? "" : t.toString()));
            }
        }

        void fallback(DecisionKind k, String reason) {
            fallbacks.computeIfAbsent(reason, x -> new LongAdder()).increment();
            byKind.computeIfAbsent(k, x -> new LongAdder[] {new LongAdder(), new LongAdder()})[1].increment();
        }

        public String summary() {
            final List<Long> ms;
            synchronized (searchMs) {
                ms = new ArrayList<>(searchMs);
            }
            Collections.sort(ms);
            final long n = searched.sum();
            final StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "pimc: requests %d searched %d (%.1f%%) | sims ok %d (%.1f/searched), game over"
                            + " in rollout %d | rollout decisions %.1f/sim | changed vs Forge's choice %d (%.1f%% of searched)%n",
                    requests.sum(), n, 100.0 * n / Math.max(1, requests.sum()), simsOk.sum(),
                    simsOk.sum() / (double) Math.max(1, n), simsGameOver.sum(),
                    rolloutDecisions.sum() / (double) Math.max(1, simsOk.sum()), changedChoice.sum(),
                    100.0 * changedChoice.sum() / Math.max(1, n)));
            final long sims = Math.max(1, simsOk.sum());
            sb.append(String.format(Locale.ROOT, "  ms/searched decision mean %.1f p50 %d p90 %d max %d | per sim: copy %.1f ms"
                            + " determinise %.2f rollout %.1f eval %.2f | copy point %.1f ms/decision | determinised %d deck"
                            + " mismatches %d%n",
                    searchNanos.sum() / 1e6 / Math.max(1, n), pct(ms, 50), pct(ms, 90), pct(ms, 100),
                    copyNanos.sum() / 1e6 / sims, detNanos.sum() / 1e6 / sims, rolloutNanos.sum() / 1e6 / sims,
                    evalNanos.sum() / 1e6 / sims, anchorNanos.sum() / 1e6 / Math.max(1, n), determinised.sum(),
                    deckMismatches.sum()));
            sb.append(String.format(Locale.ROOT, "  rollout seats: enumerate %.1f ms/sim, encode %.1f ms/sim%n",
                    simEnumNanos.sum() / 1e6 / sims, simEncodeNanos.sum() / 1e6 / sims));
            if (netForwards.sum() > 0) {
                sb.append(String.format(Locale.ROOT, "  net rollouts: forwards %.1f/sim, %.2f ms/forward (%.1f ms/sim),"
                                + " value-head leaves %d%n", netForwards.sum() / (double) sims,
                        netNanos.sum() / 1e6 / netForwards.sum(), netNanos.sum() / 1e6 / sims, netLeaves.sum()));
            }
            sb.append("  sim failures ").append(sums(failures)).append(" | fallbacks ").append(sums(fallbacks)).append('\n');
            sb.append("  by kind (searched/fallback):");
            for (DecisionKind k : DecisionKind.values()) {
                final LongAdder[] v = byKind.get(k);
                if (v != null) {
                    sb.append(' ').append(k).append(' ').append(v[0].sum()).append('/').append(v[1].sum());
                }
            }
            sb.append('\n');
            synchronized (notes) {
                for (String s : notes) {
                    sb.append("  note: ").append(s).append('\n');
                }
            }
            return sb.toString();
        }

        private static long pct(List<Long> sorted, int p) {
            if (sorted.isEmpty()) {
                return 0;
            }
            final int i = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
        }

        private static String sums(Map<String, LongAdder> m) {
            final Map<String, Long> out = new java.util.TreeMap<>();
            m.forEach((k, v) -> out.put(k, v.sum()));
            return out.toString();
        }
    }

    public static final Stats STATS = new Stats();

    /** Tests only: run at the start of every sim (may throw, to exercise the never-crash fallback). */
    public static volatile Runnable simHook;

    /**
     * Tests only: called in a sim with (the live request, the copy's first request) when the copy replays from the live
     * request itself (no answers since the copy point); may throw to end the sim.
     */
    public static volatile java.util.function.BiConsumer<DecisionRequest, DecisionRequest> firstRequestHook;

    // ------------------------------------------------------------------ per seat, per game

    private final Config cfg;
    private final SimRunner runner;
    private long gameSeed;
    private int seat;
    private int decisionNo;
    private Map<String, Object> lastInfo;
    /** The fallback with {@code --pimc-fallback random}: the seam's RandomOracle (keeps 85% of hands, passes 30%). */
    private RandomOracle fallbackRandom = new RandomOracle();

    public PimcOracle(Config cfg) {
        this.cfg = cfg;
        this.runner = new SimRunner(cfg);
    }

    public Config config() {
        return cfg;
    }

    @Override
    public void attach(PlayerControllerExternal controller) {
        runner.attach(controller);
    }

    @Override
    public void onGameStart(long seed, int seat0) {
        gameSeed = seed;
        seat = seat0;
        decisionNo = 0;
        runner.onGameStart();
        fallbackRandom = new RandomOracle();
        fallbackRandom.onGameStart(seed, seat0);
    }

    /**
     * Network rollouts: the live seat encodes its requests too, so its encoder's memory (history, opponent cards seen,
     * known library) can start the copies' encoders ({@link ObservationEncoder#copyFor}); null (no encoder, as before)
     * for the Forge-AI and random rollouts.
     */
    @Override
    public mtgai.encode.CardVocab observationVocab() {
        return cfg.rollout == Rollout.NETWORK && cfg.net != null ? cfg.net.get().observationVocab() : null;
    }

    @Override
    public boolean wantsAiHint() {
        return cfg.fallbackHint; // Forge's choice: the fallback, and the first candidate simmed under a small budget
    }

    @Override
    public boolean aiPipeline() {
        return false; // every sub-choice of the seat is a request (searched or answered by the fallback)
    }

    @Override
    public Map<String, Object> lastInfo() {
        return lastInfo;
    }

    @Override
    public String name() {
        return "pimc-" + cfg.budget;
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        final long t0 = System.nanoTime();
        STATS.requests.increment();
        decisionNo++;
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("pimc", cfg.budget);
        lastInfo = info;
        final Random savedRnd = MyRandom.getRandom();
        int chosen;
        try {
            chosen = search(req, info);
        } finally {
            MyRandom.setRandom(savedRnd); // the live game's RNG stream is never consumed by the search
            AiCache.clear();
        }
        final long ns = System.nanoTime() - t0;
        if (Boolean.TRUE.equals(info.get("searched"))) {
            STATS.searchNanos.add(ns);
            STATS.searchMs.add(ns / 1_000_000);
            if (req.aiHint() >= 0 && chosen != req.aiHint()) {
                STATS.changedChoice.increment();
            }
        } else {
            STATS.fallbackNanos.add(ns);
        }
        info.put("ms", ns / 1_000_000.0);
        runner.recordAnswer(req, chosen);
        return DecisionResponse.of(chosen);
    }

    private int fallback(DecisionRequest req, Map<String, Object> info, String reason) {
        STATS.fallback(req.kind(), reason);
        info.put("searched", false);
        info.put("reason", reason);
        if (cfg.fallbackHint && req.aiHint() >= 0 && req.aiHint() < req.size()) {
            return req.aiHint();
        }
        return fallbackRandom.decide(req).index();
    }

    private int search(DecisionRequest req, Map<String, Object> info) {
        if (!runner.attached()) {
            return fallback(req, info, "unattached");
        }
        final String why = runner.prepareAnchor(req);
        if (why != null) {
            return fallback(req, info, why);
        }
        final SimRunner.Anchor a = runner.anchor;
        final int nCand = req.size();
        // allocation: Forge's pick first, then a seeded order; round-robin until the budget is spent
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nCand; i++) {
            order.add(i);
        }
        final long dseed = mix(gameSeed, seat, decisionNo);
        Collections.shuffle(order, new Random(dseed));
        if (req.aiHint() >= 0) {
            order.remove(Integer.valueOf(req.aiHint()));
            order.add(0, req.aiHint());
        }
        final int budget = Math.max(1, cfg.budget);
        final int[] sims = new int[nCand];
        for (int s = 0; s < budget; s++) {
            sims[order.get(s % nCand)]++;
        }
        final List<int[]> tasks = new ArrayList<>(); // {candidate, j}
        final int maxJ = (budget + nCand - 1) / nCand;
        for (int j = 0; j < maxJ; j++) {
            for (int c : order) {
                if (j < sims[c]) {
                    tasks.add(new int[] {c, j});
                }
            }
        }
        final double[] sum = new double[nCand];
        final int[] cnt = new int[nCand];
        final Map<String, Integer> fails = new HashMap<>();
        final List<double[]> results = runner.runTasks(req, a, tasks, dseed, fails); // {candidate, value-or-NaN, j}
        for (double[] r : results) {
            if (!Double.isNaN(r[1])) {
                sum[(int) r[0]] += r[1];
                cnt[(int) r[0]]++;
            }
        }
        int best = -1;
        double bestV = Double.NEGATIVE_INFINITY;
        int ok = 0;
        final List<Object> vals = new ArrayList<>();
        for (int i : order) {
            ok += cnt[i];
            if (cnt[i] > 0) {
                final double v = sum[i] / cnt[i];
                vals.add(List.of(i, Math.round(v * 1000) / 1000.0, cnt[i]));
                if (v > bestV + 1e-12) { // order puts Forge's pick first: it wins ties
                    bestV = v;
                    best = i;
                }
            }
        }
        info.put("kind", req.kind().name());
        info.put("sims", tasks.size());
        info.put("ok", ok);
        if (!fails.isEmpty()) {
            info.put("fail", fails);
        }
        info.put("values", vals);
        if (best < 0) {
            return fallback(req, info, "all sims failed");
        }
        info.put("searched", true);
        STATS.searched.increment();
        STATS.byKind.computeIfAbsent(req.kind(), x -> new LongAdder[] {new LongAdder(), new LongAdder()})[0].increment();
        return best;
    }

    /**
     * Hidden cards the viewer knows: opponents' hand cards revealed to it (AI card memory REVEALED_CARDS, filled by the
     * inherited {@code PlayerControllerAi.reveal}) or currently lookable, and lookable library cards.
     */
    public static Set<Integer> pinned(Game game, Player me) {
        final Set<Integer> out = new HashSet<>();
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                if (p != me && (c.mayPlayerLook(me) || AiCardMemory.isRememberedCard(me, c, AiCardMemory.MemorySet.REVEALED_CARDS))) {
                    out.add(c.getId());
                }
            }
            for (Card c : p.getCardsIn(ZoneType.Library)) {
                if (c.mayPlayerLook(me)) {
                    out.add(c.getId());
                }
            }
        }
        return out;
    }

    static long mix(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L + b;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L + c;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
