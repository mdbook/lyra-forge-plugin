package mtgai.decision;

import mtgai.encode.CardVocab;

/**
 * The imitation teacher (learner {@code --p-teacher}): Forge's Default AI playing a seat
 * through the SAME decision surface as the neural seat. Like {@link DelegatingOracle} it answers every request with
 * {@link DecisionRequest#aiHint()} (Forge's own choice mapped onto the candidate list), but it does not take the stock
 * AI's pipeline shortcuts ({@link #aiPipeline()} false): a spell or ability the AI picks is played through the seat's
 * own pipeline, so its targets, modes, X and sacrifice / discard costs are separate requests, each answered with the
 * AI's hint for that request. With a vocab every request is encoded and recorded like a policy seat's; a step whose
 * answer is the hint carries TEACHER, a delegate miss (no mappable hint, candidate 0) does not.
 */
public final class TeacherOracle implements DecisionOracle {
    /** The only teacher today; the {@code latest.json} {@code opponents[].teacher} value. */
    public static final String FORGE_DEFAULT = "forge-default";

    private final CardVocab vocab;

    /** {@code vocab}: encode and record every request (selfplay); null = play only (bench / eval). */
    public TeacherOracle(CardVocab vocab) {
        this.vocab = vocab;
    }

    /** Teacher ids this build knows ({@code opponents[].teacher}). */
    public static boolean known(String teacher) {
        return FORGE_DEFAULT.equals(teacher);
    }

    @Override
    public boolean wantsAiHint() {
        return true;
    }

    @Override
    public boolean aiPipeline() {
        return false;
    }

    @Override
    public boolean teacher() {
        return true;
    }

    @Override
    public CardVocab observationVocab() {
        return vocab;
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        return DecisionResponse.of(req.aiHint() >= 0 && req.aiHint() < req.size() ? req.aiHint() : 0);
    }

    @Override
    public String name() {
        return "teacher-" + FORGE_DEFAULT;
    }
}
