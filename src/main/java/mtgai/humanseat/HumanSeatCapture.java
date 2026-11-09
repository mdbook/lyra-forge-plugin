package mtgai.humanseat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.HumanDecisionObserver;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import mtgai.controller.Control;
import mtgai.controller.FloatMana;
import mtgai.controller.LegalActions;
import mtgai.controller.PlayerControllerExternal;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionRequest;
import mtgai.encode.ObservationEncoder;
import mtgai.match.GameRecord;
import mtgai.traj.TrajectoryWriter;

/**
 * Behaviour cloning from the human seat: the fork's human controller reports each answer of the human
 * ({@link HumanDecisionObserver}); this turns the main ones into trajectory steps of the human's seat, flagged
 * {@code NOT_POLICY | TEACHER | HUMAN}, so the learner's imitation loss can train on them. The step is encoded exactly as
 * the Neural seat's own decisions are (same {@link ObservationEncoder}, same candidate lists), from the human's
 * perspective.
 *
 * <p>Captured: priority (pass / land / cast / activate, optional costs not yet chosen = the plain variant), keep or
 * mulligan, the London bottom picks, attackers and blockers (replayed as the seat's pick-or-DONE loops). Skipped:
 * targets, modes, X, costs and every other sub-choice; priority windows the human never saw (auto-pass / yield) or
 * the seat itself auto-passes (upkeep, draw, empty combat ...); an answer that maps to no candidate (counted in
 * {@link Seat#unmapped}); attacks on planeswalkers / battles or through bands; any game that is not 2-player or has no
 * Neural seat. On only with consent ({@link #enabled()}); nothing here may throw into the game.
 */
public final class HumanSeatCapture extends HumanDecisionObserver {
    public static final String PROPERTY = "mtgai.neural.captureHuman";
    /** NOT_POLICY | TEACHER | HUMAN. */
    static final int FLAGS = TrajectoryWriter.NOT_POLICY | TrajectoryWriter.TEACHER | TrajectoryWriter.HUMAN;

    private static final HumanSeatCapture INSTANCE = new HumanSeatCapture();

    private HumanSeatCapture() {
    }

    /**
     * Capture is on when {@code -Dmtgai.neural.captureHuman=true}, or when the player opted in to sending games
     * ({@code -Dmtgai.neural.upload} non-empty, the consent the launchers' MTGAI_UPLOAD=1 gives); an explicit
     * {@code captureHuman=false} forces it off.
     */
    public static boolean enabled() {
        final String v = System.getProperty(PROPERTY);
        if (v != null && !v.isBlank()) {
            return Boolean.parseBoolean(v.trim());
        }
        return !System.getProperty(GameUpload.UPLOAD_PROPERTY, "").isBlank();
    }

    /** Registers the observer if {@link #enabled()} (idempotent); called when a Neural seat is made. */
    static void installIfEnabled() {
        if (enabled() && HumanDecisionObserver.get() != INSTANCE) {
            HumanDecisionObserver.set(INSTANCE);
        }
    }

    /** One human seat in one game: its encoder and its trajectory record. */
    static final class Seat {
        final int index;
        final ObservationEncoder encoder;
        final GameRecord record = new GameRecord(null);
        int unmapped;
        int skipped;

        Seat(int index, ObservationEncoder encoder) {
            this.index = index;
            this.encoder = encoder;
        }

        /** Records {@code cands[idx]} as the human's pick; no step for a forced (single) candidate. */
        void step(DecisionKind kind, List<Candidate> cands, int idx) {
            if (cands.size() < 2) {
                return;
            }
            if (cands.size() > DecisionRequest.CANDIDATE_CAP) {
                cands = new ArrayList<>(cands.subList(0, DecisionRequest.CANDIDATE_CAP));
            }
            if (idx < 0 || idx >= cands.size()) {
                unmapped++;
                return;
            }
            record.step(index, new TrajectoryWriter.Step(encoder.encode(kind, cands), idx, FLAGS,
                    TrajectoryWriter.fullMask(cands.size()), 0f, 0f, 0f, record.steps(index).size(), null));
        }
    }

    /** The human's seat of the game {@code p} plays, created on first use; null when this game is not captured. */
    private static Seat seat(Player p) {
        final Game game = p.getGame();
        if (p.getController() instanceof PlayerControllerExternal || game.getRegisteredPlayers().size() != 2) {
            return null;
        }
        final HumanGameLog log = HumanGameLog.existing(game); // only games with a Neural seat have one
        if (log == null) {
            return null;
        }
        if (log.human == null) {
            final int index = game.getRegisteredPlayers().indexOf(p);
            if (index < 0 || index > 1) {
                return null;
            }
            log.human = new Seat(index, new ObservationEncoder(NeuralSeat.get().vocab(), p));
            log.addRecord(index, log.human.record);
        }
        return log.human.index == game.getRegisteredPlayers().indexOf(p) ? log.human : null;
    }

