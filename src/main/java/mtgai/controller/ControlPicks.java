package mtgai.controller;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/**
 * Candidate refs of the encoder 8 decision kinds; the encoder reads their numbers into
 * candidate bytes 13-18 and the entity behind them like any CARD / PLAYER candidate.
 */
public final class ControlPicks {
    private ControlPicks() {
    }

    /**
     * COMBAT_DAMAGE: {@code amount} damage to {@code to} (a creature in the assignment, or the defender: a Player, or a
     * planeswalker / battle Card). {@code need}: damage {@code to} still needed for lethal before this pick (0 = it
     * already has lethal; the defender: its life / loyalty); {@code left}: damage left to assign before this pick.
     */
    public record DamagePick(GameEntity to, int amount, int need, int left, boolean defender) {
    }

    /**
     * MANA_SOURCE: activate {@code ability} of {@code source} producing {@code colours} (W1 U2 B4 R8 G16 C32; the chosen
     * colour of an any-colour / combo source, {@code express} = its express choice, null for a fixed source) for the cost
     * being paid; or, with {@code source} null, pay 2 life for a Phyrexian shard. {@code amount}: mana units it makes,
     * {@code left}: mana value still unpaid before this pick, {@code paid}: of which this pick pays, {@code needColours}:
     * the colours the unpaid cost still asks for (W1 U2 B4 R8 G16, 32 generic, 64 colourless-only),
     * {@code flags}: {@link #CREATURE} ... {@link #LIFE}.
     */
    public record ManaPick(Card source, SpellAbility ability, String express, int colours, int amount, int left, int paid,
                           int needColours, int flags) {
        public static final int CREATURE = 1;
        public static final int SAC_SELF = 2;
        public static final int OTHER_ABILITIES = 4;
        public static final int DRAWBACK = 8;     // the ability costs life / has a sub-ability (damage to you, ...)
        public static final int LAND = 16;
        public static final int ARTIFACT = 32;
        public static final int LIFE = 64;        // the "pay 2 life" option of a Phyrexian shard
        public static final int TAP_OTHER = 128;  // the ability taps / sacrifices another permanent (Springleaf Drum)
    }
}
