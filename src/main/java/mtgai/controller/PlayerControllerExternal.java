package mtgai.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.LobbyPlayer;
import forge.ai.AiCache;
import forge.ai.AiController;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.keyword.Keyword;
import forge.game.spellability.AlternativeCost;
import forge.game.player.DelayedReveal;
import forge.game.cost.CostDecisionMakerBase;
import forge.util.collect.FCollectionView;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.mana.ManaConversionMatrix;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.ITriggerEvent;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.encode.CardVocab;
import mtgai.encode.Observation;
import mtgai.encode.ObservationEncoder;
import mtgai.traj.TrajectoryWriter;
import mtgai.match.GameRecord;
import mtgai.match.Watchdog;

/**
 * The external-policy seat. Extends {@link PlayerControllerAi} so that every
 * decision the policy does not own falls back to the stock heuristic; the owned decisions become
 * {@link DecisionRequest}s answered synchronously on the game thread by a {@link DecisionOracle}:
 * priority, targets ({@code chooseTargetsFor}), modes, X/announce, "may" confirmations incl.
 * {@code confirmTrigger}, attackers (pick-or-DONE), blockers (two-stage loop), mulligan keep + London
 * bottom, discards, scry/surveil (+ order).
 *
 * <p>Casting, triggers and effect-driven casts all go through forge-game's {@link PlaySpellAbility}
 * pipeline (the three overrides copied from PlayerControllerHuman: {@code orderAndPlaySimultaneousSa},
 * {@code playTrigger}, {@code playSaFromPlayEffect}; plus {@code playSpellAbilityNoStack}), so targets,
 * modes and X of triggers and madness/rebound casts reach the policy instead of
 * {@code AiController.doTrigger} / {@code canPlayFromEffectAI}.
 *
 * <p>Re-entrancy: while the controller asks the inherited AI for a hint ({@link DecisionOracle#wantsAiHint()})
 * or delegates a decision it does not own, every owned override short-circuits to {@code super}, so the AI
 * answers its own nested questions and the oracle is never re-entered.
 */
public class PlayerControllerExternal extends SeatInstruments {
    /** Priority exposure. */
    public enum Exposure { COLLAPSED, FULL }

    /**
     * Diagnostics hook (null = off): called with every enumerated priority window, on the game thread, before
     * the request is built. Used by the brute-force {@link LegalityProbe} (legal-but-hidden counter).
     */
    public static volatile java.util.function.BiConsumer<Player, List<LegalActions.Action>> windowProbe;

    private final DecisionOracle oracle;
    private final int seat;
    private final Exposure exposure;
    private final Watchdog watchdog;
    private final GameRecord record;
    private final SeatStats stats;
    /** Non-null when the oracle wants observations: one encoder per seat per game. */
    private final ObservationEncoder encoder;
    /** Encoder 8 decision groups this seat owns; none = the encoder 7 surface. */
    private final Control control;
    /** Encoder 8 MANA_SOURCE payments (flag mana). */
    private final ExternalManaPayment mana;
    /** Delegating seat, flag order: the TRIGGER_ORDER answer handed to the stock pipeline's orderSimultaneousSa call. */
    private List<SpellAbility> presetOrder;

    /** >0 while the inherited AI is computing a hint or answering a delegated decision. */
    private int inAi;
    /** >0 while targeting is delegated to the AI (divided / random targets). */
    private int delegatedTargeting;

