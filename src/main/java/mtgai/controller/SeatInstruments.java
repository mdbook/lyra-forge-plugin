package mtgai.controller;

import forge.LobbyPlayer;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Seat instruments between the stock AI and {@link PlayerControllerExternal}: the delegated-decision
 * counter ({@link DelegationCounter}, generated) and the mana instruments around every heuristic mana payment
 * ({@link ManaInstruments}).
 */
public abstract class SeatInstruments extends DelegationCounter {
    protected SeatInstruments(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                               ManaConversionMatrix matrix, boolean effect) {
        if (delegationStats == null || guard()) {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        }
        final ManaInstruments m = new ManaInstruments(player, sa, toPay);
        final boolean ok = super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        if (ok) {
            m.after(delegationStats);
        }
        return ok;
    }
}
