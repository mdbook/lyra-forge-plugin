package mtgai.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import forge.deck.Deck;
import mtgai.bootstrap.ForgeBootstrap;
import mtgai.controller.PlayerControllerExternal;
import mtgai.controller.SeatStats;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DelegatingOracle;
import mtgai.decision.RandomOracle;
import mtgai.decision.TeacherOracle;
import mtgai.match.HeadlessMatch;
import mtgai.onnx.DecisionBatcher;
import mtgai.onnx.OnnxOracle;
import mtgai.search.GumbelOracle;
import mtgai.search.PimcOracle;

/**
 * {@code mtgai} command line: bench, play, eval, validate-decks.
 *
 * <pre>
 * java -Xss8m -jar mtgai-java-*-all.jar [--res DIR] COMMAND [options]
 *   bench          -d A.dck,B.dck [-a random,random] [-n 100] [--seed 1] [--threads 1] [--rows rows.jsonl] [--logs DIR] [common]
 *                  onnx agent: --model DIR --vocab FILE [--batch N, default 0 = off] [--batch-wait-us 2000] [--bucket 64]
 *   play           -d A.dck,B.dck [-a random,random] --seed S [common]
 *                  onnx agent: --onnx DIR (or --model DIR) --vocab FILE [--greedy] [--salt 30]: per-decision
 *                  probabilities + value and a one-line state per decision (e.g. -a onnx,forge-default)
 *   eval           --agents random,delegating,forge-default --decks DIR [-n 10] [--seed 1] [--out rows.jsonl] [common]
 *   validate-decks DIR [DIR...]
 * common: --exposure collapsed|full  --turn-cap 60  --decision-cap 20000  --wall-ms 120000  --crash-dir data/crashes  -v
 * agents: random | delegating (Forge AI through the seam) | teacher (Forge AI through the seat's own surface, the
 *         imitation teacher) | forge-default (stock PlayerControllerAi) | onnx (bench, play)
 *         | pimc-BUDGET[-forge|-random] (search anchor, e.g. pimc-16), options
 *           --pimc-rollout forge|random (default forge) --pimc-depth TURNS (0) --pimc-depth-decisions N (0 = off)
 *           --pimc-threads T (1) --pimc-wall-ms MS (10000, per rollout) --pimc-fallback hint|random (hint)
 *         | gumbel-N[-mK][-forge] (Gumbel root search, n sims, top-m 4, network rollouts, or Forge-AI
 *           rollouts + material leaf with -forge; needs --model/--vocab),
 *           options --gumbel-depth TURNS (0) --gumbel-depth-decisions D (0) --gumbel-sample true|false (false)
 *           --search-threads T (1; also sets --pimc-threads)
 * </pre>
 */
public final class Main {
    private Main() {
    }

    static final String REM_AI_DECK = "forge.mtgai.aiCastRemAIDeck";

    public static void main(String[] argv) throws Exception {
        final Args a = new Args(argv);
        if (a.cmd == null || a.flag("-h") || a.flag("--help")) {
            usage(System.out);
            return;
        }
        if (("selfplay".equals(a.cmd) || "replay".equals(a.cmd)) && System.getProperty(REM_AI_DECK) == null) {
            // The forge-default curriculum/pool opponent plays its full list; read once by AiController's
            // static init, so set before any game. Replay needs the same value to reproduce those games.
            System.setProperty(REM_AI_DECK, "true");
        }
        if (a.get("--res") != null) {
            ForgeBootstrap.init(Path.of(a.get("--res")));
        }
        pimcBase = pimcConfig(a);
        gumbelBase = gumbelConfig(a);
        final int rc = switch (a.cmd) {
            case "bench" -> bench(a);
            case "play" -> play(a);
            case "eval" -> eval(a);
            case "validate-decks" -> DeckValidator.run(a.positional, System.out);
            case "vocab" -> vocab(a);
            case "selfplay" -> SelfPlay.run(a);
            case "replay" -> Replay.run(a);
            case "cardfeatures" -> mtgai.cardfeat.CardFeatures.run(a.get("--vocab") == null ? null : Path.of(a.get("--vocab")),
                    a.get("--registry") == null ? null : Path.of(a.get("--registry")), Path.of(a.req("--out")),
                    a.intOr("--min-freq", 5), a.intOr("--max-atoms", 64),
                    a.get("--pool-dump") == null ? null : Path.of(a.get("--pool-dump")), System.out);
            default -> {
                usage(System.err);
                yield 2;
            }
        };
        System.out.flush();
        System.exit(rc);
    }

