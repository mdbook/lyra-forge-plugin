package mtgai.encode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.collect.Multiset;
import com.google.common.eventbus.Subscribe;

import forge.card.ColorSet;
import forge.card.CardTypeView;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEvent;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import mtgai.controller.LegalActions;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionKind;

/**
 * Observation encoder ({@code encoder_version} 10; 2 = 1 + decision kinds 19-29 and the
 * alternative-cost bit for static "rather than pay" costs such as Fireblast; 3 = 2 + HISTORY tokens + position-tagged
 * OWN_LIBRARY tokens; 4 = 3 + the opponent-archetype id in global byte 27 from the OPP_SEEN tokens,
 * {@link ArchetypeClassifier}; 5 = 4 + usable mana sources (global 30-31, object byte 23, {@link ManaView}) and
 * candidate bytes 7-11: effect api, cost kinds, "unless" cost kinds, choice position, madness payability;
 * 6 = 5 + active effects in object bytes 24-32: damage prevention per battlefield creature, Forge Effect cards' kind,
 * duration, ability modes and source card, chosen colours / choices on any object;
 * 7 = 6 + zone casting: object bytes 33-34 "castable from this graveyard / exile"
 * and its mode, candidate byte 12 the casting mode, {@link ZoneCast}; 8 = 7 +
 * decision kinds 30-33 (COMBAT_DAMAGE, TRIGGER_ORDER, MANA_SOURCE, PLAY_DRAW, each behind a {@code Control} flag) and
 * candidate bytes 13-18 for the damage / mana picks; 9 = 8 with card ids from
 * the all-cards registry ({@link CardVocab}: every Forge card and token script, ids >= 1024 outside the v1 vocab; no new
 * byte, a model that knows only the v1 ids reads them as UNK); 10 =
 * 9 + combat history in object byte 35: attacked / dealt combat damage this turn, {@link #combatHistory}).
 * Lists sorted "by card_id" (OWN_LIBRARY, OPP_SEEN, ORACLE) sort by min(id, UNK), then name, so they keep the v1
 * vocab's order under any id space.
 * One instance per seat per game: it remembers the opponent cards this seat has seen,
 * the public actions of both players (subscribed to the game's event bus) and which of its own library cards it
 * knows the position of. Hidden information ({@link HiddenInfo}) is applied before anything is written: the
 * opponent's hand and library never produce an actor token.
 *
 * <p>Every observation also carries the ORACLE section (schema 2): the opponent's hand, both libraries' top
 * cards and the opponent's library contents, for the oracle critic only (never an actor input).
 */
public final class ObservationEncoder {
    public static final int ENCODER_VERSION = 10;
    /**
     * The oldest model encoder this encoder's observations are exact input for. Encoder 10 writes a new byte (object
     * byte 35) that an encoder-9 model reads through first-layer columns that never trained (the byte was
     * always 0), so only encoder-10 models run here and workers and learner switch together, as for encoders 6-8 (the
     * encoder-10 learner still trains on encoder-9 / 8 chunks already in flight). Encoder 9 had 8 here: it wrote no new
     * byte, and an encoder-8 model clamps every registry id >= 1024 to its UNK row.
     */
    public static final int OLDEST_MODEL = 10;

    /** Whether a model of encoder {@code model} may run on observations of encoder {@code enc}. */
    public static boolean runs(int model, int enc) {
        return model == enc || enc == ENCODER_VERSION && model >= OLDEST_MODEL && model < enc;
    }
    public static final int MAX_OBJ = 192;
    public static final int MAX_LIB = 28;
    public static final int MAX_OPP = 32;
    public static final int MAX_HIST = 32;
    /**
     * HISTORY tokens RECORDED per observation (system property {@code mtgai.histRecord}, clamped to
     * [MAX_HIST, 255]; default MAX_HIST = unchanged). Offline experiments only: the
     * acting policy still sees the last MAX_HIST ({@link Observation#actingHist}) and global byte 29 bit 8 still means
     * "more than MAX_HIST actions", so play is identical; only the trajectory keeps the longer tail.
     */
    public static final int HIST_RECORD = Math.min(255, Math.max(MAX_HIST, Integer.getInteger("mtgai.histRecord", MAX_HIST)));
    public static final int MAX_ORC = 64;
    /** Position-tagged OWN_LIBRARY tokens per end (top / bottom). */
    static final int MAX_TAGGED = 8;
    /** Library top cards per player in the ORACLE section. */
    static final int ORC_TOP = 3;
    public static final int NONE = 255;

    static final int Z_BATTLEFIELD = 1;
    static final int Z_HAND = 2;
    static final int Z_GRAVEYARD = 3;
    static final int Z_EXILE = 4;
    static final int Z_STACK = 5;
    static final int Z_LIBRARY = 6;
    static final int Z_COMMAND = 7;
    static final int Z_SEEN = 8;

    /** Object bytes 8-10, in bit order. */
    private static final Keyword[] KEYWORDS = {
        Keyword.FLYING, Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE, Keyword.TRAMPLE, Keyword.DEATHTOUCH,
        Keyword.LIFELINK, Keyword.VIGILANCE, Keyword.HASTE, Keyword.REACH, Keyword.MENACE, Keyword.HEXPROOF,
        Keyword.INDESTRUCTIBLE, Keyword.DEFENDER, Keyword.FLASH, Keyword.SHROUD, Keyword.PROWESS,
        Keyword.FLASHBACK, Keyword.MADNESS, Keyword.CYCLING, Keyword.AFFINITY, Keyword.NINJUTSU,
        Keyword.CONVOKE, Keyword.DELVE, Keyword.KICKER};

    private final CardVocab vocab;
    private final Player self;
    /** Opponent cards seen this game: name -> (Forge ids seen, a representative card). */
    private final Map<String, Seen> seen = new TreeMap<>();
    private int mulliganDecisions;
    /** HISTORY: the last MAX_HIST public actions as 12-byte tokens, most recent last; total count. */
    private final ArrayDeque<byte[]> history = new ArrayDeque<>();
    private int actions;
    /** Actions whose result is still open: SpellAbility id -> its token. */
    private final Map<Integer, byte[]> pending = new HashMap<>();
    /** Forge ids of own library cards whose position this seat chose or saw (cleared by a shuffle). */
    private final Set<Integer> knownLib = new HashSet<>();
    /** HISTORY tokens kept and written per observation (>= MAX_HIST); see {@link #HIST_RECORD}. */
    private final int histRecord;
    /** Seen-card representatives {@link #adoptState} could not find on the copy (the source's card object is kept). */
    private int unmappedSeen;
    /**
     * Spells cast this turn before this encoder's game was copied: GameCopier does not carry the stack's per-turn cast
     * list, so glob[19] would restart from 0 on a copy. Valid for turn {@code castCarryTurn} only.
     */
    private int castCarry;
    private int castCarryTurn = -1;

