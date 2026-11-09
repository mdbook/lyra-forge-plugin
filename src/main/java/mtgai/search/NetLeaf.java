package mtgai.search;

import java.util.function.Supplier;

import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.match.MatchAbort;
import mtgai.match.Watchdog;
import mtgai.ops.ForwardingOracle;

import static mtgai.search.PimcOracle.STATS;

/**
 * The network rollout's horizon and leaf: the rollout seats are fresh policy
 * oracles on the shared session; at the FIRST oracle request at / after the horizon (a turn past the root's, or a
 * decision count past the root's, both seats counted: the {@link Watchdog} counts them) the request is forwarded once
 * more, its value head is the leaf's network half, and the rollout stops. The value is the acting seat's view
 * ({@code ~E[outcome]} in [-1, 1]), negated when the request was the opponent's. Replaces the prototype's "value of
 * the latest forward BEFORE the horizon". A rollout that ends without reaching the horizon request (game over: the
 * result is the leaf; the hard caps: material only) has no network half.
 */
final class NetLeaf {
    private final Supplier<DecisionOracle> net;
    private final Watchdog wd;
    private final int turnHorizon;
    private final int decisionHorizon;
    private final int deciding;
    private float value = Float.NaN;

    /**
     * @param turnHorizon     stop at the first request of a turn after this one (0 = off)
     * @param decisionHorizon stop at the first request after this many decisions on {@code wd} (0 = off)
     * @param deciding        the seat the leaf is for (the search seat)
     */
    NetLeaf(Supplier<DecisionOracle> net, Watchdog wd, int turnHorizon, int decisionHorizon, int deciding) {
        this.net = net;
        this.wd = wd;
        this.turnHorizon = turnHorizon;
        this.decisionHorizon = decisionHorizon;
        this.deciding = deciding;
    }

    /** True once a forward at the horizon produced a value. */
    boolean has() {
        return !Float.isNaN(value);
    }

    /** The leaf: 0.5 x material + 0.5 x (value + 1) / 2 from the deciding seat's view. */
    double mix(double material) {
        final double v01 = Math.max(0, Math.min(1, (value + 1) / 2.0));
        return 0.5 * material + 0.5 * v01;
    }

    /** Seat {@code s}'s rollout policy: a fresh oracle on the shared session, timed, forwarding once more at the horizon. */
    DecisionOracle seat(int s, long seed) {
        final DecisionOracle policy = net.get();
        policy.onGameStart(seed, s);
        return new ForwardingOracle(policy) {
            @Override
            public DecisionResponse decide(DecisionRequest r) {
                final long t0 = System.nanoTime();
                final DecisionResponse resp = inner.decide(r);
                STATS.netNanos.add(System.nanoTime() - t0);
                STATS.netForwards.increment();
                final boolean turn = turnHorizon > 0 && r.turn() > turnHorizon;
                if (turn || decisionHorizon > 0 && wd.decisions() > decisionHorizon) {
                    value = s == deciding ? resp.value() : -resp.value();
                    throw new MatchAbort(turn ? MatchAbort.Reason.TURN_CAP : MatchAbort.Reason.DECISION_CAP,
                            "net leaf: horizon request of seat " + s);
                }
                return resp;
            }
        };
    }
}