    static void usage(PrintStream out) {
        out.println("usage: mtgai [--res DIR] bench|play|eval|validate-decks ...  (see mtgai.cli.Main javadoc)");
        out.println("  bench          -d A.dck,B.dck [-a random,random] [-n 100] [--seed 1] [--threads 1] [--rows rows.jsonl]");
        out.println("  play           -d A.dck,B.dck [-a random,random] --seed S"
                + "   onnx: -a onnx,forge-default --onnx DIR --vocab FILE [--greedy] [--salt 30]");
        out.println("  eval           --agents random,delegating,forge-default --decks DIR [-n 10] [--out rows.jsonl]");
        out.println("  validate-decks DIR [DIR...]");
        out.println("  agents: random | delegating | teacher | forge-default | forge-cautious | forge-reckless | onnx"
                + " | pimc-<budget>[-forge|-random]  (--pimc-rollout forge|random --pimc-depth 0 --pimc-threads 1)");
        out.println("  vocab          DIR [DIR...] --out FILE    (CardVocab: sorted card-name union of the .dck files)");
        out.println("  selfplay       --models DIR --out DIR --vocab FILE -d A.dck,B.dck [--worker-id 0] [--games-per-version 32]"
                + " [--vs random] [--eval-out rows.jsonl] [--max-games N] [--seed-base S] [--game-start G]"
                + " [--threads N | host=N,...,*=N] [--poll-ms 200] [--eval-greedy true]"
                + "   ({host} {worker} in paths are substituted)");
        out.println("                 streaming: --out tcp://HOST:5555 (ZMQ PUSH)  --models http://HOST:8765/  --metrics-port 9400"
                + "  --records games.jsonl  --decision-log FILE  --max-wedged 1");
        out.println("                 search targets:--search-agent gumbel-<n>[-m<k>] --search-frac F (both or neither;"
                + " training games' model seats; --gumbel-depth --gumbel-depth-decisions --search-threads 1)");
        out.println("  cardfeatures   --vocab FILE --out DIR [--min-freq 5] [--max-atoms 64] [--pool-dump FILE]"
                + "   (encoder 6 card-content table + append-only atom registry; offline)");
        out.println("  cardfeatures   --registry decks/cards/registry.tsv [--vocab V1 (seeds a new registry)] --out DIR ..."
                + "   (encoder 10: append new cards / tokens to the card registry, table rows for every id)");
        out.println("  replay         --game ID [--records FILE|DIR] [--debug FILE|-] [--models DIR|URL] [--deck-dir DIR] [-v]");
        out.println("  common: --control damage,order,mana,effects,playdraw|all|none (encoder 8 groups; default -Dmtgai.control)"
                + " [,float: untap enablers float first | ,floatb: explicit float actions, replaces float"
                + " | ,floatsac: sacrifice-for-mana float actions (Ashnod's Altar)"
                + " | ,floatf: filter float actions (Prophetic Prism); never part of all]");
        out.println("  common: --exposure collapsed|full --turn-cap 60 --decision-cap 20000 --wall-ms 120000"
                + " --crash-dir data/crashes -v");
    }

    // ------------------------------------------------------------------ agents / decks

    /** Set by bench when an agent is "onnx" (--model DIR --vocab FILE; shared session + batcher). */
    static Supplier<DecisionOracle> onnxAgent;

    /** PIMC options shared by every {@code pimc-<budget>} agent of this run (--pimc-*). */
    static PimcOracle.Config pimcBase = new PimcOracle.Config();

    static PimcOracle.Config pimcConfig(Args a) {
        final PimcOracle.Config c = new PimcOracle.Config();
        final String r = a.getOr("--pimc-rollout", "forge");
        c.rollout = r.equals("random") ? PimcOracle.Rollout.RANDOM
                : r.equals("net") ? PimcOracle.Rollout.NETWORK : PimcOracle.Rollout.FORGE_AI;
        c.depthTurns = a.intOr("--pimc-depth", c.depthTurns);
        c.depthDecisions = a.intOr("--pimc-depth-decisions", c.depthDecisions);
        c.threads = a.intOr("--search-threads", a.intOr("--pimc-threads", c.threads));
        c.rolloutWallMs = a.intOr("--pimc-wall-ms", (int) c.rolloutWallMs);
        c.fallbackHint = !"random".equals(a.getOr("--pimc-fallback", "hint"));
        return c;
    }

    /** search-23 Gumbel options shared by every {@code gumbel-<n>} agent of this run (--gumbel-*, --search-threads). */
    static PimcOracle.Config gumbelBase = new PimcOracle.Config();
    /** {@code --gumbel-sample true}: sample the Gumbel noise (default false: deterministic eval agent, g = 0). */
    static boolean gumbelSample;

