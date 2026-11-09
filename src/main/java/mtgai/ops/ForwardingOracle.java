package mtgai.ops;

import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.encode.CardVocab;

/** Wraps an oracle, forwarding every call (base of {@link TimedOracle} and {@link DecisionLog}). */
public abstract class ForwardingOracle implements DecisionOracle {
    protected final DecisionOracle inner;

    protected ForwardingOracle(DecisionOracle inner) {
        this.inner = inner;
    }

    public DecisionOracle inner() {
        return inner;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        return inner.decide(request);
    }

    @Override
    public boolean wantsAiHint() {
        return inner.wantsAiHint();
    }

    @Override
    public boolean aiPipeline() {
        return inner.aiPipeline();
    }

    @Override
    public boolean teacher() {
        return inner.teacher();
    }

    @Override
    public CardVocab observationVocab() {
        return inner.observationVocab();
    }

    @Override
    public void onGameStart(long seed, int seat) {
        inner.onGameStart(seed, seat);
    }

    @Override
    public float[] lastOutput() {
        return inner.lastOutput();
    }

    @Override
    public void attach(mtgai.controller.PlayerControllerExternal controller) {
        inner.attach(controller);
    }

    @Override
    public java.util.Map<String, Object> lastInfo() {
        return inner.lastInfo();
    }

    @Override
    public String name() {
        return inner.name();
    }

    /** The innermost oracle of a wrapper chain. */
    public static DecisionOracle unwrap(DecisionOracle o) {
        while (o instanceof ForwardingOracle f) {
            o = f.inner;
        }
        return o;
    }
}
