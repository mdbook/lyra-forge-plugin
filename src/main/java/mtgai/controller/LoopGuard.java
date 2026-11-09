package mtgai.controller;

import java.util.HashMap;
import java.util.Map;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * No-progress loop guard. Counts, per seat and per turn, how often the seat plays
 * the same ability (host card name + ability description) from the same coarse game state (stack size; per player
 * life, poison, mana pool, hand / library / graveyard / exile / battlefield sizes, untapped permanents). Once a
 * (ability, state) pair has been played {@code -Dmtgai.loopGuard} times this turn (default 4, 0 = off) the ability
 * is refused from that state for the rest of the turn: the stock AI seat passes priority instead, the policy seat
 * no longer offers it as a candidate.
 *
 * <p>A legitimate repeat (casting the second copy of a card, pumping with mana) changes the state (hand size,
 * untapped lands), so it never counts as a repeat; a loop that returns to the same state (stock-AI Tron: Myr
 * Retriever x3 + Ashnod's Altar) does.
 */
public final class LoopGuard {
    public static final String PROPERTY = "mtgai.loopGuard";

    private final int limit;
    private final Map<String, Integer> plays = new HashMap<>();
    private int turn = -1;

    public LoopGuard() {
        this(Integer.getInteger(PROPERTY, 4));
    }

    public LoopGuard(int limit) {
        this.limit = limit;
    }

    /** True when playing {@code sa} now would repeat it from this state more than the limit allows this turn. */
    public boolean blocked(Game game, SpellAbility sa) {
        return limit > 0 && plays.getOrDefault(key(game, sa), 0) >= limit;
    }

    /** Records that {@code sa} is played now (call before it goes on the stack / its costs are paid). */
    public void played(Game game, SpellAbility sa) {
        if (limit > 0) {
            plays.merge(key(game, sa), 1, Integer::sum);
        }
    }

    private String key(Game game, SpellAbility sa) {
        final int t = game.getPhaseHandler().getTurn();
        if (t != turn) {
            plays.clear();
            turn = t;
        }
        final StringBuilder sb = new StringBuilder(128);
        sb.append(sa.getHostCard() == null ? "?" : sa.getHostCard().getName()).append('|')
                .append(sa.getDescription()).append('|').append(game.getStack().size());
        for (Player p : game.getPlayers()) {
            int untapped = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                untapped += c.isTapped() ? 0 : 1;
            }
            sb.append('|').append(p.getLife()).append(',').append(p.getPoisonCounters()).append(',')
                    .append(p.getManaPool().totalMana()).append(',').append(p.getZone(ZoneType.Hand).size())
                    .append(',').append(p.getZone(ZoneType.Library).size()).append(',')
                    .append(p.getZone(ZoneType.Graveyard).size()).append(',').append(p.getZone(ZoneType.Exile).size())
                    .append(',').append(p.getZone(ZoneType.Battlefield).size()).append(',').append(untapped);
        }
        return sb.toString();
    }
}