    /** The priority action being played (answers getAbilityToPlay / chooseOptionalCosts). */
    private LegalActions.Action pending;
    private SpellAbility pendingAiSa;
    private final Set<SpellAbility> failedThisWindow = new HashSet<>();
    private final LoopGuard loopGuard = new LoopGuard();
    private int lastLandEligibleTurn = -1;
    private int lastLandPlayedTurn = -1;
    private int lastLandCompletedTurn = -1;

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp, DecisionOracle oracle, int seat,
                                    Exposure exposure, Watchdog watchdog, GameRecord record, SeatStats stats) {
        this(game, p, lp, oracle, seat, exposure, watchdog, record, stats, Control.NONE);
    }

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp, DecisionOracle oracle, int seat,
                                    Exposure exposure, Watchdog watchdog, GameRecord record, SeatStats stats,
                                    Control control) {
        super(game, p, lp);
        this.control = control == null ? Control.NONE : control;
        // a group whose flag is off keeps its callback AI-answered: count it as delegated, as before the override existed
        if (!this.control.has(Control.Group.DAMAGE)) {
            disown("assignCombatDamage");
        }
        if (!this.control.has(Control.Group.ORDER)) {
            disown("orderSimultaneousSa");
        }
        if (!this.control.has(Control.Group.EFFECTS)) {
            disown("chooseCardsForEffect");
            disown("chooseEntitiesForEffect");
            disown("chooseSpellAbilitiesForEffect");
        }
        this.mana = new ExternalManaPayment(this, p);
        this.oracle = oracle;
        this.seat = seat;
        this.exposure = exposure;
        this.watchdog = watchdog;
        this.record = record;
        this.stats = stats;
        this.delegationStats = stats;
        ((CountingAiController) getAi()).owner = this;
        final CardVocab vocab = oracle.observationVocab();
        this.encoder = vocab == null ? null : new ObservationEncoder(vocab, p);
        oracle.attach(this);
    }

    /** >0 while a priority action chosen by the oracle is being played through the PlaySpellAbility pipeline. */
    private int playingChosen;

    /** Flag floatb: explicit floats played in the step (turn, phase) not yet followed by an untap enabler (floatWithEnabler). */
    private int floatsPending;
    private int floatsPendingTurn = -1;
    private PhaseType floatsPendingPhase;

    /**
     * True while the oracle's own priority pick is being cast/activated (its targets, modes, X and cost choices are
     * the requests asked meanwhile); PIMC replays such sub-decisions from the priority window's game copy.
     */
    public boolean inPriorityPlay() {
        return playingChosen > 0;
    }

    /**
     * Flag floatsac: {@code ma} is the mana ability of the policy's own float being played now (a MANA action, not the AI
     * mana payer), so its sacrifice choice is a policy decision ({@link ExternalCostDecision}).
     */
    boolean playingSacFloat(SpellAbility ma) {
        final LegalActions.Action a = pending;
        return playingChosen > 0 && control.has(Control.Group.FLOATSAC) && a != null && a.kind == LegalActions.Kind.MANA
                && ma != null && ma.isManaAbility() && (a.base == ma || a.host == ma.getHostCard() && a.base.getDescription()
                .equals(ma.getDescription()));
    }

    public int seat() {
        return seat;
    }

    public Exposure exposure() {
        return exposure;
    }

    /** The encoder 8 decision groups this seat owns (search copies of this seat must own the same ones). */
    public Control control() {
        return control;
    }

    @Override
    protected AiController createBrains(Player p, Game game) {
        return new CountingAiController(p, game);
    }

    /** AiController that counts doTrigger calls the seat did not ask for (zero-counter). */
    static final class CountingAiController extends AiController {
        PlayerControllerExternal owner;

        CountingAiController(Player p, Game game) {
            super(p, game);
        }

        @Override
        public boolean doTrigger(SpellAbility sa, boolean mandatory) {
            if (owner != null && owner.inAi == 0 && owner.delegatedTargeting == 0) {
                owner.stats.aiDoTriggerOutsideTargeting++;
                final String caller = StackWalker.getInstance().walk(fr -> fr.skip(1).limit(6)
                        .map(f -> f.getClassName().replaceAll(".*\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                        .collect(java.util.stream.Collectors.joining(" < ")));
                owner.stats.note("aiDoTrigger outside targeting: " + sa.getHostCard() + " api " + sa.getApi()
                        + " @" + owner.phaseName() + " via " + caller);
            }
            return super.doTrigger(sa, mandatory);
        }
    }

    public SeatStats stats() {
        return stats;
    }

    public DecisionOracle oracle() {
        return oracle;
    }

    /** This seat's encoder (null when the oracle takes no observations). */
    public ObservationEncoder encoder() {
        return encoder;
    }

    // ------------------------------------------------------------------ plumbing

    private String phaseName() {
        final PhaseHandler ph = getGame().getPhaseHandler();
        return ph.getPhase() == null ? "-" : ph.getPhase().name();
    }

    /** Sends one request to the oracle, records it, returns the chosen index. */
    private int ask(DecisionKind kind, List<Candidate> cands, IntSupplier aiHint, String context) {
        watchdog.onDecision(getGame());
        if (cands.size() == 1) {
            stats.forcedSingle++; // one legal answer: not a decision, never sent to the oracle
            return 0;
        }
        if (cands.size() > DecisionRequest.CANDIDATE_CAP) {
            stats.candidateTruncations++;
            cands = new ArrayList<>(cands.subList(0, DecisionRequest.CANDIDATE_CAP));
        }
        int hint = -1;
        if (oracle.wantsAiHint() && aiHint != null) {
            inAi++;
            try {
                hint = aiHint.getAsInt();
            } finally {
                inAi--;
            }
            if (hint < 0 || hint >= cands.size()) {
                stats.delegateMiss(kind);
                hint = -1;
            }
        }
        final long e0 = System.nanoTime();
        final Observation obs = encoder == null ? null : encoder.encode(kind, cands);
        stats.encodeNanos += System.nanoTime() - e0;
        final DecisionRequest req = new DecisionRequest(kind, seat, getGame().getPhaseHandler().getTurn(),
                phaseName(), getGame().getPhaseHandler().isPlayerTurn(player), List.copyOf(cands), hint, context, obs);
        final DecisionResponse resp = oracle.decide(req);
        final int idx = resp.index();
        if (idx < 0 || idx >= cands.size()) {
            throw new IllegalStateException(oracle.name() + " returned " + idx + " for " + cands.size()
                    + " candidates (" + kind + ")");
        }
        stats.count(kind, cands.size());
        record.record(req, idx);
        // imitation teacher: the answer is Forge's own (mapped) choice for this request -> TEACHER label; a delegate
        // miss (hint -1, fallback candidate 0) is recorded without it
        final boolean label = oracle.teacher() && !resp.policy() && hint >= 0 && idx == hint;
        if (label) {
            stats.teacherLabels++;
        }
        if (obs != null) {
            // search targets (schema 3): a searched answer carries pi' -> SEARCHED | NOT_POLICY + the IMPROVED section
            final float[] pi = resp.improved();
            record.step(seat, new TrajectoryWriter.Step(obs, idx, (resp.policy() && pi == null ? 0 : TrajectoryWriter.NOT_POLICY)
                    | (label ? TrajectoryWriter.TEACHER : 0) | (pi != null ? TrajectoryWriter.SEARCHED : 0),
                    TrajectoryWriter.fullMask(cands.size()), resp.logp(), resp.value(), 0f, record.steps(seat).size(),
                    pi));
        }
        return idx;
    }

    /** {@link #ask} for the encoder 8 helpers in this package ({@link ExternalManaPayment}). */
    int askControl(DecisionKind kind, List<Candidate> cands, IntSupplier aiHint, String context) {
        return ask(kind, cands, aiHint, context);
    }

    /** Runs {@code r} as an AI computation: every owned callback it reaches is answered by the inherited AI. */
    <T> T asAi(java.util.function.Supplier<T> r) {
        inAi++;
        try {
            return r.get();
        } finally {
            inAi--;
        }
    }

    private static int indexOfRef(List<Candidate> cands, Object ref) {
        if (ref == null) {
            return -1;
        }
        for (int i = 0; i < cands.size(); i++) {
            if (cands.get(i).ref() == ref) {
                return i;
            }
        }
        return -1;
    }

    private static String cardLabel(Card c) {
        return c.getName() + " (" + c.getId() + ")";
    }

    public static Candidate cardCand(Card c) {
        return new Candidate(Candidate.Type.CARD, cardLabel(c), c);
    }

    public static final Candidate DONE = Candidate.of(Candidate.Type.DONE, "done");

    @Override
    protected boolean guard() {
        return inAi > 0 || stock;
    }

    /** Search rollouts: from the seat's next priority window / combat declaration on, play as the stock AI. */
    private boolean stockFromNextWindow;
    private boolean stock;

    /**
     * Search rollouts (PIMC with Forge's AI as the rollout policy): once the root decision has been replayed, the seat's
     * next priority window or combat declaration and everything after it are the stock {@code PlayerControllerAi}'s
     * (no legal-action enumeration, no requests). Takes effect only at those clean entry points, never in the middle
     * of a cast the seat already started through its own pipeline. Caps are still checked there.
     */
    public void playStockFromNextWindow() {
        stockFromNextWindow = true;
    }

    private boolean enterStock() {
        if (stockFromNextWindow && !stock && inAi == 0) {
            stock = true;
        }
        if (stock) {
            watchdog.onDecision(getGame());
        }
        return stock;
    }

    // ------------------------------------------------------------------ priority

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (enterStock() || guard()) {
            return super.chooseSpellAbilityToPlay();
        }
        // AiCache is static and cross-game; the stock AI clears it every priority call. Without this a
        // game whose only AI activity is inherited mana/cost code retains every finished Game.
        AiCache.clear();
        final Game game = getGame();
        watchdog.check(game);
        stats.windows++;
        pending = null;
        pendingAiSa = null;
        zoneChangeDone = false;

        final PhaseHandler ph = game.getPhaseHandler();
        final PhaseType phase = ph.getPhase();
        final boolean stackEmpty = game.getStack().isEmpty();
        final int turn = ph.getTurn();
        if (turn == lastLandEligibleTurn && turn != lastLandPlayedTurn && player.getLandsPlayedThisTurn() > 0) {
            stats.landTurnsPlayed++;
            lastLandPlayedTurn = turn;
        }
        if (lastLandEligibleTurn >= 0 && turn > lastLandEligibleTurn && lastLandEligibleTurn != lastLandCompletedTurn) {
            // the eligible turn is over (the game went on): completed-turn land counter
            stats.landTurnsEligibleCompleted++;
            stats.landTurnsPlayedCompleted += lastLandPlayedTurn == lastLandEligibleTurn ? 1 : 0;
            lastLandCompletedTurn = lastLandEligibleTurn;
        }
        if (exposure == Exposure.COLLAPSED && stackEmpty && autoPassStep(phase, game)) {
            stats.windowsAutoPassed++;
            failedThisWindow.clear();
            return null;
        }

        final long t0 = System.nanoTime();
        final List<LegalActions.Action> legal = LegalActions.enumerate(player, failedThisWindow, stats);
        stats.enumNanos += System.nanoTime() - t0;
        final java.util.function.BiConsumer<Player, List<LegalActions.Action>> probe = windowProbe;
        if (probe != null) {
            probe.accept(player, legal);
        }

        // PLAY_LAND must be offered on every eligible window
        final boolean landEligible = stackEmpty && phase != null && phase.isMain() && ph.isPlayerTurn(player)
                && player.getLandsPlayedThisTurn() < player.getMaxLandPlays()
                && !CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.LANDS).isEmpty();
        if (landEligible) {
            stats.landEligibleWindows++;
            if (turn != lastLandEligibleTurn) {
                stats.landTurnsEligible++;
                lastLandEligibleTurn = turn;
            }
            if (legal.stream().noneMatch(a -> a.kind == LegalActions.Kind.PLAY_LAND)) {
                stats.landMissedWindows++;
                stats.note("land not offered: turn " + ph.getTurn() + " " + phase + " hand "
                        + player.getCardsIn(ZoneType.Hand));
            }
        }

        final int before = legal.size();
        legal.removeIf(a -> a.kind != LegalActions.Kind.PLAY_LAND && loopGuard.blocked(game, a.checked));
        stats.loopGuardBlocks += before - legal.size();
        final FloatMana.Mode floatMode = FloatMana.mode(control);
        final boolean floatSac = control.has(Control.Group.FLOATSAC);
        final boolean floatFilter = control.has(Control.Group.FLOATF);
        final boolean floatOn = floatMode != FloatMana.Mode.OFF || floatSac || floatFilter;
        boolean floatWindow = false;
        // flag floatb + a hint-wanting oracle: the AI's answer, computed before the candidate list (empty = not computed)
        final List<List<SpellAbility>> earlyAi = new ArrayList<>(1);
        if (floatMode == FloatMana.Mode.IMPLICIT) {
            stats.floatNoopHidden += FloatMana.prepare(legal, player);
        } else if (floatMode == FloatMana.Mode.EXPLICIT) {
            // flag floatb: float actions go through the loop guard like any other and come last (the 64 cap
            // drops them first, every other candidate keeps its relative order)
            List<LegalActions.Action> floats = FloatMana.floatActions(legal, player, failedThisWindow);
            if (!floats.isEmpty() && oracle.wantsAiHint()) {
                // the stock AI floats as a side effect of choosing (UntapAi PoolExtraMana: ComputerUtil.playNoStack taps a
                // Forest, then it picks Arbor Elf). Run it now, before the list is built, so its pick has a tapped target
                // (not hidden as a no-op) and a source it just tapped is not offered as a float.
                inAi++;
                try {
                    earlyAi.add(super.chooseSpellAbilityToPlay());
                } finally {
                    inAi--;
                }
                floats = FloatMana.floatActions(legal, player, failedThisWindow);
            }
            final int offered = floats.size();
            floats.removeIf(a -> loopGuard.blocked(game, a.checked));
            stats.loopGuardBlocks += offered - floats.size();
            if (!floats.isEmpty()) {
                // only in a float window: elsewhere an enabler's no-op stays offered exactly as with the flag off
                stats.floatNoopHidden += FloatMana.hideNoopEnablers(legal);
                stats.floatWindows++;
                floatWindow = true;
                legal.addAll(floats);
            }
        }
        if (floatSac) {
            // flag floatsac: sacrifice floats (Ashnod's Altar, Eldrazi Spawn), loop-guarded, after everything else. A loop
            // that returns to the same state (Retriever x2 + Altar without a payoff) is refused by the loop guard.
            final List<LegalActions.Action> sacs = FloatMana.sacFloatActions(player, failedThisWindow);
            final int offered = sacs.size();
            sacs.removeIf(a -> loopGuard.blocked(game, a.checked));
            stats.loopGuardBlocks += offered - sacs.size();
            if (!sacs.isEmpty()) {
                stats.floatWindows += floatWindow ? 0 : 1;
                floatWindow = true;
                legal.addAll(sacs);
            }
        }
        if (floatFilter) {
            // flag floatf: filter floats (Prophetic Prism, Conduit Pylons: {1}, {T}: any colour) for a colour a spell in
            // hand is short of, loop-guarded, after everything else
            final List<LegalActions.Action> filters = FloatMana.filterFloatActions(player, failedThisWindow);
            final int offered = filters.size();
            filters.removeIf(a -> loopGuard.blocked(game, a.checked));
            stats.loopGuardBlocks += offered - filters.size();
            if (!filters.isEmpty()) {
                stats.floatWindows += floatWindow ? 0 : 1;
                legal.addAll(filters);
            }
        }

        if (legal.isEmpty()) {
            stats.windowsNoAction++;
            failedThisWindow.clear();
            if (floatOn && stackEmpty) {
                stats.floatManaPassed += player.getManaPool().totalMana();
            }
            return null;
        }
        stats.windowsExposed++;

        final List<Candidate> cands = priorityCandidates(legal);

        final SpellAbility[] aiPick = new SpellAbility[1];
        final int idx = ask(DecisionKind.PRIORITY, cands, () -> {
            final List<SpellAbility> ai = earlyAi.isEmpty() ? super.chooseSpellAbilityToPlay() : earlyAi.get(0);
            if (ai == null || ai.isEmpty()) {
                return 0;
            }
            aiPick[0] = ai.get(0);
            return 1 + matchAiAction(legal, aiPick[0]);
        }, stackEmpty ? "stack empty" : (game.getStack().peekAbility().getActivatingPlayer() == player
                ? "stack(mine): " : "stack(opp): ") + game.getStack().peekAbility());

        if (idx == 0) {
            failedThisWindow.clear();
            if (floatOn && stackEmpty) {
                stats.floatManaPassed += player.getManaPool().totalMana();
            }
            return null;
        }
        final LegalActions.Action action = legal.get(idx - 1);
        loopGuard.played(game, action.checked);
        if (aiPick[0] != null && oracle.aiPipeline() && idx == 1 + matchAiAction(legal, aiPick[0])) {
            // DelegatingOracle picked the AI's own choice: play the AI's SA (targets/X already chosen by
            // canPlaySa) through the AI pipeline, exactly as stock PlayerControllerAi would. (The imitation
            // teacher does not: its pick goes through the seat's pipeline below, so every sub-choice is a request.)
            pendingAiSa = aiPick[0];
            return List.of(aiPick[0]);
        }
        pending = action;
        return List.of(action.base);
    }

    /** The priority candidate list: PASS, then one per legal action (also the human-seat capture's). */
    public static List<Candidate> priorityCandidates(List<LegalActions.Action> legal) {
        final List<Candidate> cands = new ArrayList<>(legal.size() + 1);
        cands.add(Candidate.of(Candidate.Type.PASS, "pass"));
        for (LegalActions.Action a : legal) {
            cands.add(new Candidate(switch (a.kind) {
                case PLAY_LAND -> Candidate.Type.PLAY_LAND;
                case CAST -> Candidate.Type.CAST;
                case ACTIVATE, MANA -> Candidate.Type.ACTIVATE; // MANA: flag floatb, api byte = Mana
            }, a.label, a));
        }
        return cands;
    }

    /** Collapsed exposure: steps that auto-pass with an empty stack. */
    public static boolean autoPassStep(PhaseType phase, Game game) {
        if (phase == null) {
            return true;
        }
        switch (phase) {
            case UNTAP, UPKEEP, DRAW, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE, COMBAT_END, CLEANUP:
                return true;
            case COMBAT_DECLARE_ATTACKERS: {
                final Combat c = game.getCombat();
                return c == null || c.getAttackers().isEmpty();
            }
            default:
                return false;
        }
    }

    /** Index in {@code legal} of the SA the stock AI chose, or -2 (so 1 + result is never valid). */
    public static int matchAiAction(List<LegalActions.Action> legal, SpellAbility ai) {
        for (int i = 0; i < legal.size(); i++) {
            final LegalActions.Action a = legal.get(i);
            if (a.base == ai || a.checked == ai) {
                return i;
            }
        }
        for (int i = 0; i < legal.size(); i++) {
            final LegalActions.Action a = legal.get(i);
            if (a.host == ai.getHostCard()
                    && a.checked.getDescription().equals(ai.getDescription())
                    && a.checked.getAlternativeCost() == ai.getAlternativeCost()
                    && LegalActions.sameOptional(a.checked, ai)
                    && a.checked.getPayCosts().toSimpleString().equals(ai.getPayCosts().toSimpleString())) {
                return i;
            }
        }
        for (int i = 0; i < legal.size(); i++) {
            if (FloatMana.matches(legal.get(i), ai)) { // flag floatb: a mana ability on an interchangeable source
                return i;
            }
        }
        return -2;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (guard()) {
            return super.playChosenSpellAbility(sa);
        }
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
                stats.landsPlayed++;
            } else {
                stats.activationFailures++;
                stats.note("land failed: " + sa);
            }
            pending = null;
            return true;
        }
        if (pendingAiSa == sa) {
            pendingAiSa = null;
            inAi++;
            try {
                stats.spellsPlayed++;
                return super.playChosenSpellAbility(sa);
            } finally {
                inAi--;
            }
        }
        final LegalActions.Action action = pending;
        final FloatMana.Mode floatMode = FloatMana.mode(control);
        int implicitUnits = 0;
        if (action != null && action.floatPlan != null && action.base == sa && floatMode == FloatMana.Mode.IMPLICIT
                && action.kind == LegalActions.Kind.ACTIVATE) {
            // flag float: tap the planned source for mana first, so the enabler's untap is not wasted (FloatMana)
            implicitUnits = FloatMana.floatMana(action.floatPlan, player);
            if (implicitUnits > 0) {
                stats.floatActivations++;
                stats.floatUnits += implicitUnits;
            }
        }
        // flag floatb: an explicit float (a mana ability at priority) plays through the same pipeline as everything else
        final boolean floatAction = action != null && action.kind == LegalActions.Kind.MANA && action.base == sa;
        // flag floatf: a filter float is activated with its colour chosen (express choice, chooseColor answers it too)
        final String filterColour = floatAction && action.floatPlan != null ? action.floatPlan.express() : null;
        final byte filterByte = filterColour == null ? 0 : ManaAtom.fromName(filterColour.split(" ")[0]);
        final int colourBefore = filterColour == null ? 0 : player.getManaPool().getAmountOfColor(filterByte);
        if (filterColour != null) {
            sa.getManaPart().setExpressChoice(filterColour);
        }
        final int poolBefore = player.getManaPool().totalMana();
        sa.resetTargets(); // a probing pass may have left targets behind; the pipeline re-chooses them
        final boolean ok;
        playingChosen++;
        try {
            ok = PlaySpellAbility.playSpellAbility(this, player, sa);
        } finally {
            pending = null;
            playingChosen--;
            if (filterColour != null) {
                sa.getManaPart().clearExpressChoice(); // resolution clears it; a failed activation must not leave it
            }
        }
        if (ok && floatAction) {
            final int units = player.getManaPool().totalMana() - poolBefore;
            stats.floatActivations++;
            stats.floatUnits += units;
            if (units <= 0) {
                stats.note("float made no mana: " + action.label);
            }
            if (filterColour != null) {
                stats.floatFilters++;
                if (player.getManaPool().getAmountOfColor(filterByte) <= colourBefore) {
                    stats.floatFilterMissed++;
                    stats.note("filter float missed its colour " + filterColour + ": " + action.label);
                }
            }
            final PhaseHandler ph = getGame().getPhaseHandler();
            if (floatsPendingTurn != ph.getTurn() || floatsPendingPhase != ph.getPhase()) {
                floatsPending = 0;
                floatsPendingTurn = ph.getTurn();
                floatsPendingPhase = ph.getPhase();
            }
            floatsPending++;
        } else if (ok) {
            stats.spellsPlayed++;
            if (action != null && action.base == sa && FloatMana.isEnabler(action.checked)) {
                if (implicitUnits > 0) {
                    stats.floatWithEnabler++; // flag float: the float is part of the enabler's own play
                }
                final PhaseHandler ph = getGame().getPhaseHandler();
                if (floatsPending > 0 && floatsPendingTurn == ph.getTurn() && floatsPendingPhase == ph.getPhase()) {
                    stats.floatWithEnabler += floatsPending; // flag floatb: floats of this step followed by an enabler
                }
                floatsPending = 0;
            }
        } else {
            stats.activationFailures++;
            failedThisWindow.add(sa);
            stats.note("activation failed: " + (action != null ? action.label : sa.toString()) + " @turn "
                    + getGame().getPhaseHandler().getTurn() + " " + phaseName());
        }
        return ok;
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (guard() || abilities.size() <= 1) {
            return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        }
        final LegalActions.Action a = pending;
        if (a != null && a.host == hostCard && a.addCostCount == abilities.size()) {
            return abilities.get(a.addCostIndex);
        }
        final List<Candidate> cands = new ArrayList<>();
        for (SpellAbility sa : abilities) {
            cands.add(new Candidate(Candidate.Type.ABILITY, sa.toUnsuppressedString(), sa));
        }
        final int idx = ask(DecisionKind.CHOOSE_ABILITY, cands,
                () -> indexOfRef(cands, super.getAbilityToPlay(hostCard, abilities, triggerEvent)),
                cardLabel(hostCard));
        return abilities.get(idx);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        if (guard()) {
            return super.chooseOptionalCosts(chosen, optionalCostValues);
        }
        final LegalActions.Action a = pending;
        if (a != null && a.host == chosen.getHostCard()) {
            return LegalActions.pick(optionalCostValues, a.optionalCosts);
        }
        final List<List<OptionalCostValue>> options = LegalActions.subsets(optionalCostValues);
        final List<Candidate> cands = new ArrayList<>();
        for (List<OptionalCostValue> s : options) {
            cands.add(new Candidate(Candidate.Type.OPTION, s.isEmpty() ? "none" : s.toString(), s));
        }
        final int idx = ask(DecisionKind.OPTIONAL_COST, cands, () -> {
            final List<OptionalCostValue> ai = super.chooseOptionalCosts(chosen, optionalCostValues);
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).size() == (ai == null ? 0 : ai.size()) && (ai == null || options.get(i).containsAll(ai))) {
                    return i;
                }
            }
            return -1;
        }, chosen.toUnsuppressedString());
        return options.get(idx);
    }

    // ------------------------------------------------------------------ triggers / effect casts
    // Copied from PlayerControllerHuman so triggers and effect-driven casts run through PlaySpellAbility
    // and their targets/modes/X route back into this controller.

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (guard()) {
            super.orderAndPlaySimultaneousSa(activePlayerSAs);
            return;
        }
        if (pendingDelegating()) {
            // encoder 8 (flag order): the order is still a TRIGGER_ORDER decision (the delegating oracle answers it with
            // the AI's own order); the stock AI pipeline then plays them in that order
            presetOrder = control.has(Control.Group.ORDER) && activePlayerSAs.size() > 1
                    ? orderSimultaneousSa(activePlayerSAs) : null;
            inAi++;
            try {
                super.orderAndPlaySimultaneousSa(activePlayerSAs);
            } finally {
                inAi--;
                presetOrder = null;
            }
            return;
        }
        // the order: the AI's without the order flag, a TRIGGER_ORDER decision with it (encoder 8); each trigger is played
        // through the pipeline
        for (final SpellAbility next : orderSimultaneousSa(activePlayerSAs)) {
            if (next.isTrigger() && !next.isCopied()) {
                if (!PlaySpellAbility.playSpellAbility(this, player, next)) {
                    stats.triggerPlayFailures++;
                    stats.note("trigger not put on stack (stack " + getGame().getStack().size() + "): "
                            + next.getHostCard().getName() + ": " + next.toUnsuppressedString());
                }
            } else {
                if (next.isCopied()) {
                    if (next.isSpell()) {
                        if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                            next.setHostCard(player.getGame().getAction().moveToStack(next.getHostCard(), next));
                        } else {
                            player.getGame().getStackZone().add(next.getHostCard());
                        }
                    }
                    if (next.isMayChooseNewTargets()) {
                        chooseNewTargetsForCopy(next);
                    }
                }
                player.getGame().getStack().add(next);
            }
        }
    }

    /**
     * "You may choose new targets for the copy" (Chain Lightning): the seat re-picks every target (the old
     * ones are legal candidates, so keeping them is one choice); a node with no legal new pick keeps its old
     * targets (counted in {@code copyRetargetSkipped}). Mirrors {@code SpellAbility.setupNewTargets}.
     */
    private void chooseNewTargetsForCopy(SpellAbility copy) {
        for (SpellAbility s = copy; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                final TargetChoices old = s.getTargets();
                s.resetTargets();
                if (!chooseTargetsFor(s)) {
                    s.setTargets(old);
                    stats.copyRetargetSkipped++;
                }
            }
            if (s.getSubAbility() != null) {
                s.getSubAbility().setParent(s);
            }
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (guard()) {
            return super.playTrigger(host, wrapperAbility, isMandatory);
        }
        if (pendingDelegating()) {
            inAi++;
            try {
                return super.playTrigger(host, wrapperAbility, isMandatory);
            } finally {
                inAi--;
            }
        }
        return PlaySpellAbility.playSpellAbilityNoStack(this, player, wrapperAbility, false);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        if (guard()) {
            return super.playSaFromPlayEffect(tgtSA);
        }
        if (pendingDelegating()) {
            inAi++;
            try {
                return super.playSaFromPlayEffect(tgtSA);
            } finally {
                inAi--;
            }
        }
        return PlaySpellAbility.playSpellAbility(this, player, tgtSA);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        if (guard()) {
            super.playSpellAbilityNoStack(effectSA, canSetupTargets);
            return;
        }
        if (pendingDelegating()) {
            inAi++;
            try {
                super.playSpellAbilityNoStack(effectSA, canSetupTargets);
            } finally {
                inAi--;
            }
            return;
        }
        PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !canSetupTargets);
    }

    /**
     * The DelegatingOracle keeps the AI's own trigger / effect-cast paths (it is the stock AI). The imitation teacher
     * ({@link DecisionOracle#aiPipeline()} false) does not: it plays through the seat's surface like a policy seat.
     */
    private boolean pendingDelegating() {
        return oracle.aiPipeline();
    }

    // ------------------------------------------------------------------ mana payment (Forge fork hooks)

    /** True only during the retry in {@link #payManaCost}: the AI payer may sacrifice a targeted permanent. */
    private boolean paySacrificingTarget;

    @Override
    public boolean mayPayBySacrificingTarget() {
        return paySacrificingTarget;
    }

    @Override
    public boolean preferNonManaSourcesForManaTapCosts() {
        return !pendingDelegating(); // the delegating seat stays the stock AI
    }

    /** Forge Table fork change, interim fix: the AI payer keeps held instants castable (not on the delegating seat). */
    @Override
    public boolean preferSourcesKeepingInstantsCastable() {
        return !pendingDelegating();
    }

    /**
     * The AI payer never sacrifices a permanent the paid ability targets (Map token / Makeshift Munitions /
     * equip targeting the Eldrazi Spawn that is the last mana source). Targeting it is legal and so is paying
     * with it, so when that preference makes the payment fail, retry once with it lifted. The
     * failed attempt's mana is back in the pool, so the retry spends it first. Counted in
     * {@link SeatStats#manaTargetRetries}.
     */
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                               ManaConversionMatrix matrix, boolean effect) {
        if (!guard() && control.has(Control.Group.MANA)) {
            // encoder 8, flag mana: MANA_SOURCE decisions; null = a payment the policy path does not model (AI payer)
            final Boolean paid = mana.pay(toPay, sa, effect);
            if (paid != null) {
                return paid;
            }
        }
        if (super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect)) {
            return true;
        }
        if (guard() || pendingDelegating() || !targetsOwnSacrificeManaSource(sa)) {
            return false;
        }
        stats.manaTargetRetries++;
        sa.setSkip(false);
        paySacrificingTarget = true;
        try {
            final boolean ok = super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
            stats.note("mana retry sacrificing a target: " + sa.getHostCard() + (ok ? " paid" : " still unpaid"));
            return ok;
        } finally {
            paySacrificingTarget = false;
        }
    }

    private boolean targetsOwnSacrificeManaSource(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            for (Card c : s.getTargets().getTargetCards()) {
                if (c.getController() == player) {
                    for (SpellAbility m : c.getManaAbilities()) {
                        if (ComputerUtilCost.isSacrificeSelfCost(m.getPayCosts())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player payer, SpellAbility ability, boolean effect, String prompt) {
        if (guard()) {
            return super.getCostDecisionMaker(payer, ability, effect, prompt);
        }
        return new ExternalCostDecision(this, payer, ability, effect);
    }

    /**
     * "Unless" costs: pay or not is an {@code UNLESS_COST} decision; the payment runs through
     * {@link ExternalCostDecision}, so its sacrifice/discard choices are policy decisions too. Unpayable: no request.
     */
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        if (guard()) {
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
        if (!ComputerUtilCost.canPayCost(cost, sa, player, true)) {
            return false;
        }
        final Card host = sa.getHostCard();
        final List<Candidate> cands = List.of(new Candidate(Candidate.Type.NO, "don't pay", host),
                new Candidate(Candidate.Type.YES, "pay " + cost.toSimpleString(), host));
        if (ask(DecisionKind.UNLESS_COST, cands,
                () -> SpellApiToAi.Converter.get(sa).willPayUnlessCost(player, sa, cost, alreadyPaid, allPayers) ? 1 : 0,
                host.getName() + ": " + sa.toUnsuppressedString()) == 0) {
            return false;
        }
        return new CostPayment(cost, sa).payComputerCosts(getCostDecisionMaker(player, sa, true, null));
    }

    /**
     * One ability out of several for an effect: the Undercity room to venture into (initiative) and generic
     * "choose one" effects (Highway Robbery: discard or sacrifice a land; the AI loop in
     * {@code chooseSpellAbilitiesForEffect} calls this per pick). CHOOSE_ABILITY. Other callers stay AI.
     */
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
                                                  Map<String, Object> params) {
        if (guard() || sa == null || spells.isEmpty()
                || (sa.getApi() != ApiType.Venture && sa.getApi() != ApiType.GenericChoice)) {
            return super.chooseSingleSpellForEffect(spells, sa, title, params);
        }
        final List<Candidate> cands = new ArrayList<>();
        for (SpellAbility s : spells) {
            cands.add(new Candidate(Candidate.Type.ABILITY, s.toUnsuppressedString(), s));
        }
        return spells.get(ask(DecisionKind.CHOOSE_ABILITY, cands,
                () -> indexOfRef(cands, super.chooseSingleSpellForEffect(spells, sa, title, params)),
                sa.getHostCard().getName() + ": " + title));
    }

    /** "Choose a type" effects (Winding Way: creature or land; the AI flips a coin): CHOOSE_TYPE. */
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, java.util.Collection<String> validTypes, boolean isOptional) {
        if (guard() || sa == null || sa.getApi() != ApiType.ChooseType || validTypes.isEmpty()
                || validTypes.size() > DecisionRequest.CANDIDATE_CAP) {
            return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        }
        final List<Candidate> cands = new ArrayList<>();
        for (String t : validTypes) {
            cands.add(new Candidate(Candidate.Type.OPTION, t, t));
        }
        return (String) cands.get(ask(DecisionKind.CHOOSE_TYPE, cands, () -> {
            final String ai = super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
            for (int i = 0; i < cands.size(); i++) {
                if (cands.get(i).label().equals(ai)) {
                    return i;
                }
            }
            return -1;
        }, sa.getHostCard().getName() + ": " + kindOfType)).ref();
    }

    /**
     * "Choose a color" effects (Prismatic Strands, Utopia Sprawl): CHOOSE_COLOR, one color per pick, DONE once
     * the minimum is met. Colors chosen inside mana abilities (Produced$ Any) stay with the AI mana payer.
     */
    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        if (guard() || sa == null || sa.getApi() != ApiType.ChooseColor || options.countColors() == 0) {
            return super.chooseColors(message, sa, min, max, options);
        }
        final int[] ai = {-1};
        int mask = 0;
        int picks = 0;
        while (picks < max) {
            final List<Candidate> cands = new ArrayList<>();
            final boolean canStop = picks >= min;
            if (canStop) {
                cands.add(DONE);
            }
            for (MagicColor.Color c : options) {
                if ((mask & c.getColorMask()) == 0) {
                    cands.add(new Candidate(Candidate.Type.OPTION, c.getName(), (int) c.getColorMask()));
                }
            }
            if (cands.isEmpty() || (canStop && cands.size() == 1)) {
                break;
            }
            final int have = mask;
            final int idx = ask(DecisionKind.CHOOSE_COLOR, cands, () -> {
                if (ai[0] < 0) {
                    ai[0] = super.chooseColors(message, sa, min, max, options).getColor();
                }
                for (int i = 0; i < cands.size(); i++) {
                    if (cands.get(i).ref() instanceof Integer m && (ai[0] & m) != 0 && (have & m) == 0) {
                        return i;
                    }
                }
                return canStop ? 0 : -1;
            }, sa.getHostCard().getName() + ": " + message);
            if (cands.get(idx).type() == Candidate.Type.DONE) {
                break;
            }
            mask |= (Integer) cands.get(idx).ref();
            picks++;
        }
        return ColorSet.fromMask(mask);
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        final LegalActions.Action a = pending;
        if (playingChosen > 0 && a != null && a.kind == LegalActions.Kind.MANA && sa != null && a.base == sa
                && a.floatPlan != null && a.floatPlan.express() != null) {
            // flag floatf: the filter float's colour was chosen with the action (its candidate's colour byte)
            final byte c = ManaAtom.fromName(a.floatPlan.express().split(" ")[0]);
            if (colors.hasAnyColor(c)) {
                return c;
            }
        }
        if (guard() || sa == null || sa.getApi() != ApiType.ChooseColor || colors.countColors() < 2) {
            return super.chooseColor(message, sa, colors);
        }
        return chooseColors(message, sa, 1, 1, colors).getColor();
    }

    /** Edicts ("sacrifice a creature"): which permanents, one pick at a time (COST_SACRIFICE, as for sacrifice costs). */
    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets,
                                                          String message) {
        if (guard()) {
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        }
        return pickCards(DecisionKind.COST_SACRIFICE, new CardCollection(validTargets), min, max,
                () -> super.choosePermanentsToSacrifice(sa, min, max, validTargets, message),
                (sa == null ? "" : sa.getHostCard().getName() + ": ") + message);
    }

    /** {@code chooseBinary} (tap or untap, ...): a BINARY decision, NO = false (0) / YES = true (1). */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question, PlayerController.BinaryChoiceType kind, Boolean defaultVal) {
        if (guard()) {
            return super.chooseBinary(sa, question, kind, defaultVal);
        }
        return askBinary(sa, question, kind, () -> super.chooseBinary(sa, question, kind, defaultVal));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, PlayerController.BinaryChoiceType kind, Map<String, Object> params) {
        if (guard()) {
            return super.chooseBinary(sa, question, kind, params);
        }
        return askBinary(sa, question, kind, () -> super.chooseBinary(sa, question, kind, params));
    }

    private boolean askBinary(SpellAbility sa, String question, PlayerController.BinaryChoiceType kind,
                              java.util.function.BooleanSupplier ai) {
        final Card host = sa == null ? null : sa.getHostCard();
        final boolean tap = kind == PlayerController.BinaryChoiceType.TapOrUntap;
        final List<Candidate> cands = List.of(new Candidate(Candidate.Type.NO, tap ? "untap" : kind + ": no", host),
                new Candidate(Candidate.Type.YES, tap ? "tap" : kind + ": yes", host));
        return ask(DecisionKind.BINARY, cands, () -> ai.getAsBoolean() ? 1 : 0,
                (host == null ? "" : host.getName() + ": ") + question) == 1;
    }

    /**
     * Hidden-origin zone changes pick one card per call for an AI controller (Brainstorm's put-back, tutors,
     * fetches; {@code ChangeZoneEffect} never multi-selects for {@code isAI()}): a CHOOSE_CARD decision, DONE
     * (index 0) = none when optional.
     */
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                              CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt,
                                              boolean isOptional, Player decider) {
        if (guard() || fetchList.isEmpty()) {
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        final List<Candidate> cands = new ArrayList<>();
        if (isOptional) {
            cands.add(DONE);
        }
        for (Card c : fetchList) {
            cands.add(cardCand(c));
        }
        final int idx = ask(DecisionKind.CHOOSE_CARD, cands, () -> {
            final Card ai = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, null, selectPrompt,
                    isOptional, decider);
            return ai == null ? (isOptional ? 0 : -1) : indexOfRef(cands, ai);
        }, sa.getHostCard().getName() + ": " + origin + " -> " + destination);
        final Candidate c = cands.get(idx);
        zoneChangeDone = c.type() == Candidate.Type.DONE;
        return zoneChangeDone ? null : (Card) c.ref();
    }

    /** DONE was picked in a hidden-origin zone change: the engine's "Cancel search?" follow-up is answered yes. */
    private boolean zoneChangeDone;

    /**
     * Cards going onto the top of a library in any order (Brainstorm's put-back, Ponder): LIBRARY_ORDER picks the
     * next card from the top. The returned list is in move order, i.e. bottom-most first (PlayerController
     * contract). Other destinations and bottom-of-library moves stay AI.
     */
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (!guard() && encoder != null && destinationZone == ZoneType.Library
                && orderedMoveToTopOfLibrary(destinationZone, source)) {
            encoder.knowLibrary(cards); // the seat orders them: their positions are known
        }
        if (guard() || cards.size() < 2 || destinationZone != ZoneType.Library
                || !orderedMoveToTopOfLibrary(destinationZone, source)) {
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
        final CardCollectionView[] ai = new CardCollectionView[1];
        final CardCollection left = new CardCollection(cards);
        final CardCollection topFirst = new CardCollection();
        while (left.size() > 1) {
            final List<Candidate> cands = new ArrayList<>();
            for (Card c : left) {
                cands.add(cardCand(c));
            }
            final int idx = ask(DecisionKind.LIBRARY_ORDER, cands, () -> {
                if (ai[0] == null) {
                    ai[0] = super.orderMoveToZoneList(new CardCollection(cards), destinationZone, source);
                }
                for (int i = ai[0].size() - 1; i >= 0; i--) {
                    if (left.contains(ai[0].get(i))) {
                        return indexOfRef(cands, ai[0].get(i));
                    }
                }
                return 0;
            }, "next card from the top" + (source == null ? "" : " (" + source.getHostCard().getName() + ")"));
            final Card c = (Card) cands.get(idx).ref();
            topFirst.add(c);
            left.remove(c);
        }
        topFirst.addAll(left);
        java.util.Collections.reverse(topFirst);
        return topFirst;
    }

    /**
     * Single-entity effect choices. Which card to play from a Play effect (cascade, "cast one of the exiled
     * cards", ...) is PLAY_EFFECT_CHOICE, seat-owned so the AI's {@code canPlayFromEffectAI} can neither veto nor
     * aim the cast. Every other one (Dig's "put one into your hand": Malevolent Rumble, Chrome Courier;
     * the AI's chooseCardsForEffect / chooseEntitiesForEffect loops) is CHOOSE_CARD. DONE (index 0) = none, when
     * optional. A single mandatory option is taken without a request (and without any AI evaluation).
     */
    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
                                                               SpellAbility sa, String title, boolean isOptional,
                                                               Player targetedPlayer, Map<String, Object> params) {
        if (guard() || sa == null || optionList.isEmpty()) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (optionList.size() == 1 && !isOptional) {
            return optionList.get(0);
        }
        final List<Candidate> cands = new ArrayList<>();
        if (isOptional) {
            cands.add(DONE);
        }
        for (T o : optionList) {
            cands.add(targetCand(o));
        }
        final int idx = ask(sa.getApi() == ApiType.Play ? DecisionKind.PLAY_EFFECT_CHOICE : DecisionKind.CHOOSE_CARD, cands, () -> {
            final T ai = super.chooseSingleEntityForEffect(optionList, null, sa, title, isOptional, targetedPlayer, params);
            return ai == null ? (isOptional ? 0 : -1) : indexOfRef(cands, ai);
        }, sa.getHostCard().getName() + ": " + title);
        final Candidate c = cands.get(idx);
        @SuppressWarnings("unchecked")
        final T chosen = c.type() == Candidate.Type.DONE ? null : (T) c.ref();
        return chosen;
    }

    // ------------------------------------------------------------------ targets

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        if (guard()) {
            return super.chooseTargetsFor(sa);
        }
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt == null) {
            return true;
        }
        if (sa.isDividedAsYouChoose() || tgt.isRandomTarget()) {
            // damage division stays heuristic in v1; counted
            stats.targetsDelegated++;
            delegatedTargeting++;
            try {
                return super.chooseTargetsFor(sa);
            } finally {
                delegatedTargeting--;
            }
        }
        final int min = sa.getMinTargets();
        final int max = sa.getMaxTargets();
        if (max == 0 && min == 0) {
            return true;
        }

        // AI plan for the DelegatingOracle hint: what would doTrigger(sa, true) target?
        final List<GameObject> aiPlan = new ArrayList<>();
        final boolean[] planned = {false};
        final Runnable plan = () -> {
            if (planned[0]) {
                return;
            }
            planned[0] = true;
            final TargetChoices saved = sa.getTargets();
            sa.resetTargets();
            super.chooseTargetsFor(sa);
            aiPlan.addAll(sa.getTargets());
            sa.resetTargets();
            sa.setTargets(saved);
        };

        final boolean stackOnly = tgt.getZone().size() == 1 && tgt.getZone().get(0) == ZoneType.Stack;
        int guardLoops = 0;
        while (!sa.isMaxTargetChosen() && guardLoops++ < 64) {
            final List<Candidate> cands = new ArrayList<>();
            final boolean canStop = sa.isMinTargetChosen();
            if (canStop) {
                cands.add(DONE);
            }
            final List<GameObject> options = targetCandidates(sa, tgt, stackOnly);
            if (FloatMana.mode(control) != FloatMana.Mode.OFF) { // flags float / floatb
                FloatMana.pruneTargets(sa, options, player);
            }
            for (GameObject o : options) {
                if (!sa.getTargets().contains(o)) {
                    cands.add(targetCand(o));
                }
            }
            if (cands.isEmpty() || (canStop && cands.size() == 1)) {
                break;
            }
            final int idx = ask(DecisionKind.TARGET, cands, () -> {
                plan.run();
                for (GameObject o : aiPlan) {
                    if (!sa.getTargets().contains(o)) {
                        return indexOfRef(cands, o);
                    }
                }
                return canStop ? 0 : -1;
            }, sa.getHostCard().getName() + ": " + sa.toUnsuppressedString());
            final Candidate c = cands.get(idx);
            if (c.type() == Candidate.Type.DONE) {
                break;
            }
            sa.getTargets().add((GameObject) c.ref());
        }
        return sa.isTargetNumberValid();
    }

    private List<GameObject> targetCandidates(SpellAbility sa, TargetRestrictions tgt, boolean stackOnly) {
        final List<GameObject> out = new ArrayList<>();
        if (!stackOnly) {
            for (GameEntity e : tgt.getAllCandidates(sa)) {
                if (sa.canTarget(e)) {
                    out.add(e);
                }
            }
        }
        if (tgt.getZone().contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : getGame().getStack()) {
                final SpellAbility onStack = si.getSpellAbility();
                if (sa.canTargetSpellAbility(onStack) && !out.contains(onStack)) {
                    out.add(onStack);
                }
            }
        }
        return out;
    }

    private static Candidate targetCand(GameObject o) {
        if (o instanceof Card c) {
            return new Candidate(Candidate.Type.CARD, cardLabel(c) + " [" + c.getZone().getZoneType() + "]", c);
        }
        if (o instanceof Player p) {
            return new Candidate(Candidate.Type.PLAYER, p.getName(), p);
        }
        if (o instanceof SpellAbility s) {
            return new Candidate(Candidate.Type.SPELL, s.getHostCard().getName() + " (" + s.getHostCard().getId()
                    + ") on stack", s);
        }
        return new Candidate(Candidate.Type.OPTION, String.valueOf(o), o);
    }

    // ------------------------------------------------------------------ modes, X, confirmations

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num,
                                                 boolean allowRepeat) {
        if (guard()) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        final List<AbilitySub> chosen = new ArrayList<>();
        final List<AbilitySub> aiPlan = new ArrayList<>();
        final boolean[] planned = {false};
        while (chosen.size() < num) {
            final List<Candidate> cands = new ArrayList<>();
            final boolean canStop = chosen.size() >= min;
            if (canStop) {
                cands.add(DONE);
            }
            for (AbilitySub m : possible) {
                if (allowRepeat || !chosen.contains(m)) {
                    cands.add(new Candidate(Candidate.Type.MODE, m.getDescription(), m));
                }
            }
            if (cands.isEmpty() || (canStop && cands.size() == 1)) {
                break;
            }
            final int step = chosen.size();
            final int idx = ask(DecisionKind.MODE, cands, () -> {
                if (!planned[0]) {
                    planned[0] = true;
                    final List<AbilitySub> ai = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
                    if (ai != null) {
                        aiPlan.addAll(ai);
                    }
                }
                return step < aiPlan.size() ? indexOfRef(cands, aiPlan.get(step)) : (canStop ? 0 : -1);
            }, sa.getHostCard().getName());
            final Candidate c = cands.get(idx);
            if (c.type() == Candidate.Type.DONE) {
                break;
            }
            chosen.add((AbilitySub) c.ref());
        }
        return chosen;
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        if (guard()) {
            return super.announceRequirements(ability, min, max, announce);
        }
        int hi = max;
        final Cost cost = ability.getPayCosts();
        final CostPartMana mana = cost == null ? null : cost.getCostMana();
        if ("X".equalsIgnoreCase(announce) && mana != null && mana.getAmountOfX() > 0) {
            final int left = ComputerUtilMana.determineLeftoverMana(ability, player, ability.isTrigger());
            hi = Math.min(hi, left / mana.getAmountOfX());
        }
        hi = Math.max(min, Math.min(hi, min + DecisionRequest.CANDIDATE_CAP - 1));
        final List<Candidate> cands = new ArrayList<>();
        for (int v = min; v <= hi; v++) {
            cands.add(new Candidate(Candidate.Type.NUMBER, announce + "=" + v, v));
        }
        final int lo = min;
        final int idx = ask(DecisionKind.ANNOUNCE, cands, () -> {
            final Integer ai = super.announceRequirements(ability, min, max, announce);
            return ai == null ? -1 : ai - lo;
        }, ability.getHostCard().getName() + " " + announce);
        return min + idx;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                                 Card cardToShow, Map<String, Object> params) {
        if (guard()) {
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }
        if (zoneChangeDone && mode == PlayerActionConfirmMode.ChangeZoneGeneral) {
            zoneChangeDone = false;
            return true; // DONE already meant "stop searching"
        }
        if (sa != null && sa.getApi() == ApiType.Play && sa.isKeyword(Keyword.MADNESS) && cardToShow != null
                && !madnessPayable(sa, cardToShow)) {
            // the madness cast would fail at payment ("payManaCost() cost was not paid") and the card go to the
            // graveyard anyway: decline without offering YES (and the target pick behind it), encoder 5
            stats.madnessUnpayable++;
            return false;
        }
        // encoder 7: a Play effect's YES carries the cast (its mode goes in candidate byte 12; label and type unchanged)
        final Object yes = sa != null && sa.getApi() == ApiType.Play && cardToShow != null
                ? new LegalActions.PlayEffectCast(sa, cardToShow) : null;
        final List<Candidate> cands = List.of(Candidate.of(Candidate.Type.NO, "no"),
                new Candidate(Candidate.Type.YES, "yes", yes));
        return ask(DecisionKind.CONFIRM_ACTION, cands,
                () -> super.confirmAction(sa, mode, message, options, cardToShow, params) ? 1 : 0,
                (mode == null ? "" : mode + ": ") + message) == 1;
    }

    /**
     * Can the madness cast behind {@code play} (the keyword's Play effect, {@code PlayCost$} = the madness cost) be paid
     * right now? Builds the spell the way PlayEffect does (the card's spells with the mana cost replaced, madness as the
     * alternative cost) and asks the same AI mana payer that would pay it, in test mode (nothing is tapped).
     */
    private boolean madnessPayable(SpellAbility play, Card card) {
        final String pc = play.getParam("PlayCost");
        if (pc == null) {
            return true;
        }
        final Cost cost = "ManaCost".equals(pc) ? new Cost(card.getManaCost(), false) : new Cost(pc, false);
        for (SpellAbility s : AbilityUtils.getBasicSpellsFromPlayEffect(card, player)) {
            s.setActivatingPlayer(player);
            final SpellAbility copy = s.copyWithManaCostReplaced(player, cost);
            copy.setActivatingPlayer(player);
            copy.setAlternativeCost(AlternativeCost.Madness);
            inAi++;
            try {
                if (ComputerUtilCost.canPayCost(copy, player, false)) {
                    return true;
                }
            } finally {
                inAi--;
            }
        }
        return false;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        if (guard() || wrapper.isMandatory()) {
            return super.confirmTrigger(wrapper);
        }
        if (!triggerCostPayable(wrapper.getWrappedAbility())) {
            // "you may pay X. If you do, ..." (Nihil Spellbomb's {B}): YES would fail at payment ("payManaCost() cost
            // was not paid") and do nothing, so decline without a request, as for unpayable madness
            stats.optionalCostUnpayable++;
            return false;
        }
        final List<Candidate> cands = List.of(Candidate.of(Candidate.Type.NO, "no"), Candidate.of(Candidate.Type.YES, "yes"));
        return ask(DecisionKind.CONFIRM_TRIGGER, cands, () -> super.confirmTrigger(wrapper) ? 1 : 0,
                wrapper.getHostCard().getName() + ": " + wrapper.toUnsuppressedString()) == 1;
    }

    /**
     * Can the cost of an optional trigger's ability be paid right now? That cost is paid on resolution, after the
     * confirmation (WrappedAbility.resolve, then PlaySpellAbility with the trigger as an effect payment), by its
     * activating player. Asks the same AI payer in test mode (nothing is tapped); no cost = payable.
     */
    private boolean triggerCostPayable(SpellAbility sa) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || cost.getCostParts().isEmpty() || cost.isFree()) {
            return true;
        }
        final Player payer = sa.getActivatingPlayer() != null ? sa.getActivatingPlayer() : player;
        inAi++;
        try {
            return ComputerUtilCost.canPayCost(cost, sa, payer, sa.isTrigger());
        } finally {
            inAi--;
        }
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (enterStock() || guard()) {
            super.declareAttackers(attacker, combat);
            return;
        }
        GameEntity defender = null;
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Player) {
                defender = d;
                break;
            }
        }
        if (defender == null) {
            super.declareAttackers(attacker, combat);
            return;
        }
        final List<Card> eligible = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, defender)) {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }
        // AI plan (hint): let the AI declare, remember its attackers, clear
        final List<Card> aiPlan = new ArrayList<>();
        if (oracle.wantsAiHint()) {
            inAi++;
            try {
                super.declareAttackers(attacker, combat);
                aiPlan.addAll(combat.getAttackers());
            } finally {
                inAi--;
            }
        }
        combat.clearAttackers();
        final GameEntity def = defender;
        while (true) {
            final List<Candidate> cands = new ArrayList<>();
            cands.add(DONE);
            for (Card c : eligible) {
                if (!combat.isAttacking(c) && CombatUtil.canAttack(c, def)) {
                    cands.add(cardCand(c));
                }
            }
            if (cands.size() == 1) {
                break;
            }
            final int idx = ask(DecisionKind.ATTACK, cands, () -> {
                for (Card c : aiPlan) {
                    if (!combat.isAttacking(c)) {
                        return indexOfRef(cands, c);
                    }
                }
                return 0;
            }, "attackers so far: " + combat.getAttackers().size());
            if (idx == 0) {
                break;
            }
            combat.addAttacker((Card) cands.get(idx).ref(), def);
        }
        if (!CombatUtil.validateAttackers(combat)) {
            stats.attackFallbacks++;
            stats.note("attack fallback: " + combat.getAttackers());
            combat.clearAttackers();
            final Map<Card, GameEntity> legal = combat.getAttackConstraints().getLegalAttackers().getLeft();
            for (Map.Entry<Card, GameEntity> e : legal.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            if (!CombatUtil.validateAttackers(combat)) {
                combat.clearAttackers();
                inAi++;
                try {
                    super.declareAttackers(attacker, combat);
                } finally {
                    inAi--;
                }
            }
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (enterStock() || guard()) {
            super.declareBlockers(defender, combat);
            return;
        }
        // AI plan (hint): (attacker, blocker) pairs in the AI's declaration order
        final List<Card[]> aiPairs = new ArrayList<>();
        if (oracle.wantsAiHint()) {
            inAi++;
            try {
                super.declareBlockers(defender, combat);
            } finally {
                inAi--;
            }
            for (Card a : combat.getAttackers()) {
                for (Card b : combat.getBlockers(a)) {
                    if (b.getController().equals(defender)) {
                        aiPairs.add(new Card[] {a, b});
                    }
                }
            }
            clearOwnBlocks(defender, combat);
        }
        final List<Card> myCreatures = new ArrayList<>(defender.getCreaturesInPlay());
        int loops = 0;
        while (loops++ < 200) {
            // stage 1: which attacker to (further) block, or DONE
            final List<Candidate> stage1 = new ArrayList<>();
            stage1.add(DONE);
            for (Card a : combat.getAttackers()) {
                if (isAttackingMe(combat, a, defender) && blockOffered(combat, a, defender, myCreatures)) {
                    stage1.add(cardCand(a));
                }
            }
            if (stage1.size() == 1) {
                break;
            }
            final int i1 = ask(DecisionKind.BLOCK_ATTACKER, stage1, () -> {
                for (Card[] p : aiPairs) {
                    if (!combat.isBlocking(p[1], p[0])) {
                        return indexOfRef(stage1, p[0]);
                    }
                }
                return 0;
            }, "blocks so far: " + combat.getAllBlockers().size());
            if (i1 == 0) {
                break;
            }
            final Card att = (Card) stage1.get(i1).ref();
            // stage 2: the blocker for it. An attacker that needs several blockers (menace ...) and has too few
            // keeps getting stage 2 asks until it has enough: picking it in stage 1 was the commitment (no DONE)
            do {
                final List<Candidate> stage2 = new ArrayList<>();
                for (Card b : myCreatures) {
                    if (CombatUtil.canBlock(att, b, combat)) {
                        stage2.add(cardCand(b));
                    }
                }
                if (stage2.isEmpty()) {
                    break; // ran out of legal blockers mid-run (e.g. a block-count restriction): validation repairs
                }
                final int i2 = ask(DecisionKind.BLOCK_BLOCKER, stage2, () -> {
                    for (Card[] p : aiPairs) {
                        if (p[0] == att && !combat.isBlocking(p[1], p[0])) {
                            return indexOfRef(stage2, p[1]);
                        }
                    }
                    return -1;
                }, "blocking " + cardLabel(att));
                combat.addBlocker(att, (Card) stage2.get(i2).ref());
            } while (blockersStillNeeded(combat, att, defender) > 0);
        }
        String invalid = CombatUtil.validateBlocks(combat, defender);
        if (invalid != null && repairBlockCounts(defender, combat)) {
            final String after = CombatUtil.validateBlocks(combat, defender);
            stats.note("block repair: " + invalid + (after == null ? "" : " / still: " + after));
            if (after == null) {
                stats.blockRepairs++;
            }
            invalid = after;
        }
        if (invalid != null) {
            stats.blockFallbacks++;
            stats.note("block fallback: " + invalid);
            clearOwnBlocks(defender, combat);
            if (CombatUtil.validateBlocks(combat, defender) != null) {
                inAi++;
                try {
                    super.declareBlockers(defender, combat);
                } finally {
                    inAi--;
                }
            }
        }
    }

    /**
     * How many more blockers {@code attacker} needs before its block is legal: 0 for a normal attacker (one blocker
     * is enough) and for a multi-blocker attacker (menace ...) that already has its minimum.
     */
    public static int blockersStillNeeded(Combat combat, Card attacker, Player defender) {
        final int need = CombatUtil.getMinNumBlockersForAttacker(attacker, defender);
        return need <= 1 ? 0 : Math.max(0, need - combat.getBlockers(attacker).size());
    }

    /**
     * Stage 1 offer rule: is {@code attacker} worth a (further) block pick? Needs a creature of {@code mine} that can
     * block it (Forge's canBlock already refuses one more blocker past a "can't be blocked by more than N" cap). A
     * multi-blocker attacker that is not yet legally blocked is offered only when enough of {@code mine} can still
     * join it, so the forced run of stage 2 picks can complete.
     */
    public static boolean blockOffered(Combat combat, Card attacker, Player defender, List<Card> mine) {
        int usable = 0;
        for (Card b : mine) {
            if (CombatUtil.canBlock(attacker, b, combat)) {
                usable++;
            }
        }
        if (usable == 0) {
            return false;
        }
        final int more = blockersStillNeeded(combat, attacker, defender);
        return more == 0 || usable >= more && CombatUtil.canAttackerBeBlockedWithAmount(attacker,
                CombatUtil.getMinNumBlockersForAttacker(attacker, defender), combat);
    }

    /**
     * Minimal repair of an invalid declaration: drop only the blocks on attackers whose blocker count is illegal
     * (a lone blocker on a menace attacker ...), keeping every other block. True when something was dropped.
     */
    private static boolean repairBlockCounts(Player defender, Combat combat) {
        boolean dropped = false;
        for (Card a : combat.getAttackers()) {
            final int n = combat.getBlockers(a).size();
            if (n > 0 && !CombatUtil.canAttackerBeBlockedWithAmount(a, n, combat)) {
                for (Card b : new CardCollection(combat.getBlockers(a))) {
                    if (b.getController().equals(defender)) {
                        combat.removeBlockAssignment(a, b);
                        dropped = true;
                    }
                }
            }
        }
        return dropped;
    }

    private static boolean isAttackingMe(Combat combat, Card attacker, Player me) {
        final GameEntity d = combat.getDefenderByAttacker(attacker);
        if (d instanceof Player p) {
            return p.equals(me);
        }
        if (d instanceof Card c) {
            return c.getController().equals(me);
        }
        return false;
    }

    private static void clearOwnBlocks(Player defender, Combat combat) {
        for (Card b : new CardCollection(combat.getAllBlockers())) {
            if (b.getController().equals(defender)) {
                for (Card a : new CardCollection(combat.getAttackersBlockedBy(b))) {
                    combat.removeBlockAssignment(a, b);
                }
            }
        }
    }

    // ------------------------------------------------------------------ mulligan

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        if (guard() || !control.has(Control.Group.PLAYDRAW)) {
            return LobbyPlayerExternal.startingPlayer(getGame(), super.chooseStartingPlayer(isFirstGame)); // Handicap.DRAW
        }
        final Player forced = LobbyPlayerExternal.startingPlayer(getGame(), null); // Handicap.DRAW: not the seat's choice
        if (forced != null) {
            return forced;
        }
        Player opp = null;
        for (Player o : getGame().getPlayers()) {
            if (o != player) {
                opp = o;
                break;
            }
        }
        if (opp == null) {
            return player;
        }
        // encoder 8, flag playdraw: PLAY_DRAW, NO = draw (0) / YES = play (1)
        final List<Candidate> cands = List.of(new Candidate(Candidate.Type.NO, "draw", opp),
                new Candidate(Candidate.Type.YES, "play", player));
        return ask(DecisionKind.PLAY_DRAW, cands, () -> super.chooseStartingPlayer(isFirstGame) == player ? 1 : 0,
                isFirstGame ? "won the toss" : "lost the previous game") == 1 ? player : opp;
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (guard()) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        final Boolean forced = LobbyPlayerExternal.forcedKeep(player); // Handicap.MULL6: not the seat's decision
        if (forced != null) {
            if (encoder != null) {
                encoder.skipMulliganDecision(); // later observations count this prompt as if it had been asked
            }
            return forced;
        }
        if (player.getCardsIn(ZoneType.Hand).size() <= cardsToReturn) {
            return true; // nothing left to keep: stop mulliganning
        }
        final List<Candidate> cands = List.of(Candidate.of(Candidate.Type.KEEP, "keep"),
                Candidate.of(Candidate.Type.MULLIGAN, "mulligan"));
        return ask(DecisionKind.MULLIGAN, cands, () -> super.mulliganKeepHand(firstPlayer, cardsToReturn) ? 0 : 1,
                "hand " + player.getCardsIn(ZoneType.Hand) + ", bottom " + cardsToReturn) == 0;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (guard()) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        return pickCards(DecisionKind.MULLIGAN_BOTTOM, new CardCollection(hand), cardsToReturn, cardsToReturn,
                () -> super.tuckCardsViaMulligan(new CardCollection(hand), cardsToReturn), "bottom " + cardsToReturn);
    }

    // ------------------------------------------------------------------ discards

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards,
                                                       int min, int max, CardCollectionView visibleToChooser) {
        if (guard()) {
            return super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        }
        return pickCards(DecisionKind.DISCARD, new CardCollection(validCards), min, max,
                () -> super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser),
                "discard " + min + "-" + max + (sa == null ? "" : " for " + sa.getHostCard().getName()));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        if (guard()) {
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        }
        return pickCards(DecisionKind.DISCARD, new CardCollection(player.getCardsIn(ZoneType.Hand)), numDiscard,
                numDiscard, () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard), "hand size " + numDiscard);
    }

    /**
     * Sequential single picks of {@code min..max} cards from {@code pool}; DONE (index 0) once min is met. When
     * the whole pool must be taken there is no choice: no request, counted in {@code forcedSingle}.
     */
    CardCollection pickCards(DecisionKind kind, CardCollection pool, int min, int max,
                             java.util.function.Supplier<CardCollectionView> ai, String context) {
        if (min >= pool.size()) {
            stats.forcedSingle++;
            return new CardCollection(pool);
        }
        final CardCollection chosen = new CardCollection();
        final List<Card> aiPlan = new ArrayList<>();
        final boolean[] planned = {false};
        max = Math.min(max, pool.size());
        while (chosen.size() < max) {
            final List<Candidate> cands = new ArrayList<>();
            final boolean canStop = chosen.size() >= min;
            if (canStop) {
                cands.add(DONE);
            }
            for (Card c : pool) {
                if (!chosen.contains(c)) {
                    cands.add(cardCand(c));
                }
            }
            if (cands.isEmpty() || (canStop && cands.size() == 1)) {
                break;
            }
            final int idx = ask(kind, cands, () -> {
                if (!planned[0]) {
                    planned[0] = true;
                    final CardCollectionView v = ai.get();
                    if (v != null) {
                        for (Card c : v) {
                            aiPlan.add(c);
                        }
                    }
                }
                for (Card c : aiPlan) {
                    if (!chosen.contains(c)) {
                        return indexOfRef(cands, c);
                    }
                }
                return canStop ? 0 : -1;
            }, context);
            final Candidate c = cands.get(idx);
            if (c.type() == Candidate.Type.DONE) {
                break;
            }
            chosen.add((Card) c.ref());
        }
        return chosen;
    }

    // ------------------------------------------------------------------ scry / surveil

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (guard()) {
            return super.arrangeForScry(topN);
        }
        return arrange(topN, DecisionKind.SCRY, DecisionKind.SCRY_ORDER, Candidate.Type.BOTTOM, true);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        if (guard()) {
            return super.arrangeForSurveil(topN);
        }
        return arrange(topN, DecisionKind.SURVEIL, DecisionKind.SURVEIL_ORDER, Candidate.Type.GRAVEYARD, false);
    }

    private ImmutablePair<CardCollection, CardCollection> arrange(CardCollection topN, DecisionKind kind,
                                                                  DecisionKind orderKind, Candidate.Type away,
                                                                  boolean scry) {
        if (encoder != null) {
            encoder.knowLibrary(topN); // scried / surveilled cards: the seat places them
        }
        final ImmutablePair<CardCollection, CardCollection>[] ai = new ImmutablePair[1];
        final Runnable plan = () -> {
            if (ai[0] == null) {
                ai[0] = scry ? super.arrangeForScry(new CardCollection(topN))
                        : super.arrangeForSurveil(new CardCollection(topN));
            }
        };
        final CardCollection top = new CardCollection();
        final CardCollection rest = new CardCollection();
        for (Card c : topN) {
            final List<Candidate> cands = List.of(new Candidate(Candidate.Type.TOP, "top: " + cardLabel(c), c),
                    new Candidate(away, away.name().toLowerCase() + ": " + cardLabel(c), c));
            final int idx = ask(kind, cands, () -> {
                plan.run();
                return ai[0].getRight() != null && ai[0].getRight().contains(c) ? 1 : 0;
            }, cardLabel(c));
            (idx == 0 ? top : rest).add(c);
        }
        final CardCollection ordered = new CardCollection();
        while (top.size() > 1) {
            final List<Candidate> cands = new ArrayList<>();
            for (Card c : top) {
                cands.add(cardCand(c));
            }
            final int idx = ask(orderKind, cands, () -> {
                plan.run();
                final CardCollection aiTop = ai[0].getLeft();
                if (aiTop != null) {
                    for (Card c : aiTop) {
                        if (top.contains(c)) {
                            return indexOfRef(cands, c);
                        }
                    }
                }
                return 0;
            }, "next card from the top");
            final Card c = (Card) cands.get(idx).ref();
            ordered.add(c);
            top.remove(c);
        }
        ordered.addAll(top);
        return ImmutablePair.of(ordered, rest);
    }

    // ------------------------------------------------------------------ encoder 8 decision groups (Control)
    // Each override answers exactly as before (super, counted as delegated) while its flag is off.

    /**
     * Combat damage assignment (flag {@code damage}): sequential COMBAT_DAMAGE picks. A pick gives a recipient the damage
     * it still needs for lethal (or everything left, if it already has lethal or less is left); with trample, once every
     * blocker has lethal, the defender is a candidate for everything left. This fork plays the Foundations rules
     * ({@code GameRules.hasOrderCombatants()} false, so {@code overrideOrder} is true and there is no damage assignment
     * order): any split among the blockers is legal, and trample damage reaches the defender only after every blocker has
     * lethal damage (CR 702.19c), lethal counting deathtouch as 1 and damage already marked or assigned this step
     * ({@code Card.getLethalDamage}, the rule the desktop GUI's VAssignCombatDamage enforces). Shapes the seam does not
     * model (another player's creature: banding / Defensive Formation; "assign as though unblocked" / "divided as you
     * choose" keywords; legacy damage order) stay with the AI, counted in {@code damageDelegated}.
     */
    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        if (guard() || !control.has(Control.Group.DAMAGE) || damageDealt <= 0) { // 0 damage (0-power blocker): no choice
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        final Combat combat = getGame().getCombat();
        boolean modelled = overrideOrder && combat != null && attacker.getController() == player
                && !blockers.isEmpty()
                && !attacker.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                        + "defending player and/or any number of creatures they control.");
        for (Card b : blockers) {
            modelled &= b.isCreature() && (combat.isBlocking(b, attacker) || combat.isBlocking(attacker, b));
        }
        if (!modelled) {
            stats.damageDelegated++;
            stats.note("damage left to the AI: " + cardLabel(attacker) + " (" + (attacker.getController() == player ? "own"
                    : "opponent's") + ", order " + overrideOrder + ", " + damageDealt + " damage) -> " + blockers);
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        stats.damageAssignments++;
        final boolean trample = defender != null && attacker.isAttacking() && attacker.hasKeyword(Keyword.TRAMPLE);
        final boolean deathtouch = attacker.hasKeyword(Keyword.DEATHTOUCH);
        final List<Card> to = new ArrayList<>(blockers);
        final int[] got = new int[to.size()];
        int toDefender = 0;
        int left = damageDealt;
        final Map<Object, Integer>[] ai = new Map[1];
        while (left > 0) {
            final List<Candidate> cands = new ArrayList<>();
            boolean allLethal = true;
            for (int i = 0; i < to.size(); i++) {
                final Card b = to.get(i);
                final int need = Math.max(0, lethalNeed(b, deathtouch) - got[i]);
                allLethal &= need == 0;
                cands.add(new Candidate(Candidate.Type.CARD, cardLabel(b) + " +" + (need > 0 ? Math.min(need, left) : left),
                        new ControlPicks.DamagePick(b, need > 0 ? Math.min(need, left) : left, need, left, false)));
            }
            if (trample && allLethal) {
                final int life = defender instanceof Player p ? p.getLife()
                        : defender instanceof Card c ? (c.isPlaneswalker() ? c.getCurrentLoyalty() : c.getCurrentDefense()) : 0;
                cands.add(new Candidate(defender instanceof Player ? Candidate.Type.PLAYER : Candidate.Type.CARD,
                        "defender " + defender + " +" + left,
                        new ControlPicks.DamagePick(defender, left, Math.max(0, life - toDefender), left, true)));
            }
            final int dLeft = left;
            final int dDef = toDefender;
            final int idx = ask(DecisionKind.COMBAT_DAMAGE, cands, () -> {
                if (ai[0] == null) {
                    final Map<Card, Integer> m = super.assignCombatDamage(attacker, blockers, remaining, damageDealt,
                            defender, overrideOrder);
                    ai[0] = m == null ? Map.of() : new java.util.HashMap<Object, Integer>(m);
                }
                for (int i = 0; i < cands.size(); i++) {
                    final ControlPicks.DamagePick d = (ControlPicks.DamagePick) cands.get(i).ref();
                    final int want = ai[0].getOrDefault(d.defender() ? null : d.to(), 0);
                    if (want > (d.defender() ? dDef : got[i])) {
                        return i;
                    }
                }
                return -1;
            }, cardLabel(attacker) + ": " + dLeft + " of " + damageDealt + " damage left");
            final ControlPicks.DamagePick d = (ControlPicks.DamagePick) cands.get(idx).ref();
            if (d.defender()) {
                toDefender += d.amount();
            } else {
                got[idx] += d.amount();
            }
            left -= d.amount();
        }
        final Map<Card, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < to.size(); i++) {
            if (got[i] > 0) {
                out.put(to.get(i), got[i]);
            }
        }
        if (toDefender > 0) {
            out.put(null, toDefender); // Combat: a null key is the defender (trample)
        }
        return out;
    }

    /** Damage {@code c} needs to have lethal damage assigned from this source (VAssignCombatDamage.getDamageToKill). */
    private static int lethalNeed(Card c, boolean deathtouch) {
        int lethal = Math.max(0, c.getLethalDamage());
        if (c.isPlaneswalker()) {
            lethal = c.getCurrentLoyalty();
        } else if (deathtouch) {
            lethal = Math.min(lethal, 1);
        }
        return lethal;
    }

    /**
     * Simultaneous triggers (flag {@code order}): sequential TRIGGER_ORDER picks of the ability that resolves next (top of
     * the stack first; ABILITY candidates, one per group of identical abilities: same source name and text, which are
     * interchangeable). The returned list is in put-on-the-stack order (PlayerController contract: the AI's
     * {@code orderPlaySa} puts the first one on the stack first, so it resolves last). One group = no decision.
     */
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (presetOrder != null) { // the delegating seat's stock pipeline asking for the order it already chose
            final List<SpellAbility> o = presetOrder;
            presetOrder = null;
            return o;
        }
        if (guard() || !control.has(Control.Group.ORDER) || activePlayerSAs.size() < 2) {
            return super.orderSimultaneousSa(activePlayerSAs);
        }
        final List<SpellAbility> left = new ArrayList<>(activePlayerSAs);
        final List<SpellAbility> resolveFirst = new ArrayList<>();
        final List<SpellAbility>[] ai = new List[1];
        boolean asked = false;
        while (true) {
            final java.util.LinkedHashMap<String, SpellAbility> groups = new java.util.LinkedHashMap<>();
            for (SpellAbility sa : left) {
                groups.putIfAbsent(orderKey(sa), sa);
            }
            if (groups.size() < 2) {
                break;
            }
            final List<Candidate> cands = new ArrayList<>();
            for (SpellAbility sa : groups.values()) {
                cands.add(new Candidate(Candidate.Type.ABILITY, sa.getHostCard().getName() + ": " + sa.toUnsuppressedString(), sa));
            }
            final int idx = ask(DecisionKind.TRIGGER_ORDER, cands, () -> {
                if (ai[0] == null) { // the AI's put-on-the-stack order; it resolves in reverse (orderPlaySa edits its input)
                    ai[0] = new ArrayList<>(super.orderSimultaneousSa(new ArrayList<>(activePlayerSAs)));
                    java.util.Collections.reverse(ai[0]);
                }
                for (SpellAbility sa : ai[0]) {
                    if (left.contains(sa)) {
                        final String k = orderKey(sa);
                        for (int i = 0; i < cands.size(); i++) {
                            if (orderKey((SpellAbility) cands.get(i).ref()).equals(k)) {
                                return i;
                            }
                        }
                    }
                }
                return -1;
            }, left.size() + " simultaneous abilities, resolves next");
            asked = true;
            final SpellAbility next = (SpellAbility) cands.get(idx).ref();
            resolveFirst.add(next);
            left.remove(next);
        }
        if (!asked) { // identical abilities: their order is no choice, keep the AI's
            return super.orderSimultaneousSa(activePlayerSAs);
        }
        stats.triggerOrders++;
        resolveFirst.addAll(left);
        java.util.Collections.reverse(resolveFirst);
        return resolveFirst;
    }

    private static String orderKey(SpellAbility sa) {
        return sa.getHostCard().getName() + " | " + sa.toUnsuppressedString() + " | " + sa.isCopied();
    }

    /**
     * "Choose N cards" effects (flag {@code effects}; Extract a Confession's victim, ChooseCard, counters ...): sequential
     * CHOOSE_CARD picks, DONE (index 0) once the minimum is met, or before the first pick when the choice is optional.
     */
    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min,
                                                   int max, boolean isOptional, Map<String, Object> params) {
        if (guard() || !control.has(Control.Group.EFFECTS) || sa == null || sourceList.isEmpty()) {
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }
        final CardCollection pool = new CardCollection(sourceList);
        final int hi = Math.min(max, pool.size());
        final int lo = Math.min(min, hi);
        if (!isOptional && lo >= pool.size()) {
            stats.forcedSingle++;
            return pool;
        }
        final CardCollection chosen = new CardCollection();
        final List<Card> aiPlan = new ArrayList<>();
        final boolean[] planned = {false};
        while (chosen.size() < hi) {
            final List<Candidate> cands = new ArrayList<>();
            final boolean canStop = chosen.size() >= lo || isOptional && chosen.isEmpty();
            if (canStop) {
                cands.add(DONE);
            }
            for (Card c : pool) {
                if (!chosen.contains(c)) {
                    cands.add(cardCand(c));
                }
            }
            if (cands.isEmpty() || (canStop && cands.size() == 1)) {
                break;
            }
            final int idx = ask(DecisionKind.CHOOSE_CARD, cands, () -> {
                if (!planned[0]) {
                    planned[0] = true;
                    final CardCollectionView v = super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
                    if (v != null) {
                        for (Card c : v) {
                            aiPlan.add(c);
                        }
                    }
                }
                for (Card c : aiPlan) {
                    if (!chosen.contains(c)) {
                        return indexOfRef(cands, c);
                    }
                }
                return canStop ? 0 : -1;
            }, sa.getHostCard().getName() + ": " + title);
            if (cands.get(idx).type() == Candidate.Type.DONE) {
                break;
            }
            chosen.add((Card) cands.get(idx).ref());
        }
        return chosen;
    }

    /**
     * The stock AI's loop already asks {@link #chooseSingleEntityForEffect} per pick (CHOOSE_CARD), so these were
     * policy-owned before encoder 8 and only counted as delegated; with the {@code effects} flag they are declared here
     * (same answer: the AI's loop over the seat's single picks) and no longer counted.
     */
    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max,
                                                                  DelayedReveal delayedReveal, SpellAbility sa, String title,
                                                                  Player targetedPlayer, Map<String, Object> params) {
        return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
                                                            int num, Map<String, Object> params) {
        return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
    }

    /** {@link ExternalManaPayment}: this seat's stats and decision plumbing. */
    SeatStats seatStats() {
        return stats;
    }
}