    // ------------------------------------------------------------------ priority

    @Override
    protected void priority(Player p, List<SpellAbility> chosen) {
        final Seat s = seat(p);
        if (s == null) {
            return;
        }
        final Game game = p.getGame();
        if (game.getStack().isEmpty() && PlayerControllerExternal.autoPassStep(game.getPhaseHandler().getPhase(), game)) {
            return; // the Neural seat is never asked here
        }
        final PriorityStep step = priorityStep(p, chosen, Control.fromSystemProperty());
        if (step == null) {
            return;
        }
        if (step.index() < 0) {
            s.unmapped++; // matchAiAction = -2 when nothing matches
            return;
        }
        s.step(DecisionKind.PRIORITY, step.candidates(), step.index());
    }

    /** A priority window as the Neural seat would see it, and the human's answer in it ({@code index} -1 = unmapped). */
    public record PriorityStep(List<Candidate> candidates, int index) {
    }

    /**
     * The priority candidates for {@code p} now (as the seat lists them) and the index of {@code chosen} (null / empty =
     * pass); null when there is no legal action. With flag {@code floatb} the seat's float actions are offered too, so a
     * human tapping a land for mana while an untap enabler could untap it is labelled as that float; with flag
     * {@code floatsac} a sacrifice-for-mana activation is labelled as its sacrifice float; with flag {@code floatf} a
     * filter activation (Prophetic Prism) as its filter float.
     */
    public static PriorityStep priorityStep(Player p, List<SpellAbility> chosen, Control control) {
        final List<LegalActions.Action> legal = LegalActions.enumerate(p, new HashSet<>());
        if (FloatMana.mode(control) == FloatMana.Mode.EXPLICIT) {
            final List<LegalActions.Action> floats = FloatMana.floatActions(legal, p, Set.of());
            if (!floats.isEmpty()) { // as the seat: no-op enablers are hidden in float windows only
                FloatMana.hideNoopEnablers(legal);
                legal.addAll(floats);
            }
        }
        if (control.has(Control.Group.FLOATSAC)) { // a human activating Ashnod's Altar at priority = that float
            legal.addAll(FloatMana.sacFloatActions(p, Set.of()));
        }
        if (control.has(Control.Group.FLOATF)) {
            // a human activating Prophetic Prism at priority = its filter float; the colour is chosen only at resolution,
            // so with several colours offered the first (WUBRG order) is the label (usually only one is offered)
            legal.addAll(FloatMana.filterFloatActions(p, Set.of()));
        }
        if (legal.isEmpty()) {
            return null;
        }
        final int idx = chosen == null || chosen.isEmpty() ? 0 : 1 + PlayerControllerExternal.matchAiAction(legal, chosen.get(0));
        return new PriorityStep(PlayerControllerExternal.priorityCandidates(legal), Math.max(idx, -1));
    }

    // ------------------------------------------------------------------ mulligan

    @Override
    protected void mulligan(Player p, boolean keep, int cardsToReturn) {
        final Seat s = seat(p);
        if (s == null || p.getCardsIn(ZoneType.Hand).size() <= cardsToReturn) {
            return;
        }
        s.step(DecisionKind.MULLIGAN, List.of(Candidate.of(Candidate.Type.KEEP, "keep"),
                Candidate.of(Candidate.Type.MULLIGAN, "mulligan")), keep ? 0 : 1);
    }

    /** The London bottom picks, in hand order: the seat's sequential single picks of {@code n} cards. */
    @Override
    protected void bottom(Player p, CardCollectionView hand, CardCollectionView selected) {
        final Seat s = seat(p);
        if (s == null || selected.size() >= hand.size()) {
            return;
        }
        final CardCollection chosen = new CardCollection();
        for (Card pick : selected) {
            final List<Candidate> cands = new ArrayList<>();
            for (Card c : hand) {
                if (!chosen.contains(c)) {
                    cands.add(PlayerControllerExternal.cardCand(c));
                }
            }
            s.step(DecisionKind.MULLIGAN_BOTTOM, cands, indexOf(cands, pick));
            chosen.add(pick);
        }
    }

    // ------------------------------------------------------------------ combat

