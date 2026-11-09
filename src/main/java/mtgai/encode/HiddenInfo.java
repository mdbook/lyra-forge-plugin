package mtgai.encode;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.Zone;

/**
 * Hidden information, applied before encoding: {@code CardView.canBeShownTo} /
 * {@code canFaceDownBeShownTo} re-implemented on the game model (forge-game 2.0.15, forked), minus
 * the mind-slave branch (no Pauper card in v1 controls a player).
 */
public final class HiddenInfo {
    private HiddenInfo() {
    }

    /** May {@code viewer} see this card object at all (the token exists for them)? */
    public static boolean canSee(Card c, Player viewer) {
        final Zone z = c.getZone();
        if (z == null) {
            return true;
        }
        switch (z.getZoneType()) {
            case Ante, Command, Battlefield, Graveyard, Flashback, Stack, Junkyard, SchemeDeck:
                return true;
            case Exile, Merged:
                if (!c.isFaceDown()) {
                    return true;
                }
                break;
            case Hand:
                if (c.getController() == viewer) {
                    return true;
                }
                break;
            case Sideboard:
                if (!c.getController().isOpponentOf(viewer) || c.mayPlayerLook(viewer)) {
                    return true;
                }
                break;
            default:
                break;
        }
        return c.mayPlayerLook(viewer);
    }

    /** May {@code viewer} see the card's face (card id, stats)? False: token keeps card_id 0 + face-down bit. */
    public static boolean canSeeFace(Card c, Player viewer) {
        if (!c.isFaceDown() || c.mayPlayerLook(viewer)) {
            return true;
        }
        final Zone z = c.getZone();
        return z != null && (z.is(forge.game.zone.ZoneType.Battlefield) || z.is(forge.game.zone.ZoneType.Stack)
                || z.is(forge.game.zone.ZoneType.Sideboard)) && c.getController() == viewer;
    }
}
