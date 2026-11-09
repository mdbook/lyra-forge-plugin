package mtgai.match;

import java.util.ArrayList;
import java.util.List;

import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionRequest;
import mtgai.traj.TrajectoryWriter;

/**
 * The action-index log of one game (the canonical record: seed + per-seat action indices), plus an
 * optional listener for human-readable per-decision output ({@code mtgai play}).
 */
public final class GameRecord {
    /** One decision: seat, kind, candidate count, chosen index. */
    public record Action(int seat, DecisionKind kind, int candidates, int chosen) {
        @Override
        public String toString() {
            return seat + " " + kind + " " + chosen + "/" + candidates;
        }
    }

    public interface Listener {
        void onDecision(DecisionRequest request, int chosen);
    }

    private final List<Action> actions = new ArrayList<>();
    /** Per-seat trajectory steps (only for seats whose oracle asks for observations). */
    @SuppressWarnings("unchecked")
    private final List<TrajectoryWriter.Step>[] steps = new List[] {new ArrayList<>(), new ArrayList<>()};
    private final Listener listener;

    public GameRecord(Listener listener) {
        this.listener = listener;
    }

    public void record(DecisionRequest req, int chosen) {
        actions.add(new Action(req.seat(), req.kind(), req.size(), chosen));
        if (listener != null) {
            listener.onDecision(req, chosen);
        }
    }

    public void step(int seat, TrajectoryWriter.Step s) {
        steps[seat].add(s);
    }

    public List<TrajectoryWriter.Step> steps(int seat) {
        return steps[seat];
    }

    public List<Action> actions() {
        return actions;
    }

    /** The chosen indices of one seat, in order: the input of a {@code ScriptedOracle} replay. */
    public List<Integer> script(int seat) {
        List<Integer> out = new ArrayList<>();
        for (Action a : actions) {
            if (a.seat() == seat) {
                out.add(a.chosen());
            }
        }
        return out;
    }
}