    static PimcOracle.Config gumbelConfig(Args a) {
        final PimcOracle.Config c = new PimcOracle.Config();
        c.depthTurns = a.intOr("--gumbel-depth", c.depthTurns);
        c.depthDecisions = a.intOr("--gumbel-depth-decisions", c.depthDecisions);
        c.threads = a.intOr("--search-threads", a.intOr("--pimc-threads", c.threads));
        c.rolloutWallMs = a.intOr("--pimc-wall-ms", (int) c.rolloutWallMs);
        gumbelSample = "true".equals(a.getOr("--gumbel-sample", "false"));
        return c;
    }

    static Supplier<DecisionOracle> agent(String name) {
        if (GumbelOracle.isGumbelAgent(name)) {
            final PimcOracle.Config c = GumbelOracle.configFor(name, gumbelBase);
            c.net = java.util.Objects.requireNonNull(onnxAgent, "gumbel-N needs --model DIR --vocab FILE");
            final int m = GumbelOracle.mFor(name);
            return () -> new GumbelOracle(c, m, gumbelSample);
        }
        if (PimcOracle.isPimcAgent(name)) {
            final PimcOracle.Config c = PimcOracle.configFor(name, pimcBase);
            if (c.rollout == PimcOracle.Rollout.NETWORK) { // search-23 prototype: the network plays both rollout seats
                c.net = java.util.Objects.requireNonNull(onnxAgent, "pimc-N-net needs --model DIR --vocab FILE");
            }
            return () -> new PimcOracle(c);
        }
        return switch (name) {
            case "onnx" -> java.util.Objects.requireNonNull(onnxAgent, "agent onnx needs --model DIR --vocab FILE");
            case "random" -> RandomOracle::new;
            case "delegating" -> DelegatingOracle::new;
            case "teacher" -> () -> new TeacherOracle(null);
            case "forge-default", "forge", "default", "forge-cautious", "forge-reckless", "forge-experimental" -> null;
            default -> throw new IllegalArgumentException("unknown agent: " + name
                    + " (random | delegating | teacher | forge-default | forge-cautious | forge-reckless"
                    + " | forge-experimental | pimc-<budget>[-random|-forge])");
        };
    }

    static String canonicalAgent(String name) {
        return switch (name) {
            case "forge", "default" -> "forge-default";
            default -> name;
        };
    }

    static Deck loadDeck(Path p) {
        ForgeBootstrap.get();
        final Deck d = forge.deck.io.DeckSerializer.fromFile(p.toFile());
        if (d == null) {
            throw new IllegalArgumentException("cannot read deck " + p);
        }
        // Deck loads its sections lazily and not thread-safely (a concurrent first load can hand a game an empty
        // library: turn-1 loss, seen in the 1.2 determinism run); load now, before game threads share it
        d.getMain();
        return d;
    }

    static String deckName(Path p) {
        final String f = p.getFileName().toString();
        return f.endsWith(".dck") ? f.substring(0, f.length() - 4) : f;
    }

    static HeadlessMatch.Config baseConfig(Args a) {
        final HeadlessMatch.Config c = new HeadlessMatch.Config();
        c.turnCap = a.intOr("--turn-cap", 60);
        c.decisionCap = a.intOr("--decision-cap", 20_000);
        c.wallCapMillis = a.intOr("--wall-ms", 120_000);
        c.exposure = PlayerControllerExternal.Exposure.valueOf(a.getOr("--exposure", "collapsed").toUpperCase(Locale.ROOT));
        c.crashDir = Path.of(a.getOr("--crash-dir", "data/crashes"));
        if (a.get("--control") != null) { // encoder 8 decision groups (default: -Dmtgai.control)
            c.control = mtgai.controller.Control.parse(a.get("--control"));
        }
        return c;
    }

    static HeadlessMatch.Seat[] seats(Args a) {
        final String[] decks = a.req("-d").split(",");
        final String[] agents = a.getOr("-a", "random,random").split(",");
        if (decks.length != 2 || agents.length != 2) {
            throw new IllegalArgumentException("-d and -a take exactly two comma-separated values");
        }
        final HeadlessMatch.Seat[] s = new HeadlessMatch.Seat[2];
        for (int i = 0; i < 2; i++) {
            final Path p = Path.of(decks[i]);
            s[i] = new HeadlessMatch.Seat(canonicalAgent(agents[i]), loadDeck(p), deckName(p), agent(agents[i]));
        }
        return s;
    }

    static int vocab(Args a) throws IOException {
        final List<String> names = mtgai.encode.CardVocab.union(a.positional.stream().map(Path::of).toList());
        Files.write(Path.of(a.req("--out")), names, StandardCharsets.UTF_8);
        System.out.println(names.size() + " names -> " + a.req("--out"));
        return 0;
    }

