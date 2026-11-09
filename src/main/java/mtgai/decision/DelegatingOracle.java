package mtgai.decision;

/**
 * Forge's own AI routed through the seam: every request is answered with {@link DecisionRequest#aiHint()},
 * which the controller computes by asking the inherited {@code PlayerControllerAi} what it would do and
 * mapping that answer onto the legal candidate list. A hint that cannot be mapped (-1) counts as a
 * delegate miss in the seat's stats and falls back to candidate 0 (PASS / DONE / NO / KEEP / TOP by
 * convention). Used for the DelegatingOracle-vs-stock parity test.
 */
public final class DelegatingOracle implements DecisionOracle {
    @Override
    public boolean wantsAiHint() {
        return true;
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        return DecisionResponse.of(req.aiHint() >= 0 && req.aiHint() < req.size() ? req.aiHint() : 0);
    }

    @Override
    public String name() {
        return "delegating";
    }
}
