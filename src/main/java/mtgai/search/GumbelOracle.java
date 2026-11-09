package mtgai.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.ai.AiCache;
import forge.util.MyRandom;
import mtgai.controller.PlayerControllerExternal;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;

import static mtgai.search.PimcOracle.STATS;
import static mtgai.search.PimcOracle.mix;

/**
 * Gumbel root search (Danihelka et al. 2022, root only) on
 * the PIMC machinery. Per decision: one forward of the policy on the live observation (logits l, value v0); Gumbel
 * top-m of g + l; sequential halving over the budget n, each sim a {@link SimRunner} sim (determinised copy, replay,
 * network rollouts to the horizon, {@link NetLeaf}); play argmax of g + l + sigma(q) over the survivors, with
 * sigma(q) = (cVisit + max N) x cScale x q. Requests the search cannot replay (mulligans, resolution-time choices, a
 * failed copy point) and decisions whose sims all fail are answered by the policy's argmax, never by Forge.
 *
 * <p>Sim j of every action shares deal j and rollout seed j (common random numbers); a sim is a pure function of
 * (decision point, candidate, j), and sums are taken in j order, so the choice does not depend on the thread count.
 */
public final class GumbelOracle implements DecisionOracle {
    /** Paper constants: sigma(q) = (C_VISIT + max N) x C_SCALE x q, q in [0, 1]. C_SCALE: -Dmtgai.gumbel.cscale (sweep). */
    public static final double C_VISIT = 50;
    public static final double C_SCALE = Double.parseDouble(System.getProperty("mtgai.gumbel.cscale", "1.0"));
    public static final int DEFAULT_M = 4;

    private static final Pattern NAME = Pattern.compile("gumbel-(\\d+)(?:-m(\\d+))?(-forge)?");

    /** True for an agent name {@code gumbel-<n>[-m<k>][-forge]}. */
    public static boolean isGumbelAgent(String name) {
        return NAME.matcher(name).matches();
    }

    /**
     * The search config of agent {@code name} on top of {@code base}: budget n, no Forge hint at the root; network
     * rollouts + {@link NetLeaf}, or with {@code -forge} Forge-AI rollouts + MaterialEval leaf (as {@code pimc-<n>-forge}).
     */
    public static PimcOracle.Config configFor(String name, PimcOracle.Config base) {
        final Matcher mt = NAME.matcher(name);
        if (!mt.matches()) {
            throw new IllegalArgumentException("not a gumbel agent: " + name);
        }
        final PimcOracle.Config c = (base == null ? new PimcOracle.Config() : base).copy();
        c.budget = Integer.parseInt(mt.group(1));
        c.rollout = mt.group(3) != null ? PimcOracle.Rollout.FORGE_AI : PimcOracle.Rollout.NETWORK;
        c.fallbackHint = false;
        return c;
    }

    /** The m of agent {@code name} ({@code -m<k>}), else {@link #DEFAULT_M}. */
    public static int mFor(String name) {
        final Matcher mt = NAME.matcher(name);
        return mt.matches() && mt.group(2) != null ? Integer.parseInt(mt.group(2)) : DEFAULT_M;
    }

    // ------------------------------------------------------------------ the halving allocator (pure)

    /**
     * One halving phase: {@code actions} remaining actions get {@code perAction} sims each; the first {@code extra}
     * ranked ones one more.
     */
    public record Phase(int actions, int perAction, int extra) {
        public int sims() {
            return actions * perAction + extra;
        }
    }

    /** m actually used: at most the candidates, and at most the sims (every action needs one). */
    public static int effectiveM(int m, int candidates, int n) {
        return Math.max(1, Math.min(m, Math.min(candidates, n)));
    }