    // ------------------------------------------------------------------ bench

    static int bench(Args a) throws IOException {
        final long bootMs = ForgeBootstrap.get().loadMillis();
        final int threads = a.intOr("--threads", 1);
        DecisionBatcher batcher = null;
        if (a.get("--model") != null) {
            final OnnxOracle.Model model = new OnnxOracle.Model(Path.of(a.get("--model")));
            model.reload();
            final int maxBatch = a.intOr("--batch", 0);
            final DecisionBatcher b = maxBatch > 0 ? new DecisionBatcher(model, maxBatch, a.intOr("--batch-wait-us", 2000),
                    a.intOr("--bucket", 64)) : null;
            batcher = b;
            final mtgai.encode.CardVocab vocab = mtgai.encode.CardVocab.load(Path.of(a.req("--vocab")));
            final boolean greedy = a.flag("--greedy");
            onnxAgent = () -> new OnnxOracle(model, vocab, 0, b).greedy(greedy);
        }
        final HeadlessMatch.Seat[] seats = seats(a);
        final int n = a.intOr("-n", 100);
        final long seed = a.longOr("--seed", 1);
        final boolean verbose = a.flag("-v");
        final String rowsPath = a.get("--rows");
        final Path logDir = a.get("--logs") == null ? null : Files.createDirectories(Path.of(a.get("--logs")));

        final long[] ms = new long[n];
        final SeatStats[] tot = {new SeatStats(), new SeatStats()};
        int[] wins = new int[3];
        long turns = 0;
        int aborts = 0;
        int exceptions = 0;
        @SuppressWarnings("unchecked")
        final java.util.Set<String>[] cast = new java.util.Set[] {new java.util.HashSet<>(), new java.util.HashSet<>()};
        final long w0 = System.nanoTime();
        // N games on N threads in one JVM (needs the Forge fork's thread-safety changes); each game has its own
        // game thread
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final List<java.util.concurrent.Future<HeadlessMatch.Result>> futures = new ArrayList<>();
        for (int g = 0; g < n; g++) {
            final HeadlessMatch.Config c = baseConfig(a);
            c.seed = seed + g;
            c.seats = seats;
            futures.add(pool.submit(() -> {
                final HeadlessMatch.Result r = HeadlessMatch.play(c);
                r.logHash = HeadlessMatch.hash(HeadlessMatch.normalise(r.log));
                if (logDir != null) { // determinism debugging: diff two runs' logs per seed
                    Files.write(logDir.resolve("seed" + c.seed + ".log"), r.log, StandardCharsets.UTF_8);
                }
                r.log.clear();
                return r;
            }));
        }
        pool.shutdown();
        try (BufferedWriter rw = rowsPath == null ? null : Files.newBufferedWriter(Path.of(rowsPath), StandardCharsets.UTF_8)) {
            for (int g = 0; g < n; g++) {
                final HeadlessMatch.Result r = futures.get(g).get();
                ms[g] = r.wallMillis;
                turns += r.turns;
                wins[r.winnerSeat < 0 ? 2 : r.winnerSeat]++;
                if (r.abort != null) {
                    aborts++;
                }
                if (r.exception != null) {
                    exceptions++;
                }
                tot[0].add(r.stats[0]);
                tot[1].add(r.stats[1]);
                cast[0].addAll(r.cast[0]);
                cast[1].addAll(r.cast[1]);
                if (rw != null) {
                    rw.write(row(r));
                    rw.newLine();
                }
                if (verbose || r.exception != null) {
                    System.out.printf(Locale.ROOT, "game %d seed %d: %d ms, %d turns, winner %s, %s%s%n", g + 1, r.seed,
                            r.wallMillis, r.turns, r.winnerSeat < 0 ? "-" : seats[r.winnerSeat].agent() + "@s" + r.winnerSeat,
                            r.endReason, r.crashBundle != null ? " bundle " + r.crashBundle : "");
                }
            }
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
        final long wallAll = (System.nanoTime() - w0) / 1_000_000;
        final long[] sorted = ms.clone();
        Arrays.sort(sorted);
        final double mean = Arrays.stream(ms).average().orElse(0);
        final double meanTurns = (double) turns / n;
        final PrintStream o = System.out;
        o.printf(Locale.ROOT, "%nbench: %s (%s) vs %s (%s), %d games, seeds %d..%d, exposure %s%n",
                seats[0].deckName(), seats[0].agent(), seats[1].deckName(), seats[1].agent(), n, seed, seed + n - 1,
                a.getOr("--exposure", "collapsed"));
        o.printf(Locale.ROOT, "bootstrap %d ms; games wall %d ms (%.2f games/s, %d threads)%n", bootMs, wallAll,
                n * 1000.0 / wallAll, threads);
        o.printf(Locale.ROOT, "ms/game mean %.0f  p50 %d  p90 %d  max %d | turns %.2f | ms/turn %.1f%n", mean,
                sorted[n / 2], sorted[Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1)], sorted[n - 1], meanTurns,
                mean / meanTurns);
        o.printf(Locale.ROOT, "wins s0 %d  s1 %d  draw/truncated %d | aborts %d  exceptions %d%n", wins[0], wins[1],
                wins[2], aborts, exceptions);
        o.printf(Locale.ROOT, "peak RSS %s%n", peakRss());
        if (batcher != null) {
            final long req = batcher.requests.get();
            o.printf(Locale.ROOT, "batcher: %d requests, %d forwards (mean batch %.2f), session.run %.3f ms/request;"
                            + " tokens p50 %d p95 %d max %d; candidates p50 %d p95 %d%n", req, batcher.forwards.get(),
                    req / (double) Math.max(1, batcher.forwards.get()), batcher.runNanos.get() / 1e6 / Math.max(1, req),
                    DecisionBatcher.percentile(batcher.tokenHist, 50), DecisionBatcher.percentile(batcher.tokenHist, 95),
                    DecisionBatcher.percentile(batcher.tokenHist, 100), DecisionBatcher.percentile(batcher.candHist, 50),
                    DecisionBatcher.percentile(batcher.candHist, 95));
            batcher.close();
        }
        for (int i = 0; i < 2; i++) {
            printSeat(o, "s" + i + " " + seats[i].agent() + " " + seats[i].deckName(), tot[i], n);
            final java.util.Set<String> spells = new java.util.TreeSet<>();
            for (Map.Entry<forge.item.PaperCard, Integer> e : seats[i].deck().getMain()) {
                if (!e.getKey().getRules().getType().isLand()) {
                    spells.add(e.getKey().getName());
                }
            }
            final java.util.Set<String> dead = new java.util.TreeSet<>(spells);
            dead.removeAll(cast[i]);
            o.printf(Locale.ROOT, "  dead cards (never cast in %d games): %d of %d nonland names (%.0f%%) %s%n", n,
                    dead.size(), spells.size(), 100.0 * dead.size() / Math.max(1, spells.size()), dead);
        }
        if (PimcOracle.isPimcAgent(seats[0].agent()) || PimcOracle.isPimcAgent(seats[1].agent())) {
            for (HeadlessMatch.Seat st : seats) {
                if (PimcOracle.isPimcAgent(st.agent())) {
                    o.printf("%n[%s: %s]%n", st.agent(), PimcOracle.configFor(st.agent(), pimcBase));
                }
            }
            o.print(PimcOracle.STATS.summary());
        }
        if (GumbelOracle.isGumbelAgent(seats[0].agent()) || GumbelOracle.isGumbelAgent(seats[1].agent())) {
            for (HeadlessMatch.Seat st : seats) {
                if (GumbelOracle.isGumbelAgent(st.agent())) {
                    o.printf("%n[%s: m=%d sample=%s %s]%n", st.agent(), GumbelOracle.mFor(st.agent()), gumbelSample,
                            GumbelOracle.configFor(st.agent(), gumbelBase));
                }
            }
            o.print(GumbelOracle.GSTATS.summary());
            o.print(PimcOracle.STATS.summary()); // sims / copies / failures / s per decision (labelled pimc)
        }
        return exceptions == 0 && tot[0].activationFailures + tot[1].activationFailures == 0 ? 0 : 1;
    }

