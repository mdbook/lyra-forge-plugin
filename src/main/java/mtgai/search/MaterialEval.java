package mtgai.search;

import forge.ai.CreatureEvaluator;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * PIMC leaf value: the game result when the rollout ended the game, else a material score adapted from
 * forge-ai's {@code GameStateEvaluator}, squashed to a win-ish number in [0, 1].
 *
 * <p>Adaptations, for a two-player Pauper race rather than Forge's own move picker: symmetric (both sides' board and
 * hand are scored; Forge scores only its own mana base); life is worth more (Forge: 2 per point, less than a vanilla
 * 2/2) and convex near zero, because both v1 decks win by burn/evasion races; cards in hand are worth about a third of
 * a creature (Forge: 4-5 points, which is about nothing). Creature, land and other-permanent values are Forge's own
 * ({@code CreatureEvaluator}, {@code GameStateEvaluator.evaluateLand}, 50 + 30 x mana value).
 */
public final class MaterialEval {
    /** Points per life point, and extra per point under {@link #DANGER_LIFE}. */
    public static final int LIFE = 10;
    public static final int DANGER_LIFE = 8;
    public static final int DANGER = 15;
    /** Points per card in hand. */
    public static final int HAND = 50;
    /** Points per poison counter (none in the v1 pair; kept for the other archetypes). */
    public static final int POISON = 40;
    /** Logistic scale: a 400-point lead (a good creature plus a few life) is ~0.73. */
    public static final double SCALE = 400;

    private final CreatureEvaluator creatures = new CreatureEvaluator();

    /** Leaf value for {@code me} in [0, 1]: 1 / 0 / 0.5 for a won / lost / drawn game. */
    public double value(Game g, Player me) {
        if (g.isGameOver() || me.hasLost() || me.hasWon()) {
            if (me.hasWon()) {
                return 1.0;
            }
            if (me.hasLost()) {
                return 0.0;
            }
            for (Player p : g.getPlayers()) {
                if (p != me && p.hasLost()) {
                    return 1.0;
                }
            }
            return 0.5;
        }
        return 1.0 / (1.0 + Math.exp(-score(g, me) / SCALE));
    }

    /** Material difference me - opponents (points). */
    public int score(Game g, Player me) {
        int s = 0;
        for (Player p : g.getPlayers()) {
            final int side = side(p);
            s += p == me ? side : -side;
        }
        return s;
    }

    int side(Player p) {
        final int life = p.getLife();
        int v = LIFE * life - DANGER * Math.max(0, DANGER_LIFE - life) - POISON * p.getPoisonCounters();
        v += HAND * p.getCardsIn(ZoneType.Hand).size();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            v += card(c);
        }
        return v;
    }

    int card(Card c) {
        if (c.isPhasedOut()) {
            return 0;
        }
        if (c.isCreature()) {
            return creatures.evaluateCreature(c);
        }
        if (c.isLand()) {
            return GameStateEvaluator.evaluateLand(c);
        }
        if (c.isEnchantingCard()) {
            return 0;
        }
        int value = 50 + 30 * c.getCMC();
        if (c.isPlaneswalker()) {
            value += 2 * c.getCounters(CounterEnumType.LOYALTY);
        }
        return value;
    }
}
