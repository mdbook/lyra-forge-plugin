package mtgai.decision;

import java.util.Random;

/**
 * Seeded uniform-random policy. Uses its own {@link Random} (never MyRandom), so the engine's RNG
 * stream is the same whether a seat is driven by this oracle or by a {@link ScriptedOracle} replay.
 * Two knobs keep random games finite and representative (same as the original probe): PRIORITY passes
 * with probability {@code passP} when PASS is a candidate, otherwise picks uniformly among the
 * non-pass candidates; MULLIGAN keeps with probability {@code keepP}.
 */
public final class RandomOracle implements DecisionOracle {
    private final double passP;
    private final double keepP;
    private final long salt;
    private Random rnd;

    public RandomOracle() {
        this(0.3, 0.85, 0x5eed);
    }

    public RandomOracle(double passP, double keepP, long salt) {
        this.passP = passP;
        this.keepP = keepP;
        this.salt = salt;
        this.rnd = new Random(salt);
    }

    @Override
    public void onGameStart(long seed, int seat) {
        rnd = new Random(seed * 1_000_003L + seat * 7919L + salt);
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        final int n = req.size();
        if (req.kind() == DecisionKind.PRIORITY && req.candidates().get(0).type() == Candidate.Type.PASS) {
            if (n == 1 || rnd.nextDouble() < passP) {
                return DecisionResponse.of(0);
            }
            return DecisionResponse.of(1 + rnd.nextInt(n - 1));
        }
        if (req.kind() == DecisionKind.MULLIGAN) {
            return DecisionResponse.of(rnd.nextDouble() < keepP ? 0 : 1);
        }
        return DecisionResponse.of(rnd.nextInt(n));
    }

    @Override
    public String name() {
        return "random";
    }
}
