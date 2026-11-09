package mtgai.match;

/**
 * Handicapped starts (curriculum lever, learner {@code --p-handicap} / {@code --handicaps}): one per game, applied to
 * one seat (the learner's; {@link HeadlessMatch.Config#handicaps}). Every variant is visible in that seat's
 * observation (life total, hand size, whose turn it is), so the value head is not trained on a hidden confounder.
 * <ul>
 * <li>{@link #LIFE14}: the seat starts at {@value #LIFE} life ({@code RegisteredPlayer.setStartingLife}).</li>
 * <li>{@link #MULL6}: the seat mulligans once and keeps 6 (London: draw 7, bottom 1; the bottom card is still the
 * seat's own decision); the keep / mulligan prompts are answered by the controller, not asked.</li>
 * <li>{@link #DRAW}: the seat is on the draw, whoever wins the coin toss ({@code chooseStartingPlayer}).</li>
 * </ul>
 */
public enum Handicap {
    LIFE14("life14"), MULL6("mull6"), DRAW("draw");

    /** Starting life of a {@link #LIFE14} seat. */
    public static final int LIFE = 14;
    /** Hand size a {@link #MULL6} seat keeps. */
    public static final int MULL_TO = 6;

    /** The name in latest.json {@code opponents[].handicap} and in the match row's {@code handicap}. */
    public final String id;

    Handicap(String id) {
        this.id = id;
    }

    /** {@code null} for {@code null}; else the variant named {@code id}; null for an unknown name (see {@link #known}). */
    public static Handicap of(String id) {
        if (id == null) {
            return null;
        }
        for (Handicap h : values()) {
            if (h.id.equals(id)) {
                return h;
            }
        }
        return null;
    }

    /** True for a variant name this worker can play. */
    public static boolean known(String id) {
        return of(id) != null;
    }
}
