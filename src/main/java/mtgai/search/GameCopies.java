package mtgai.search;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;

import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.combat.Combat;
import forge.game.spellability.OptionalCost;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.MagicStack;
import mtgai.controller.LobbyPlayerExternal;
import mtgai.controller.PlayerControllerExternal;
import mtgai.controller.SeatStats;
import mtgai.decision.RandomOracle;
import mtgai.match.GameRecord;
import mtgai.match.Watchdog;

/**
 * Game copies for search, built on forge-ai's {@link GameCopier} (the path Forge's own simulation
 * AI uses), plus the three things GameCopier leaves out that a mid-game decision point needs:
 * <ol>
 * <li><b>controller injection</b>: the copy's seats get rollout controllers from a {@link
 * LobbyPlayerExternal.CopyControllers} factory (a thread-local hook in {@code LobbyPlayerExternal.createIngamePlayer}),
 * so the copy never reaches the live seat's oracle, record or stats;</li>
 * <li><b>priority state</b> ({@link PhaseState}): who has priority, who passed first, whether priority is being given
 * and the per-turn counters are private {@code PhaseHandler} fields GameCopier does not carry (its {@code devModeSet}
 * resets priority to the active player and drops the "give priority" flag, so a copy made inside a priority window
 * would skip that window);</li>
 * <li><b>the stack</b>: {@code GameSimulator.COPY_STACK} is off and copies spells only; here spells, activated and
 * triggered abilities are rebuilt on the copy's cards (targets, X, optional costs, paid lists, triggering objects
 * mapped by id) and pushed without re-running cast triggers ({@link StackCopyException} when an entry cannot be).</li>
 * </ol>
 * A {@link Rewind} moves a copy made inside a combat declaration back to the end of the previous step, so driving it
 * with {@code mainLoopStep} re-asks the declaration from its start.
 */
public final class GameCopies {
    private GameCopies() {
    }

    /** Thrown when a stack entry (or another part of a mid-game state) cannot be rebuilt on the copy. */
    public static final class StackCopyException extends RuntimeException {
        public StackCopyException(String msg) {
            super(msg, null, false, false);
        }
    }

    /** Where a copy resumes (see class doc). */
    public enum Rewind {
        /** Resume inside the same priority window: the next mainLoopStep asks the priority player again. */
        NONE,
        /** Resume at the end of beginning-of-combat, all passed: the next step declares attackers from scratch. */
        TO_DECLARE_ATTACKERS,
        /** Resume at the end of declare-attackers, all passed: the next step declares blockers from scratch. */
        TO_DECLARE_BLOCKERS
    }

    // ------------------------------------------------------------------ phase / priority state

    private static final List<Field> PH_FIELDS = new ArrayList<>();

    static {
        for (Field f : PhaseHandler.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            final Class<?> t = f.getType();
            final boolean player = t == Player.class;
            if (!(t == int.class || t == boolean.class || player)) {
                continue; // game, combat, phase (devModeSet), extra turns/phases (not carried: none in v1 decks), sw
            }
            if (f.getName().equals("turn") || f.getName().equals("playerTurn")) {
                continue; // devModeSet carries these
            }
            f.setAccessible(true);
            PH_FIELDS.add(f);
        }
    }

    /** The private priority/turn bookkeeping of a {@link PhaseHandler}, by field name; players by id. */
    public static final class PhaseState {
        private final Map<String, Object> values = new LinkedHashMap<>();
        final PhaseType phase;
        final int turn;
        final int playerTurnId;

        private PhaseState(PhaseType phase, int turn, int playerTurnId) {
            this.phase = phase;
            this.turn = turn;
            this.playerTurnId = playerTurnId;
        }

