package mtgai.search;

import java.util.List;

import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.decision.RandomOracle;

/**
 * The deciding seat's oracle on a game copy: first replays a forced sequence (the decisions the live seat already
 * made since the copy point, then the root candidate under evaluation), each matched by kind and candidate text,
 * then hands over to the rollout policy: uniform random ({@link RandomOracle}) or Forge's own AI through the seam
 * (the request's AI hint, played through the stock AI pipeline like {@code DelegatingOracle}).
 */
final class RolloutOracle implements DecisionOracle {
    /** One forced answer: the request kind and the chosen candidate's {@code toString()} (type:label). */
    record Step(DecisionKind kind, String candidate) {
    }

    /** Thrown when the copy asks something other than the forced sequence expects (copy infidelity). */
    static final class Mismatch extends Error {
        Mismatch(String msg) {
            super(msg, null, false, false);
        }
    }

    private final List<Step> forced;
    private final boolean forgeAi;
    private final RandomOracle random;
    private int next;
    /** Called once when the root (the last forced step) has been answered. */
    private final Runnable onRoot;

    /** Non-null: the post-root policy is this oracle (the network, prototype) instead of Forge / random. */
    private final DecisionOracle policy;

    RolloutOracle(List<Step> forced, boolean forgeAi, long seed, Runnable onRoot) {
        this(forced, forgeAi, seed, onRoot, null);
    }

    RolloutOracle(List<Step> forced, boolean forgeAi, long seed, Runnable onRoot, DecisionOracle policy) {
        this.forced = forced;
        this.forgeAi = forgeAi && policy == null;
        this.random = this.forgeAi || policy != null ? null : new RandomOracle(0.3, 0.85, seed);
        this.onRoot = onRoot;
        this.policy = policy;
    }

    /** The network policy needs observations: the copy's seat builds an encoder when this is non-null. */
    @Override
    public mtgai.encode.CardVocab observationVocab() {
        return policy == null ? null : policy.observationVocab();
    }

    boolean replaying() {
        return next < forced.size();
    }

    @Override
    public boolean wantsAiHint() {
        return forgeAi && !replaying();
    }

    @Override
    public boolean aiPipeline() {
        return wantsAiHint();
    }

    /** Tests only ({@link PimcOracle#firstRequestHook}): sees the copy's first request, once; may throw to end the sim. */
    java.util.function.Consumer<DecisionRequest> firstRequest;

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        if (firstRequest != null) {
            final java.util.function.Consumer<DecisionRequest> f = firstRequest;
            firstRequest = null;
            f.accept(req);
        }
        if (replaying()) {
            final Step s = forced.get(next);
            if (req.kind() != s.kind()) {
                throw new Mismatch("expected " + s.kind() + " " + s.candidate() + ", copy asked " + req.kind()
                        + " " + req.candidates());
            }
            int idx = -1;
            for (int i = 0; i < req.size(); i++) {
                if (req.candidates().get(i).toString().equals(s.candidate())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new Mismatch("candidate " + s.candidate() + " not offered on the copy: " + req.candidates());
            }
            next++;
            if (!replaying() && onRoot != null) {
                onRoot.run();
            }
            return DecisionResponse.of(idx);
        }
        if (policy != null) {
            return policy.decide(req);
        }
        if (forgeAi) {
            return DecisionResponse.of(req.aiHint() >= 0 && req.aiHint() < req.size() ? req.aiHint() : 0);
        }
        return random.decide(req);
    }

    @Override
    public String name() {
        return "rollout";
    }
}