    private static final class Seen {
        final Set<Integer> ids = new LinkedHashSet<>();
        Card card;
    }

    public ObservationEncoder(CardVocab vocab, Player self) {
        this(vocab, self, HIST_RECORD);
    }

    /** {@code histRecord}: HISTORY tokens to record, clamped to [MAX_HIST, 255] (tests; production uses the property). */
    public ObservationEncoder(CardVocab vocab, Player self, int histRecord) {
        this.vocab = vocab;
        this.self = self;
        this.histRecord = Math.min(255, Math.max(MAX_HIST, histRecord));
        self.getGame().subscribeToEvents(new Object() {
            @Subscribe
            public void on(GameEvent ev) {
                onEvent(ev);
            }
        });
    }

    /**
     * Search support: an encoder for {@code copySeat}, this seat's player in a
     * game copy, that starts from this encoder's per-game memory (HISTORY, opponent cards seen, known own-library
     * cards, mulligan count) instead of empty, so the copy's first observation equals the live one. See
     * {@link #adoptState}.
     */
    public ObservationEncoder copyFor(Player copySeat) {
        final ObservationEncoder e = new ObservationEncoder(vocab, copySeat, histRecord);
        e.adoptState(this);
        return e;
    }

    /** Sort key of a name in the lists sorted by card_id: min(id, UNK), the v1 vocab's order under any id space. */
    private int sortId(String name) {
        return Math.min(vocab.id(name), CardVocab.UNK);
    }

    public int unmappedSeen() {
        return unmappedSeen;
    }

    /**
     * Replaces this encoder's per-game memory with a deep copy of {@code from}'s (an encoder of the same seat on the game
     * this one's game was copied from: the live game, or a copy of it). Everything keyed by an object is re-keyed to this
     * encoder's game: card ids are preserved by {@code GameCopier}, so the id-keyed state ({@code knownLib}, the ids per
     * seen name) transfers as is; the seen cards' representative {@link Card} is looked up by id on this game (kept as
     * the source's object only when the copy has no card of that id and name, e.g. a card the determinisation dropped,
     * counted in {@link #unmappedSeen}); {@code pending} (SpellAbility id -> open history token) is re-keyed through the
     * stack instance ids, which the copy preserves while SpellAbility ids are fresh on every copy; the history tokens
     * are cloned, keeping the aliasing between {@code history} and {@code pending}. Mutable state is never shared with
     * {@code from}: a rollout on the copy cannot change the live encoder. Call it after the copy is built (events fired
     * while it was built are discarded) and before the first {@link #encode}.
     */
    public void adoptState(ObservationEncoder from) {
        final Game dst = self.getGame();
        mulliganDecisions = from.mulliganDecisions;
        actions = from.actions;
        final Map<byte[], byte[]> tokens = new IdentityHashMap<>();
        history.clear();
        for (byte[] t : from.history) {
            final byte[] c = t.clone();
            tokens.put(t, c);
            history.addLast(c);
        }
        pending.clear();
        if (!from.pending.isEmpty()) {
            final Map<Integer, Integer> instanceOfSa = new HashMap<>();
            for (SpellAbilityStackInstance si : from.self.getGame().getStack()) {
                instanceOfSa.put(si.getSpellAbility().getId(), si.getId());
            }
            final Map<Integer, Integer> saOfInstance = new HashMap<>();
            for (SpellAbilityStackInstance si : dst.getStack()) {
                saOfInstance.put(si.getId(), si.getSpellAbility().getId());
            }
            for (Map.Entry<Integer, byte[]> e : from.pending.entrySet()) {
                final Integer inst = instanceOfSa.get(e.getKey());
                final Integer sa = inst == null ? null : saOfInstance.get(inst);
                if (sa != null) { // else a stale entry: its spell is not on the stack, nothing can resolve it
                    pending.put(sa, tokens.computeIfAbsent(e.getValue(), byte[]::clone));
                }
            }
        }
        knownLib.clear();
        knownLib.addAll(from.knownLib);
        castCarry = from.spellsCastThisTurn() - dst.getStack().getSpellsCastThisTurn().size();
        castCarryTurn = dst.getPhaseHandler().getTurn();
        seen.clear();
        unmappedSeen = 0;
        for (Map.Entry<String, Seen> e : from.seen.entrySet()) {
            final Seen s = new Seen();
            s.ids.addAll(e.getValue().ids);
            final Card old = e.getValue().card;
            Card c = old == null ? null : dst.findById(old.getId());
            if (old != null && (c == null || !cardName(c).equals(e.getKey()))) {
                c = old;
                unmappedSeen++;
            }
            s.card = c;
            seen.put(e.getKey(), s);
        }
    }

    /** Spells cast this turn on this encoder's game, plus those cast before it was copied (this turn only). */
    private int spellsCastThisTurn() {
        final Game game = self.getGame();
        return game.getStack().getSpellsCastThisTurn().size()
                + (game.getPhaseHandler().getTurn() == castCarryTurn ? castCarry : 0);
    }

    /** Forge ids of the own library cards whose position this seat knows (a search must keep them in place). */
    public Set<Integer> knownLibrary() {
        return new HashSet<>(knownLib);
    }

    /**
     * A keep / mulligan prompt answered for the seat without asking it (Handicap.MULL6): counted like an asked one, so
     * glob[26] reads the same as after a mulligan the seat chose itself.
     */
    public void skipMulliganDecision() {
        mulliganDecisions++;
    }

    /** The seat chose or saw the library position of these own cards (scry, surveil, ordered put-back). */
    public void knowLibrary(Iterable<Card> cards) {
        for (Card c : cards) {
            knownLib.add(c.getId());
        }
    }

    // ------------------------------------------------------------------ HISTORY (public actions, both seats)

