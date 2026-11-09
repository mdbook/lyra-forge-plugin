package mtgai.match;

/**
 * Cooperative cap abort: thrown on the game thread from a decision point when a turn,
 * decision or wall-time cap is exceeded. The engine ignores interrupts and Thread.stop throws on JDK 21,
 * so this is the only way to end a runaway game. It extends {@link Error}, not RuntimeException, so
 * engine code that catches Exception on the way up cannot swallow it.
 */
public final class MatchAbort extends Error {
    public enum Reason { TURN_CAP, DECISION_CAP, WALL_CAP }

    private final Reason reason;

    public MatchAbort(Reason reason, String detail) {
        super(reason + ": " + detail, null, false, true);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
