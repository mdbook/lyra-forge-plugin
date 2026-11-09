package mtgai.decision;

import java.util.List;

/**
 * Replays a recorded action-index list for one seat (the canonical game record: seed + per-seat
 * action indices re-drive the seeded engine). Throws {@link ScriptDivergence} if the recorded index is
 * out of range, i.e. the replay left the recorded game; at the end of the script it delegates to
 * {@code after} (or throws when that is null).
 */
public final class ScriptedOracle implements DecisionOracle {
    private final List<Integer> script;
    private final DecisionOracle after;
    private int pos;

    public ScriptedOracle(List<Integer> script, DecisionOracle after) {
        this.script = script;
        this.after = after;
    }

    @Override
    public void onGameStart(long seed, int seat) {
        pos = 0;
        if (after != null) {
            after.onGameStart(seed, seat);
        }
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        if (pos >= script.size()) {
            if (after == null) {
                throw new ScriptDivergence("script exhausted after " + pos + " decisions at " + req.kind());
            }
            return after.decide(req);
        }
        final int idx = script.get(pos++);
        if (idx < 0 || idx >= req.size()) {
            throw new ScriptDivergence("decision " + (pos - 1) + " (" + req.kind() + "): recorded index " + idx
                    + " but only " + req.size() + " candidates");
        }
        return DecisionResponse.of(idx);
    }

    public int position() {
        return pos;
    }

    @Override
    public String name() {
        return "scripted";
    }

    public static final class ScriptDivergence extends RuntimeException {
        public ScriptDivergence(String msg) {
            super(msg);
        }
    }
}
