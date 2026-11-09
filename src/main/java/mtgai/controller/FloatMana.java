package mtgai.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Flag {@code float}: implicit float on an untap enabler.
 * When the policy activates a targeted Untap ability (Arbor Elf "Untap target Forest", Quirion Ranger) and none of the
 * mana sources it could untap is tapped (the untap would do nothing), the seat first taps the best such source for mana
 * (most units, attached "tapped for mana" extras such as Utopia Sprawl / Wild Growth included; ties by card id), then
 * plays the enabler, whose TARGET list no longer offers untapped permanents. The floated mana is spent first by the next
 * payment (ExternalManaPayment and the AI payer both pay from the pool first) and is lost at the step's end if unused.
 * No new candidate or decision kind: the enabler's ACTIVATE candidate carries the float's units / colours in bytes 13 / 16.
 *
 * <p>Flag {@code floatb} (replaces the implicit float when both flags are named): an explicit, gated float
 * action. On the seat's own turn, in a main phase, with an empty stack, every own untapped mana source that a legal untap
 * enabler (not the source itself) could target gets one {@link LegalActions.Kind#MANA} action (fixed-output, cost
 * exactly {T}, no any-colour / combo / sacrifice), deduped by {@code ExternalManaPayment.sourceKey} and appended after
 * the other actions; it plays through the ordinary pipeline (PlaySpellAbility; a mana ability resolves at once). The
 * enabler is offered only while one of its targets is tapped (else its untap is a no-op and it is hidden), so the line
 * is: float, enabler (TARGET pruned to the tapped source), cast. The hiding applies in float windows only, and a
 * hint-wanting seat (delegating / teacher / PIMC fallback) asks the stock AI before the float list is built, so Forge's
 * own float (UntapAi PoolExtraMana) keeps its Arbor Elf pick and a source it tapped is not offered.
 *
 * <p>Flag {@code floatsac} (independent of the two above): in the same window, one {@link LegalActions.Kind#MANA}
 * action per own fixed-output sacrifice-cost mana ability (Ashnod's Altar, Eldrazi Spawn), no enabler needed; the
 * sacrifice choice is the policy's (COST_SACRIFICE). Enables Myr Retriever x2 + Ashnod's Altar + Pactdoll Terror.
 *
 * <p>Flag {@code floatf} (independent of the three above): in the same window, one
 * {@link LegalActions.Kind#MANA} action per (own filter source, colour): an any-colour mana ability whose cost has a mana
 * part (Prophetic Prism, Conduit Pylons, Giant's Boulder "{1}, {T}: Add one mana of any color", Energy Refractor "{2}"),
 * for each colour a spell / ability not castable now would become castable with. Forge's AI payer never activates such
 * an ability ({@code ComputerUtilMana.getAIPlayableMana}), so without the float a black spell whose only black source is
 * a filter is never castable. The filter's own mana is paid by the normal payment path; the colour goes to the pool.
 */
public final class FloatMana {
    private static final Pattern PRODUCED = Pattern.compile("Produced\\$\\s*([^|]+)");
    private static final Pattern AMOUNT = Pattern.compile("Amount\\$\\s*(\\d+)");

    private FloatMana() {
    }

    /**
     * Which float behaviour a seat runs: none, implicit (flag {@code float}) or explicit (flag {@code floatb}, wins).
     * Flags {@code floatsac} / {@code floatf} are independent of the mode ({@link #sacFloatActions},
     * {@link #filterFloatActions}).
     */
    public enum Mode { OFF, IMPLICIT, EXPLICIT }

    public static Mode mode(Control c) {
        return c.has(Control.Group.FLOATB) ? Mode.EXPLICIT : c.has(Control.Group.FLOAT) ? Mode.IMPLICIT : Mode.OFF;
    }

    /**
     * A source's float: the source, its mana ability, units it makes (extras included), colours W1 U2 B4 R8 G16 C32 and
     * its MANA_SOURCE source flags (candidate byte 17; written only on a {@link LegalActions.Kind#MANA} candidate).
     * {@code express}: flag {@code floatf}, the colour choice the any-colour ability is activated with ("B"); else null.
     */
    public record Plan(Card source, SpellAbility ability, int units, int colours, int flags, String express) {
        public Plan(Card source, SpellAbility ability, int units, int colours, int flags) {
            this(source, ability, units, colours, flags, null);
        }
    }

    /** A targeted Untap ability with nothing else in it (a rider such as "It gains haste" makes the target matter). */
    static boolean isEnabler(SpellAbility sa) {
        return sa != null && sa.getApi() == ApiType.Untap && sa.usesTargeting() && !(sa instanceof AbilitySub)
                && sa.getSubAbility() == null;
    }

    /**
     * Flag on, after enumeration: sets {@link LegalActions.Action#floatPlan} on every enabler whose untap would do
     * nothing without a float and removes the enablers that can do nothing at all (no tapped own target, no source to
     * float, cost is only {T}: today's no-op candidate). Returns how many were removed.
     */
    static int prepare(List<LegalActions.Action> legal, Player player) {
        int hidden = 0;
        for (Iterator<LegalActions.Action> it = legal.iterator(); it.hasNext();) {
            final LegalActions.Action a = it.next();
            if (a.kind != LegalActions.Kind.ACTIVATE || !isEnabler(a.checked)) {
                continue;
            }
            final List<Card> targets = cardTargets(a.checked);
            if (targets.stream().anyMatch(c -> c.isTapped() && c.getController() == player)) {
                continue; // a real untap is available: no float
            }
            a.floatPlan = plan(a.checked, targets, player);
            if (a.floatPlan == null && targets.stream().noneMatch(Card::isTapped) && onlyTapsItself(a.checked.getPayCosts())) {
                it.remove();
                hidden++;
            }
        }
        return hidden;
    }

    /** TARGET pruning for an enabler: with a tapped own permanent among the targets, untapped ones are never useful. */
    static void pruneTargets(SpellAbility sa, List<? extends GameObject> targets, Player player) {
        if (!isEnabler(sa)) {
            return;
        }
        final boolean tappedOwn = targets.stream().anyMatch(e -> e instanceof Card c && c.isTapped() && c.getController() == player);
        if (tappedOwn) {
            targets.removeIf(e -> e instanceof Card c && c.isUntapped());
        }
    }

    /**
     * Taps {@code p}'s source for mana as the AI payer activates a source (ExternalManaPayment.apply), without paying a
     * cost: the mana floats. Returns the units added to the pool (0 = the source could not be activated any more).
     */
    static int floatMana(Plan p, Player player) {
        final SpellAbility ma = p.ability();
        ma.setActivatingPlayer(player);
        if (!p.source().isUntapped() || !p.source().isInPlay() || !ma.canPlay()) {
            return 0;
        }
        final int before = player.getManaPool().totalMana();
        final CostPayment pay = new CostPayment(ma.getPayCosts(), ma);
        if (!pay.payComputerCosts(new AiCostDecision(player, ma, false, true))) {
            return 0;
        }
        player.getGame().getStack().addAndUnfreeze(ma); // a mana ability resolves at once; TapsForMana extras fire with it
        return player.getManaPool().totalMana() - before;
    }

    private static List<Card> cardTargets(SpellAbility sa) {
        final List<Card> out = new ArrayList<>();
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt == null) {
            return out;
        }
        for (GameEntity e : tgt.getAllCandidates(sa)) {
            if (e instanceof Card c && sa.canTarget(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Best untapped own source among {@code targets}: fixed-output, {T}-only mana ability; most units, then lowest id. */
    private static Plan plan(SpellAbility enabler, List<Card> targets, Player player) {
        Plan best = null;
        for (Card c : targets) {
            if (c.getController() != player || !c.isUntapped() || c == enabler.getHostCard()) {
                continue;
            }
            for (Plan p : options(c, player)) {
                if (best == null || p.units() > best.units() || p.units() == best.units() && c.getId() < best.source().getId()) {
                    best = p;
                }
            }
        }
        return best;
    }

    /** {@code c}'s floatable mana abilities (fixed output, cost exactly {T}, playable now, units > 0), in ability order. */
    private static List<Plan> options(Card c, Player player) {
        final List<Plan> out = new ArrayList<>();
        for (SpellAbility ma : c.getManaAbilities()) {
            ma.setActivatingPlayer(player);
            if (!onlyTapsItself(ma.getPayCosts())) {
                continue;
            }
            final Plan p = fixedPlan(c, ma, true);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * {@code ma}'s float on {@code c} when it is a fixed-output mana ability (no any-colour / combo / special / reflected
     * mana, no sub-ability) playable now that makes units > 0, else null. The activating player must be set.
     * {@code extras}: count attached "tapped for mana" extras (only when the cost taps the source).
     */
    private static Plan fixedPlan(Card c, SpellAbility ma, boolean extras) {
        final AbilityManaPart mp = ma.getManaPart();
        if (mp == null || mp.isAnyMana() || mp.isComboMana() || mp.isSpecialMana() || ma.getSubAbility() != null
                || ma.getApi() == ApiType.ManaReflected || !ma.canPlay()) {
            return null;
        }
        final String produced = GameActionUtil.generatedTotalMana(ma);
        if (produced.isEmpty()) {
            return null;
        }
        int units = 0;
        int colours = 0;
        for (String unit : produced.split(" ")) {
            final byte b = ManaAtom.fromName(unit);
            if (b != 0) {
                units++;
                colours |= ExternalManaPayment.colourBit(b);
            }
        }
        if (extras) {
            final int[] extra = attachedExtras(c);
            units += extra[0];
            colours |= extra[1];
        }
        return units > 0 ? new Plan(c, ma, units, colours, ExternalManaPayment.flags(c, ma)) : null;
    }

    /** Own turn, a main phase, empty stack: the window floats are offered in (flags floatb / floatsac / floatf). */
    private static boolean floatWindow(Player player) {
        final Game game = player.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        return ph.isPlayerTurn(player) && ph.getPhase() != null && ph.getPhase().isMain() && game.getStack().isEmpty();
    }

    // ------------------------------------------------------------------ flag floatb (option B)

    /**
     * Flag {@code floatb}: the float actions for this window, to append after {@code legal} (the enumerated, loop-guarded
     * actions). Empty unless it is {@code player}'s turn, a main phase and the stack is empty; then one
     * {@link LegalActions.Kind#MANA} action per distinct ({@code sourceKey}, mana ability) among the own untapped
     * sources (fixed output, cost exactly {T}) that a legal untap enabler in {@code legal} can target and that is not the
     * enabler itself. Order: by source id, then ability. {@code exclude}: abilities that already failed this window.
     */
    public static List<LegalActions.Action> floatActions(List<LegalActions.Action> legal, Player player,
                                                         Set<SpellAbility> exclude) {
        if (!floatWindow(player)) {
            return new ArrayList<>();
        }
        final List<Card> sources = new ArrayList<>();
        for (LegalActions.Action e : legal) {
            if (e.kind != LegalActions.Kind.ACTIVATE || !isEnabler(e.checked)) {
                continue;
            }
            for (Card c : cardTargets(e.checked)) {
                if (c.getController() == player && c.isUntapped() && c != e.host && !sources.contains(c)) {
                    sources.add(c);
                }
            }
        }
        sources.sort(Comparator.comparingInt(Card::getId));
        final Map<String, LegalActions.Action> byKey = new LinkedHashMap<>();
        for (Card c : sources) {
            for (Plan p : options(c, player)) {
                if (exclude.contains(p.ability())) {
                    continue;
                }
                byKey.computeIfAbsent(key(c, p.ability()), k -> {
                    final LegalActions.Action a = new LegalActions.Action(LegalActions.Kind.MANA, c, p.ability(), 0, 1,
                            List.of(), p.ability());
                    a.floatPlan = p;
                    return a;
                });
            }
        }
        return new ArrayList<>(byKey.values());
    }

    // ------------------------------------------------------------------ flag floatsac

    /**
     * Flag {@code floatsac}: the sacrifice-float actions for this window, to append after the other actions. Empty
     * unless it is {@code player}'s turn, a main phase and the stack is empty (the floatb window); then one
     * {@link LegalActions.Kind#MANA} action per distinct ({@code sourceKey}, mana ability) among {@code player}'s
     * permanents whose mana ability is fixed-output, costs a sacrifice (optionally with {T}; no mana / life / other part)
     * and is playable and payable now: Ashnod's Altar ("Sacrifice a creature: Add {C}{C}"), Eldrazi Spawn ("Sacrifice
     * this: Add {C}"). No untap enabler is needed. Any-colour sources (Phyrexian Altar, Treasure) are not offered (the
     * fixed-output restriction floatb has). Order: by source id, then ability. {@code exclude}: abilities that already
     * failed this window. The sacrifice choice of a non-self sacrifice is a policy COST_SACRIFICE decision
     * ({@code ExternalCostDecision}, only while the seat plays such a float).
     */
    public static List<LegalActions.Action> sacFloatActions(Player player, Set<SpellAbility> exclude) {
        if (!floatWindow(player)) {
            return new ArrayList<>();
        }
        final List<Card> sources = new ArrayList<>(player.getCardsIn(ZoneType.Battlefield));
        sources.sort(Comparator.comparingInt(Card::getId));
        final Map<String, LegalActions.Action> byKey = new LinkedHashMap<>();
        for (Card c : sources) {
            if (c.getController() != player) {
                continue;
            }
            for (SpellAbility ma : c.getManaAbilities()) {
                if (exclude.contains(ma)) {
                    continue;
                }
                ma.setActivatingPlayer(player);
                final Cost cost = ma.getPayCosts();
                if (!sacrificeCost(cost) || !CostPayment.canPayAdditionalCosts(cost, ma, false)) {
                    continue;
                }
                final Plan p = fixedPlan(c, ma, cost.hasTapCost());
                if (p == null) {
                    continue;
                }
                byKey.computeIfAbsent(key(c, ma), k -> {
                    final LegalActions.Action a = new LegalActions.Action(LegalActions.Kind.MANA, c, ma, 0, 1,
                            List.of(), ma);
                    a.floatPlan = p;
                    return a;
                });
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** The cost has a sacrifice part and nothing but sacrifice / {T} parts (no mana, life, counters, ...). */
    static boolean sacrificeCost(Cost cost) {
        if (cost == null) {
            return false;
        }
        boolean sac = false;
        for (CostPart p : cost.getCostParts()) {
            if (p instanceof CostSacrifice) {
                sac = true;
            } else if (!(p instanceof CostTap)) {
                return false;
            }
        }
        return sac;
    }

    // ------------------------------------------------------------------ flag floatf

    private static final byte[] WUBRG = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED,
        MagicColor.GREEN};

    /**
     * Flag {@code floatf}: the filter-float actions for this window, to append after the other actions. Empty unless it
     * is {@code player}'s turn, a main phase and the stack is empty (the floatb window); then one
     * {@link LegalActions.Kind#MANA} action per distinct ({@code sourceKey}, mana ability, colour) among {@code player}'s
     * permanents whose mana ability is a filter ({@link #filterCost}: any colour / combo, its cost a mana part plus at
     * most {T}), playable now with its mana part payable by the player's OTHER sources, for each colour it can make that
     * some spell / ability not castable now (only its mana is short) would become castable with: one unit of that colour
     * paid, the filter's own mana added, the rest payable by the other sources (AI payer, test mode, the filter source
     * held back). A spell already castable asks for no float, so the list stays empty in the usual case. Order: by source
     * id, then ability, then WUBRG. {@code exclude}: abilities that already failed this window.
     *
     * <p>ponytail: one unit per float and per test (a spell needing {B}{B} from two filters is not found); optional-cost
     * variants (kicker) are not tested; the combined test pays the filter's mana under the spell's mana restrictions.
     * Upgrade: test with the float's units and every optional-cost subset if the deck pool ever needs it.
     */
    public static List<LegalActions.Action> filterFloatActions(Player player, Set<SpellAbility> exclude) {
        if (!floatWindow(player)) {
            return new ArrayList<>();
        }
        final List<Card> sources = new ArrayList<>(player.getCardsIn(ZoneType.Battlefield));
        sources.sort(Comparator.comparingInt(Card::getId));
        final Map<String, LegalActions.Action> byKey = new LinkedHashMap<>();
        List<SpellAbility> wanting = null; // computed once, only when a usable filter exists
        for (Card c : sources) {
            if (c.getController() != player) {
                continue;
            }
            for (SpellAbility ma : c.getManaAbilities()) {
                if (exclude.contains(ma)) {
                    continue;
                }
                ma.setActivatingPlayer(player);
                final ManaCost own = filterCost(ma);
                if (own == null || !ma.canPlay() || !CostPayment.canPayAdditionalCosts(ma.getPayCosts(), ma, false)
                        || !payableWithout(new ManaCostBeingPaid(own), ma, player, c)) {
                    continue;
                }
                if (wanting == null) {
                    wanting = manaShort(player);
                }
                if (wanting.isEmpty()) {
                    return new ArrayList<>();
                }
                final AbilityManaPart mp = ma.getManaPart();
                final int amount = Math.max(1, ma.amountOfManaGenerated(false));
                for (byte col : WUBRG) {
                    final String n = MagicColor.toShortString(col);
                    final String key = key(c, ma) + "#" + n;
                    if (byKey.containsKey(key) || !(mp.isComboMana()
                            ? java.util.Arrays.asList(mp.getComboColors(ma).split(" ")).contains(n) : mp.canProduce(n, ma))
                            || !enables(wanting, col, own, c, player)) {
                        continue;
                    }
                    final String express = mp.isComboMana() ? String.join(" ", java.util.Collections.nCopies(amount, n)) : n;
                    final LegalActions.Action a = new LegalActions.Action(LegalActions.Kind.MANA, c, ma, 0, 1, List.of(), ma);
                    a.floatPlan = new Plan(c, ma, amount, ExternalManaPayment.colourBit(col), ExternalManaPayment.flags(c, ma),
                            express);
                    byKey.put(key, a);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * The mana part of a filter ability's cost: an any-colour / combo mana ability (no special / reflected mana, no
     * sub-ability) whose cost is a non-zero mana part plus at most {T} (Prophetic Prism "{1}, {T}", Energy Refractor
     * "{2}"); else null.
     */
    static ManaCost filterCost(SpellAbility ma) {
        final AbilityManaPart mp = ma.getManaPart();
        final Cost cost = ma.getPayCosts();
        if (mp == null || !(mp.isAnyMana() || mp.isComboMana()) || mp.isSpecialMana() || ma.getSubAbility() != null
                || ma.getApi() != ApiType.Mana || cost == null || !cost.hasManaCost()) {
            return null;
        }
        for (CostPart p : cost.getCostParts()) {
            if (!(p instanceof CostPartMana) && !(p instanceof CostTap)) {
                return null;
            }
        }
        final ManaCost m = cost.getTotalMana();
        return m == null || m.isZero() ? null : m;
    }

    /**
     * The spells / non-mana abilities {@code player} could play now but for their mana (timing, restrictions, targets and
     * non-mana costs all fine, {@code canPayCost} false), in {@code LegalActions.enumerate} order (alternative-additional
     * cost variants included, optional costs not).
     */
    private static List<SpellAbility> manaShort(Player player) {
        final List<SpellAbility> out = new ArrayList<>();
        for (Card card : ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(player.getGame(), player))) {
            for (SpellAbility sa : card.getAllPossibleAbilities(player, false)) {
                if (sa.isLandAbility() || sa.isManaAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(player);
                if (!sa.canPlay()) {
                    continue;
                }
                for (SpellAbility v : GameActionUtil.getAdditionalCostSpell(sa)) {
                    v.setActivatingPlayer(player);
                    final Cost cost = v.getPayCosts();
                    if (cost != null && cost.hasManaCost() && v.canPlay() && v.canCastTiming(player)
                            && v.checkRestrictions(player) && v.isLegalAfterStack() && ComputerUtilAbility.isFullyTargetable(v)
                            && CostPayment.canPayAdditionalCosts(cost, v, false) && !ComputerUtilCost.canPayCost(v, player, false)) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Would one unit of {@code col} from {@code filter} (its own mana {@code own} paid by the other sources) make any of
     * {@code wanting} payable? One coloured shard {@code col} pays is taken off its cost (a mono-coloured one first),
     * {@code own} is added, and the AI payer (test mode) must pay the rest without {@code filter}.
     */
    private static boolean enables(List<SpellAbility> wanting, byte col, ManaCost own, Card filter, Player player) {
        for (SpellAbility sa : wanting) {
            final ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
            ManaCostShard shard = null;
            for (ManaCostShard s : cost.getUnpaidShards()) {
                if (s.isGeneric() || s.isColorless() || s == ManaCostShard.X || !s.canBePaidWithManaOfColor(col)) {
                    continue;
                }
                if (shard == null || s.isMonoColor() && !shard.isMonoColor()) {
                    shard = s;
                }
            }
            if (shard == null) {
                continue;
            }
            final ManaCostBeingPaid sim = new ManaCostBeingPaid(cost);
            sim.decreaseShard(shard, 1);
            sim.addManaCost(own);
            if (payableWithout(sim, sa, player, filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Would the AI payer (test mode, floating mana included) pay {@code cost} for {@code sa} without {@code held}? The
     * card is marked used-to-pay (its mana abilities unplayable, as {@code LegalActions} keeps a sacrifice source back)
     * and tapped (raw flag, no event) for the duration of the check, then restored.
     */
    private static boolean payableWithout(ManaCostBeingPaid cost, SpellAbility sa, Player player, Card held) {
        final boolean wasUsed = held.isUsedToPay();
        final boolean wasTapped = held.isTapped();
        held.setUsedToPay(true);
        held.setTapped(true);
        try {
            return ComputerUtilMana.canPayManaCost(cost, sa, player, false);
        } finally {
            held.setTapped(wasTapped);
            held.setUsedToPay(wasUsed);
        }
    }

    /**
     * Flag {@code floatb}: removes the untap enablers whose untap is a no-op (no target is tapped) and whose cost is only
     * {T}; with the float action offered separately the enabler is only worth playing after a float. Returns how many.
     */
    public static int hideNoopEnablers(List<LegalActions.Action> legal) {
        int hidden = 0;
        for (Iterator<LegalActions.Action> it = legal.iterator(); it.hasNext();) {
            final LegalActions.Action a = it.next();
            if (a.kind == LegalActions.Kind.ACTIVATE && isEnabler(a.checked) && onlyTapsItself(a.checked.getPayCosts())
                    && cardTargets(a.checked).stream().noneMatch(Card::isTapped)) {
                it.remove();
                hidden++;
            }
        }
        return hidden;
    }

    /**
     * Whether {@code chosen} (a mana ability someone activated at priority: the human seat) is the float {@code a} offers:
     * the same ability on the same or an interchangeable source ({@code sourceKey}).
     */
    public static boolean matches(LegalActions.Action a, SpellAbility chosen) {
        if (a.kind != LegalActions.Kind.MANA || chosen == null || !chosen.isManaAbility() || chosen.getHostCard() == null) {
            return false;
        }
        if (a.base == chosen) {
            return true;
        }
        final Card h = chosen.getHostCard();
        return h.getController() == a.host.getController() && h.isUntapped() == a.host.isUntapped()
                && h.getManaAbilities().indexOf(chosen) >= 0 && key(h, chosen).equals(key(a.host, a.base));
    }

    private static String key(Card c, SpellAbility ma) {
        return ExternalManaPayment.sourceKey(c, c.getManaAbilities().indexOf(ma));
    }

    /**
     * {units, colours} added by Auras / Equipment attached to {@code c} with a "whenever enchanted ... is tapped for mana,
     * add ..." trigger (Utopia Sprawl: the chosen colour; Wild Growth: {G}). ponytail: reads the trigger's Mana SVar
     * (Produced / Amount); global extras (Mana Reflection, Zendikar Resurgent) are not counted, they add the same to
     * every source and do not change which one is best.
     */
    private static int[] attachedExtras(Card c) {
        int units = 0;
        int colours = 0;
        for (Card a : c.getAttachedCards()) {
            for (Trigger t : a.getTriggers()) {
                if (t.getMode() != TriggerType.TapsForMana || !t.hasParam("Execute")) {
                    continue;
                }
                final String exec = a.getSVar(t.getParam("Execute"));
                final Matcher pm = PRODUCED.matcher(exec);
                if (!exec.contains("DB$ Mana") || !pm.find()) {
                    continue;
                }
                final Matcher am = AMOUNT.matcher(exec);
                final int n = am.find() ? Integer.parseInt(am.group(1)) : 1;
                final String produced = pm.group(1).trim();
                int col = 0;
                if ("Chosen".equals(produced)) {
                    for (String name : a.getChosenColors()) {
                        final byte b = MagicColor.fromName(name);
                        col |= b == 0 ? 0 : ExternalManaPayment.colourBit(b);
                    }
                } else {
                    for (String unit : produced.split(" ")) {
                        final byte b = ManaAtom.fromName(unit);
                        col |= b == 0 ? 0 : ExternalManaPayment.colourBit(b);
                    }
                }
                if (col != 0) {
                    units += n;
                    colours |= col;
                }
            }
        }
        return new int[] {units, colours};
    }

    /** The cost is exactly {T} (no mana, no sacrifice, no other part). */
    private static boolean onlyTapsItself(Cost cost) {
        return cost != null && cost.getCostParts().size() == 1 && cost.getCostParts().get(0) instanceof CostTap;
    }
}