    static void printSeat(PrintStream o, String title, SeatStats s, int games) {
        o.printf(Locale.ROOT, "%n[%s] per game: windows %.1f exposed %.1f autopassed %.1f pass-only %.1f | "
                        + "decisions %.1f | lands %.1f spells %.1f | enum %.1f ms%n",
                title, s.windows / (double) games, s.windowsExposed / (double) games,
                s.windowsAutoPassed / (double) games, s.windowsNoAction / (double) games,
                s.decisions() / (double) games, s.landsPlayed / (double) games, s.spellsPlayed / (double) games,
                s.enumNanos / 1e6 / games);
        o.printf(Locale.ROOT, "  activation failures %d | aiDoTrigger outside targeting %d | land eligible %d missed %d"
                        + " | delegate misses %d | truncations %d | fallbacks att %d blk %d | blk repaired %d | targets delegated %d"
                        + " | trigger-play refusals %d | forced single %d | mana target retries %d | joint-cost filtered %d%n",
                s.activationFailures, s.aiDoTriggerOutsideTargeting, s.landEligibleWindows, s.landMissedWindows,
                s.delegateMisses(), s.candidateTruncations, s.attackFallbacks, s.blockFallbacks, s.blockRepairs, s.targetsDelegated,
                s.triggerPlayFailures, s.forcedSingle, s.manaTargetRetries, s.jointCostFiltered);
        final long own = s.decisions();
        o.printf(Locale.ROOT, "  delegated-decision share %.1f%% (%d AI-answered callbacks, %.2f/game; policy requests %d) %s%n",
                100.0 * s.delegated / Math.max(1, s.delegated + own), s.delegated, s.delegated / (double) games, own,
                s.delegatedByMethod);
        o.printf(Locale.ROOT, "  mana payments %d (%.1f/game): ambiguous %.1f%% | held instant %d, regret %d (%.1f%% of held,"
                        + " %.2f%% of payments) | sac source cracked %d%n", s.manaPayments, s.manaPayments / (double) games,
                100.0 * s.manaAmbiguous / Math.max(1, s.manaPayments), s.manaHeldInstant, s.manaInstantRegret,
                100.0 * s.manaInstantRegret / Math.max(1, s.manaHeldInstant),
                100.0 * s.manaInstantRegret / Math.max(1, s.manaPayments), s.manaSacCracked);
        o.printf(Locale.ROOT, "  encoder 8 groups: damage assignments %d (delegated %d) | trigger orders %d | mana policy payments %d"
                        + " (auto-resolved steps/payments %d, delegated %d, stranded %d)%n", s.damageAssignments,
                s.damageDelegated, s.triggerOrders, s.manaPolicyPayments, s.manaAutoResolved, s.manaDelegated, s.manaStranded);
        if (s.floatActivations + s.floatNoopHidden + s.floatManaPassed + s.floatWindows > 0) { // the float flags only
            o.printf(Locale.ROOT, "  float: activations %d (%.2f/game) units %d | with enabler %d | no-op enablers hidden %d"
                            + " | mana passed %d | float windows %d | filter floats %d (colour missed %d)%n", s.floatActivations,
                    s.floatActivations / (double) games, s.floatUnits, s.floatWithEnabler, s.floatNoopHidden, s.floatManaPassed,
                    s.floatWindows, s.floatFilters, s.floatFilterMissed);
        }
        o.printf("  %-16s %10s %9s %9s %8s %7s%n", "kind", "total", "per game", "mean cand", "max cand", "misses");
        for (DecisionKind k : DecisionKind.values()) {
            final long[] v = s.byKind.get(k);
            if (v == null) {
                o.printf("  %-16s %10d %9s %9s %8s %7s%n", k, 0, "-", "-", "-", "-");
            } else {
                o.printf(Locale.ROOT, "  %-16s %10d %9.2f %9.2f %8d %7d%n", k, v[0], v[0] / (double) games,
                        v[1] / (double) v[0], v[2], v[3]);
            }
        }
        for (String note : s.notes) {
            o.println("  note: " + note);
        }
    }

