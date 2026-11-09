package mtgai.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Priority-window legal-action enumeration: forge-game legality predicates only
 * ({@code sa.canPlay}, {@code canCastTiming}, {@code checkRestrictions}, {@code isLegalAfterStack},
 * {@code isFullyTargetable}, {@code ComputerUtilCost.canPayCost}) -- never {@code AiController.canPlaySa},
 * which is AI willingness, not legality.
 *
 * <p>Cost variants are expanded here, as distinct candidates, instead of asking the controller:
 * alternative costs (Fireblast, spectacle, ...) come from {@code Card.getAllPossibleAbilities};
 * alternative-additional costs ({@code GameActionUtil.getAdditionalCostSpell}) and optional costs
 * (kicker, buyback, ...: {@code GameActionUtil.getOptionalCostValues}) are expanded into one candidate per
 * choice. So enumeration never re-enters the oracle ({@code chooseOptionalCosts} is pre-answered): when a
 * variant is played, the controller answers {@code getAbilityToPlay} / {@code chooseOptionalCosts} from the
 * chosen {@link Action}.
 */
public final class LegalActions {
    /** Up to this many optional costs, every subset is a candidate; above it, none / each single / all. */
    private static final int FULL_SUBSETS_MAX = 3;

    private LegalActions() {
    }

    /** One priority candidate. {@code base} is what goes into the PlaySpellAbility pipeline. */
    public static final class Action {
        public final Kind kind;
        public final Card host;
        public final SpellAbility base;
        /** Index into {@code getAdditionalCostSpell(base)} and that list's size (1 = no variants). */
        public final int addCostIndex;
        public final int addCostCount;
        /** Optional costs to pay (empty = none). */
        public final List<OptionalCostValue> optionalCosts;
        /** The fully-costed variant the legality checks ran on. */
        public final SpellAbility checked;
        public final String label;
        /**
         * Flag {@code float}: the mana this untap enabler floats before it is played; flag {@code floatb}: the mana a
         * {@link Kind#MANA} action floats ({@link FloatMana}); else null.
         */
        public FloatMana.Plan floatPlan;

        Action(Kind kind, Card host, SpellAbility base, int addCostIndex, int addCostCount,
               List<OptionalCostValue> optionalCosts, SpellAbility checked) {
            this.kind = kind;
            this.host = host;
            this.base = base;
            this.addCostIndex = addCostIndex;
            this.addCostCount = addCostCount;
            this.optionalCosts = optionalCosts;
            this.checked = checked;
            this.label = makeLabel();
        }

        private String makeLabel() {
            StringBuilder sb = new StringBuilder();
            sb.append(host.getName()).append(" (").append(host.getId()).append(")");
            if (kind != Kind.PLAY_LAND) {
                String d = checked.toUnsuppressedString();
                if (d.length() > 90) {
                    d = d.substring(0, 87) + "...";
                }
                sb.append(": ").append(d);
            }
            final String alt = altCost(checked);
            if (alt != null) {
                sb.append(" [alt ").append(alt).append("]");
            }
            if (addCostCount > 1) {
                sb.append(" [addcost ").append(addCostIndex).append("]");
            }
            if (!optionalCosts.isEmpty()) {
                sb.append(" [opt");
                for (OptionalCostValue v : optionalCosts) {
                    sb.append(' ').append(v.getType());
                }
                sb.append("]");
            }
            if (kind == Kind.MANA) {
                sb.append(" [float]");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return kind + " " + label;
        }
    }

    /** {@code MANA}: flag {@code floatb} only, tap a source for mana at priority (a float, {@link FloatMana}). */
    public enum Kind { PLAY_LAND, CAST, ACTIVATE, MANA }

    /**
     * The ref of the YES candidate when a Play effect asks "Do you want to play X?" (madness, cascade, suspend, rebound,
     * ...): {@code play} the Play ability, {@code card} the card it would cast. Encoder 7 reads the casting mode from it
     * (candidate byte 12); every other candidate byte stays as for a plain YES.
     */
    public record PlayEffectCast(SpellAbility play, Card card) {
    }

