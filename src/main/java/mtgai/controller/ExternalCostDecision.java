package mtgai.controller;

import java.util.function.Supplier;

import forge.ai.AiCostDecision;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostReturn;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import mtgai.decision.DecisionKind;

/**
 * Cost-part choices are policy decisions. For the plain "choose N cards of a type" form of
 * Sacrifice / Discard / Exile / TapType / Return (ninjutsu's unblocked attacker), the valid pool is computed
 * the way {@code HumanCostDecision} does and the seat picks N cards one at a time ({@code COST_*} kinds; the
 * AI's own choice is the hint). Everything else (the source itself, "All", random/same-name/total-CMC
 * variants, crew, costs of mana abilities, which the AI mana solver plans around) stays {@link AiCostDecision}; under
 * flag floatsac a mana ability the policy activates as an explicit float is the exception.
 */
final class ExternalCostDecision extends AiCostDecision {
    private final PlayerControllerExternal seat;

    ExternalCostDecision(PlayerControllerExternal seat, Player payer, SpellAbility sa, boolean effect) {
        super(payer, sa, effect);
        this.seat = seat;
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        final String t = cost.getType();
        if (!routed(cost) || "All".equals(cost.getAmount()) || "OriginalHost".equals(t) || t.contains("+")) {
            return super.visit(cost);
        }
        CardCollection pool = CardLists.filter(player.getCardsIn(ZoneType.Battlefield),
                CardPredicates.canBeSacrificedBy(ability, isEffect()));
        pool = CardLists.getValidCards(pool, t.split(";"), player, source, ability);
        return choose(DecisionKind.COST_SACRIFICE, cost, pool, () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        final String t = cost.getType();
        if (!routed(cost) || t.equals("Hand") || t.equals("LastDrawn") || t.equals("Random") || t.contains("+")) {
            return super.visit(cost);
        }
        final CardCollection pool = CardLists.getValidCards(player.getCardsIn(ZoneType.Hand), t.split(";"), player,
                source, ability);
        return choose(DecisionKind.COST_DISCARD, cost, pool, () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        final String t = cost.getType();
        if (!routed(cost) || t.equals("All") || t.equals("OriginalHost") || t.contains("+") || t.contains("FromTopGrave")
                || cost.zoneRestriction != 1 || cost.from.contains(ZoneType.Library)) {
            return super.visit(cost);
        }
        CardCollection pool = CardLists.getValidCards(player.getCardsIn(cost.from), t.split(";"), player, source, ability);
        pool = CardLists.filter(pool, CardPredicates.canExiledBy(ability, isEffect()));
        return choose(DecisionKind.COST_EXILE, cost, pool, () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        final String t = cost.getType();
        if (!routed(cost) || ability.isCrew() || t.equals("OriginalHost") || t.contains("+") || t.contains("sharesCreatureTypeWith")) {
            return super.visit(cost);
        }
        CardCollection pool = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), t.split(";"), player,
                source, ability);
        pool = CardLists.filter(pool, CardPredicates.CAN_TAP);
        if (!cost.canTapSource) {
            pool.remove(source);
        }
        return choose(DecisionKind.COST_TAP, cost, pool, () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        if (!routed(cost)) {
            return super.visit(cost);
        }
        final CardCollection pool = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), player, source, ability);
        return choose(DecisionKind.COST_RETURN, cost, pool, () -> super.visit(cost));
    }

    /**
     * Policy-owned unless the source pays for itself or this is a mana ability's cost (mana payment stays AI), except
     * a mana ability the policy activated itself as a float (flag floatsac: Ashnod's Altar's "Sacrifice a creature").
     */
    private boolean routed(CostPart cost) {
        return !cost.payCostFromSource() && (!ability.isManaAbility() || seat.playingSacFloat(ability))
                && player == seat.getPlayer();
    }

    private PaymentDecision choose(DecisionKind kind, CostPart cost, CardCollection pool, Supplier<PaymentDecision> ai) {
        final int n = cost.getAbilityAmount(ability);
        if (n <= 0) {
            return PaymentDecision.number(0);
        }
        if (pool.size() < n) {
            return null;
        }
        final CardCollectionView chosen = seat.pickCards(kind, pool, n, n, () -> {
            final PaymentDecision d = ai.get();
            return d == null ? null : d.cards;
        }, source.getName() + ": " + cost);
        return chosen.size() == n ? PaymentDecision.card(chosen) : null;
    }
}