    /**
     * Sequential halving over {@code n} sims for {@code m} actions: ceil(log2 m) phases (at least 1); each remaining
     * action gets max(1, floor(left / (phases left x remaining))) sims, recomputed from the sims still unspent so that
     * exactly {@code n} are spent (the last phase hands its remainder to its top-ranked actions); never more than
     * {@code n}. With a budget too small for the later phases the plan stops early. The remaining set halves
     * (ceil) between phases. Needs {@code m <= n}.
     */
    public static List<Phase> plan(int n, int m) {
        final List<Phase> out = new ArrayList<>();
        if (n < 1 || m < 1 || m > n) {
            return out;
        }
        final int phases = Math.max(1, 32 - Integer.numberOfLeadingZeros(m - 1)); // ceil(log2 m)
        int rem = m;
        int left = n;
        for (int p = 0; p < phases && left >= rem; p++) {
            final int per = Math.min(Math.max(1, left / ((phases - p) * rem)), left / rem);
            left -= per * rem;
            final int next = (rem + 1) / 2;
            final boolean last = p == phases - 1 || left < next;
            final int extra = last ? left : 0;
            left -= extra;
            out.add(new Phase(rem, per, extra));
            rem = next;
        }
        return out;
    }

    // ------------------------------------------------------------------ stats (JVM-wide)

    /** Gumbel-specific counters; the sim / copy / failure counters are {@link PimcOracle#STATS}. */
    public static final class Stats {
        public final LongAdder searched = new LongAdder();
        public final LongAdder sims = new LongAdder();
        public final LongAdder simsOk = new LongAdder();
        public final LongAdder candidates = new LongAdder();
        public final LongAdder mUsed = new LongAdder();
        public final LongAdder agree = new LongAdder();

        public String summary() {
            final long n = Math.max(1, searched.sum());
            return String.format(Locale.ROOT, "gumbel: searched %d | sims/decision %.2f (ok %.2f) | candidates %.2f m %.2f"
                            + " | root-candidate agreement (played = argmax logits) %d (%.1f%%)%n", searched.sum(),
                    sims.sum() / (double) n, simsOk.sum() / (double) n, candidates.sum() / (double) n,
                    mUsed.sum() / (double) n, agree.sum(), 100.0 * agree.sum() / n);
        }
    }

    public static final Stats GSTATS = new Stats();

    // ------------------------------------------------------------------ per seat, per game

    private final PimcOracle.Config cfg;
    private final SimRunner runner;
    private final int m;
    private final boolean sample;
    private final DecisionOracle policy;
    /** Training mode (search targets): the searched fraction of copy points; NaN = the eval / bench agent. */
    private final double frac;
    private long gameSeed;
    private int seat;
    private int decisionNo;
    private Map<String, Object> lastInfo;
    /** The improved policy of the last {@link #search} that searched (mEff >= 2, a usable sim), else null. */
    private double[] lastPi;
    private int searchedGame;
    private long searchNanosGame;

    /**
     * The eval / bench agent: every decision searched where possible, the policy's argmax otherwise.
     *
     * @param cfg    search config ({@link #configFor}); {@code cfg.net} is the policy (root forward, network rollouts)
     * @param m      Gumbel top-m
     * @param sample true: g ~ Gumbel(0, 1) from the decision's seed (training-target / exploration); false: g = 0 (the
     *               deterministic eval agent: top-m by logits)
     */
    public GumbelOracle(PimcOracle.Config cfg, int m, boolean sample) {
        this(cfg, m, sample, Double.NaN);
    }

    private GumbelOracle(PimcOracle.Config cfg, int m, boolean sample, double frac) {
        if (cfg.rollout == PimcOracle.Rollout.RANDOM) {
            throw new IllegalArgumentException("gumbel searches with network or Forge-AI rollouts");
        }
        this.cfg = cfg;
        this.runner = new SimRunner(cfg);
        this.m = Math.max(1, m);
        this.sample = sample;
        this.frac = frac;
        this.policy = Objects.requireNonNull(cfg.net, "gumbel needs cfg.net (the ONNX policy)").get();
    }