        public static PhaseState capture(Game g) {
            final PhaseHandler ph = g.getPhaseHandler();
            final PhaseState s = new PhaseState(ph.getPhase(), ph.getTurn(),
                    ph.getPlayerTurn() == null ? -1 : ph.getPlayerTurn().getId());
            try {
                for (Field f : PH_FIELDS) {
                    final Object v = f.get(ph);
                    s.values.put(f.getName(), v instanceof Player p ? Integer.valueOf(p.getId()) : v);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            return s;
        }

        /** Writes the captured fields into {@code g}'s phase handler (players mapped by id). */
        public void apply(Game g) {
            final PhaseHandler ph = g.getPhaseHandler();
            try {
                for (Field f : PH_FIELDS) {
                    final Object v = values.get(f.getName());
                    if (f.getType() == Player.class) {
                        f.set(ph, v == null ? null : g.getPlayer((Integer) v));
                    } else {
                        f.set(ph, v);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        public Object get(String field) {
            return values.get(field);
        }

        public PhaseType phase() {
            return phase;
        }

        public int turn() {
            return turn;
        }

        @Override
        public String toString() {
            return phase + " t" + turn + " " + values;
        }
    }

    private static Field field(String name) {
        for (Field f : PH_FIELDS) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        throw new IllegalStateException("PhaseHandler has no field " + name);
    }

    /**
     * Puts {@code g} at the end of {@code phase} with every player passed and an empty stack: the next mainLoopStep
     * moves to the following step and runs its turn-based action.
     */
    static void rewindTo(Game g, PhaseType phase, Combat combat) {
        final PhaseHandler ph = g.getPhaseHandler();
        final Player active = ph.getPlayerTurn();
        ph.devModeSet(phase, active, false, ph.getTurn());
        ph.setCombat(combat);
        Player other = null;
        for (Player p : g.getPlayers()) {
            if (p != active) {
                other = p;
            }
        }
        try {
            field("pPlayerPriority").set(ph, other);
            field("pFirstPriority").set(ph, active);
            field("givePriorityToPlayer").set(ph, false);
            field("bRepeatCleanup").set(ph, false);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ copy

    /**
     * Controllers for a copy that is only ever copied from (a search's copy point): random seats, no caps. Control.NONE
     * on purpose: the copy point is never stepped (its controllers are never asked) and a copy of it gets its seats from
     * the sim's own {@link LobbyPlayerExternal.CopyControllers} ({@code GameCopier} carries no controller state over),
     * so the decision groups that matter are the ones the sim gives its deciding seat (the live seat's).
     */
    public static LobbyPlayerExternal.CopyControllers inert() {
        final Watchdog wd = new Watchdog(0, 0, 0);
        return (p, lobby, s) -> new PlayerControllerExternal(p.getGame(), p, lobby, new RandomOracle(), s,
                PlayerControllerExternal.Exposure.COLLAPSED, wd, new GameRecord(null), new SeatStats());
    }

    /**
     * A copy of {@code src} whose seats are driven by {@code controllers}, with {@code ps} (captured on the game the copy
     * stands for: the live game, for a copy of a copy too) and the stack applied, rewound per {@code rewind}.
     */
    public static Game copy(Game src, PhaseState ps, LobbyPlayerExternal.CopyControllers controllers, Rewind rewind) {
        final LobbyPlayerExternal.CopyControllers prev = LobbyPlayerExternal.COPY_CONTROLLERS.get();
        LobbyPlayerExternal.COPY_CONTROLLERS.set(controllers);
        final Game g;
        try {
            g = new GameCopier(src).makeCopy();
        } finally {
            if (prev == null) {
                LobbyPlayerExternal.COPY_CONTROLLERS.remove();
            } else {
                LobbyPlayerExternal.COPY_CONTROLLERS.set(prev);
            }
        }
        copyStack(src, g);
        copyExtras(src, g);
        ps.apply(g);
        switch (rewind) {
            case TO_DECLARE_ATTACKERS -> {
                if (!g.getStack().isEmpty()) {
                    throw new StackCopyException("rewind to declare attackers with a non-empty stack");
                }
                rewindTo(g, PhaseType.COMBAT_BEGIN, new Combat(g.getPhaseHandler().getPlayerTurn()));
            }
            case TO_DECLARE_BLOCKERS -> {
                final Combat c = g.getPhaseHandler().getCombat();
                if (c == null || !g.getStack().isEmpty()) {
                    throw new StackCopyException("rewind to declare blockers without combat or with a stack");
                }
                for (Card b : new CardCollection(c.getAllBlockers())) {
                    for (Card a : new CardCollection(c.getAttackersBlockedBy(b))) {
                        c.removeBlockAssignment(a, b);
                    }
                }
                rewindTo(g, PhaseType.COMBAT_DECLARE_ATTACKERS, c);
            }
            default -> {
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ what GameCopier leaves out

    private static final List<Field> PLAYER_TURN_FIELDS = new ArrayList<>();
    private static final Field PLOTTED;
    private static final Field ATTACKED_THIS_TURN;

    static {
        for (Field f : Player.class.getDeclaredFields()) {
            final String n = f.getName();
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class
                    && (n.endsWith("ThisTurn") || n.endsWith("LastTurn") || n.endsWith("ThisDrawStep")
                    || n.endsWith("StartedThisTurnWith"))) {
                f.setAccessible(true);
                PLAYER_TURN_FIELDS.add(f);
            }
        }
        try {
            PLOTTED = Card.class.getDeclaredField("plotted");
            PLOTTED.setAccessible(true);
            ATTACKED_THIS_TURN = forge.game.card.CardDamageHistory.class.getDeclaredField("attackedThisTurn");
            ATTACKED_THIS_TURN.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Per-turn player counters (cards drawn / discarded / ... this turn: Sneaky Snacker's third draw), the plotted
     * flag of exiled cards (Highway Robbery: cast free from exile), each card's turn-in-zone / turn-in-controller and
     * each battlefield card's attacks / damage dealt this turn (encoder 10's object byte 35, "attacked this turn"
     * conditions), which GameCopier does not carry. The flag is set directly: {@code setPlotted} would fire a
     * BecomesPlotted trigger on the copy.
     */
    static void copyExtras(Game src, Game dst) {
        try {
            for (Player p : src.getPlayers()) {
                final Player q = dst.getPlayer(p.getId());
                for (Field f : PLAYER_TURN_FIELDS) {
                    f.setInt(q, f.getInt(p));
                }
                for (Card c : p.getCardsIn(forge.game.zone.ZoneType.Exile)) {
                    if (c.isPlotted()) {
                        final Card d = dst.findById(c.getId());
                        if (d != null) {
                            PLOTTED.setBoolean(d, true);
                        }
                    }
                }
            }
            // when each card entered its zone / came under its controller: the copy's zone adds stamp the current
            // turn, which makes every card "entered this turn" (a plotted card is then not castable)
            for (Card c : src.getCardsInGame()) {
                final Card d = dst.findById(c.getId());
                if (d != null) {
                    d.setTurnInZone(c.getTurnInZone());
                    d.setTurnInController(c.getTurnInController() == null ? null
                            : dst.getPlayer(c.getTurnInController().getId()));
                }
            }
            for (Card c : src.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                final Card d = dst.findById(c.getId());
                if (d != null) {
                    copyTurnHistory(c, d, dst);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The defenders {@code c} attacked this turn and the damage it dealt this turn, into {@code d}'s own
     * {@code CardDamageHistory} (never the shared object: the copy's combat would write into the live card's). The damage
     * entries are the same immutable pairs; the copy has no damage LKI for them, which Forge's per-turn damage queries
     * treat as "no source / target filter to check".
     */
    @SuppressWarnings("unchecked")
    private static void copyTurnHistory(Card c, Card d, Game dst) throws IllegalAccessException {
        final List<forge.game.GameEntity> from = (List<forge.game.GameEntity>) ATTACKED_THIS_TURN.get(c.getDamageHistory());
        final List<forge.game.GameEntity> to = (List<forge.game.GameEntity>) ATTACKED_THIS_TURN.get(d.getDamageHistory());
        to.clear();
        for (forge.game.GameEntity e : from) {
            final forge.game.GameEntity m = e instanceof Player p ? dst.getPlayer(p.getId())
                    : e instanceof Card k ? dst.findById(k.getId()) : null;
            if (m != null) {
                to.add(m);
            }
        }
        final List<org.apache.commons.lang3.tuple.Pair<Integer, Boolean>> dmg = d.getDamageHistory().getAllDmgInstances();
        dmg.clear();
        dmg.addAll(c.getDamageHistory().getAllDmgInstances());
    }

    // ------------------------------------------------------------------ stack

    private static final Method PUSH;

    static {
        try {
            PUSH = MagicStack.class.getDeclaredMethod("push", SpellAbility.class, SpellAbilityStackInstance.class, int.class);
            PUSH.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Why {@code g}'s stack cannot be rebuilt on a copy (cheap pre-check), or null. */
    public static String stackProblem(Game g) {
        for (SpellAbilityStackInstance si : g.getStack()) {
            final SpellAbility sa = si.getSpellAbility();
            if (sa.isCopied()) {
                return "copied spell/ability on the stack: " + sa;
            }
        }
        return null;
    }

    /**
     * Rebuilds {@code src}'s stack on {@code dst} bottom-up (GameCopier copied the stack zone's cards but not the
     * stack). Pushed with the private {@code MagicStack.push} so cast triggers, storm counts and "becomes target"
     * triggers do not run a second time.
     */
    static void copyStack(Game src, Game dst) {
        if (src.getStack().isEmpty()) {
            return;
        }
        final List<SpellAbilityStackInstance> entries = new ArrayList<>();
        for (SpellAbilityStackInstance si : src.getStack()) {
            entries.add(si);
        }
        Collections.reverse(entries); // iteration is top-first; push bottom-first
        final Map<SpellAbility, SpellAbility> saMap = new IdentityHashMap<>();
        for (SpellAbilityStackInstance si : entries) {
            final SpellAbility sa = si.getSpellAbility();
            final SpellAbility nsa;
            if (sa.isCopied()) {
                throw new StackCopyException("copied spell/ability on the stack: " + sa);
            } else if (sa instanceof WrappedAbility w) {
                nsa = copyTrigger(w, src, dst, saMap);
            } else {
                final Card host = mapCardOrLki(sa.getHostCard(), dst);
                final SpellAbility found = findSA(sa, host);
                if (found == null) {
                    throw new StackCopyException("no matching ability on the copy for " + sa);
                }
                nsa = found;
                nsa.setActivatingPlayer(mapPlayer(sa.getActivatingPlayer(), dst));
                copyChoices(sa, nsa, dst, saMap);
            }
            saMap.put(sa, nsa);
            try {
                PUSH.invoke(dst.getStack(), nsa, null, si.getId());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                throw new StackCopyException("push failed for " + sa + ": " + e.getCause());
            }
        }
    }

    private static SpellAbility copyTrigger(WrappedAbility w, Game src, Game dst, Map<SpellAbility, SpellAbility> saMap) {
        final Trigger t = w.getTrigger();
        final SpellAbility inner = w.getWrappedAbility();
        if (t == null || t.getHostCard() == null) {
            throw new StackCopyException("trigger without a host: " + w);
        }
        final Card host = mapCardOrLki(t.getHostCard(), dst);
        final Trigger nt = findTrigger(t, host);
        final SpellAbility base = nt.ensureAbility();
        if (base == null) {
            throw new StackCopyException("trigger without an ability on the copy: " + t);
        }
        final Player act = mapPlayer(inner.getActivatingPlayer() != null ? inner.getActivatingPlayer()
                : w.getActivatingPlayer(), dst);
        final SpellAbility nsa = base.copy(host, act, false, true);
        nsa.setTrigger(nt);
        nsa.setOptionalTrigger(inner.isOptionalTrigger());
        final Map<AbilityKey, Object> trig = inner.getTriggeringObjects();
        if (trig != null) {
            for (Map.Entry<AbilityKey, Object> e : trig.entrySet()) {
                final Object v = mapObject(e.getValue(), dst, saMap);
                if (v != null) {
                    nsa.setTriggeringObject(e.getKey(), v);
                }
            }
        }
        copyChoices(inner, nsa, dst, saMap);
        final WrappedAbility nw = new WrappedAbility(nt, nsa, w.getDecider() == null ? null : mapPlayer(w.getDecider(), dst));
        nw.setActivatingPlayer(act);
        return nw;
    }

    private static Trigger findTrigger(Trigger t, Card host) {
        Trigger loose = null;
        int looseCount = 0;
        for (Trigger x : host.getTriggers()) {
            if (x.getMode() != t.getMode() || !Objects.equals(x.getParam("Execute"), t.getParam("Execute"))) {
                continue;
            }
            if (Objects.equals(x.toString(), t.toString())) {
                return x;
            }
            loose = x;
            looseCount++;
        }
        if (looseCount == 1) {
            return loose;
        }
        throw new StackCopyException("trigger not found on the copy (" + looseCount + " candidates): " + t);
    }

    /** Targets (whole sub-ability chain, divided allocations), X, optional costs and paid lists. */
    private static void copyChoices(SpellAbility from, SpellAbility to, Game dst, Map<SpellAbility, SpellAbility> saMap) {
        for (SpellAbility s = from, n = to; s != null; s = s.getSubAbility(), n = n == null ? null : n.getSubAbility()) {
            if (n == null) {
                throw new StackCopyException("sub-ability chain differs on the copy: " + from);
            }
            if (s.usesTargeting() && s.getTargets() != null) {
                n.resetTargets();
                for (GameObject o : s.getTargets()) {
                    final Object m = mapObject(o, dst, saMap);
                    if (!(m instanceof GameObject go)) {
                        throw new StackCopyException("unmappable target " + o + " of " + from);
                    }
                    n.getTargets().add(go);
                    if (s.isDividedAsYouChoose() && s.getDividedValue(o) != null) {
                        n.addDividedAllocation(go, s.getDividedValue(o));
                    }
                }
            }
            if (s.getXManaCostPaid() != null) {
                n.setXManaCostPaid(s.getXManaCostPaid());
            }
        }
        for (OptionalCost oc : from.getOptionalCosts()) {
            to.addOptionalCost(oc);
        }
        final TreeBasedTable<String, Boolean, CardCollection> paid = from.getPaidHash();
        if (paid != null && !paid.isEmpty()) {
            final TreeBasedTable<String, Boolean, CardCollection> np = TreeBasedTable.create();
            for (Table.Cell<String, Boolean, CardCollection> c : paid.cellSet()) {
                final CardCollection cc = new CardCollection();
                for (Card x : c.getValue()) {
                    final Card m = dst.findById(x.getId());
                    if (m != null) {
                        cc.add(m);
                    }
                }
                np.put(c.getRowKey(), c.getColumnKey(), cc);
            }
            to.setPaidHash(np);
        }
    }

    private static Object mapObject(Object o, Game dst, Map<SpellAbility, SpellAbility> saMap) {
        if (o instanceof Card c) {
            return dst.findById(c.getId());
        }
        if (o instanceof Player p) {
            return mapPlayer(p, dst);
        }
        if (o instanceof SpellAbility sa) {
            return saMap.get(sa);
        }
        if (o instanceof CardCollection cc) {
            final CardCollection out = new CardCollection();
            for (Card c : cc) {
                final Card m = dst.findById(c.getId());
                if (m != null) {
                    out.add(m);
                }
            }
            return out;
        }
        if (o instanceof Number || o instanceof String || o instanceof Boolean || o instanceof Enum<?>) {
            return o;
        }
        return null; // other triggering objects (cost payments, LKI tables, ...) are dropped
    }

    private static Player mapPlayer(Player p, Game dst) {
        final Player m = p == null ? null : dst.getPlayer(p.getId());
        if (p != null && m == null) {
            throw new StackCopyException("unmappable player " + p);
        }
        return m;
    }

    /** The copy's card with {@code c}'s id; for a host that left the game (a sacrificed token), an LKI copy. */
    private static Card mapCardOrLki(Card c, Game dst) {
        final Card m = dst.findById(c.getId());
        if (m != null) {
            return m;
        }
        final Card lki = new CardCopyService(c, dst).copyCard(false, mapPlayer(c.getOwner(), dst));
        lki.dangerouslySetGame(dst);
        return lki;
    }

    private static SpellAbility findSA(SpellAbility sa, Card c) {
        final String d = sa.getDescription();
        for (SpellAbility x : c.getAllSpellAbilities()) {
            if (d.equals(x.getDescription())) {
                return x;
            }
        }
        final Map<String, String> p = sa.getOriginalMapParams();
        for (SpellAbility x : c.getAllSpellAbilities()) {
            if (p.equals(x.getOriginalMapParams())) {
                return x;
            }
        }
        return null;
    }
}