    static String peakRss() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (l.startsWith("VmHWM:")) {
                    return l.substring(6).trim();
                }
            }
        } catch (IOException ignored) {
            // not Linux
        }
        return "n/a";
    }

    // ------------------------------------------------------------------ play

    static int play(Args a) {
        final String modelDir = a.get("--onnx") != null ? a.get("--onnx") : a.get("--model");
        OnnxOracle.Model model = null;
        if (modelDir != null) {
            model = new OnnxOracle.Model(Path.of(modelDir));
            model.reload();
            final OnnxOracle.Model m = model;
            final mtgai.encode.CardVocab vocab = mtgai.encode.CardVocab.load(Path.of(a.req("--vocab")));
            final long salt = a.longOr("--salt", 0);
            final boolean greedy = a.flag("--greedy");
            onnxAgent = () -> new OnnxOracle(m, vocab, salt).greedy(greedy);
        }
        final HeadlessMatch.Seat[] seats = seats(a);
        final HeadlessMatch.Config c = baseConfig(a);
        c.seed = a.longOr("--seed", 1);
        c.seats = seats;
        c.oracles = new DecisionOracle[2];
        for (int i = 0; i < 2; i++) {
            if (seats[i].agent().equals("onnx")) {
                c.oracles[i] = seats[i].oracle().get();
                c.modelVersions[i] = model.version();
            }
        }
        final PrintStream o = System.out;
        final int[] n = {0};
        c.listener = (req, chosen) -> {
            n[0]++;
            final DecisionOracle or = c.oracles[req.seat()];
            final float[] out = or == null ? null : or.lastOutput();
            o.printf(Locale.ROOT, "  >> #%d s%d T%d %s %s%s%s%n", n[0], req.seat(), req.turn(), req.phase(), req.kind(),
                    req.context() == null ? "" : "  (" + req.context() + ")",
                    out != null && out.length > req.size() ? String.format(Locale.ROOT, "  V=%.3f", out[req.size()]) : "");
            if (out != null && out.length > req.size()) {
                o.println(PlayState.line(req));
            }
            final double[] p = out != null && out.length > req.size() ? softmax(out, req.size()) : null;
            for (int i = 0; i < req.size(); i++) {
                o.printf(Locale.ROOT, "     %s %2d %s%s%n", i == chosen ? "*" : " ", i,
                        p == null ? "" : String.format(Locale.ROOT, "%5.1f%% ", 100 * p[i]), req.candidates().get(i));
            }
        };
        o.printf("play: seed %d  s0 %s (%s)  s1 %s (%s)  exposure %s%s%n", c.seed, seats[0].deckName(), seats[0].agent(),
                seats[1].deckName(), seats[1].agent(), c.exposure,
                model == null ? "" : "  model v" + model.version() + (a.flag("--greedy") ? " greedy" : " sampled"));
        o.println("(decisions are printed as they happen; the full GameLog follows the game)");
        final HeadlessMatch.Result r = HeadlessMatch.play(c);
        o.println("\n==== GameLog (chronological) ====");
        for (String l : r.log) {
            o.println(l);
        }
        o.printf(Locale.ROOT, "%n==== result: winner %s, turns %d, first seat %d, %s, %d ms, %d decisions%n",
                r.winnerSeat < 0 ? "none" : "s" + r.winnerSeat + " " + seats[r.winnerSeat].agent(), r.turns, r.firstSeat,
                r.endReason, r.wallMillis, r.decisions);
        o.println("normalised GameLog sha256: " + HeadlessMatch.hash(HeadlessMatch.normalise(r.log)));
        if (r.crashBundle != null) {
            o.println("crash bundle: " + r.crashBundle);
        }
        for (int i = 0; i < 2; i++) {
            printSeat(o, "s" + i + " " + seats[i].agent() + " " + seats[i].deckName(), r.stats[i], 1);
        }
        return r.exception == null ? 0 : 1;
    }

    private static double[] softmax(float[] out, int n) {
        double mx = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            mx = Math.max(mx, out[i]);
        }
        final double[] p = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            p[i] = Math.exp(out[i] - mx);
            sum += p[i];
        }
        for (int i = 0; i < n; i++) {
            p[i] /= sum;
        }
        return p;
    }

    // ------------------------------------------------------------------ eval

    static int eval(Args a) throws IOException {
        final String[] agents = a.req("--agents").split(",");
        final Path dir = Path.of(a.req("--decks"));
        final List<Path> deckFiles;
        try (Stream<Path> s = Files.list(dir)) {
            deckFiles = s.filter(p -> p.toString().endsWith(".dck")).sorted().toList();
        }
        final Map<Path, Deck> decks = new LinkedHashMap<>();
        for (Path p : deckFiles) {
            decks.put(p, loadDeck(p));
        }
        final int n = a.intOr("-n", 10);
        long seed = a.longOr("--seed", 1);
        final Path out = Path.of(a.getOr("--out", "data/eval/eval-" + System.currentTimeMillis() + ".jsonl"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        // wins[agentA][agentB] from A's perspective
        final Map<String, int[]> table = new LinkedHashMap<>();
        int rows = 0;
        long totalFailures = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < agents.length; i++) {
                for (int j = i + 1; j < agents.length; j++) {
                    for (Path da : deckFiles) {
                        for (Path db : deckFiles) {
                            for (int order = 0; order < 2; order++) {
                                final String x = canonicalAgent(order == 0 ? agents[i] : agents[j]);
                                final String y = canonicalAgent(order == 0 ? agents[j] : agents[i]);
                                for (int g = 0; g < n; g++) {
                                    final HeadlessMatch.Config c = baseConfig(a);
                                    c.seed = seed++;
                                    c.seats = new HeadlessMatch.Seat[] {
                                        new HeadlessMatch.Seat(x, decks.get(da), deckName(da), agent(x)),
                                        new HeadlessMatch.Seat(y, decks.get(db), deckName(db), agent(y))};
                                    final HeadlessMatch.Result r = HeadlessMatch.play(c);
                                    w.write(row(r));
                                    w.newLine();
                                    rows++;
                                    totalFailures += r.activationFailures();
                                    final String key = canonicalAgent(agents[i]) + " vs " + canonicalAgent(agents[j]);
                                    final int[] t = table.computeIfAbsent(key, k -> new int[3]);
                                    if (r.winnerSeat < 0) {
                                        t[2]++;
                                    } else {
                                        final boolean firstAgentWon = r.agents[r.winnerSeat].equals(canonicalAgent(agents[i]));
                                        t[firstAgentWon ? 0 : 1]++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("eval: %d rows -> %s  (activation failures %d)%n", rows, out, totalFailures);
        for (Map.Entry<String, int[]> e : table.entrySet()) {
            final int[] t = e.getValue();
            final int games = t[0] + t[1] + t[2];
            System.out.printf(Locale.ROOT, "  %-32s %4d-%4d-%3d  (%.1f%% for the first-named agent, draws excluded)%n",
                    e.getKey(), t[0], t[1], t[2], 100.0 * t[0] / Math.max(1, t[0] + t[1]));
        }
        openSkillTable(out);
        return totalFailures == 0 ? 0 : 1;
    }

    /**
     * eval prints an OpenSkill table (random = 0, forge-default = 1000). The rating code
     * is the reference {@code openskill} package, run through {@code scripts/bench/eval-summary.py} with uv
     * when both are available from the working directory; otherwise the command is printed.
     */
    static void openSkillTable(Path rows) {
        final Path script = Path.of("scripts/bench/eval-summary.py");
        final String cmd = "uv run --python 3.12 " + script + " " + rows;
        if (Files.isRegularFile(script)) {
            try {
                final Process p = new ProcessBuilder("uv", "run", "-q", "--python", "3.12", script.toString(), rows.toString())
                        .redirectErrorStream(true).start();
                final String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0) {
                    System.out.print(outText);
                    return;
                }
            } catch (IOException e) {
                // uv not installed: fall through
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("  OpenSkill table (random = 0, forge-default = 1000): " + cmd);
    }

    static String row(HeadlessMatch.Result r) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", r.seed);
        m.put("decks", List.of(r.decks[0], r.decks[1]));
        m.put("agents", List.of(r.agents[0], r.agents[1]));
        m.put("first_seat", r.firstSeat);
        m.put("winner_seat", r.winnerSeat);
        m.put("winner", r.winnerSeat < 0 ? null : r.agents[r.winnerSeat]);
        m.put("turns", r.turns);
        m.put("end", r.endReason);
        m.put("decisions", List.of(r.stats[0].decisions(), r.stats[1].decisions()));
        m.put("wall_ms", r.wallMillis);
        m.put("activation_failures", List.of(r.stats[0].activationFailures, r.stats[1].activationFailures));
        m.put("ai_dotrigger_outside", List.of(r.stats[0].aiDoTriggerOutsideTargeting, r.stats[1].aiDoTriggerOutsideTargeting));
        m.put("delegate_misses", List.of(r.stats[0].delegateMisses(), r.stats[1].delegateMisses()));
        m.put("forced_single", List.of(r.stats[0].forcedSingle, r.stats[1].forcedSingle));
        m.put("mana_target_retries", List.of(r.stats[0].manaTargetRetries, r.stats[1].manaTargetRetries));
        m.put("joint_cost_filtered", List.of(r.stats[0].jointCostFiltered, r.stats[1].jointCostFiltered));
        m.put("exception", r.exception);
        if (r.logHash != null) {
            m.put("log_hash", r.logHash);
        }
        return Json.write(m);
    }

    // ------------------------------------------------------------------ args

    static final class Args {
        final String cmd;
        final Map<String, String> opts = new HashMap<>();
        final List<String> positional = new ArrayList<>();

        Args(String[] argv) {
            String c = null;
            for (int i = 0; i < argv.length; i++) {
                final String s = argv[i];
                if (s.startsWith("-")) {
                    if (s.equals("-v") || s.equals("-h") || s.equals("--help")) {
                        opts.put(s, "true");
                    } else if (i + 1 < argv.length) {
                        opts.put(s, argv[++i]);
                    } else {
                        throw new IllegalArgumentException("missing value for " + s);
                    }
                } else if (c == null) {
                    c = s;
                } else {
                    positional.add(s);
                }
            }
            cmd = c;
        }

        String get(String k) {
            return opts.get(k);
        }

        String getOr(String k, String d) {
            return opts.getOrDefault(k, d);
        }

        String req(String k) {
            final String v = opts.get(k);
            if (v == null) {
                throw new IllegalArgumentException("missing " + k);
            }
            return v;
        }

        boolean flag(String k) {
            return opts.containsKey(k);
        }

        int intOr(String k, int d) {
            return opts.containsKey(k) ? Integer.parseInt(opts.get(k)) : d;
        }

        long longOr(String k, long d) {
            return opts.containsKey(k) ? Long.parseLong(opts.get(k)) : d;
        }
    }
}