    /**
     * Search targets: a self-play training seat: every decision is the
     * policy's sampled decide ({@code cfg.net}, as an {@code OnnxOracle} seat), and at a copy point the seat searches
     * (sampled Gumbel noise) iff u < {@code frac}, u from an RNG of its own per (game seed, seat, decision). A searched
     * decision plays the search's pick with pi' ({@link DecisionResponse#searched}); any other decision returns the
     * policy's sampled response unchanged. Needs m >= 2 and n >= 2 (a search of one action has no pi').
     */
    public static GumbelOracle training(PimcOracle.Config cfg, int m, double frac) {
        if (!(frac >= 0 && frac <= 1)) {
            throw new IllegalArgumentException("search fraction " + frac + " not in [0, 1]");
        }
        if (m < 2 || cfg.budget < 2) {
            throw new IllegalArgumentException("search targets need m >= 2 and n >= 2 (m " + m + ", n " + cfg.budget + ")");
        }
        return new GumbelOracle(cfg, m, true, frac);
    }

    /** True for a {@link #training} oracle. */
    public boolean training() {
        return !Double.isNaN(frac);
    }

    /** Training mode: decisions of this game answered with pi' (SEARCHED). */
    public int searchedThisGame() {
        return searchedGame;
    }

    /** Training mode: wall time of this game's decisions spent past the root forward (gate, copy point, sims). */
    public long searchNanosThisGame() {
        return searchNanosGame;
    }

    /** One-line config for the bench header. */
    public String describe() {
        return String.format(Locale.ROOT, "gumbel n=%d m=%d sample=%s depthTurns=%d depthDecisions=%d threads=%d%s",
                cfg.budget, m, sample, cfg.depthTurns, cfg.depthDecisions, cfg.threads,
                cfg.rollout == PimcOracle.Rollout.FORGE_AI ? " rollout=forge" : "");
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
        searchedGame = 0;
        searchNanosGame = 0;
        runner.onGameStart();
        policy.onGameStart(seed, seat0);
    }

    /** Training mode: the policy's raw output behind the last decision (the per-decision debug log); eval: none. */
    @Override
    public float[] lastOutput() {
        return training() ? policy.lastOutput() : null;
    }

    /** The live seat encodes its requests: the root forward, and the copies' encoder state. */
    @Override
    public mtgai.encode.CardVocab observationVocab() {
        return policy.observationVocab();
    }

    @Override
    public boolean wantsAiHint() {
        return false;
    }

    @Override
    public boolean aiPipeline() {
        return false; // every sub-choice of the seat is a request (searched or answered by the policy)
    }

    @Override
    public Map<String, Object> lastInfo() {
        return lastInfo;
    }

    @Override
    public String name() {
        return "gumbel-" + cfg.budget;
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        final long t0 = System.nanoTime();
        STATS.requests.increment();
        decisionNo++;
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("gumbel", cfg.budget);
        lastInfo = info;
        final Random savedRnd = MyRandom.getRandom();
        final DecisionResponse resp;
        try {
            resp = training() ? train(req, info) : DecisionResponse.of(choose(req, info));
        } finally {
            MyRandom.setRandom(savedRnd); // the live game's RNG stream is never consumed by the search
            AiCache.clear();
        }
        final long ns = System.nanoTime() - t0;
        if (Boolean.TRUE.equals(info.get("searched"))) {
            STATS.searchNanos.add(ns);
            if (!training()) { // per-decision list is for bench percentiles; unbounded in a long-lived worker
                STATS.searchMs.add(ns / 1_000_000);
            }
        } else {
            STATS.fallbackNanos.add(ns);
        }
        info.put("ms", ns / 1_000_000.0);
        runner.recordAnswer(req, resp.index());
        return resp;
    }

