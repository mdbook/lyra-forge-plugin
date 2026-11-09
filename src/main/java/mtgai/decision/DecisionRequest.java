package mtgai.decision;

import java.util.List;

/**
 * One decision point on the game thread.
 *
 * @param kind        the decision kind
 * @param seat        seat index of the deciding player (0 = first registered player)
 * @param turn        game turn number
 * @param phase       current phase/step name
 * @param ownTurn     true if the deciding player is the active player
 * @param candidates  the legal list (never empty, never above {@link #CANDIDATE_CAP})
 * @param aiHint      index Forge's own AI would pick, or -1 (only filled for oracles that ask, see
 *                    {@link DecisionOracle#wantsAiHint()})
 * @param context     short description (the spell being targeted, the prompt, ...)
 * @param observation the encoded observation ({@code mtgai.encode.Observation}) when the oracle asks for one
 *                    ({@link DecisionOracle#observationVocab()}), else null
 */
public record DecisionRequest(DecisionKind kind, int seat, int turn, String phase, boolean ownTurn,
                              List<Candidate> candidates,
                              int aiHint, String context, Object observation) {
    /** No decision ever carries more than 64 candidates. */
    public static final int CANDIDATE_CAP = 64;

    public int size() {
        return candidates.size();
    }
}
