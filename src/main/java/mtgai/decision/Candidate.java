package mtgai.decision;

/**
 * One legal option in a {@link DecisionRequest}. {@code ref} is the engine object behind it (a Card, a
 * SpellAbility, a Player, ...) and is only valid on the game thread for the duration of the request;
 * {@code label} is a stable human-readable description for logs.
 */
public record Candidate(Type type, String label, Object ref) {
    public enum Type {
        PASS, DONE, YES, NO, KEEP, MULLIGAN,
        PLAY_LAND, CAST, ACTIVATE,
        CARD, PLAYER, SPELL, MODE, NUMBER, OPTION, ABILITY,
        TOP, BOTTOM, GRAVEYARD
    }

    public static Candidate of(Type type, String label) {
        return new Candidate(type, label, null);
    }

    @Override
    public String toString() {
        return type + ":" + label;
    }
}