    /**
     * Training mode, one decision: the root forward is the policy's sampled decide; search iff a copy point passes the
     * gate (or a sub-choice follows a searched copy point's anchor) and the search yields pi'; else the root response.
     */
    private DecisionResponse train(DecisionRequest req, Map<String, Object> info) {
        info.put("kind", req.kind().name());
        final DecisionResponse root = policy.decide(req); // an error here fails the seat as on an OnnxOracle seat
        final long t0 = System.nanoTime();
        try {
            if (req.size() == 1) {
                info.put("searched", false);
                info.put("reason", "single candidate");
                return root;
            }
            final float[] out = policy.lastOutput();
            if (out == null || out.length != req.size() + 1) {
                runner.anchor = null; // never leave a stale anchor behind an unsearched decision
                return notSearched(root, req, info, "no policy output");
            }
            final double[] l = new double[req.size()];
            for (int i = 0; i < l.length; i++) {
                l[i] = out[i];
            }
            final double v0 = clamp01((out[out.length - 1] + 1) / 2.0);
            lastPi = null;
            try {
                search(req, info, l, v0, this::gate);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable e) {
                STATS.fail("gumbel", e);
                return notSearched(root, req, info, "search failed");
            }
            final double[] pi = lastPi;
            if (pi == null || !Boolean.TRUE.equals(info.get("searched"))) {
                info.put("searched", false);
                return root;
            }
            final float[] improved = new float[pi.length];
            for (int i = 0; i < pi.length; i++) {
                improved[i] = (float) pi[i];
                if (!Float.isFinite(improved[i])) { // a diverged model: lose this target, not the worker at game end
                    return notSearched(root, req, info, "non-finite pi'");
                }
            }
            searchedGame++;
            return DecisionResponse.searched((Integer) info.get("played"), root.value(), improved);
        } finally {
            searchNanosGame += System.nanoTime() - t0;
        }
    }

    /** Training mode, not searched: the root sampled response unchanged (counted as a fallback). */
    private DecisionResponse notSearched(DecisionResponse root, DecisionRequest req, Map<String, Object> info,
                                         String reason) {
        fallback(req, info, reason, null);
        return root;
    }

    /** Training mode: search this copy point iff u < f, u from (game seed, seat, decision), independent of the policy. */
    private boolean gate() {
        return gate(gameSeed, seat, decisionNo, frac);
    }

    /** The gate of decision {@code decisionNo} (1-based) of {@code seat}: u < f, u from mix(seed, seat, decision, 0x5EA4C4). */
    public static boolean gate(long gameSeed, int seat, int decisionNo, double frac) {
        return new Random(mix(mix(gameSeed, seat, decisionNo), 0x5EA4C4L, 0)).nextDouble() < frac;
    }

    private int choose(DecisionRequest req, Map<String, Object> info) {
        info.put("kind", req.kind().name());
        if (req.size() == 1) {
            info.put("searched", false);
            info.put("reason", "single candidate");
            return 0;
        }
        final float[] out;
        try { // step 1: the root forward
            policy.decide(req);
            out = policy.lastOutput();
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            STATS.fail("policy", e);
            return fallback(req, info, "policy forward failed", null);
        }
        if (out == null || out.length < 2) {
            return fallback(req, info, "no policy output", null);
        }
        final int c = Math.min(req.size(), out.length - 1);
        final double[] l = new double[c];
        for (int i = 0; i < c; i++) {
            l[i] = out[i];
        }
        final double v0 = clamp01((out[out.length - 1] + 1) / 2.0);
        try {
            return search(req, info, l, v0, null);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            STATS.fail("gumbel", e);
            return fallback(req, info, "search failed", l);
        }
    }

    /** The policy's answer (argmax of the logits, lowest index on a tie), not Forge's. */
    private int fallback(DecisionRequest req, Map<String, Object> info, String reason, double[] l) {
        STATS.fallback(req.kind(), reason);
        info.put("searched", false);
        info.put("reason", reason);
        return l == null ? 0 : argmax(l);
    }

