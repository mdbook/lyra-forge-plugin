package mtgai.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import mtgai.controller.LegalActions;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionRequest;

/**
 * {@code mtgai play} with an onnx seat: a short state snapshot per decision (life, lands untapped, board, the deciding
 * seat's hand and exile, the opponent's hand size). Game thread only.
 */
final class PlayState {
    private PlayState() {
    }

    static String line(DecisionRequest r) {
        final Game g = game(r);
        if (g == null) {
            return "     [state: no engine object in the candidates]";
        }
        final StringBuilder b = new StringBuilder();
        for (Player p : g.getPlayers()) {
            final boolean me = p.getName().startsWith("s" + r.seat() + "-");
            final List<Card> bf = new ArrayList<>(p.getCardsIn(ZoneType.Battlefield));
            final long lands = bf.stream().filter(Card::isLand).count();
            final long untapped = bf.stream().filter(c -> c.isLand() && !c.isTapped()).count();
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(String.format(Locale.ROOT, "     [%s %s life %d | lands %d (untapped %d) | bf: %s | gy %d",
                    me ? "ME " : "OPP", p.getName(), p.getLife(), lands, untapped,
                    cards(bf.stream().filter(c -> !c.isLand()).toList(), true), p.getCardsIn(ZoneType.Graveyard).size()));
            if (me) {
                b.append(" | hand: ").append(cards(new ArrayList<>(p.getCardsIn(ZoneType.Hand)), false));
                b.append(" | exile: ").append(cards(new ArrayList<>(p.getCardsIn(ZoneType.Exile)), false));
            } else {
                b.append(" | hand#: ").append(p.getCardsIn(ZoneType.Hand).size());
            }
            b.append(']');
        }
        return b.toString();
    }

    private static Game game(DecisionRequest r) {
        for (Candidate c : r.candidates()) {
            final Object ref = c.ref();
            if (ref instanceof LegalActions.Action a) {
                return a.host.getGame();
            } else if (ref instanceof SpellAbility sa && sa.getHostCard() != null) {
                return sa.getHostCard().getGame();
            } else if (ref instanceof Card cd) {
                return cd.getGame();
            } else if (ref instanceof Player p) {
                return p.getGame();
            }
        }
        return null;
    }

    private static String cards(List<Card> cs, boolean detail) {
        final StringBuilder b = new StringBuilder();
        for (Card c : cs) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(c.getName());
            if (detail && c.isCreature()) {
                b.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
            }
            if (detail && c.isTapped()) {
                b.append(" (T)");
            }
            if (detail && c.isCreature() && c.isSick()) {
                b.append(" (sick)");
            }
        }
        return b.toString();
    }
}
