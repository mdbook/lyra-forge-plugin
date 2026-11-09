package mtgai.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Brute-force legal-but-hidden probe (the second must-be-zero counter). At a priority window it
 * enumerates every spell/ability of every card in the game the player could activate, with the same variant
 * expansion as {@link LegalActions}, and decides legality with the forge-game predicates plus an exact
 * mana-payment search instead of {@code ComputerUtilCost.canPayCost} (whose mana check is the AI's greedy
 * solver). An action the brute force finds payable that {@link LegalActions#enumerate} did not expose is
 * "hidden".
 *
 * <p>Exact mana search: every mana source is expanded into mana units (a colour mask each) over its
 * abilities: tap / sacrifice-self sources (one use; a card's {T} abilities are alternatives), filters with a
 * mana cost ({1},{T}: any colour; {2}: any colour, repeatable), and "sacrifice a creature" sources (Ashnod's
 * Altar, one use per creature you could sacrifice). Every combination of alternatives and filter uses is
 * tried; for each, the adjusted cost (affinity etc., via {@code ComputerUtilMana.calculateManaCost}) plus
 * the filters' own costs must match: coloured shards by bipartite matching, generic by the remaining units.
 * Sources with other costs (tap a creature, pay life, ...) or restricted mana are not modelled, so the
 * search is a lower bound on what is payable: a "payable" verdict is sound, "unpayable" may be wrong when
 * {@link Report#unmodelledSources} is non-zero. X is taken at its minimum.
 */
public final class LegalityProbe {
    private static final int W = 1;
    private static final int U = 2;
    private static final int B = 4;
    private static final int R = 8;
    private static final int G = 16;
    private static final int C = 32;
    private static final int ANY = W | U | B | R | G;
    private static final int COMBO_CAP = 4096;

    private LegalityProbe() {
    }

    /** Aggregated over every probed window. */
    public static final class Report {
        public long windows;
        public long candidates;           // brute-force variants examined (rules predicates evaluated)
        public long bruteLegal;           // ... that are legal with an exact payment
        public long exposed;              // LegalActions actions in the probed windows
        public long hidden;               // bruteLegal and not exposed  (must be 0)
        public long hiddenSimple;         // ... payable with plain sources only (tap / sac-self, no filter, no Altar)
        public long exposedUnpayable;     // exposed, exact search found no payment (lower bound; see class doc)
        public long unmodelledSources;    // windows x sources the exact search could not model
        public long searchCapped;         // combinations above the cap (verdict unknown)
        public final List<String> hiddenNotes = new ArrayList<>();
        public final List<String> hiddenSimpleNotes = new ArrayList<>();
        public final List<String> unpayableNotes = new ArrayList<>();
        public final Set<String> hiddenKinds = new HashSet<>();
        /** The last exact search's inputs (need; fixed units; choices), for the notes. */
        String lastSearch = "";

        @Override
        public String toString() {
            return String.format("windows %d, brute-force variants %d, brute-legal %d, exposed %d, HIDDEN %d (of which "
                            + "payable without filters/Altar %d), exposed-but-no-exact-payment %d, unmodelled source-windows %d,"
                            + " capped %d", windows, candidates, bruteLegal, exposed, hidden, hiddenSimple, exposedUnpayable,
                    unmodelledSources, searchCapped);
        }
    }

    /** Runs the probe for {@code player} at the current priority window against the exposed {@code legal}. */
    public static void probe(Player player, List<LegalActions.Action> legal, Report rep) {
        rep.windows++;
        rep.exposed += legal.size();
        final Set<String> exposedKeys = new HashSet<>();
        for (LegalActions.Action a : legal) {
            exposedKeys.add(key(a));
        }
        final Game game = player.getGame();
        final Set<String> seen = new HashSet<>();
        for (Card card : game.getCardsInGame()) {
            final List<SpellAbility> abilities;
            try {
                abilities = card.getAllPossibleAbilities(player, false);
            } catch (RuntimeException e) {
                continue; // odd zone / face-down states: not an action source
            }
            for (SpellAbility sa : abilities) {
                if (sa.isManaAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(player);
                if (sa.isLandAbility()) {
                    final LegalActions.Action a = new LegalActions.Action(LegalActions.Kind.PLAY_LAND, card, sa, 0, 1,
                            List.of(), sa);
                    rep.candidates++;
                    if (sa.canPlay() && game.getStack().isEmpty() && seen.add(key(a))) {
                        rep.bruteLegal++;
                        if (!exposedKeys.contains(key(a))) {
                            hidden(rep, a, "land");
                        }
                    }
                    continue;
                }
                if (!sa.canPlay()) {
                    continue;
                }
                final List<SpellAbility> addVariants = GameActionUtil.getAdditionalCostSpell(sa);
                for (int i = 0; i < addVariants.size(); i++) {
                    final SpellAbility v = addVariants.get(i);
                    v.setActivatingPlayer(player);
                    for (List<OptionalCostValue> subset : LegalActions.subsets(GameActionUtil.getOptionalCostValues(v))) {
                        final SpellAbility checked = subset.isEmpty() ? v : GameActionUtil.addOptionalCosts(v, subset);
                        checked.setActivatingPlayer(player);
                        final LegalActions.Action a = new LegalActions.Action(sa.isSpell() ? LegalActions.Kind.CAST
                                : LegalActions.Kind.ACTIVATE, card, sa, i, addVariants.size(), subset, checked);
                        rep.candidates++;
                        if (!seen.add(key(a)) || !rulesLegal(checked, player)) {
                            continue;
                        }
                        final int verdict = exactManaPayable(checked, player, rep);
                        final boolean isExposed = exposedKeys.contains(key(a));
                        if (verdict > 0) {
                            rep.bruteLegal++;
                            if (!isExposed) {
                                final boolean simple = exactManaPayable(checked, player, rep, true) > 0;
                                hidden(rep, a, simple ? "payable with plain sources" : "needs a filter / Altar");
                                if (simple) {
                                    rep.hiddenSimple++;
                                    if (rep.hiddenSimpleNotes.size() < 20) {
                                        rep.hiddenSimpleNotes.add(a + " @" + game.getPhaseHandler().getPhase() + " ["
                                                + rep.lastSearch + "]");
                                    }
                                }
                            }
                        } else if (verdict == 0 && isExposed) {
                            rep.exposedUnpayable++;
                            if (rep.unpayableNotes.size() < 20) {
                                rep.unpayableNotes.add(a.toString() + " @" + game.getPhaseHandler().getPhase()
                                        + " [" + rep.lastSearch + "]");
                            }
                        }
                    }
                }
            }
        }
    }

    private static void hidden(Report rep, LegalActions.Action a, String why) {
        rep.hidden++;
        rep.hiddenKinds.add(a.host.getName());
        if (rep.hiddenNotes.size() < 40) {
            rep.hiddenNotes.add(a + " (" + why + ") @" + a.host.getGame().getPhaseHandler().getPhase()
                    + " turn " + a.host.getGame().getPhaseHandler().getTurn());
        }
    }

    /**
     * Stable identity of a (variant) action, shared with the exposed list. Same-name cards in hand are one
     * action ({@code LegalActions} dedupes them via {@code ComputerUtilCard.dedupeCards}), so hand cards key
     * by name, everything else by card id.
     */
    static String key(LegalActions.Action a) {
        final StringBuilder sb = new StringBuilder();
        final String who = a.host.isInZone(ZoneType.Hand) && !a.host.hasPerpetual()
                ? "hand:" + a.host.getOwner().getId() + ":" + a.host.getName() : String.valueOf(a.host.getId());
        sb.append(a.kind).append('|').append(who).append('|').append(a.checked.toUnsuppressedString())
                .append('|').append(a.checked.getAlternativeCost()).append('|').append(a.addCostIndex);
        for (OptionalCostValue v : a.optionalCosts) {
            sb.append('|').append(v.getType());
        }
        return sb.toString();
    }

    /** {@link LegalActions#isLegal} minus the AI mana solver: forge-game predicates + non-mana cost parts. */
    static boolean rulesLegal(SpellAbility sa, Player player) {
        return sa.canPlay()
                && sa.canCastTiming(player)
                && sa.checkRestrictions(player)
                && sa.isLegalAfterStack()
                && ComputerUtilAbility.isFullyTargetable(sa)
                && CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false, player);
    }

    // ------------------------------------------------------------------ exact mana search

    /** One way a source can be used: units produced, mana it costs (as a cost being paid), or not usable. */
    private record Use(int[] units, ManaCostBeingPaid cost) {
    }

    /** A source: alternative uses (index 0 = unused), or a repeatable use with a max count. */
    private record Source(List<Use> alternatives, Use repeatable, int maxRepeats) {
    }

    /** 1 = payable, 0 = no payment found, -1 = unknown (search capped / unsupported cost). */
    static int exactManaPayable(SpellAbility sa, Player player, Report rep) {
        return exactManaPayable(sa, player, rep, false);
    }

    /** {@code plainOnly}: no filter uses (mana-cost mana abilities) and no sacrifice-a-creature sources. */
    static int exactManaPayable(SpellAbility sa, Player player, Report rep, boolean plainOnly) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasManaCost()) {
            return 1;
        }
        final ManaCostBeingPaid need;
        try {
            need = ComputerUtilMana.calculateManaCost(cost, sa, player, true, 0, false);
        } catch (RuntimeException e) {
            return -1;
        }
        final Card host = sa.getHostCard();
        final boolean hostBusy = cost.hasTapCost() || cost.getCostParts().stream()
                .anyMatch(p -> p instanceof CostSacrifice cs && cs.payCostFromSource());

        final List<int[]> fixedUnits = new ArrayList<>();
        final List<Source> choices = new ArrayList<>();
        // floating mana
        for (byte col : MagicColor.WUBRG) {
            for (int i = player.getManaPool().getAmountOfColor(col); i > 0; i--) {
                fixedUnits.add(new int[] {mask(col)});
            }
        }
        for (int i = player.getManaPool().getAmountOfColor((byte) ManaAtom.COLORLESS); i > 0; i--) {
            fixedUnits.add(new int[] {C});
        }
        final int creatures = CardLists.filter(player.getCreaturesInPlay(),
                CardPredicates.canBeSacrificedBy(null, false)).size();
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (hostBusy && c == host) {
                continue;
            }
            final List<Use> tapUses = new ArrayList<>();
            boolean unmodelled = false;
            for (SpellAbility m : c.getManaAbilities()) {
                m.setActivatingPlayer(player);
                if (!m.canPlay()) {
                    continue;
                }
                final AbilityManaPart mp = m.getManaPart();
                if (mp == null || mp.isSpecialMana() || (mp.getManaRestrictions() != null
                        && !mp.getManaRestrictions().isEmpty())) {
                    unmodelled = true;
                    continue;
                }
                final int[] units = units(m, mp);
                if (units == null) {
                    unmodelled = true;
                    continue;
                }
                final Cost mc = m.getPayCosts();
                boolean tap = false;
                boolean sacSelf = false;
                int sacCreature = 0;
                ManaCostBeingPaid manaIn = null;
                boolean other = false;
                for (CostPart p : mc.getCostParts()) {
                    if (p instanceof CostTap) {
                        tap = true;
                    } else if (p instanceof CostSacrifice cs && cs.payCostFromSource()) {
                        sacSelf = true;
                    } else if (p instanceof CostSacrifice cs && "Creature".equals(cs.getType())
                            && "1".equals(cs.getAmount())) {
                        sacCreature = 1;
                    } else if (p instanceof CostPartMana cpm) {
                        manaIn = new ManaCostBeingPaid(cpm.getManaCostFor(m));
                    } else {
                        other = true;
                    }
                }
                if (plainOnly && (manaIn != null || sacCreature > 0)) {
                    continue;
                }
                if (other) {
                    unmodelled = true;
                } else if (tap) {
                    tapUses.add(new Use(units, manaIn));
                } else if (sacCreature > 0) {
                    choices.add(new Source(List.of(), new Use(units, manaIn), creatures));
                } else if (sacSelf && manaIn == null) {
                    fixedUnits.add(units);
                } else if (!sacSelf && manaIn != null) {
                    choices.add(new Source(List.of(), new Use(units, manaIn), 3)); // Energy Refractor {2}: any
                } else {
                    unmodelled = true;
                }
            }
            if (unmodelled && !plainOnly) {
                rep.unmodelledSources++;
            }
            if (tapUses.size() == 1 && tapUses.get(0).cost() == null) {
                fixedUnits.add(tapUses.get(0).units());
            } else if (!tapUses.isEmpty()) {
                final List<Use> alts = new ArrayList<>();
                alts.add(null);
                alts.addAll(tapUses);
                choices.add(new Source(alts, null, 0));
            }
        }
        final StringBuilder dbg = new StringBuilder("need ").append(need).append(" units");
        for (int[] u : fixedUnits) {
            dbg.append(' ').append(java.util.Arrays.toString(u));
        }
        dbg.append(" choices ").append(choices.size());
        rep.lastSearch = dbg.toString();
        final int[] budget = {COMBO_CAP};
        final int r = search(need, fixedUnits, choices, 0, new ArrayList<>(), new ArrayList<>(), player, budget);
        if (r < 0 && !plainOnly) {
            rep.searchCapped++;
        }
        return r;
    }

    private static int search(ManaCostBeingPaid need, List<int[]> fixed, List<Source> choices, int idx,
                              List<int[]> extraUnits, List<ManaCostBeingPaid> extraCosts, Player player, int[] budget) {
        if (idx == choices.size()) {
            if (budget[0]-- <= 0) {
                return -1;
            }
            return feasible(need, fixed, extraUnits, extraCosts, player) ? 1 : 0;
        }
        final Source s = choices.get(idx);
        boolean capped = false;
        if (s.repeatable() != null) {
            for (int n = 0; n <= s.maxRepeats(); n++) {
                for (int k = 0; k < n; k++) {
                    extraUnits.add(s.repeatable().units());
                    if (s.repeatable().cost() != null) {
                        extraCosts.add(s.repeatable().cost());
                    }
                }
                final int r = search(need, fixed, choices, idx + 1, extraUnits, extraCosts, player, budget);
                for (int k = 0; k < n; k++) {
                    extraUnits.remove(extraUnits.size() - 1);
                    if (s.repeatable().cost() != null) {
                        extraCosts.remove(extraCosts.size() - 1);
                    }
                }
                if (r > 0) {
                    return 1;
                }
                capped |= r < 0;
            }
            return capped ? -1 : 0;
        }
        for (Use u : s.alternatives()) {
            if (u != null) {
                extraUnits.add(u.units());
                if (u.cost() != null) {
                    extraCosts.add(u.cost());
                }
            }
            final int r = search(need, fixed, choices, idx + 1, extraUnits, extraCosts, player, budget);
            if (u != null) {
                extraUnits.remove(extraUnits.size() - 1);
                if (u.cost() != null) {
                    extraCosts.remove(extraCosts.size() - 1);
                }
            }
            if (r > 0) {
                return 1;
            }
            capped |= r < 0;
        }
        return capped ? -1 : 0;
    }

    /** Coloured shards by bipartite matching onto units, generic by the units left over. */
    private static boolean feasible(ManaCostBeingPaid need, List<int[]> fixed, List<int[]> extraUnits,
                                    List<ManaCostBeingPaid> extraCosts, Player player) {
        final List<Integer> units = new ArrayList<>();
        for (int[] u : fixed) {
            for (int m : u) {
                units.add(m);
            }
        }
        for (int[] u : extraUnits) {
            for (int m : u) {
                units.add(m);
            }
        }
        final List<Integer> shards = new ArrayList<>();
        int generic = 0;
        int life = player.getLife();
        final List<ManaCostBeingPaid> all = new ArrayList<>(extraCosts);
        all.add(need);
        for (ManaCostBeingPaid mc : all) {
            for (ManaCostShard s : mc.getUnpaidShards()) {
                if (s == ManaCostShard.X) {
                    continue; // X at its minimum (0)
                }
                if (s.isPhyrexian() && life > 2) {
                    life -= 2;
                    continue;
                }
                final int m = shardMask(s);
                if (m == 0) {
                    generic++;
                } else {
                    shards.add(m);
                }
            }
        }
        if (units.size() < shards.size() + generic) {
            return false;
        }
        // Kuhn's augmenting paths: shard i -> unit
        final int[] unitOwner = new int[units.size()];
        java.util.Arrays.fill(unitOwner, -1);
        for (int i = 0; i < shards.size(); i++) {
            if (!augment(i, shards, units, unitOwner, new boolean[units.size()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(int shard, List<Integer> shards, List<Integer> units, int[] owner, boolean[] seen) {
        for (int u = 0; u < units.size(); u++) {
            if (!seen[u] && (units.get(u) & shards.get(shard)) != 0) {
                seen[u] = true;
                if (owner[u] < 0 || augment(owner[u], shards, units, owner, seen)) {
                    owner[u] = shard;
                    return true;
                }
            }
        }
        return false;
    }

    /** 0 = generic; else the unit colours that can pay the shard ({2/W} conservatively as W only). */
    private static int shardMask(ManaCostShard s) {
        final int colours = mask((byte) (s.getColorMask() & MagicColor.ALL_COLORS));
        if (s.isColorless()) {
            return C | colours; // {C} (or {C/W}-style hybrids)
        }
        if (colours == 0) {
            return 0; // generic, X, snow-as-generic
        }
        return colours;
    }

    /** WUBRG bits only (colourless is handled by the callers). */
    private static int mask(byte col) {
        int m = 0;
        if ((col & MagicColor.WHITE) != 0) {
            m |= W;
        }
        if ((col & MagicColor.BLUE) != 0) {
            m |= U;
        }
        if ((col & MagicColor.BLACK) != 0) {
            m |= B;
        }
        if ((col & MagicColor.RED) != 0) {
            m |= R;
        }
        if ((col & MagicColor.GREEN) != 0) {
            m |= G;
        }
        return m;
    }

    /** Units one activation produces, or null if the produced string is not modelled. */
    private static int[] units(SpellAbility m, AbilityManaPart mp) {
        int amount = 1;
        if (m.hasParam("Amount")) {
            try {
                amount = AbilityUtils.calculateAmount(m.getHostCard(), m.getParam("Amount"), m);
            } catch (RuntimeException e) {
                return null;
            }
        }
        final List<Integer> out = new ArrayList<>();
        if (mp.isComboMana()) {
            int cm = 0;
            for (String tok : mp.getComboColors(m).trim().split("\\s+")) {
                final int t = token(tok);
                if (t < 0) {
                    return null;
                }
                cm |= t;
            }
            for (int i = 0; i < amount; i++) {
                out.add(cm);
            }
        } else {
            final String produced = mp.getOrigProduced().trim();
            final List<Integer> once = new ArrayList<>();
            for (String tok : produced.split("\\s+")) {
                final int t = token(tok);
                if (t < 0) {
                    return null;
                }
                once.add(t);
            }
            if (once.size() > 1 && amount > 1) {
                return null;
            }
            for (int i = 0; i < amount; i++) {
                out.addAll(once);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int token(String tok) {
        switch (tok) {
            case "W": return W;
            case "U": return U;
            case "B": return B;
            case "R": return R;
            case "G": return G;
            case "C": return C;
            case "Any": return ANY;
            default: return -1;
        }
    }
}