    /** {@code gate}: training mode's copy-point gate ({@link SimRunner#prepareAnchor}), null = eval (every copy point). */
    private int search(DecisionRequest req, Map<String, Object> info, double[] l, double v0,
                       java.util.function.BooleanSupplier gate) {
        if (!runner.attached()) {
            return fallback(req, info, "unattached", l);
        }
        final String why = runner.prepareAnchor(req, gate);
        if (why != null) {
            return fallback(req, info, why, l);
        }
        final SimRunner.Anchor a = runner.anchor;
        final int c = l.length;
        final int n = Math.max(1, cfg.budget);
        final long dseed = mix(gameSeed, seat, decisionNo);
        // step 2: Gumbel-top-m of g + l
        final double[] g = new double[c];
        if (sample) {
            final Random r = new Random(dseed ^ 0x6A09E667F3BCC908L);
            for (int i = 0; i < c; i++) {
                final double u = Math.max(1e-12, Math.min(1 - 1e-12, r.nextDouble()));
                g[i] = -Math.log(-Math.log(u));
            }
        }
        final int mEff = effectiveM(m, c, n);
        List<Integer> rem = rank(c, i -> g[i] + l[i]).subList(0, mEff);
        final List<Integer> top = new ArrayList<>(rem);
        // step 3: sequential halving
        final double[][] val = new double[c][];
        for (int i : rem) {
            val[i] = new double[n];
            java.util.Arrays.fill(val[i], Double.NaN);
        }
        final Map<String, Integer> fails = new HashMap<>();
        double[] q = new double[c];
        int[] cnt = new int[c];
        int simsRun = 0;
        int jDone = 0;
        int phasesRun = 0;
        final List<Phase> plan = mEff > 1 ? plan(n, mEff) : List.<Phase>of();
        for (Phase ph : plan) {
            final List<int[]> tasks = new ArrayList<>(); // {candidate, j}: j-major, so the workers stay balanced
            for (int j = 0; j <= ph.perAction(); j++) {
                for (int r = 0; r < rem.size(); r++) {
                    if (j < ph.perAction() || j == ph.perAction() && r < ph.extra()) {
                        tasks.add(new int[] {rem.get(r), jDone + j});
                    }
                }
            }
            for (double[] res : runner.runTasks(req, a, tasks, dseed, fails)) {
                if (!Double.isNaN(res[1])) {
                    val[(int) res[0]][(int) res[2]] = res[1];
                }
            }
            simsRun += tasks.size();
            jDone += ph.perAction();
            phasesRun++;
            q = new double[c];
            cnt = new int[c];
            tally(val, q, cnt);
            if (sum(cnt) == 0) {
                break; // nothing usable so far: the policy answers
            }
            if (phasesRun < plan.size()) {
                final double[] score = scores(g, l, q, cnt, v0);
                final List<Integer> keep = new ArrayList<>(rem);
                keep.sort((x, y) -> score[y] != score[x] ? Double.compare(score[y], score[x]) : Integer.compare(x, y));
                rem = keep.subList(0, (rem.size() + 1) / 2);
            }
        }
        final int ok = sum(cnt);
        info.put("sims", simsRun);
        info.put("ok", ok);
        info.put("c", c);
        info.put("m", mEff);
        info.put("phases", phasesRun);
        info.put("v0", round3(v0));
        if (!fails.isEmpty()) {
            info.put("fail", fails);
        }
        if (mEff > 1 && ok == 0) {
            return fallback(req, info, "all sims failed", l);
        }
        // step 4: play argmax g + l + sigma(completed q) over the survivors
        final double[] score = scores(g, l, q, cnt, v0);
        int best = rem.get(0);
        for (int i : rem) {
            if (score[i] > score[best] + 1e-12 || Math.abs(score[i] - score[best]) <= 1e-12 && i < best) {
                best = i;
            }
        }
        // step 5: improved policy pi' = softmax(l + sigma(completedQ)), completedQ = q if visited else v_mix
        final double vmix = mixedValue(l, v0, q, cnt);
        final int maxN = max(cnt);
        final double[] z = new double[c];
        for (int i = 0; i < c; i++) {
            z[i] = l[i] + sigma(cnt[i] > 0 ? q[i] : vmix, maxN);
        }
        final double[] pi = softmax(z);
        if (mEff > 1) { // a search over >= 2 actions with a usable sim (ok > 0 above): training mode's target
            lastPi = pi;
        }
        final int argL = argmax(l);
        final List<Object> qs = new ArrayList<>();
        for (int i : top) {
            if (cnt[i] > 0) {
                qs.add(List.of(i, round3(q[i]), cnt[i]));
            }
        }
        final List<Object> pis = new ArrayList<>();
        for (double p : pi) {
            pis.add(Math.round(p * 10000) / 10000.0);
        }
        info.put("vmix", round3(vmix));
        info.put("top", top);
        info.put("q", qs);
        info.put("pi", pis);
        info.put("played", best);
        info.put("argmaxL", argL);
        info.put("agree", best == argL);
        info.put("searched", true);
        STATS.searched.increment();
        STATS.byKind.computeIfAbsent(req.kind(), x -> new LongAdder[] {new LongAdder(), new LongAdder()})[0].increment();
        GSTATS.searched.increment();
        GSTATS.sims.add(simsRun);
        GSTATS.simsOk.add(ok);
        GSTATS.candidates.add(c);
        GSTATS.mUsed.add(mEff);
        if (best == argL) {
            GSTATS.agree.increment();
        }
        return best;
    }

