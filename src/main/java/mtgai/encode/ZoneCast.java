package mtgai.encode;

import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Encoder 7: how a card is cast, and whether a card in a
 * graveyard or exile can be cast from there.
 *
 * <p>{@link Mode} is the casting mode of a candidate (candidate byte 12) and of an object token's first zone route
 * (object byte 34). It is read from Forge's own classification of the SpellAbility ({@code getAlternativeCost()},
 * the optional costs, {@code getMayPlay()}, {@code isAdventure()} / {@code isAftermath()}, the keyword an ability
 * belongs to), never from card names. Append-only: a code never changes meaning.
 *
 * <p>{@link #object} is object byte 33 (+ 34): the routes by which a graveyard / exile card can be cast or played from
 * where it is, ignoring mana, timing and additional costs, from public information only.
 */
public final class ZoneCast {
    private ZoneCast() {
    }

    /** Casting mode (code = ordinal). Append-only. */
    public enum Mode {
        /** 0: not a cast / play / zone ability (or unknown). */
        NONE,
        /** 1: a spell cast or land played the normal way (no alternative cost, no permission). */
        NORMAL,
        FLASHBACK,
        MADNESS,
        /** 4: casting the Adventure half. */
        ADVENTURE,
        /** 5: casting the card from exile while it is on an adventure. */
        ADVENTURE_EXILE,
        FORETOLD,
        PLOTTED,
        ESCAPE,
        JUMP_START,
        RETRACE,
        DISTURB,
        EVOKE,
        AFTERMATH,
        HARMONIZE,
        MAYHEM,
        /** 16: any other keyword alternative cost (dash, blitz, spectacle, surge, prowl, emerge, bestow, ...). */
        OTHER_ALT,
        /** 17: a static "rather than pay this spell's mana cost" (Fireblast). */
        STATIC_ALT,
        /** 18: a may-play permission, card in exile (impulse draw, "you may cast it this turn"). */
        PERMISSION_EXILE,
        /** 19: a may-play permission, card in a graveyard. */
        PERMISSION_GRAVEYARD,
        /** 20: a may-play permission, card elsewhere (top of a library, ...). */
        PERMISSION_OTHER,
        /** 21: the cascade keyword's free cast (CONFIRM_ACTION YES). */
        CASCADE,
        /** 22: the suspend keyword's free cast (CONFIRM_ACTION YES). */
        SUSPEND,
        /** 23: any other Play effect's cast offer (CONFIRM_ACTION YES of an {@code ApiType.Play} effect). */
        PLAY_EFFECT,
        /** 24: casting the Omen half. */
        OMEN,
        /** 25: an activated ability used from a graveyard or exile (scavenge, encore, ...). */
        ZONE_ABILITY,
        /** 26: an activated ability used from the hand (cycling, ninjutsu, channel, ...). */
        HAND_ABILITY,
        UNEARTH,
        EMBALM,
        ETERNALIZE,
        /** 30: the rebound keyword's free cast from exile (CONFIRM_ACTION YES). */
        REBOUND;

        public int code() {
            return ordinal();
        }
    }

    /** Object byte 33 bits. */
    public static final int SELF = 1;
    public static final int OPP = 2;
    public static final int PERMISSION = 4;
    public static final int KEYWORD = 8;
    public static final int FREE = 16;
    public static final int ABILITY = 32;

    /**
     * The casting mode of a priority candidate: {@code sa} is the costed variant the legality checks ran on
     * ({@code LegalActions.Action.checked}), {@code host} its card.
     */
    public static Mode of(SpellAbility sa, Card host) {
        if (sa == null) {
            return Mode.NONE;
        }
        if (sa.isLandAbility()) {
            return sa.getMayPlay() != null ? permission(host) : Mode.NORMAL;
        }
        if (!sa.isSpell()) {
            if (host == null || host.isInPlay()) {
                return Mode.NONE;
            }
            if (sa.isKeyword(Keyword.UNEARTH)) {
                return Mode.UNEARTH;
            }
            if (sa.isEmbalm()) {
                return Mode.EMBALM;
            }
            if (sa.isEternalize()) {
                return Mode.ETERNALIZE;
            }
            return host.isInZone(ZoneType.Hand) ? Mode.HAND_ABILITY : Mode.ZONE_ABILITY;
        }
        if (sa.isAftermath() && host != null && host.isInZone(ZoneType.Graveyard)) {
            return Mode.AFTERMATH;
        }
        final AlternativeCost alt = sa.getAlternativeCost();
        if (alt != null) {
            return switch (alt) {
                case Flashback -> Mode.FLASHBACK;
                case Madness -> Mode.MADNESS;
                case Foretold -> Mode.FORETOLD;
                case Plotted -> Mode.PLOTTED;
                case Escape -> Mode.ESCAPE;
                case Disturb -> Mode.DISTURB;
                case Evoke -> Mode.EVOKE;
                case Harmonize -> Mode.HARMONIZE;
                case Mayhem -> Mode.MAYHEM;
                default -> Mode.OTHER_ALT;
            };
        }
        if (sa.isOptionalCostPaid(OptionalCost.Jumpstart)) {
            return Mode.JUMP_START;
        }
        if (sa.isOptionalCostPaid(OptionalCost.Retrace)) {
            return Mode.RETRACE;
        }
        if (sa.getMayPlay() != null) {
            return host != null && host.isOnAdventure() && host.isInZone(ZoneType.Exile) ? Mode.ADVENTURE_EXILE
                    : permission(host);
        }
        if (sa.isAdventure()) {
            return Mode.ADVENTURE;
        }
        if (sa.isOmen()) {
            return Mode.OMEN;
        }
        if (sa.isOptionalCostPaid(OptionalCost.AltCost)) {
            return Mode.STATIC_ALT;
        }
        return Mode.NORMAL;
    }

    /** The casting mode a Play effect offers (its CONFIRM_ACTION "Do you want to play X?" YES candidate). */
    public static Mode ofPlayEffect(SpellAbility play) {
        if (play.isKeyword(Keyword.MADNESS)) {
            return Mode.MADNESS;
        }
        if (play.isKeyword(Keyword.CASCADE)) {
            return Mode.CASCADE;
        }
        if (play.isKeyword(Keyword.SUSPEND)) {
            return Mode.SUSPEND;
        }
        if (play.isKeyword(Keyword.REBOUND)) {
            return Mode.REBOUND;
        }
        return Mode.PLAY_EFFECT;
    }

    private static Mode permission(Card host) {
        if (host == null) {
            return Mode.PERMISSION_OTHER;
        }
        if (host.isInZone(ZoneType.Exile)) {
            return Mode.PERMISSION_EXILE;
        }
        return host.isInZone(ZoneType.Graveyard) ? Mode.PERMISSION_GRAVEYARD : Mode.PERMISSION_OTHER;
    }

    /**
     * Object bytes 33 (low byte) and 34 (bits 8-15) for a card in a graveyard or in exile, as seen by {@code self}; 0
     * elsewhere. Byte 33: {@link #SELF} / {@link #OPP} the player may cast or play it from this zone, {@link #PERMISSION}
     * through a may-play permission ({@code Card.mayPlay}: impulse draw, "you may cast it this turn", an adventure in
     * exile, ...), {@link #KEYWORD} through its own zone-cast keyword or a spell ability whose activation zone is this
     * zone (flashback, escape, harmonize, mayhem, retrace, jump-start, foretold, plotted, disturb, aftermath),
     * {@link #FREE} some route needs no mana payment (plotted; a permission without paying its mana cost), {@link #ABILITY}
     * an activated ability usable from this zone (unearth, embalm, eternalize, scavenge, ...). A card's own routes belong
     * to its owner (CR: only the owner casts a card from their graveyard / their foretold or plotted card). Byte 34: the
     * {@link Mode} of the first route (self's before the opponent's, keyword before permission; an activated ability's
     * mode only when there is no cast route).
     *
     * <p>Ignores mana, timing and additional costs on purpose ("could cast it from here given mana and timing"), which
     * also keeps the opponent's hidden hand out of it (retrace / jump-start discards, escape's exile count are not
     * checked). Everything read is public for a face-up card in a public zone: its keywords, its spell abilities, the
     * may-play permissions (granted by public effects) and the foretold / plotted / discarded-this-turn marks. The
     * caller writes nothing for a card whose face {@code self} cannot see. Forge's {@code GameActionUtil.getAlternativeCosts}
     * is deliberately not called: with a static ability that affects the stack it re-runs
     * {@code checkStaticAbilities} on the live game, which an observation must never do; the keyword conditions below
     * mirror its zone gates instead (same keywords, same conditions, minus timing).
     */
    public static int object(Card c, Player self) {
        final ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        if (z != ZoneType.Graveyard && z != ZoneType.Exile) {
            return 0;
        }
        final Player owner = c.getOwner();
        final int ownerBit = owner == self ? SELF : OPP;
        int bits = 0;
        Mode selfMode = Mode.NONE;
        Mode oppMode = Mode.NONE;
        Mode abilityMode = Mode.NONE;

        // the card's own routes (owner only)
        Mode own = Mode.NONE;
        for (KeywordInterface inst : c.getKeywords()) {
            final Keyword k = inst.getKeyword();
            if (k == null) {
                continue;
            }
            final Mode m = z == ZoneType.Graveyard ? graveyardKeyword(c, k, inst.getOriginal()) : exileKeyword(c, k);
            if (m != Mode.NONE) {
                own = own == Mode.NONE ? m : own;
                if (m == Mode.PLOTTED) {
                    bits |= FREE;
                }
            }
        }
        if (z == ZoneType.Exile && own == Mode.NONE && c.isForetold() && c.isForetoldCostByEffect()
                && !c.enteredThisTurn()) {
            own = Mode.FORETOLD;   // foretold by an external source (its cost is the mana cost reduced by {2})
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isManaAbility() || sa.getRestrictions() == null || sa.getRestrictions().getZone() != z) {
                continue;
            }
            if (sa.isSpell() || sa.isLandAbility()) {
                if (own == Mode.NONE) {
                    final Mode m = of(sa, c);
                    own = m == Mode.NORMAL ? Mode.OTHER_ALT : m;
                }
            } else if (sa.isActivatedAbility()) {
                bits |= ABILITY;
                if (abilityMode == Mode.NONE) {
                    abilityMode = of(sa, c);
                }
            }
        }
        if (own != Mode.NONE) {
            bits |= KEYWORD | ownerBit;
            if (owner == self) {
                selfMode = own;
            } else {
                oppMode = own;
            }
        }

        // may-play permissions (any player)
        for (CardPlayOption o : c.getMayPlay().values()) {
            final Player p = o.getPlayer();
            if (p == null) {
                continue;
            }
            bits |= PERMISSION | (p == self ? SELF : OPP);
            if (o.getPayManaCost() == CardPlayOption.PayManaCost.NO) {
                bits |= FREE;
            }
            final Mode m = z == ZoneType.Exile && c.isOnAdventure() ? Mode.ADVENTURE_EXILE
                    : z == ZoneType.Exile ? Mode.PERMISSION_EXILE : Mode.PERMISSION_GRAVEYARD;
            if (p == self && selfMode == Mode.NONE) {
                selfMode = m;
            } else if (p != self && oppMode == Mode.NONE) {
                oppMode = m;
            }
        }
        final Mode first = selfMode != Mode.NONE ? selfMode : oppMode != Mode.NONE ? oppMode : abilityMode;
        return bits | first.code() << 8;
    }

    /** A graveyard zone-cast keyword route (the conditions of {@code GameActionUtil.getAlternativeCosts} /
     * {@code getOptionalCostValues} for a card in a graveyard), else NONE. */
    private static Mode graveyardKeyword(Card c, Keyword k, String original) {
        return switch (k) {
            // "Flashback" without its own cost on a card with no mana cost can't be cast (Forge skips it too)
            case FLASHBACK -> "Flashback".equals(original) && c.getManaCost().isNoCost() ? Mode.NONE : Mode.FLASHBACK;
            case HARMONIZE -> "Harmonize".equals(original) && c.getManaCost().isNoCost() ? Mode.NONE : Mode.HARMONIZE;
            case ESCAPE -> Mode.ESCAPE;
            case MAYHEM -> c.wasDiscarded() && c.enteredThisTurn() ? Mode.MAYHEM : Mode.NONE;
            case RETRACE -> Mode.RETRACE;
            case JUMP_START -> Mode.JUMP_START;
            default -> Mode.NONE;
        };
    }

    /** An exile zone-cast keyword route (foretold / plotted, not on the turn it was exiled), else NONE. */
    private static Mode exileKeyword(Card c, Keyword k) {
        if (c.enteredThisTurn()) {
            return Mode.NONE;
        }
        if (k == Keyword.FORETELL && c.isForetold()) {
            return Mode.FORETOLD;
        }
        if (k == Keyword.PLOT && c.isPlotted()) {
            return Mode.PLOTTED;
        }
        return Mode.NONE;
    }
}
