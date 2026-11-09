package mtgai.match;

import forge.game.Game;

/**
 * Per-game caps, checked at every decision point of every seat (external seats at each oracle request,
 * stock-AI seats at each priority/attack/block call). Throws {@link MatchAbort} on the game thread.
 * A cap of 0 disables it.
 */
public final class Watchdog {
    private final int turnCap;
    private final int decisionCap;
    private final long wallCapNanos;
    private final long startNanos = System.nanoTime();
    private int decisions;
    private MatchAbort fired;

    public Watchdog(int turnCap, int decisionCap, long wallCapMillis) {
        this.turnCap = turnCap;
        this.decisionCap = decisionCap;
        this.wallCapNanos = wallCapMillis * 1_000_000L;
    }

    /** Counts one decision and checks every cap. */
    public void onDecision(Game game) {
        decisions++;
        check(game);
    }

    public void check(Game game) {
        if (fired != null) {
            throw fired;
        }
        final int turn = game.getPhaseHandler().getTurn();
        if (turnCap > 0 && turn > turnCap) {
            fire(new MatchAbort(MatchAbort.Reason.TURN_CAP, "turn " + turn + " > " + turnCap));
        }
        if (decisionCap > 0 && decisions > decisionCap) {
            fire(new MatchAbort(MatchAbort.Reason.DECISION_CAP, decisions + " decisions > " + decisionCap));
        }
        if (wallCapNanos > 0 && System.nanoTime() - startNanos > wallCapNanos) {
            fire(new MatchAbort(MatchAbort.Reason.WALL_CAP,
                    (System.nanoTime() - startNanos) / 1_000_000 + " ms > " + wallCapNanos / 1_000_000));
        }
    }

    private void fire(MatchAbort a) {
        fired = a;
        throw a;
    }

    public int decisions() {
        return decisions;
    }

    public MatchAbort fired() {
        return fired;
    }
}