    // ------------------------------------------------------------------ numbers

    static double sigma(double q, int maxN) {
        return (C_VISIT + maxN) * C_SCALE * q;
    }

    /** Per candidate: g + l + sigma(completed q), completed = q if visited else v_mix. */
    private static double[] scores(double[] g, double[] l, double[] q, int[] cnt, double v0) {
        final double vmix = mixedValue(l, v0, q, cnt);
        final int maxN = max(cnt);
        final double[] s = new double[l.length];
        for (int i = 0; i < s.length; i++) {
            s[i] = g[i] + l[i] + sigma(cnt[i] > 0 ? q[i] : vmix, maxN);
        }
        return s;
    }

    /**
     * The paper's mixed value: (v0 + sumN x sum_{N(b)>0} pi(b) q(b) / sum_{N(b)>0} pi(b)) / (1 + sumN), pi = softmax(l);
     * v0 alone when nothing is visited.
     */
    static double mixedValue(double[] l, double v0, double[] q, int[] cnt) {
        final double[] pi = softmax(l);
        double sumN = 0;
        double p = 0;
        double pq = 0;
        for (int i = 0; i < l.length; i++) {
            if (cnt[i] > 0) {
                sumN += cnt[i];
                p += pi[i];
                pq += pi[i] * q[i];
            }
        }
        return sumN == 0 || p <= 0 ? v0 : (v0 + sumN * pq / p) / (1 + sumN);
    }

    /** Mean of the usable sims per candidate, summed in j order (thread-count independent). */
    private static void tally(double[][] val, double[] q, int[] cnt) {
        for (int i = 0; i < val.length; i++) {
            if (val[i] == null) {
                continue;
            }
            double s = 0;
            for (double v : val[i]) {
                if (!Double.isNaN(v)) {
                    s += v;
                    cnt[i]++;
                }
            }
            q[i] = cnt[i] > 0 ? s / cnt[i] : 0;
        }
    }

    static double[] softmax(double[] z) {
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : z) {
            mx = Math.max(mx, v);
        }
        final double[] p = new double[z.length];
        double s = 0;
        for (int i = 0; i < z.length; i++) {
            p[i] = Math.exp(z[i] - mx);
            s += p[i];
        }
        for (int i = 0; i < z.length; i++) {
            p[i] /= s;
        }
        return p;
    }

    /** Indices 0..c-1 by key descending, lowest index first on a tie. */
    private static List<Integer> rank(int c, java.util.function.IntToDoubleFunction key) {
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < c; i++) {
            idx.add(i);
        }
        idx.sort((x, y) -> key.applyAsDouble(y) != key.applyAsDouble(x)
                ? Double.compare(key.applyAsDouble(y), key.applyAsDouble(x)) : Integer.compare(x, y));
        return idx;
    }

    private static int argmax(double[] v) {
        int b = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[b]) {
                b = i;
            }
        }
        return b;
    }

    private static int sum(int[] v) {
        int s = 0;
        for (int x : v) {
            s += x;
        }
        return s;
    }

    private static int max(int[] v) {
        int s = 0;
        for (int x : v) {
            s = Math.max(s, x);
        }
        return s;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
