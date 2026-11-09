package mtgai.search;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * One determinisation of the hidden zones of a game copy, from the deciding player's point of view.
 *
 * <p>Each opponent's hand and library are re-dealt from that opponent's (archetype) decklist minus everything public
 * (battlefield, graveyard, exile, stack, command: cards they own) minus the pinned cards (known to the viewer: revealed,
 * {@code AiCardMemory.REVEALED_CARDS}, or lookable), which keep their zone and library position. Hidden-zone sizes
 * are preserved. When the decklist is consistent with the hidden card count (the normal case: the archetype list is
 * the deck being played) the copy's own hidden card objects are reused and only missing names are created; otherwise
 * (an off-list deck) the hidden multiset is permuted as is and the call is counted as a decklist mismatch.
 *
 * <p>The viewer's own library order is hidden from them too: its unpinned positions are shuffled.
 *
 * <p>Pure function of (copy, decklists, pinned ids, rnd): same seed, same deal.
 */
public final class Determinizer {
    /** What one call did (summed into the search stats). */
    public static final class Result {
        public int created;
        public int dropped;
        public int mismatches;
    }

    private final Map<Integer, Map<String, Integer>> decklists;
    private final Map<String, PaperCard> paper;

    /**
     * @param decklists per opponent player id: card name -> count (main deck; null entry = unknown list: permute only)
     * @param paper     card name -> printing, to create cards the copy lacks
     */
    public Determinizer(Map<Integer, Map<String, Integer>> decklists, Map<String, PaperCard> paper) {
        this.decklists = decklists;
        this.paper = paper;
    }

    private static final ZoneType[] PUBLIC = {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command};

    /** True for a card that counts against a decklist (a real card, not a token or effect). */
    static boolean deckCard(Card c) {
        return !c.isToken() && !c.isImmutable() && c.getPaperCard() != null && !c.isCopiedSpell();
    }

    static String name(Card c) {
        return c.getPaperCard().getName();
    }

    /** Re-deals {@code g}'s hidden zones for viewer {@code viewerId}. */
    public Result determinise(Game g, int viewerId, Set<Integer> pinned, Random rnd) {
        final Result r = new Result();
        final Player viewer = g.getPlayer(viewerId);
        for (Player o : g.getPlayers()) {
            if (o == viewer) {
                continue;
            }
            redeal(g, o, pinned, rnd, r);
        }
        // own library: order unknown to the viewer
        final List<Card> lib = list(viewer.getZone(ZoneType.Library).getCards());
        final List<Integer> free = new ArrayList<>();
        final List<Card> freeCards = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            if (!pinned.contains(lib.get(i).getId())) {
                free.add(i);
                freeCards.add(lib.get(i));
            }
        }
        Collections.shuffle(freeCards, rnd);
        for (int i = 0; i < free.size(); i++) {
            lib.set(free.get(i), freeCards.get(i));
        }
        viewer.getZone(ZoneType.Library).setCards(lib);
        return r;
    }

    private void redeal(Game g, Player o, Set<Integer> pinned, Random rnd, Result r) {
        final List<Card> hand = list(o.getZone(ZoneType.Hand).getCards());
        final List<Card> lib = list(o.getZone(ZoneType.Library).getCards());
        final List<Card> keepHand = new ArrayList<>();
        final List<Card> free = new ArrayList<>();
        for (Card c : hand) {
            (pinned.contains(c.getId()) ? keepHand : free).add(c);
        }
        for (Card c : lib) {
            if (!pinned.contains(c.getId())) {
                free.add(c);
            }
        }
        List<Card> pool = free;
        final Map<String, Integer> deck = decklists == null ? null : decklists.get(o.getId());
        if (deck != null) {
            final Map<String, Integer> want = new TreeMap<>(deck);
            boolean ok = true;
            for (Card c : publicCards(g, o)) {
                ok &= dec(want, name(c));
            }
            for (Card c : hand) {
                if (pinned.contains(c.getId()) && deckCard(c)) {
                    ok &= dec(want, name(c));
                }
            }
            for (Card c : lib) {
                if (pinned.contains(c.getId()) && deckCard(c)) {
                    ok &= dec(want, name(c));
                }
            }
            int total = 0;
            for (int v : want.values()) {
                total += v;
            }
            boolean allDeckCards = true;
            for (Card c : free) {
                allDeckCards &= deckCard(c);
            }
            if (!ok || total != free.size() || !allDeckCards) {
                r.mismatches++;
            } else {
                // reuse the copy's own objects by name, create what is missing, drop the rest
                final Map<String, Deque<Card>> byName = new HashMap<>();
                for (Card c : free) {
                    byName.computeIfAbsent(name(c), k -> new ArrayDeque<>()).add(c);
                }
                pool = new ArrayList<>(free.size());
                for (Map.Entry<String, Integer> e : want.entrySet()) {
                    final Deque<Card> have = byName.getOrDefault(e.getKey(), new ArrayDeque<>());
                    for (int i = 0; i < e.getValue(); i++) {
                        if (!have.isEmpty()) {
                            pool.add(have.poll());
                        } else {
                            final PaperCard pc = paper.get(e.getKey());
                            if (pc == null) {
                                throw new IllegalStateException("no printing for " + e.getKey());
                            }
                            pool.add(CardFactory.getCard(pc, o, g));
                            r.created++;
                        }
                    }
                }
                for (Deque<Card> left : byName.values()) {
                    r.dropped += left.size();
                }
            }
        }
        final List<Card> deal = new ArrayList<>(pool);
        Collections.shuffle(deal, rnd);
        int k = 0;
        final List<Card> newHand = new ArrayList<>(keepHand);
        while (newHand.size() < hand.size()) {
            newHand.add(deal.get(k++));
        }
        final List<Card> newLib = new ArrayList<>(lib.size());
        for (Card c : lib) {
            newLib.add(pinned.contains(c.getId()) ? c : deal.get(k++));
        }
        o.getZone(ZoneType.Hand).setCards(newHand);
        o.getZone(ZoneType.Library).setCards(newLib);
    }

    static List<Card> list(Iterable<Card> cards) {
        final List<Card> out = new ArrayList<>();
        for (Card c : cards) {
            out.add(c);
        }
        return out;
    }

    private static boolean dec(Map<String, Integer> m, String name) {
        final Integer v = m.get(name);
        if (v == null || v == 0) {
            return false;
        }
        m.put(name, v - 1);
        return true;
    }

    /** {@code o}'s own cards in public zones (any controller's battlefield, the stack, ...), deck cards only. */
    static List<Card> publicCards(Game g, Player o) {
        final List<Card> out = new ArrayList<>();
        for (Player p : g.getPlayers()) {
            for (ZoneType z : PUBLIC) {
                for (Card c : p.getCardsIn(z)) {
                    if (c.getOwner() == o && deckCard(c)) {
                        out.add(c);
                    }
                }
            }
        }
        for (Card c : g.getStackZone().getCards()) {
            if (c.getOwner() == o && deckCard(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** card name -> count of a deck's main board. */
    public static Map<String, Integer> decklist(forge.deck.Deck d) {
        final Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<PaperCard, Integer> e : d.getMain()) {
            out.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        return out;
    }

    /** card name -> a printing, for {@link #Determinizer}. */
    public static Map<String, PaperCard> printings(forge.deck.Deck d) {
        final Map<String, PaperCard> out = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : d.getMain()) {
            out.putIfAbsent(e.getKey().getName(), e.getKey());
        }
        return out;
    }
}