    /**
     * The alternative cost a variant is cast for, or null: keyword ones (Spectacle, Flashback, ...) by name,
     * a card's own static "rather than pay this spell's mana cost" (Fireblast) by its cost text.
     */
    public static String altCost(SpellAbility sa) {
        if (sa.getAlternativeCost() != null) {
            return sa.getAlternativeCost().toString();
        }
        return sa.isOptionalCostPaid(OptionalCost.AltCost) ? sa.getPayCosts().toSimpleString().replace("{0}, ", "") : null;
    }

    /**
     * All legal non-pass priority actions for {@code player}, in a deterministic order (lands, then
     * spells/abilities in {@code getAvailableCards} order). {@code exclude}: base SAs that already failed
     * in this window.
     */
    public static List<Action> enumerate(Player player, Set<SpellAbility> exclude) {
        return enumerate(player, exclude, null);
    }

    /** As {@link #enumerate(Player, Set)}; {@code stats} (nullable) counts joint-check refusals. */
    public static List<Action> enumerate(Player player, Set<SpellAbility> exclude, SeatStats stats) {
        final Game game = player.getGame();
        final List<Action> out = new ArrayList<>();

        // land drops (the helper returns null unless the stack is empty and it is a main phase)
        final CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
        if (lands != null) {
            for (Card land : lands) {
                for (SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                    if (sa.isLandAbility() && !exclude.contains(sa)) {
                        sa.setActivatingPlayer(player);
                        if (sa.canPlay()) {
                            out.add(new Action(Kind.PLAY_LAND, land, sa, 0, 1, List.of(), sa));
                        }
                    }
                }
            }
        }

        // spells and non-mana activated abilities (alternative costs are separate SAs already)
        final CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(game, player));
        for (Card card : cards) {
            for (SpellAbility sa : card.getAllPossibleAbilities(player, false)) {
                if (sa.isLandAbility() || sa.isManaAbility() || exclude.contains(sa)) {
                    continue;
                }
                sa.setActivatingPlayer(player);
                if (!sa.canPlay()) {
                    continue;
                }
                final List<SpellAbility> addVariants = GameActionUtil.getAdditionalCostSpell(sa);
                for (int i = 0; i < addVariants.size(); i++) {
                    final SpellAbility v = addVariants.get(i);
                    v.setActivatingPlayer(player);
                    final List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(v);
                    for (List<OptionalCostValue> subset : subsets(opts)) {
                        final SpellAbility checked = subset.isEmpty() ? v : GameActionUtil.addOptionalCosts(v, subset);
                        checked.setActivatingPlayer(player);
                        if (isLegal(checked, player)) {
                            if (!sacrificeAndManaJointlyPayable(checked, player)) {
                                if (stats != null) {
                                    stats.jointCostFiltered++;
                                }
                                continue;
                            }
                            out.add(new Action(sa.isSpell() ? Kind.CAST : Kind.ACTIVATE, card, sa, i,
                                    addVariants.size(), subset, checked));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The legality filter. */
    public static boolean isLegal(SpellAbility sa, Player player) {
        return sa.canPlay()
                && sa.canCastTiming(player)
                && sa.checkRestrictions(player)
                && sa.isLegalAfterStack()
                && ComputerUtilAbility.isFullyTargetable(sa)
                && ComputerUtilCost.canPayCost(sa, player, false);
    }

    /**
     * Joint sacrifice + mana check (the "chosen-but-failed" counter). {@code canPayCost} checks each cost
     * part on its own, so a permanent that is both the only thing to sacrifice and a sacrifice-for-mana source
     * (Eldrazi Spawn, Treasure) passes both checks, yet mana is paid first (Cost.sort) and the sacrifice part
     * then finds nothing: Makeshift Munitions "{1}, Sacrifice an artifact or creature" with a lone Spawn.
     * Permanents whose mana abilities do not sacrifice them (lands, dorks) stay sacrificeable after the mana
     * is paid; when those are too few, some sacrifice-for-mana candidates must be kept back: the mana part is
     * re-tested with them marked used-to-pay (their mana abilities unplayable), over every choice of them
     * (bounded; the case is rare). ExternalCostDecision / mana-source selection supersede it.
     */
    public static boolean sacrificeAndManaJointlyPayable(SpellAbility sa, Player player) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasManaCost() || cost.getTotalMana().isZero()) {
            return true;
        }
        for (CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostSacrifice cs) || cs.payCostFromSource() || "All".equals(cs.getAmount())) {
                continue;
            }
            final int n = cs.getAbilityAmount(sa);
            CardCollection valid = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield),
                    cs.getType().split(";"), player, sa.getHostCard(), sa);
            valid = CardLists.filter(valid, CardPredicates.canBeSacrificedBy(sa, false));
            final List<Card> consumed = new ArrayList<>();
            int durable = 0;
            for (Card c : valid) {
                if (sacrificesItselfForMana(c)) {
                    consumed.add(c);
                } else {
                    durable++;
                }
            }
            final int keep = n - durable;
            if (keep <= 0) {
                continue;
            }
            if (consumed.size() < keep) {
                return false; // canPayCost would not have passed; defensive
            }
            if (!anyKeepSetLeavesManaPayable(sa, player, consumed, keep, 0, new ArrayList<>(), new int[] {64})) {
                return false;
            }
        }
        return true;
    }

