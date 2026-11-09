package mtgai.decision;

/**
 * The policy seam: answers every {@link DecisionRequest} synchronously on the game
 * thread. Implementations: {@link RandomOracle}, {@link ScriptedOracle}, {@link DelegatingOracle}; later
 * OnnxOracle / RemoteOracle / PimcOracle behind the same interface.
 */
public interface DecisionOracle {
    DecisionResponse decide(DecisionRequest request);

    /** If true the controller computes {@link DecisionRequest#aiHint()} (costly: runs Forge's AI). */
    default boolean wantsAiHint() {
        return false;
    }

    /**
     * With {@link #wantsAiHint()}: true = the seat plays the AI's own chosen priority action, triggers and effect casts
     * through the stock AI pipeline, so their sub-choices never become requests ({@link DelegatingOracle}, the parity
     * seam); false = every sub-choice is a request on the seat's own surface, each with its hint ({@link TeacherOracle}).
     */
    default boolean aiPipeline() {
        return wantsAiHint();
    }

    /** True: a recorded step whose answer is the request's AI hint is flagged TEACHER ({@link TeacherOracle}). */
    default boolean teacher() {
        return false;
    }

    /**
     * Non-null: the controller encodes every request ({@code ObservationEncoder} v0 with this vocab) into
     * {@link DecisionRequest#observation()} and records a trajectory step per decision.
     */
    default mtgai.encode.CardVocab observationVocab() {
        return null;
    }

    /**
     * The raw policy output behind the last {@link #decide} (logits[0..C) then the value), or null for a
     * non-model oracle; read by the per-decision debug log ({@code mtgai.ops.DecisionLog}).
     */
    default float[] lastOutput() {
        return null;
    }

    /** Called on the game thread before the first decision of each game. */
    default void onGameStart(long seed, int seat) {
    }

    /**
     * Called once by the seat's live controller when it is created, before the game starts: a search oracle
     * ({@code mtgai.search.PimcOracle}) needs the live game and player to copy. Default: ignored. Controllers built on
     * game copies (search rollouts) never call it.
     */
    default void attach(mtgai.controller.PlayerControllerExternal controller) {
    }

    /**
     * Search bookkeeping behind the last {@link #decide} (PIMC: searched or not, sims, ms, per-candidate values), or
     * null; written by the per-decision debug log ({@code mtgai.ops.DecisionLog}) as its {@code search} field.
     */
    default java.util.Map<String, Object> lastInfo() {
        return null;
    }

    String name();
}