    /**
     * Attackers, replayed: one step per declared attacker (candidates DONE + the creatures not attacking yet), then
     * DONE, as the seat's own loop asks. The combat is never touched (an emptied-and-refilled combat leaves empty
     * attacking bands that read as unblocked attackers); {@code CombatUtil.canAttack} does not read it.
     */
    @Override
    protected void attackers(Player p, Combat combat) {
        final Seat s = seat(p);
        if (s == null) {
            return;
        }
        GameEntity def = null;
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Player) {
                def = d;
                break;
            }
        }
        if (def == null) {
            return;
        }
        final List<Card> declared = new ArrayList<>(combat.getAttackers());
        for (Card a : declared) {
            if (combat.getDefenderByAttacker(a) != def || combat.getBandOfAttacker(a).getAttackers().size() > 1) {
                s.skipped++; // planeswalker / battle target or a band: not the seat's surface
                return;
            }
        }
        final List<Card> eligible = new ArrayList<>();
        for (Card c : p.getCreaturesInPlay()) {
            if (declared.contains(c) || CombatUtil.canAttack(c, def)) {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }
        for (int k = 0; k <= declared.size(); k++) {
            final List<Card> already = declared.subList(0, k);
            final List<Candidate> cands = new ArrayList<>();
            cands.add(PlayerControllerExternal.DONE);
            for (Card c : eligible) {
                if (!already.contains(c) && CombatUtil.canAttack(c, def)) {
                    cands.add(PlayerControllerExternal.cardCand(c));
                }
            }
            if (cands.size() == 1) {
                break;
            }
            s.step(DecisionKind.ATTACK, cands, k < declared.size() ? indexOf(cands, declared.get(k)) : 0);
        }
    }

    /** Blockers, replayed like {@link #attackers}: the human's blocks are removed, then added back pair by pair. */
    @Override
    protected void blockers(Player p, Combat combat) {
        final Seat s = seat(p);
        if (s == null) {
            return;
        }
        final List<Card[]> pairs = new ArrayList<>(); // (attacker, blocker) in the combat's order
        for (Card a : combat.getAttackers()) {
            if (attackingMe(combat, a, p) && combat.getBandOfAttacker(a).getAttackers().size() > 1) {
                s.skipped++; // a band: one blocker would be replayed once per attacker; not the seat's surface
                return;
            }
            for (Card b : combat.getBlockers(a)) {
                if (b.getController().equals(p)) {
                    pairs.add(new Card[] {a, b});
                }
            }
        }
        final List<Card> mine = new ArrayList<>(p.getCreaturesInPlay());
        for (Card[] pr : pairs) {
            combat.removeBlockAssignment(pr[0], pr[1]);
        }
        try {
            Card run = null; // a multi-blocker attacker (menace ...) still short of blockers: stage 2 only, no stage 1
            for (int k = 0; k <= pairs.size(); k++) {
                final Card[] pr = k < pairs.size() ? pairs.get(k) : null;
                if (run == null || pr == null || pr[0] != run) {
                    run = null;
                    final List<Candidate> stage1 = new ArrayList<>();
                    stage1.add(PlayerControllerExternal.DONE);
                    for (Card a : combat.getAttackers()) {
                        if (attackingMe(combat, a, p) && PlayerControllerExternal.blockOffered(combat, a, p, mine)) {
                            stage1.add(PlayerControllerExternal.cardCand(a));
                        }
                    }
                    if (stage1.size() == 1) {
                        break;
                    }
                    if (pr == null) {
                        s.step(DecisionKind.BLOCK_ATTACKER, stage1, 0);
                        break;
                    }
                    s.step(DecisionKind.BLOCK_ATTACKER, stage1, indexOf(stage1, pr[0]));
                }
                final List<Candidate> stage2 = new ArrayList<>();
                for (Card b : mine) {
                    if (CombatUtil.canBlock(pr[0], b, combat)) {
                        stage2.add(PlayerControllerExternal.cardCand(b));
                    }
                }
                s.step(DecisionKind.BLOCK_BLOCKER, stage2, indexOf(stage2, pr[1]));
                if (!combat.isBlocking(pr[1], pr[0])) {
                    combat.addBlocker(pr[0], pr[1]);
                }
                run = PlayerControllerExternal.blockersStillNeeded(combat, pr[0], p) > 0 ? pr[0] : null;
            }
        } finally {
            for (Card[] pr : pairs) {
                if (!combat.isBlocking(pr[1], pr[0])) {
                    combat.addBlocker(pr[0], pr[1]);
                }
            }
        }
    }

    private static boolean attackingMe(Combat combat, Card attacker, Player me) {
        final GameEntity d = combat.getDefenderByAttacker(attacker);
        return d instanceof Player pl ? pl.equals(me) : d instanceof Card c && c.getController().equals(me);
    }

    private static int indexOf(List<Candidate> cands, Object ref) {
        for (int i = 0; i < cands.size(); i++) {
            if (cands.get(i).ref() == ref) {
                return i;
            }
        }
        return -1;
    }
}
