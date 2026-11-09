package mtgai.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.AiCardMemory;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardPlayOption;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPayment;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityManaConvert;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionKind;

/**
 * Encoder 8 mana payment (flag {@code mana}): the seat pays a mana cost
 * one source at a time, each MANA_SOURCE request = "which source (and colour) pays the next part of this cost".
 *
 * <p>Step: floating mana pays first (the AI payer's rule); then every usable mana ability of every available source
 * ({@code ComputerUtilMana.getAvailableManaSources}, the AI payer's own list), per colour it can make, is simulated on a
 * copy of the unpaid cost ({@code ManaCostBeingPaid.ai_payMana}, the shard choice the real payment makes). A pick is a
 * candidate only if it pays something AND the rest stays payable without it: the AI payer in test mode
 * ({@code ComputerUtilMana.canPayManaCost}) with that source held back ({@code HELD_MANA_SOURCES_FOR_NEXT_SPELL}, the
 * reservation the payer already honours). So the policy can never strand a cost: the AI's own next source always
 * qualifies, and every pick leaves a payable rest. Picks with the same effect (interchangeable sources: same name,
 * ability, counters, attachments, damage; and the same unpaid rest) are one candidate; one candidate = no request. A
 * payment where every usable unit of mana is needed (tapping out) is no choice either and goes to the AI payer.
 *
 * <p>The chosen ability is activated as the AI payer activates one (its own costs paid by {@code AiCostDecision},
 * resolved off the stack, its mana applied with {@code ManaPool.payManaFromAbility}); a Phyrexian shard also offers
 * "pay 2 life". Not modelled (the AI payer pays, counted in {@code manaDelegated}): offering / emerge, converge, an
 * AI-hint SVar ({@code ManaNeededToAvoidNegativeEffect}), snow shards, Adversary-style {@code NumTimes}, a payment the
 * AI payer cannot make either. Sources whose mana ability itself costs mana (Prophetic Prism) are not candidates; if
 * only such a source can finish, the AI payer finishes ({@code manaAiFinished} via the fallback, never stranded unless
 * the AI payer fails too: {@code manaStranded}, must be 0).
 */