    private static boolean sacrificesItselfForMana(Card c) {
        for (SpellAbility m : c.getManaAbilities()) {
            if (ComputerUtilCost.isSacrificeSelfCost(m.getPayCosts())) {
                return true;
            }
        }
        return false;
    }

    /** Tries keep-sets of size {@code keep} from {@code pool} (at most {@code budget[0]} tests). */
    private static boolean anyKeepSetLeavesManaPayable(SpellAbility sa, Player player, List<Card> pool, int keep,
                                                       int from, List<Card> chosen, int[] budget) {
        if (chosen.size() == keep) {
            if (budget[0]-- <= 0) {
                return false;
            }
            for (Card c : chosen) {
                c.setUsedToPay(true);
            }
            try {
                return ComputerUtilMana.canPayManaCost(sa, player, 0, false);
            } finally {
                for (Card c : chosen) {
                    c.setUsedToPay(false);
                }
            }
        }
        for (int i = from; i < pool.size(); i++) {
            chosen.add(pool.get(i));
            final boolean ok = anyKeepSetLeavesManaPayable(sa, player, pool, keep, i + 1, chosen, budget);
            chosen.remove(chosen.size() - 1);
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** Optional-cost choices: all subsets for up to 3 costs, else none / each single / all. */
    static List<List<OptionalCostValue>> subsets(List<OptionalCostValue> opts) {
        final List<List<OptionalCostValue>> out = new ArrayList<>();
        out.add(List.of());
        final int n = opts.size();
        if (n == 0) {
            return out;
        }
        if (n <= FULL_SUBSETS_MAX) {
            for (int mask = 1; mask < (1 << n); mask++) {
                final List<OptionalCostValue> s = new ArrayList<>();
                for (int b = 0; b < n; b++) {
                    if ((mask & (1 << b)) != 0) {
                        s.add(opts.get(b));
                    }
                }
                out.add(s);
            }
        } else {
            for (OptionalCostValue v : opts) {
                out.add(List.of(v));
            }
            out.add(new ArrayList<>(opts));
        }
        return out;
    }

    /** Does the chosen optional-cost list of an action match {@code offered} by type? */
    static List<OptionalCostValue> pick(List<OptionalCostValue> offered, List<OptionalCostValue> wanted) {
        final List<OptionalCostValue> out = new ArrayList<>();
        for (OptionalCostValue w : wanted) {
            for (OptionalCostValue o : offered) {
                if (o.getType() == w.getType() && !out.contains(o)) {
                    out.add(o);
                    break;
                }
            }
        }
        return out;
    }

    static boolean sameOptional(SpellAbility a, SpellAbility b) {
        final List<OptionalCost> x = new ArrayList<>();
        a.getOptionalCosts().forEach(x::add);
        final List<OptionalCost> y = new ArrayList<>();
        b.getOptionalCosts().forEach(y::add);
        return x.size() == y.size() && x.containsAll(y);
    }
}