    private void onEvent(GameEvent ev) {
        final Game game = self.getGame();
        if (ev instanceof GameEventSpellAbilityCast e && e.si() != null) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getId() != e.si().getId()) {
                    continue;
                }
                final SpellAbility sa = si.getSpellAbility();
                if (sa.isTrigger() || !(sa.isSpell() || sa.isActivatedAbility())) {
                    return; // triggers and static abilities are not actions
                }
                final Candidate.Type t = sa.isSpell() ? Candidate.Type.CAST : Candidate.Type.ACTIVATE;
                final byte[] tok = action(t, si.getActivatingPlayer(), si.getSourceCard());
                final Card tc = sa.getTargetCard();
                if (tc != null && HiddenInfo.canSee(tc, self) && HiddenInfo.canSeeFace(tc, self)) {
                    final int id = vocab.id(tc);
                    tok[6] = (byte) id;
                    tok[7] = (byte) (id >>> 8);
                }
                final Player tp = sa.getTargets() == null ? null : sa.getTargets().getFirstTargetedPlayer();
                tok[8] = (byte) (tp == null ? 0 : tp == self ? 1 : 2);
                pending.put(sa.getId(), tok);
                return;
            }
        } else if (ev instanceof GameEventLandPlayed e) {
            final Card c = game.findById(e.land().getId());
            if (c != null) {
                action(Candidate.Type.PLAY_LAND, c.getController(), c)[9] = 1;
            }
        } else if (ev instanceof GameEventSpellResolved e && e.spell() != null) {
            final byte[] tok = pending.remove(e.spell().getId());
            if (tok != null) {
                tok[9] = (byte) (e.hasFizzled() ? 3 : 1);
            }
        } else if (ev instanceof GameEventSpellRemovedFromStack e && e.sa() != null) {
            final byte[] tok = pending.remove(e.sa().getId());
            if (tok != null) {
                tok[9] = 2; // left the stack without resolving: countered (or exiled from the stack)
            }
        } else if (ev instanceof GameEventShuffle e && e.player().getId() == self.getId()) {
            knownLib.clear();
        }
    }

    private byte[] action(Candidate.Type type, Player actor, Card card) {
        final Game game = self.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final byte[] tok = new byte[Observation.HIST];
        tok[0] = u8(ph.getTurn());
        tok[1] = u8(ph.getPhase() == null ? 0 : ph.getPhase().ordinal());
        tok[2] = (byte) (actor == self ? 0 : 1);
        tok[3] = (byte) (type.ordinal() + 1);
        if (card != null && HiddenInfo.canSeeFace(card, self)) {
            final int id = vocab.id(card);
            tok[4] = (byte) id;
            tok[5] = (byte) (id >>> 8);
        }
        history.addLast(tok);
        actions++;
        if (history.size() > histRecord) {
            history.removeFirst();
        }
        return tok;
    }

    public Observation encode(DecisionKind kind, List<Candidate> cands) {
        final Game game = self.getGame();
        final Player opp = self.getOpponents().getFirst();
        final byte[] glob = new byte[Observation.GLOBAL];

        // ---- OBJECTS: stack, battlefield self/opp, hand, graveyard self/opp, exile, command
        final List<Object> order = new ArrayList<>();   // Card or SpellAbilityStackInstance
        for (SpellAbilityStackInstance si : game.getStack()) {
            order.add(si);
        }
        addCards(order, self.getCardsIn(ZoneType.Battlefield));
        addCards(order, opp.getCardsIn(ZoneType.Battlefield));
        addCards(order, self.getCardsIn(ZoneType.Hand));
        addCards(order, self.getCardsIn(ZoneType.Graveyard));
        addCards(order, opp.getCardsIn(ZoneType.Graveyard));
        final List<Card> exile = new ArrayList<>(self.getCardsIn(ZoneType.Exile));
        exile.addAll(opp.getCardsIn(ZoneType.Exile));
        addCards(order, exile);
        final List<Card> command = new ArrayList<>(self.getCardsIn(ZoneType.Command));
        command.addAll(opp.getCardsIn(ZoneType.Command));
        addCards(order, command);
        if (order.size() > MAX_OBJ) {
            order.subList(MAX_OBJ, order.size()).clear();
            glob[29] |= 1;
        }
        final Map<Object, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            final Object o = order.get(i);
            if (o instanceof SpellAbilityStackInstance si) {
                index.put(si.getSpellAbility(), i);
                index.putIfAbsent(si.getSourceCard(), i);
            } else {
                index.put(o, i);
            }
        }
        final Combat combat = game.getCombat();
        final ManaView mana = ManaView.of(self);
        // encoder 6: one scan per decision; a fog is a damage-prevention replacement too, so it needs one to exist
        boolean anyPrevention;
        Boolean fog;
        try {
            anyPrevention = anyPreventionEffect(game);
            fog = anyPrevention && game.getReplacementHandler().isPreventCombatDamageThisTurn();
        } catch (RuntimeException e) {   // encoder 6 features must never kill a game: degrade to 0
            effectError(e);
            anyPrevention = false;
            fog = Boolean.FALSE;
        }
        final byte[] obj = new byte[order.size() * Observation.OBJ];
        int depth = 0;
        for (int i = 0; i < order.size(); i++) {
            final int off = i * Observation.OBJ;
            if (order.get(i) instanceof SpellAbilityStackInstance si) {
                final Card c = si.getSourceCard();
                writeCard(obj, off, c, Z_STACK, si.getActivatingPlayer(), false);
                obj[off + 22] = (byte) Math.min(depth++, 255);
            } else {
                final Card c = (Card) order.get(i);
                writeCard(obj, off, c, zoneCode(c), c.getController(), false);
                if (combat != null && c.isInPlay()) {
                    final List<Card> other = combat.isAttacking(c) ? combat.getBlockers(c)
                            : combat.isBlocking(c) ? combat.getAttackersBlockedBy(c) : List.of();
                    obj[off + 19] = (byte) (other.isEmpty() ? NONE : index.getOrDefault(other.get(0), NONE));
                }
                final GameEntity att = c.getEntityAttachedTo();
                if (att != null) {
                    obj[off + 20] = (byte) (int) index.getOrDefault(att, NONE);
                }
                obj[off + 23] = (byte) mana.mask(c); // encoder 5: colours it can make now (own usable sources only)
                if (anyPrevention) {
                    try {
                        obj[off + 24] = (byte) damagePrevented(c, fog); // encoder 6
                    } catch (RuntimeException e) {
                        effectError(e);
                        obj[off + 24] = 0;
                    }
                }
                if (HiddenInfo.canSeeFace(c, self)) {
                    try {
                        final int zc = ZoneCast.object(c, self); // encoder 7: castable from this graveyard / exile
                        obj[off + 33] = (byte) zc;
                        obj[off + 34] = (byte) (zc >>> 8);
                    } catch (RuntimeException e) {
                        effectError(e);
                        obj[off + 33] = 0;
                        obj[off + 34] = 0;
                    }
                }
            }
        }

        // ---- OPP_SEEN: update from what is visible now, then emit (by card_id, name)
        for (Object o : order) {
            final Card c = o instanceof SpellAbilityStackInstance si ? si.getSourceCard() : (Card) o;
            if (c.getOwner() == opp && !c.isToken() && HiddenInfo.canSeeFace(c, self)) {
                final Seen s = seen.computeIfAbsent(cardName(c), k -> new Seen());
                s.ids.add(c.getId());
                s.card = c;
            }
        }
        final List<Map.Entry<String, Seen>> seenList = new ArrayList<>(seen.entrySet());
        seenList.sort(Comparator.comparingInt((Map.Entry<String, Seen> e) -> sortId(e.getKey())).thenComparing(Map.Entry::getKey));
        if (seenList.size() > MAX_OPP) {
            seenList.subList(MAX_OPP, seenList.size()).clear();
            glob[29] |= 4;
        }
        final byte[] oppSeen = new byte[seenList.size() * Observation.OBJ];
        for (int i = 0; i < seenList.size(); i++) {
            final Seen s = seenList.get(i).getValue();
            writeStatic(oppSeen, i * Observation.OBJ, s.card, Z_SEEN, 1, 1, s.ids.size());
        }

        // ---- OWN_LIBRARY: position-tagged tokens for known top / bottom cards, then one token per distinct name
        final List<Card> library = new ArrayList<>(self.getCardsIn(ZoneType.Library));   // index 0 = top
        final List<Card> tagCards = new ArrayList<>();
        final List<Integer> tagPos = new ArrayList<>();
        int top = 0;
        while (top < library.size() && top < MAX_TAGGED && knownLib.contains(library.get(top).getId())) {
            tagCards.add(library.get(top));
            tagPos.add(1 + top++);
        }
        for (int d = 0; d < MAX_TAGGED && library.size() - 1 - d >= top; d++) {
            final Card c = library.get(library.size() - 1 - d);
            if (!knownLib.contains(c.getId())) {
                break;
            }
            tagCards.add(c);
            tagPos.add(255 - d);
        }
        final Map<String, List<Card>> libGroups = new TreeMap<>();
        for (Card c : library) {
            libGroups.computeIfAbsent(cardName(c), k -> new ArrayList<>()).add(c);
        }
        final List<Map.Entry<String, List<Card>>> libList = new ArrayList<>(libGroups.entrySet());
        libList.sort(Comparator.comparingInt((Map.Entry<String, List<Card>> e) -> sortId(e.getKey())).thenComparing(Map.Entry::getKey));
        final int nLib = Math.min(MAX_LIB, tagCards.size() + libList.size());
        if (tagCards.size() + libList.size() > MAX_LIB) {
            glob[29] |= 2;
        }
        final byte[] lib = new byte[nLib * Observation.OBJ];
        for (int i = 0; i < nLib; i++) {
            if (i < tagCards.size()) {
                writeStatic(lib, i * Observation.OBJ, tagCards.get(i), Z_LIBRARY, 0, 0, 1);
                lib[i * Observation.OBJ + 22] = (byte) (int) tagPos.get(i);
            } else {
                final List<Card> g = libList.get(i - tagCards.size()).getValue();
                writeStatic(lib, i * Observation.OBJ, g.get(0), Z_LIBRARY, 0, 0, g.size());
            }
        }

        // ---- HISTORY
        final byte[] hist = new byte[history.size() * Observation.HIST];
        int h = 0;
        for (byte[] tok : history) {
            System.arraycopy(tok, 0, hist, h, Observation.HIST);
            h += Observation.HIST;
        }
        if (actions > MAX_HIST) {
            glob[29] |= 8;
        }

        // ---- CANDIDATES
        final byte[] cand = new byte[cands.size() * Observation.CAND];
        for (int i = 0; i < cands.size(); i++) {
            writeCandidate(cand, i * Observation.CAND, cands.get(i), i, index, kind, mana);
        }

        // ---- global
        final PhaseHandler ph = game.getPhaseHandler();
        glob[0] = u8(self.getLife());
        glob[1] = u8(opp.getLife());
        glob[2] = u8(self.getPoisonCounters());
        glob[3] = u8(opp.getPoisonCounters());
        final ZoneType[] zs = {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Battlefield};
        for (int z = 0; z < zs.length; z++) {
            glob[4 + 2 * z] = u8(self.getCardsIn(zs[z]).size());
            glob[5 + 2 * z] = u8(opp.getCardsIn(zs[z]).size());
        }
        glob[14] = u8(ph.getTurn());
        glob[15] = u8(ph.getPhase() == null ? 0 : ph.getPhase().ordinal());
        glob[16] = (byte) (ph.isPlayerTurn(self) ? 1 : 0);
        glob[17] = u8(game.getStack().size());
        glob[18] = u8(self.getLandsPlayedThisTurn());
        glob[19] = u8(spellsCastThisTurn());
        for (int c = 0; c < 6; c++) {
            glob[20 + c] = u8(self.getManaPool().getAmountOfColor(MagicColor.WUBRGC[c]));
        }
        if (kind == DecisionKind.MULLIGAN) {
            glob[26] = u8(mulliganDecisions++);
        } else {
            glob[26] = u8(Math.max(0, mulliganDecisions - 1));
        }
        // opponent archetype id from the OPP_SEEN tokens as written (public information only), 0 = nothing seen
        glob[27] = (byte) (vocab.archetypes() == null ? 0 : vocab.archetypes().predict(oppSeen));
        glob[28] = (byte) kind.ordinal();
        // encoder 5: untapped usable mana sources (one per permanent), and those that can make red
        glob[30] = u8(mana.count());
        glob[31] = u8(mana.count(MagicColor.RED));

        // ---- ORACLE (critic only): opponent hand, top cards of both libraries, opponent library contents
        final List<byte[]> orc = new ArrayList<>();
        for (Card c : sorted(opp.getCardsIn(ZoneType.Hand))) {
            final byte[] t = new byte[Observation.OBJ];
            writeCard(t, 0, c, Z_HAND, opp, true);
            orc.add(t);
        }
        for (Player p : List.of(self, opp)) {
            final List<Card> l = new ArrayList<>(p.getCardsIn(ZoneType.Library));
            for (int d = 0; d < Math.min(ORC_TOP, l.size()); d++) {
                final byte[] t = new byte[Observation.OBJ];
                writeStatic(t, 0, l.get(d), Z_LIBRARY, p == self ? 0 : 1, p == self ? 0 : 1, 1);
                t[22] = (byte) (1 + d);
                orc.add(t);
            }
        }
        final Map<String, List<Card>> oppLib = new TreeMap<>();
        for (Card c : opp.getCardsIn(ZoneType.Library)) {
            oppLib.computeIfAbsent(cardName(c), k -> new ArrayList<>()).add(c);
        }
        final List<Map.Entry<String, List<Card>>> oppList = new ArrayList<>(oppLib.entrySet());
        oppList.sort(Comparator.comparingInt((Map.Entry<String, List<Card>> e) -> sortId(e.getKey())).thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, List<Card>> e : oppList) {
            final byte[] t = new byte[Observation.OBJ];
            writeStatic(t, 0, e.getValue().get(0), Z_LIBRARY, 1, 1, e.getValue().size());
            orc.add(t);
        }
        final int orcFlags = orc.size() > MAX_ORC ? 1 : 0;
        final byte[] orcBytes = new byte[Math.min(MAX_ORC, orc.size()) * Observation.OBJ];
        for (int i = 0; i < orcBytes.length / Observation.OBJ; i++) {
            System.arraycopy(orc.get(i), 0, orcBytes, i * Observation.OBJ, Observation.OBJ);
        }
        return new Observation(kind.ordinal(), obj, lib, oppSeen, hist, cand, glob, orcBytes, orcFlags);
    }

    // ------------------------------------------------------------------ helpers

    private void addCards(List<Object> order, Iterable<Card> cards) {
        for (Card c : sorted(cards)) {
            if (HiddenInfo.canSee(c, self)) {
                order.add(c);
            }
        }
    }

    private static List<Card> sorted(Iterable<Card> cards) {
        final List<Card> l = new ArrayList<>();
        cards.forEach(l::add);
        l.sort(Comparator.comparingInt(Card::getId));
        return l;
    }

    private static String cardName(Card c) {
        final forge.item.IPaperCard pc = c.getPaperCard();
        return pc != null && !c.isToken() ? pc.getName() : c.getName();
    }

    private static int zoneCode(Card c) {
        if (c.getZone() == null) {
            return Z_EXILE;
        }
        return switch (c.getZone().getZoneType()) {
            case Battlefield -> Z_BATTLEFIELD;
            case Hand -> Z_HAND;
            case Graveyard -> Z_GRAVEYARD;
            case Stack -> Z_STACK;
            case Library -> Z_LIBRARY;
            case Command -> Z_COMMAND;
            default -> Z_EXILE;
        };
    }

    private static byte u8(int v) {
        return (byte) Math.max(0, Math.min(255, v));
    }

    /** {@code reveal}: write the face even if the seat cannot see it (ORACLE section only). */
    private void writeCard(byte[] b, int off, Card c, int zone, Player controller, boolean reveal) {
        final boolean face = reveal || HiddenInfo.canSeeFace(c, self);
        writeStatic(b, off, c, zone, controller == self ? 0 : 1, c.getOwner() == self ? 0 : 1, 1);
        if (!face) {
            b[off] = 0;
            b[off + 1] = 0;
        }
        int st = 0;
        if (c.isInPlay()) {
            final Combat combat = c.getGame().getCombat();
            st |= c.isTapped() ? 1 : 0;
            st |= c.isCreature() && c.isSick() ? 2 : 0;
            st |= combat != null && combat.isAttacking(c) ? 8 : 0;
            st |= combat != null && combat.isBlocking(c) ? 16 : 0;
            if (c.isCreature()) {
                st |= controller == self && CombatUtil.canAttack(c) ? 32 : 0;
                st |= CombatUtil.canBlock(c) ? 64 : 0;
            }
        }
        st |= c.isToken() ? 4 : 0;
        st |= c.isFaceDown() ? 128 : 0;
        b[off + 5] = (byte) st;
        if (c.isInPlay()) {
            b[off + 11] = u8(c.isCreature() ? c.getNetPower() : 0);
            b[off + 12] = u8(c.isCreature() ? c.getNetToughness() : 0);
            b[off + 13] = u8(c.getDamage());
        }
        int p1 = 0;
        int m1 = 0;
        int other = 0;
        for (Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
            if (e.getElement().is(CounterEnumType.P1P1)) {
                p1 += e.getCount();
            } else if (e.getElement().is(CounterEnumType.M1M1)) {
                m1 += e.getCount();
            } else {
                other += e.getCount();
            }
        }
        b[off + 14] = u8(p1);
        b[off + 15] = u8(m1);
        b[off + 16] = u8(other);
        b[off + 18] = u8(c.getTurnInZone());
        b[off + 19] = (byte) NONE;
        b[off + 20] = (byte) NONE;
        if (face) {
            try {
                writeEffect(b, off, c);
            } catch (RuntimeException e) {   // never kill a game over an exotic effect: leave bytes 25-32 at 0
                effectError(e);
                java.util.Arrays.fill(b, off + 25, off + 33, (byte) 0);
            }
        }
        if (zone == Z_BATTLEFIELD && c.isCreature()) {
            try {
                b[off + 35] = (byte) combatHistory(c); // encoder 10
            } catch (RuntimeException e) {
                effectError(e);
                b[off + 35] = 0;
            }
        }
    }

    /** Object byte 35 bits (encoder 10). */
    public static final int HIST_ATTACKED = 1;
    public static final int HIST_COMBAT_DAMAGE = 2;

    /**
     * Object byte 35 of a creature on the battlefield (encoder 10): bit 0 it attacked this turn,
     * bit 1 it dealt combat damage this turn (to anything). Both read the card's {@code CardDamageHistory} lists that
     * Forge clears in every cleanup step ({@code newTurn}), so they survive the end of combat, where the attacking bit
     * of byte 5 is gone, and are 0 again on the next turn. Not {@code getHasdealtCombatDamagetoAny}: its set is never
     * cleared (once per game). Combat damage is registered on the source's LKI copy, which shares the live card's
     * history object, so the live card sees it.
     */
    static int combatHistory(Card c) {
        final forge.game.card.CardDamageHistory h = c.getDamageHistory();
        int v = h.getCreatureAttacksThisTurn() > 0 ? HIST_ATTACKED : 0;
        v |= h.getDamageDoneThisTurn(true, true, null, null, c, c.getController(), null) > 0 ? HIST_COMBAT_DAMAGE : 0;
        return v;
    }

    /** Encoder-6 / 7 / 10 feature failures (degraded to 0), process-wide; the first few are logged. */
    public static final java.util.concurrent.atomic.AtomicLong EFFECT_ERRORS = new java.util.concurrent.atomic.AtomicLong();

    private static void effectError(RuntimeException e) {
        if (EFFECT_ERRORS.incrementAndGet() <= 5) {
            System.err.println("encoder 6/7/10 effect, zone-cast or combat-history feature failed (written as 0): " + e);
        }
    }

    // ------------------------------------------------------------------ encoder 6: active effects

    /** Effect flag bits (object byte 25). */
    static final int EFF_EFFECT = 1;
    static final int EFF_EMBLEM = 2;
    static final int EFF_REPLACEMENT = 4;
    static final int EFF_STATIC = 8;
    static final int EFF_TRIGGER = 16;
    static final int EFF_PREVENT_DAMAGE = 32;
    static final int EFF_READS_CHOICE = 64;
    static final int EFF_COMBAT_ONLY = 128;

    /** Duration codes (object byte 26). */
    static final int DUR_THIS_TURN = 1;
    static final int DUR_PERMANENT = 2;
    static final int DUR_THIS_COMBAT = 3;
    static final int DUR_LATER_TURN = 4;
    static final int DUR_OBJECT = 5;
    static final int DUR_OTHER = 6;

    /**
     * Object bytes 27 (any object: chosen colours W1 U2 B4 R8 G16, 32 a chosen type, 64 a chosen or named card, 128 a
     * chosen player) and, for a Forge Effect card (command zone, {@link Card#isImmutable}), 25 flags, 26 duration, 28
     * first replacement event ({@code ReplacementType.ordinal() + 1}), 29 first static mode (lowest
     * {@code StaticAbilityMode.ordinal() + 1}), 30 first trigger mode ({@code TriggerType.ordinal() + 1}), 31-32 the card
     * that created it (u16 card id; 0 none / face not visible). The effect token's own card id (bytes 0-1) is unchanged
     * (its name is "X's Effect": UNK), so a model that never saw these bytes reads the token exactly as before.
     */
    private void writeEffect(byte[] b, int off, Card c) {
        int ch = 0;
        for (String s : c.getChosenColors()) {
            ch |= MagicColor.fromName(s) & 31;
        }
        ch |= c.hasChosenType() ? 32 : 0;
        ch |= c.hasChosenCard() || c.hasNamedCard() ? 64 : 0;
        ch |= c.hasChosenPlayer() ? 128 : 0;
        b[off + 27] = (byte) ch;
        if (!c.isImmutable()) {
            return;
        }
        int f = EFF_EFFECT | (c.isEmblem() ? EFF_EMBLEM : 0);
        int rep = 0;
        for (forge.game.replacement.ReplacementEffect re : c.getReplacementEffects()) {
            f |= EFF_REPLACEMENT | traitBits(re);
            if (re.getMode() == forge.game.replacement.ReplacementType.DamageDone
                    && (re.hasParam("Prevent") || re.hasParam("PreventionEffect"))) {
                f |= EFF_PREVENT_DAMAGE;
            }
            if (rep == 0 && re.getMode() != null) {
                rep = re.getMode().ordinal() + 1;
            }
        }
        int st = 0;
        for (forge.game.staticability.StaticAbility sa : c.getStaticAbilities()) {
            f |= EFF_STATIC | traitBits(sa);
            if (st == 0) {
                for (forge.game.staticability.StaticAbilityMode m : sa.getMode()) {
                    st = st == 0 ? m.ordinal() + 1 : Math.min(st, m.ordinal() + 1);
                }
            }
        }
        int tr = 0;
        for (forge.game.trigger.Trigger t : c.getTriggers()) {
            f |= EFF_TRIGGER | traitBits(t);
            if (tr == 0 && t.getMode() != null) {
                tr = t.getMode().ordinal() + 1;
            }
        }
        b[off + 25] = (byte) f;
        b[off + 26] = (byte) duration(c);
        b[off + 28] = u8(rep);
        b[off + 29] = u8(st);
        b[off + 30] = u8(tr);
        final Card src = c.getEffectSource();
        if (src != null && src != c && sourceVisible(src, self)) {
            // structural bytes (byte_feats), not a card_emb lookup: no model clamps them, so registry ids >= 1024
            // (encoder 9) are written as UNK and these bytes stay the encoder-8 ones
            final int id = Math.min(vocab.id(src), CardVocab.UNK);
            b[off + 31] = (byte) id;
            b[off + 32] = (byte) (id >>> 8);
        }
    }

    /**
     * May the effect's source card id be written (bytes 31-32)? Only if it is the viewer's own card (owner or
     * controller) or sits face up in a public zone (battlefield, graveyard, exile, stack, command); a source in the
     * opponent's hand or library (or nowhere) writes 0, whatever {@link HiddenInfo#canSeeFace} says about its face.
     */
    public static boolean sourceVisible(Card src, Player viewer) {
        if (src.getOwner() == viewer || src.getController() == viewer) {
            return true;
        }
        final forge.game.zone.Zone z = src.getZone();
        if (z == null || !HiddenInfo.canSeeFace(src, viewer)) {
            return false;
        }
        return switch (z.getZoneType()) {
            case Battlefield, Graveyard, Exile, Stack, Command -> true;
            default -> false;
        };
    }

    /** EFF_READS_CHOICE / EFF_COMBAT_ONLY from a trait's parameters (any Valid* / Affected filter naming a choice). */
    private static int traitBits(forge.game.CardTraitBase t) {
        int bits = 0;
        for (Map.Entry<String, String> e : t.getMapParams().entrySet()) {
            final String k = e.getKey();
            final String v = e.getValue();
            if ((k.startsWith("Valid") || k.equals("Affected")) && v != null && v.contains("Chosen")) {
                bits |= EFF_READS_CHOICE;
            }
            if ((k.equals("IsCombat") || k.equals("CombatDamage")) && "True".equals(v)) {
                bits |= EFF_COMBAT_ONLY;
            }
        }
        return bits;
    }

    /** Duration code of an Effect card, from its creating ability's {@code Duration$} (absent = until end of turn). */
    static int duration(Card c) {
        final SpellAbility sa = c.getEffectSourceAbility();
        if (sa == null) {
            return c.isEmblem() ? DUR_PERMANENT : 0;
        }
        final String d = sa.getParam("Duration");
        if (d == null || d.equals("UntilEndOfTurn")) {
            return DUR_THIS_TURN;
        }
        if (d.equals("Permanent")) {
            return DUR_PERMANENT;
        }
        if (d.equals("UntilEndOfCombat")) {
            return DUR_THIS_COMBAT;
        }
        if (d.contains("Next") || d.equals("ThisTurnAndNextTurn")) {
            return DUR_LATER_TURN;
        }
        if (d.contains("Host") || d.startsWith("AsLongAs") || d.contains("Untaps") || d.equals("UntilUnattached")
                || d.equals("UntilFacedown")) {
            return DUR_OBJECT;
        }
        return DUR_OTHER;
    }

    /** A damage-prevention replacement effect is active somewhere (cheap gate for {@link #damagePrevented}). */
    private static boolean anyPreventionEffect(Game game) {
        for (Card ca : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (forge.game.replacement.ReplacementEffect re : ca.getReplacementEffects()) {
                if (re.getMode() == forge.game.replacement.ReplacementType.DamageDone
                        && (re.hasParam("Prevent") || re.hasParam("PreventionEffect"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Object byte 24 for a creature on the battlefield: bit 0 combat damage it would deal to its controller's opponent
     * is prevented entirely, bit 1 non-combat damage likewise, bit 2 combat damage partly prevented (a shield smaller
     * than its power). Forge's own read-only forecast ({@code GameEntity.staticDamagePrevention}: replacement effects
     * with {@code Prevent$} / {@code PreventionEffect$}, ValidSource / ValidTarget / IsCombat, "can't be prevented",
     * fog effects); amount = its power, at least 1.
     */
    private static int damagePrevented(Card c, Boolean fog) {
        if (!c.isInPlay() || !c.isCreature()) {
            return 0;
        }
        final Player to = c.getController().getOpponents().isEmpty() ? null : c.getController().getOpponents().getFirst();
        if (to == null) {
            return 0;
        }
        final int dmg = Math.max(1, c.getNetCombatDamage());
        final int combat = to.staticDamagePrevention(dmg, 0, c, true, fog);
        final int other = to.staticDamagePrevention(dmg, 0, c, false, Boolean.FALSE);
        return (combat == 0 ? 1 : 0) | (other == 0 ? 2 : 0) | (combat > 0 && combat < dmg ? 4 : 0);
    }

    /** Card identity + printed-ish features (id, zone, sides, colors, types, keywords, P/T, mana value). */
    private void writeStatic(byte[] b, int off, Card c, int zone, int controller, int owner, int count) {
        final int id = vocab.id(c);
        b[off] = (byte) id;
        b[off + 1] = (byte) (id >>> 8);
        b[off + 2] = (byte) zone;
        b[off + 3] = (byte) controller;
        b[off + 4] = (byte) owner;
        final ColorSet col = c.getColor();
        int cb = (col.hasWhite() ? 1 : 0) | (col.hasBlue() ? 2 : 0) | (col.hasBlack() ? 4 : 0) | (col.hasRed() ? 8 : 0)
                | (col.hasGreen() ? 16 : 0) | (col.isColorless() ? 32 : 0) | (col.isMulticolor() ? 64 : 0);
        b[off + 6] = (byte) cb;
        final CardTypeView t = c.getType();
        int tb = (t.isLand() ? 1 : 0) | (t.isCreature() ? 2 : 0) | (t.isInstant() ? 4 : 0) | (t.isSorcery() ? 8 : 0)
                | (t.isArtifact() ? 16 : 0) | (t.isEnchantment() ? 32 : 0) | (t.isPlaneswalker() ? 64 : 0)
                | (t.isBasic() ? 128 : 0);
        b[off + 7] = (byte) tb;
        for (int k = 0; k < KEYWORDS.length; k++) {
            if (c.hasKeyword(KEYWORDS[k])) {
                b[off + 8 + k / 8] |= (byte) (1 << (k % 8));
            }
        }
        if (c.isCreature()) {
            b[off + 11] = u8(c.getCurrentPower());
            b[off + 12] = u8(c.getCurrentToughness());
        }
        b[off + 17] = u8(c.getCMC());
        b[off + 19] = (byte) NONE;
        b[off + 20] = (byte) NONE;
        b[off + 21] = u8(count);
    }

    private void writeCandidate(byte[] b, int off, Candidate cand, int i, Map<Object, Integer> index, DecisionKind kind,
                                ManaView mana) {
        b[off] = (byte) (cand.type().ordinal() + 1);
        b[off + 1] = (byte) NONE;
        Object ref = cand.ref();
        // encoder 8: the numbers of a COMBAT_DAMAGE / MANA_SOURCE pick in bytes 13-18,
        // then the entity behind it like any CARD / PLAYER candidate
        if (ref instanceof mtgai.controller.ControlPicks.DamagePick d) {
            b[off + 13] = u8(d.amount());
            b[off + 14] = u8(d.need());
            b[off + 15] = u8(d.left());
            ref = d.to();
        } else if (ref instanceof mtgai.controller.ControlPicks.ManaPick m) {
            b[off + 13] = u8(m.amount());
            b[off + 14] = u8(m.paid());
            b[off + 15] = u8(m.left());
            b[off + 16] = (byte) m.colours();
            b[off + 17] = (byte) m.flags();
            b[off + 18] = (byte) m.needColours();
            ref = m.source();
        }
        int cost = 0;
        int number = 0;
        SpellAbility what = null; // encoder 5: the ability this candidate plays / chooses (bytes 7-9)
        if (ref instanceof LegalActions.Action a) {
            what = a.kind == LegalActions.Kind.PLAY_LAND ? null : a.checked;
            if (a.kind == LegalActions.Kind.PLAY_LAND) {
                b[off + 11] = (byte) madnessInHand(mana, ManaView.landColours(a.host));
            }
            final SpellAbility sa = a.checked;
            cost |= LegalActions.altCost(sa) != null ? 1 : 0;
            cost |= !a.optionalCosts.isEmpty() ? 2 : 0;
            cost |= sa.isFlashback() ? 4 : 0;
            cost |= sa.isMadness() ? 8 : 0;
            cost |= a.host.isInZone(ZoneType.Graveyard) ? 16 : 0;
            cost |= a.host.isInZone(ZoneType.Exile) ? 32 : 0;
            b[off + 12] = (byte) castMode(sa, a.host); // encoder 7: casting mode
            if (a.floatPlan != null) {
                // flag float: an untap enabler that floats first; flag floatb: the float action itself;
                // units and colours as on a MANA_SOURCE pick, and for the float action the source flags too
                b[off + 13] = u8(a.floatPlan.units());
                b[off + 16] = (byte) a.floatPlan.colours();
                if (a.kind == LegalActions.Kind.MANA) {
                    b[off + 17] = (byte) a.floatPlan.flags();
                }
            }
            number = a.host.getSpellAbilities().indexOf(a.base);
            ref = a.host;
        } else if (ref instanceof LegalActions.PlayEffectCast pc) {
            // encoder 7: the YES of a Play effect's "Do you want to play X?" (madness, cascade, suspend, rebound, ...);
            // only byte 12 is written, so every other byte is what encoder 6 wrote for this YES
            b[off + 12] = (byte) castMode(pc.play(), null);
            ref = null;
        } else if (ref instanceof SpellAbility sa) {
            final Integer at = index.get(sa) != null ? index.get(sa) : index.get(sa.getRootAbility());
            if (at != null) {
                b[off + 1] = (byte) (int) at;
            }
            ref = sa.getHostCard();
            if (cand.type() == Candidate.Type.ABILITY) {
                what = sa;
                b[off + 10] = u8(i + 1); // encoder 5: choice position (GenericChoice subs are not host abilities)
                if (ref instanceof Card h) {
                    number = h.getSpellAbilities().indexOf(sa);
                }
            }
        } else if (ref instanceof Player p) {
            b[off + 4] = (byte) (p == self ? 1 : 2);
            ref = null;
        } else if (ref instanceof Integer v) {
            number = v;
            ref = null;
        } else if (cand.type() == Candidate.Type.MODE || cand.type() == Candidate.Type.OPTION) {
            number = i;
        }
        if (ref instanceof Card c) {
            final Integer at = index.get(c);
            if (at != null && b[off + 1] == (byte) NONE) {
                b[off + 1] = (byte) (int) at;
            }
            if (c.getOwner() == self || HiddenInfo.canSeeFace(c, self)) {
                final int id = vocab.id(c);
                b[off + 2] = (byte) id;
                b[off + 3] = (byte) (id >>> 8);
            }
            if ((kind == DecisionKind.DISCARD || kind == DecisionKind.COST_DISCARD) && c.getOwner() == self) {
                final forge.card.mana.ManaCost mc = ManaView.madnessCost(c);
                if (mc != null) {
                    final int ok = mana.canPay(mc, 0) ? 6 : 0;
                    b[off + 11] = (byte) (1 | ok); // discarding changes no mana: payable before == after
                }
            }
        }
        b[off + 5] = u8(number);
        b[off + 6] = (byte) cost;
        if (what != null) {
            b[off + 7] = u8(what.getApi() == null ? 0 : what.getApi().ordinal() + 1);
            b[off + 8] = (byte) costKinds(what.getPayCosts());
            if (what.hasParam("UnlessCost")) {
                int u;
                try {
                    u = costKinds(new forge.game.cost.Cost(what.getParam("UnlessCost"), true));
                } catch (RuntimeException e) {
                    u = 128;
                }
                b[off + 9] = (byte) (u == 0 ? 128 : u);
            }
        }
    }

    /**
     * Candidate byte 12 (encoder 7): {@link ZoneCast.Mode} code of a priority candidate's costed variant ({@code host}
     * non-null) or of a Play effect's cast offer ({@code host} null); 0 on any failure (never kills a game).
     */
    private static int castMode(SpellAbility sa, Card host) {
        try {
            return (host == null ? ZoneCast.ofPlayEffect(sa) : ZoneCast.of(sa, host)).code();
        } catch (RuntimeException e) {
            effectError(e);
            return 0;
        }
    }

    /**
     * Candidate byte 8/9 bits: 0 mana (non-zero or X), 1 tap/untap self, 2 sacrifice, 3 discard, 4 pay life, 5 exile,
     * 6 return to hand / tap or untap other permanents, 7 anything else.
     */
    static int costKinds(forge.game.cost.Cost cost) {
        if (cost == null) {
            return 0;
        }
        int bits = 0;
        for (forge.game.cost.CostPart p : cost.getCostParts()) {
            if (p instanceof forge.game.cost.CostPartMana m) {
                bits |= m.getMana() != null && (!m.getMana().isZero() || m.getMana().countX() > 0) ? 1 : 0;
            } else if (p instanceof forge.game.cost.CostTap || p instanceof forge.game.cost.CostUntap) {
                bits |= 2;
            } else if (p instanceof forge.game.cost.CostSacrifice) {
                bits |= 4;
            } else if (p instanceof forge.game.cost.CostDiscard) {
                bits |= 8;
            } else if (p instanceof forge.game.cost.CostPayLife) {
                bits |= 16;
            } else if (p instanceof forge.game.cost.CostExile) {
                bits |= 32;
            } else if (p instanceof forge.game.cost.CostReturn || p instanceof forge.game.cost.CostTapType
                    || p instanceof forge.game.cost.CostUntapType) {
                bits |= 64;
            } else {
                bits |= 128;
            }
        }
        return bits;
    }

    /**
     * PLAY_LAND madness bits (candidate byte 11): bit 0 own hand holds a madness card, bit 1 one of them has its madness
     * cost payable after this land drop (the land's colours added unless it enters tapped), bit 2 payable before it.
     */
    private int madnessInHand(ManaView mana, int landColours) {
        int bits = 0;
        for (Card c : self.getCardsIn(ZoneType.Hand)) {
            final forge.card.mana.ManaCost mc = ManaView.madnessCost(c);
            if (mc == null) {
                continue;
            }
            bits |= 1;
            bits |= mana.canPay(mc, landColours) ? 2 : 0;
            bits |= mana.canPay(mc, 0) ? 4 : 0;
        }
        return bits;
    }
}