final class ExternalManaPayment {
    private static final byte[] COLOURS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED,
        MagicColor.GREEN, (byte) ManaAtom.COLORLESS};

    private final PlayerControllerExternal seat;
    private final Player player;
    /**
     * Sacrifice-for-mana permanents (Eldrazi Spawn, Treasure) the payment keeps for a later sacrifice part of the same
     * cost (Makeshift Munitions: "{1}, Sacrifice an artifact or creature"; mana is paid first): never a candidate, held
     * back in every feasibility check (LegalActions.sacrificeAndManaJointlyPayable proved such a set exists).
     */
    private Set<Card> kept = Set.of();

    ExternalManaPayment(PlayerControllerExternal seat, Player player) {
        this.seat = seat;
        this.player = player;
    }

    /** Pays {@code toPay} for {@code sa}; null = not modelled, the caller's AI path pays. */
    Boolean pay(ManaCost toPay, SpellAbility sa, boolean effect) {
        final SeatStats stats = seat.seatStats();
        final Card host = sa.getHostCard();
        if (toPay.isZero() && toPay.countX() == 0) {
            return null; // {0} (a mana ability's empty mana part, ...): nothing to choose
        }
        if (host == null || sa.isOffering() || sa.isEmerge() || host.hasConverge()
                || host.hasSVar("ManaNeededToAvoidNegativeEffect") || "NumTimes".equals(sa.getParam("Announce"))) {
            stats.manaDelegated++;
            return null;
        }
        // the cost as the AI payer computes it, first in test mode (no side effects), so a payment left to the AI payer
        // is computed exactly once, by it
        final ManaCostBeingPaid test = ComputerUtilMana.calculateManaCost(new Cost(toPay, effect), sa, player, true, 0, effect);
        if (test.getUnpaidShards().contains(ManaCostShard.S) || !seat.asAi(() -> canPay(test, sa, effect, Set.of()))) {
            stats.manaDelegated++;
            return null;
        }
        if (tappingOut(test)) {
            stats.manaAutoResolved++;
            return null; // every usable mana is needed: no choice, the AI payer taps everything
        }
        final Set<Card> keep = keepForSacrifice(test, sa, effect);
        if (keep == null) {
            stats.manaDelegated++;
            return null;
        }
        final Set<Card> outer = kept; // a nested payment (a mana ability's own {0}) must not clobber it
        kept = keep;
        try {
            return payByPicks(toPay, sa, effect, host, stats);
        } finally {
            kept = outer;
        }
    }

    private Boolean payByPicks(ManaCost toPay, SpellAbility sa, boolean effect, Card host, SeatStats stats) {
        final ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(new Cost(toPay, effect), sa, player, false, 0, effect);
        final ManaInstruments instruments = new ManaInstruments(player, sa, toPay);
        stats.manaPolicyPayments++;
        AiCardMemory.clearMemorySet(player, AiCardMemory.MemorySet.PAYS_TAP_COST);
        AiCardMemory.clearMemorySet(player, AiCardMemory.MemorySet.PAYS_SAC_COST);
        final ManaPool pool = player.getManaPool();
        convert(pool, sa, effect);
        final List<Mana> spent = sa.getPayingMana();
        final List<Card> aiPlan = new ArrayList<>();
        final boolean[] planned = {false};
        final Set<Card> used = new HashSet<>();
        boolean ok = true;
        int guardLoops = 0;
        while (!cost.isPaid() && guardLoops++ < 64) {
            if (!pool.isEmpty() && pool.payManaCostFromPool(cost, sa, false, spent)) {
                break; // floating mana first, as the AI payer does
            }
            if (cost.isPaid()) {
                break;
            }
            final List<Candidate> cands = candidates(cost, sa, effect);
            if (cands.isEmpty()) {
                ok = false; // only a source the seam does not activate can pay the rest: the AI payer finishes
                break;
            }
            final int idx;
            if (cands.size() == 1) {
                stats.manaAutoResolved++;
                idx = 0;
            } else {
                final ManaCostBeingPaid plan = new ManaCostBeingPaid(cost);
                idx = seat.askControl(DecisionKind.MANA_SOURCE, cands, () -> {
                    if (!planned[0]) { // the AI payer's sources for the rest, in its order (test mode)
                        planned[0] = true;
                        final CardCollection c = ComputerUtilMana.getManaSourcesToPayCost(plan, sa, player, effect);
                        if (c != null) {
                            aiPlan.addAll(c);
                        }
                    }
                    int life = -1;
                    for (Card c : aiPlan) {
                        if (!used.contains(c)) {
                            for (int i = 0; i < cands.size(); i++) {
                                if (((ControlPicks.ManaPick) cands.get(i).ref()).source() == c) {
                                    return i;
                                }
                            }
                        }
                    }
                    for (int i = 0; i < cands.size(); i++) {
                        if (((ControlPicks.ManaPick) cands.get(i).ref()).source() == null) {
                            life = i;
                        }
                    }
                    return life;
                }, host.getName() + " " + cost);
            }
            final ControlPicks.ManaPick pick = (ControlPicks.ManaPick) cands.get(idx).ref();
            if (!apply(pick, cost, sa, effect)) {
                ok = false;
                break;
            }
            if (pick.source() != null) {
                used.add(pick.source());
            }
        }
        if (!ok || !cost.isPaid()) {
            // the AI payer finishes; it refunds everything spent so far if it fails
            if (!ComputerUtilMana.payManaCost(cost, sa, player, effect)) {
                stats.manaStranded++;
                stats.note("mana stranded: " + host.getName() + " " + toPay + ", unpaid " + cost);
                return false;
            }
        }
        instruments.after(stats);
        return true;
    }

    // ------------------------------------------------------------------ candidates

    private List<Candidate> candidates(ManaCostBeingPaid cost, SpellAbility sa, boolean effect) {
        final ManaPool pool = player.getManaPool();
        final int left = cost.getConvertedManaCost();
        final int need = needColours(cost);
        final Map<String, Candidate> out = new LinkedHashMap<>();
        final Map<String, Boolean> feasible = new java.util.HashMap<>();
        for (Card src : ComputerUtilMana.getAvailableManaSources(player, true)) {
            if (kept.contains(src)) {
                continue;
            }
            final List<SpellAbility> abilities = ComputerUtilMana.getAIPlayableMana(src);
            for (int ai = 0; ai < abilities.size(); ai++) {
                final SpellAbility ma = abilities.get(ai);
                ma.setActivatingPlayer(player);
                final AbilityManaPart mp = ma.getManaPart();
                if (mp == null || !ma.canPlay() || ma.getPayCosts().hasManaCost() || mp.isSpecialMana()
                        || ma.getApi() == forge.game.ability.ApiType.ManaReflected
                        || !CostPayment.canPayAdditionalCosts(ma.getPayCosts(), ma, false)
                        || !mp.meetsManaRestrictions(sa)) {
                    continue;
                }
                // the permanents its cost taps / sacrifices besides itself (Saruli Caretaker, Jaspera Sentinel: tap an
                // untapped creature), as the AI cost decision that pays it will choose them: held back too in the test
                final Set<Card> fodder = fodder(ma, effect);
                if (fodder == null || fodder.stream().anyMatch(kept::contains)) {
                    continue;
                }
                final boolean choice = mp.isAnyMana() || mp.isComboMana();
                final int amount = Math.max(1, ma.amountOfManaGenerated(false));
                for (byte c : choice ? COLOURS : new byte[] {0}) {
                    final String produced;
                    final String express;
                    if (choice) {
                        final String n = MagicColor.toShortString(c);
                        if (c == (byte) ManaAtom.COLORLESS || !(mp.isComboMana()
                                ? java.util.Arrays.asList(mp.getComboColors(ma).split(" ")).contains(n) : mp.canProduce(n, ma))) {
                            continue;
                        }
                        produced = String.join(" ", java.util.Collections.nCopies(amount, n));
                        express = mp.isComboMana() ? produced : n;
                    } else {
                        produced = GameActionUtil.generatedTotalMana(ma);
                        express = null;
                    }
                    if (produced.isEmpty()) {
                        continue;
                    }
                    final ManaCostBeingPaid sim = new ManaCostBeingPaid(cost);
                    int colours = 0;
                    boolean pays = false;
                    for (String unit : produced.split(" ")) {
                        final byte b = ManaAtom.fromName(unit);
                        if (b == 0 || !sa.allowsPayingWithShard(src, b)) {
                            continue;
                        }
                        colours |= colourBit(b);
                        pays |= sim.ai_payMana(unit, pool);
                    }
                    if (!pays) {
                        continue;
                    }
                    final String key = sourceKey(src, ai) + "#" + sim.toString();
                    if (out.containsKey(key)) {
                        continue;
                    }
                    final Set<Card> held = new java.util.LinkedHashSet<>(fodder);
                    held.add(src);
                    final StringBuilder fk = new StringBuilder(sourceKey(src, -1)).append('#').append(sim);
                    for (Card fc : fodder) {
                        fk.append('#').append(fc.getId());
                    }
                    final String fkey = fk.toString();
                    Boolean f = feasible.get(fkey);
                    if (f == null) {
                        f = sim.isPaid() || seat.asAi(() -> canPay(sim, sa, effect, held));
                        feasible.put(fkey, f);
                    }
                    if (!f) {
                        continue;
                    }
                    final int paid = left - sim.getConvertedManaCost();
                    final ControlPicks.ManaPick pick = new ControlPicks.ManaPick(src, ma, express, colours, amount, left,
                            paid, need, flags(src, ma));
                    out.put(key, new Candidate(Candidate.Type.CARD, src.getName() + " (" + src.getId() + ") "
                            + (choice ? MagicColor.toShortString(c) : produced) + (abilities.size() > 1 ? " #" + ai : ""), pick));
                }
            }
        }
        // a Phyrexian shard: pay 2 life instead
        if (cost.containsPhyrexianMana() && player.canPayLife(2, false, sa)
                && (player.getLife() > 2 || player.cantLoseForZeroOrLessLife())) {
            final ManaCostBeingPaid sim = new ManaCostBeingPaid(cost);
            sim.payPhyrexian();
            if (sim.isPaid() || seat.asAi(() -> canPay(sim, sa, effect, Set.of()))) {
                out.put("life#" + sim, new Candidate(Candidate.Type.OPTION, "pay 2 life",
                        new ControlPicks.ManaPick(null, null, null, 0, 0, left, left - sim.getConvertedManaCost(), need,
                                ControlPicks.ManaPick.LIFE)));
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Would the AI payer (test mode) pay {@code cost} with every card in {@code held} used up? Held cards are reserved
     * ({@code HELD_MANA_SOURCES_FOR_NEXT_SPELL}: not a mana source) and marked tapped for the duration of the check
     * (the raw flag, no event: not fodder for another source's "tap an untapped creature" cost either), then restored.
     */
    private boolean canPay(ManaCostBeingPaid cost, SpellAbility sa, boolean effect, Set<Card> held) {
        final List<Card> added = new ArrayList<>();
        final List<Card> tapped = new ArrayList<>();
        final Set<Card> all = new java.util.LinkedHashSet<>(held);
        all.addAll(kept);
        try {
            for (Card c : all) {
                if (!AiCardMemory.isRememberedCard(player, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
                    AiCardMemory.rememberCard(player, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                    added.add(c);
                }
                if (c.isUntapped() && c.isInPlay()) {
                    c.setTapped(true);
                    tapped.add(c);
                }
            }
            return ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(cost), sa, player, effect);
        } finally {
            for (Card c : tapped) {
                c.setTapped(false);
            }
            for (Card c : added) {
                AiCardMemory.forgetCard(player, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
        }
    }

    /**
     * {@link #kept}: for each "sacrifice N" part of {@code sa}'s cost (not of the source itself), the permanents that do
     * not sacrifice themselves for mana count as available; the shortfall is kept back from the sacrifice-for-mana ones,
     * the first such set (at most 64 tried) with which the AI payer can still pay {@code cost}. Empty when nothing needs
     * keeping; null when no set works (the payment goes to the AI payer).
     */
    private Set<Card> keepForSacrifice(ManaCostBeingPaid cost, SpellAbility sa, boolean effect) {
        final Cost c = sa.getPayCosts();
        if (c == null) {
            return Set.of();
        }
        final Set<Card> out = new java.util.LinkedHashSet<>();
        for (CostPart part : c.getCostParts()) {
            if (!(part instanceof CostSacrifice cs) || cs.payCostFromSource() || "All".equals(cs.getAmount())) {
                continue;
            }
            final int n = cs.getAbilityAmount(sa);
            CardCollection valid = forge.game.card.CardLists.getValidCards(player.getCardsIn(forge.game.zone.ZoneType.Battlefield),
                    cs.getType().split(";"), player, sa.getHostCard(), sa);
            valid = forge.game.card.CardLists.filter(valid, forge.game.card.CardPredicates.canBeSacrificedBy(sa, false));
            final List<Card> consumed = new ArrayList<>();
            int durable = 0;
            for (Card v : valid) {
                if (out.contains(v)) {
                    continue;
                }
                boolean sacMana = false;
                for (SpellAbility m : v.getManaAbilities()) {
                    sacMana |= forge.ai.ComputerUtilCost.isSacrificeSelfCost(m.getPayCosts());
                }
                if (sacMana) {
                    consumed.add(v);
                } else {
                    durable++;
                }
            }
            final int keep = n - durable;
            if (keep <= 0) {
                continue;
            }
            if (consumed.size() < keep) {
                return null;
            }
            final List<Card> pick = firstKeepSet(consumed, keep, cost, sa, effect, out);
            if (pick == null) {
                return null;
            }
            out.addAll(pick);
        }
        return out;
    }

    private List<Card> firstKeepSet(List<Card> pool, int keep, ManaCostBeingPaid cost, SpellAbility sa, boolean effect,
                                    Set<Card> already) {
        final int[] idx = new int[keep];
        for (int i = 0; i < keep; i++) {
            idx[i] = i;
        }
        for (int tried = 0; tried < 64; tried++) {
            final Set<Card> h = new java.util.LinkedHashSet<>(already);
            final List<Card> pick = new ArrayList<>();
            for (int i : idx) {
                pick.add(pool.get(i));
            }
            h.addAll(pick);
            if (seat.asAi(() -> canPay(cost, sa, effect, h))) {
                return pick;
            }
            int i = keep - 1;
            while (i >= 0 && idx[i] == pool.size() - keep + i) {
                i--;
            }
            if (i < 0) {
                return null;
            }
            idx[i]++;
            for (int k = i + 1; k < keep; k++) {
                idx[k] = idx[k - 1] + 1;
            }
        }
        return null;
    }

    /**
     * The other permanents {@code ma}'s cost taps or sacrifices, as {@code AiCostDecision} (which pays it in
     * {@link #apply}) chooses them; empty for the usual {T} / sacrifice-itself sources, null if it cannot be paid.
     */
    private Set<Card> fodder(SpellAbility ma, boolean effect) {
        final Set<Card> out = new java.util.LinkedHashSet<>();
        for (CostPart p : ma.getPayCosts().getCostParts()) {
            if (p instanceof CostTapType || p instanceof CostSacrifice cs && !cs.payCostFromSource()) {
                final forge.game.cost.PaymentDecision d = seat.asAi(() -> p.accept(new AiCostDecision(player, ma, effect, true)));
                if (d == null) {
                    return null;
                }
                out.addAll(d.cards);
            }
        }
        return out;
    }

    /**
     * Every usable unit of mana is needed (sources x their largest output == the unpaid mana value, nothing floating, no
     * Phyrexian shard): every complete payment taps every source, so the order and the shard each one pays leave the
     * same state. No decision.
     */
    private boolean tappingOut(ManaCostBeingPaid cost) {
        if (cost.containsPhyrexianMana() || cost.getXcounter() > 0 || !player.getManaPool().isEmpty()) {
            return false;
        }
        int units = 0;
        for (Card src : ComputerUtilMana.getAvailableManaSources(player, true)) {
            int best = 0;
            int usable = 0;
            for (SpellAbility ma : ComputerUtilMana.getAIPlayableMana(src)) {
                ma.setActivatingPlayer(player);
                if (ma.getManaPart() == null || !ma.canPlay()) {
                    continue;
                }
                usable++;
                best = Math.max(best, ma.amountOfManaGenerated(false));
                if (ma.getPayCosts().hasManaCost() || !ma.getPayCosts().isRenewableResource() || ma.getSubAbility() != null) {
                    return false; // a source with a real cost or a drawback: using it or not is a choice
                }
            }
            if (usable > 1) {
                return false; // e.g. a painland's colourless vs coloured ability
            }
            units += best;
        }
        return units == cost.getConvertedManaCost();
    }

    // ------------------------------------------------------------------ activation

    /** Activates {@code pick} and applies its mana to {@code cost}, as the AI payer does; false = it could not. */
    private boolean apply(ControlPicks.ManaPick pick, ManaCostBeingPaid cost, SpellAbility sa, boolean effect) {
        if (pick.source() == null) { // pay 2 life for a Phyrexian shard
            if (!cost.payPhyrexian()) {
                return false;
            }
            sa.setSpendPhyrexianMana(true);
            player.payLife(2, sa, false);
            return true;
        }
        final SpellAbility ma = pick.ability();
        ma.setActivatingPlayer(player);
        if (pick.express() != null) {
            ma.getManaPart().setExpressChoice(pick.express());
        }
        final CostPayment pay = new CostPayment(ma.getPayCosts(), ma);
        if (!pay.payComputerCosts(new AiCostDecision(player, ma, effect, true))) {
            return false;
        }
        player.getGame().getStack().addAndUnfreeze(ma);
        player.getManaPool().payManaFromAbility(sa, cost, ma);
        return true;
    }

    /** The mana-conversion setup of the AI payer (ComputerUtilMana.payManaCost) before the floating mana is used. */
    private void convert(ManaPool pool, SpellAbility sa, boolean effect) {
        if (player.getControllingPlayer() != null) {
            return;
        }
        pool.restoreColorReplacements();
        final CardPlayOption mayPlay = sa.getMayPlayOption();
        if (!effect) {
            if (sa.isSpell() && mayPlay != null) {
                mayPlay.applyManaConvert(pool);
            } else if (sa.isActivatedAbility() && sa.getGrantorStatic() != null
                    && sa.getGrantorStatic().hasParam("ManaConversion")) {
                AbilityUtils.applyManaColorConversion(pool, sa.getGrantorStatic().getParam("ManaConversion"));
            }
        }
        if (sa.hasParam("ManaConversion")) {
            AbilityUtils.applyManaColorConversion(pool, sa.getParam("ManaConversion"));
        }
        StaticAbilityManaConvert.manaConvert(pool, player, sa.getHostCard(), effect && !sa.isCastFromPlayEffect() ? null : sa);
    }

    // ------------------------------------------------------------------ features / keys

    /** Interchangeable sources: same name, ability (-1 = any), token, counters, damage, attachments, sickness. */
    static String sourceKey(Card c, int ability) {
        final StringBuilder b = new StringBuilder(c.getName()).append('|').append(ability).append('|').append(c.isToken())
                .append('|').append(c.getCounters()).append('|').append(c.getDamage()).append('|').append(c.isSick())
                .append('|').append(c.getZone() == null ? "" : c.getZone().getZoneType()).append('|')
                .append(c.isCreature());
        for (Card a : c.getAttachedCards()) {
            b.append('|').append(a.getId());
        }
        return b.toString();
    }

    static int flags(Card src, SpellAbility ma) {
        int f = 0;
        f |= src.isCreature() ? ControlPicks.ManaPick.CREATURE : 0;
        f |= src.isLand() ? ControlPicks.ManaPick.LAND : 0;
        f |= src.isArtifact() ? ControlPicks.ManaPick.ARTIFACT : 0;
        for (CostPart p : ma.getPayCosts().getCostParts()) {
            if (p instanceof CostSacrifice s) {
                f |= s.payCostFromSource() ? ControlPicks.ManaPick.SAC_SELF : ControlPicks.ManaPick.TAP_OTHER;
            } else if (p instanceof CostTapType) {
                f |= ControlPicks.ManaPick.TAP_OTHER;
            } else if (p instanceof CostPayLife) {
                f |= ControlPicks.ManaPick.DRAWBACK;
            }
        }
        if (ma.getSubAbility() != null) {
            f |= ControlPicks.ManaPick.DRAWBACK;
        }
        for (SpellAbility o : src.getSpellAbilities()) {
            if (o.isActivatedAbility() && !o.isManaAbility()) {
                f |= ControlPicks.ManaPick.OTHER_ABILITIES;
                break;
            }
        }
        return f;
    }

    /** W1 U2 B4 R8 G16 C32. */
    static int colourBit(byte b) {
        return switch (b) {
            case MagicColor.WHITE -> 1;
            case MagicColor.BLUE -> 2;
            case MagicColor.BLACK -> 4;
            case MagicColor.RED -> 8;
            case MagicColor.GREEN -> 16;
            default -> 32;
        };
    }

    /** What the unpaid cost still asks for: W1 U2 B4 R8 G16 (colour shards), 32 generic, 64 colourless-only. */
    private static int needColours(ManaCostBeingPaid cost) {
        int m = 0;
        for (ManaCostShard s : cost.getUnpaidShards()) {
            if (s.isGeneric() || s == ManaCostShard.X) {
                m |= 32;
            } else if (s.isColorless()) {
                m |= 64;
            } else {
                final byte c = s.getColorMask();
                m |= ((c & MagicColor.WHITE) != 0 ? 1 : 0) | ((c & MagicColor.BLUE) != 0 ? 2 : 0)
                        | ((c & MagicColor.BLACK) != 0 ? 4 : 0) | ((c & MagicColor.RED) != 0 ? 8 : 0)
                        | ((c & MagicColor.GREEN) != 0 ? 16 : 0);
            }
        }
        return m;
    }
}
