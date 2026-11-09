package mtgai.decision;

/**
 * An oracle's answer: the chosen candidate index, plus the behaviour-policy log-probability and value
 * for the trajectory record. {@code policy} is false for any answer not sampled from
 * the policy (random, scripted, delegated, fallback): the record gets NOT_POLICY and logp = value = 0.
 *
 * <p>{@code improved} (search targets, schema 3): the search's improved policy pi' over every candidate (request
 * order), set only by a training-mode {@code GumbelOracle} decision that searched; the record gets SEARCHED | NOT_POLICY
 * and an IMPROVED section. Null for everyone else.
 */
public record DecisionResponse(int index, float logp, float value, boolean policy, float[] improved) {
    public DecisionResponse(int index, float logp, float value, boolean policy) {
        this(index, logp, value, policy, null);
    }

    public static DecisionResponse of(int index) {
        return new DecisionResponse(index, 0f, 0f, false);
    }

    public static DecisionResponse sampled(int index, float logp, float value) {
        return new DecisionResponse(index, logp, value, true);
    }

    /** A searched decision: not a policy sample (logp 0), the root forward's {@code value}, pi' = {@code improved}. */
    public static DecisionResponse searched(int index, float value, float[] improved) {
        return new DecisionResponse(index, 0f, value, false, java.util.Objects.requireNonNull(improved));
    }
}
