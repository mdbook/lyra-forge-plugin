package mtgai.controller;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Encoder 8 decision groups: decisions the external seat used to delegate to the
 * inherited Forge AI, each group behind its own flag so the rollout can enable them one at a time. All off (the default)
 * = the seat behaves exactly like encoder 7 (same games, same requests, same records for the same seeds).
 *
 * <p>Set per match ({@code HeadlessMatch.Config.control}, CLI {@code --control damage,order,...|all|none}); the default
 * comes from the system property {@code mtgai.control} (same syntax, unset = none). {@code all} = the encoder 8 groups;
 * {@code float} / {@code floatb} / {@code floatsac} / {@code floatf} (default off) are never implied by it.
 */
public record Control(Set<Group> on) {
    public enum Group {
        /** {@code assignCombatDamage}: COMBAT_DAMAGE, which blocker / the defender gets the next chunk of damage. */
        DAMAGE,
        /** {@code orderSimultaneousSa}: TRIGGER_ORDER, which simultaneous trigger resolves next. */
        ORDER,
        /** {@code payManaCost}: MANA_SOURCE, which source pays the next part of a mana cost. */
        MANA,
        /** {@code chooseCardsForEffect}: CHOOSE_CARD, sequential picks with DONE. */
        EFFECTS,
        /** {@code chooseStartingPlayer}: PLAY_DRAW, play or draw after winning the toss. */
        PLAYDRAW,
        /**
         * Untap enablers float first ({@link FloatMana}, implicit float). Not
         * an encoder 8 group and not in {@link #ALL} until measured: only an explicit {@code float} turns it on.
         */
        FLOAT,
        /**
         * Explicit gated float action ({@link FloatMana}): a PRIORITY ACTIVATE candidate per mana source a
         * usable untap enabler could untap again. Replaces {@link #FLOAT}'s implicit float when both are named. Not in
         * {@link #ALL}: only an explicit {@code floatb} turns it on.
         */
        FLOATB,
        /**
         * Sacrifice floats ({@link FloatMana#sacFloatActions}): a PRIORITY float per own fixed-output sacrifice-cost mana
         * ability (Ashnod's Altar, Eldrazi Spawn), its sacrifice a policy COST_SACRIFICE decision. Independent of
         * {@link #FLOAT} / {@link #FLOATB}; not in {@link #ALL}: only an explicit {@code floatsac} turns it on.
         */
        FLOATSAC,
        /**
         * Filter floats ({@link FloatMana#filterFloatActions}): a PRIORITY float per own any-colour mana ability whose cost
         * has a mana part (Prophetic Prism, Conduit Pylons, Energy Refractor) and colour a spell in hand needs to become
         * castable. Independent of the other float flags; not in {@link #ALL}: only an explicit {@code floatf} turns it on.
         */
        FLOATF;

        public String flag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Control NONE = new Control(EnumSet.noneOf(Group.class));
    /** The encoder 8 groups ({@code all}): every group except the float flags, which must be named. */
    public static final Control ALL = new Control(EnumSet.complementOf(EnumSet.of(Group.FLOAT, Group.FLOATB, Group.FLOATSAC,
            Group.FLOATF)));
    /** The system property holding the default ({@link #fromSystemProperty()}). */
    public static final String PROPERTY = "mtgai.control";

    public Control {
        on = on.isEmpty() ? EnumSet.noneOf(Group.class) : EnumSet.copyOf(on);
    }

    public boolean has(Group g) {
        return on.contains(g);
    }

    public boolean none() {
        return on.isEmpty();
    }

    /** {@code "damage,order"}, {@code "all"}, {@code "none"} / empty / null = none. Unknown names are refused. */
    public static Control parse(String spec) {
        if (spec == null || spec.isBlank() || spec.trim().equalsIgnoreCase("none")) {
            return NONE;
        }
        if (spec.trim().equalsIgnoreCase("all")) {
            return ALL;
        }
        final EnumSet<Group> s = EnumSet.noneOf(Group.class);
        for (String t : spec.split(",")) {
            final String n = t.trim();
            if (n.isEmpty()) {
                continue;
            }
            try {
                s.add(Group.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown control group '" + n + "' (damage, order, mana, effects,"
                        + " playdraw, float, floatb, floatsac, floatf, all, none)");
            }
        }
        return new Control(s);
    }

    public static Control fromSystemProperty() {
        return parse(System.getProperty(PROPERTY));
    }

    /** {@code "damage,order"} (canonical order), {@code ""} for none. */
    @Override
    public String toString() {
        final StringBuilder b = new StringBuilder();
        for (Group g : on) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(g.flag());
        }
        return b.toString();
    }
}
