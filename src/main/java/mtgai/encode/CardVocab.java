package mtgai.encode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import forge.game.card.Card;
import forge.item.IPaperCard;
import forge.item.PaperToken;

/**
 * Card name -> u16 id; id 0 = none / hidden face, {@link #UNK} = a name the vocab does not
 * know. Two file forms:
 * <ul>
 * <li>the v1 vocab ({@code cardvocab-v1.txt}): one card name per line, line k (1-based) = id k. The sorted union of the
 * probe and meta decks ({@code mtgai vocab}); every other card and every token is UNK.</li>
 * <li>the encoder-9 registry ({@code registry.tsv}, in a {@code cards/} directory next to {@code pauper/} under
 * {@code decks/}): a
 * header, then {@code id \t name \t kind \t cardsfolder_sha16} per id, append-only. Ids 1-216 are the v1 names with
 * their v1 ids, 217-1022 are unused on purpose, every other Forge card and token script ({@code token:<script>}) has an
 * id from {@link #FIRST_NEW} up. Every exported model clamps ids past its table to its UNK row, so a model that only
 * knows the v1 ids reads a registry id >= 1024 exactly as it read UNK.</li>
 * </ul>
 */
public final class CardVocab {
    /** The v1 id block (the first model's 1,024 W_id rows): ids below it keep their meaning in every id space. */
    public static final int ROWS = 1024;
    public static final int UNK = ROWS - 1;
    /** First registry id of a card outside the v1 vocab. */
    public static final int FIRST_NEW = ROWS;
    public static final String REGISTRY_HEAD = "id\tname\tkind\tsince";
    public static final String TOKEN = "token:";

    private final Map<String, Integer> ids = new HashMap<>();
    /** id -> name (null = unused); length = {@link #rows()}. */
    private final String[] byId;
    /** The registry form (token script ids); false = the v1 vocab, whose {@link #id(Card)} is the encoder-8 one. */
    private final boolean registry;
    /** Opponent-archetype classifier over the dated deck set (global byte 27), or null. */
    private ArchetypeClassifier archetypes;

    /** The v1 form: name k-1 has id k. */
    public CardVocab(List<String> names) {
        if (names.size() >= UNK) {
            throw new IllegalArgumentException(names.size() + " names do not fit below UNK=" + UNK);
        }
        byId = new String[ROWS];
        registry = false;
        for (int i = 0; i < names.size(); i++) {
            put(i + 1, names.get(i));
        }
    }

    private CardVocab(String[] byId) {
        this.byId = new String[byId.length];
        registry = true;
        for (int i = 0; i < byId.length; i++) {
            if (byId[i] != null) {
                put(i, byId[i]);
            }
        }
    }

    private void put(int id, String name) {
        if (ids.put(name, id) != null) {
            throw new IllegalArgumentException("duplicate card name " + name);
        }
        byId[id] = name;
    }

    /** A registry from its lines (header first). Ids are explicit; 0 and UNK must not be assigned. */
    public static CardVocab registry(List<String> lines) {
        if (lines.isEmpty() || !lines.getFirst().equals(REGISTRY_HEAD)) {
            throw new IllegalArgumentException("not a card registry (header " + REGISTRY_HEAD + ")");
        }
        int rows = ROWS;
        final List<String[]> rs = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            if (!l.isEmpty()) {
                final String[] f = l.split("\t", -1);
                rs.add(f);
                rows = Math.max(rows, Integer.parseInt(f[0]) + 1);
            }
        }
        if (rows > 65536) {
            throw new IllegalArgumentException("registry ids past the u16 card_id field: " + rows);
        }
        final String[] byId = new String[rows];
        for (String[] f : rs) {
            final int id = Integer.parseInt(f[0]);
            if (id <= 0 || id == UNK || byId[id] != null) {
                throw new IllegalArgumentException("registry id " + id + " is reserved or assigned twice");
            }
            byId[id] = f[1];
        }
        return new CardVocab(byId);
    }

    /**
     * The registry lines after appending every name of {@code cards} then {@code tokens} (script names, stored as
     * {@code token:<script>}) it does not have yet, each in sorted order, from {@code max(rows, FIRST_NEW)} on, tagged
     * {@code since}. {@code lines} empty = a new registry seeded with the v1 names {@code v1} (ids 1..n). Existing lines
     * are never changed or reordered.
     */
    public static List<String> appendRegistry(List<String> lines, List<String> v1, Collection<String> cards,
                                              Collection<String> tokens, String since) {
        final List<String> out = new ArrayList<>(lines);
        if (out.isEmpty()) {
            out.add(REGISTRY_HEAD);
            for (int i = 0; i < v1.size(); i++) {
                out.add((i + 1) + "\t" + v1.get(i) + "\tcard\t" + since);
            }
        }
        final CardVocab have = registry(out);
        int next = Math.max(have.rows(), FIRST_NEW);
        for (String n : new TreeSet<>(cards)) {
            if (!have.ids.containsKey(n)) {
                out.add(next++ + "\t" + n + "\tcard\t" + since);
            }
        }
        for (String n : new TreeSet<>(tokens)) {
            if (!have.ids.containsKey(TOKEN + n)) {
                out.add(next++ + "\t" + TOKEN + n + "\ttoken\t" + since);
            }
        }
        return out;
    }

    /**
     * The vocab or registry file plus the archetype classifier over the dated deck set {@link #deckSet}. Missing deck
     * set = an error: encoder 4 promises global byte 27.
     */
    public static CardVocab load(Path file) {
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            final CardVocab v = !lines.isEmpty() && lines.getFirst().equals(REGISTRY_HEAD) ? registry(lines)
                    : new CardVocab(lines.stream().filter(s -> !s.isEmpty()).toList());
            final Path decks = deckSet(file);
            if (!Files.exists(decks.resolve("manifest.json"))) {
                throw new IllegalStateException("no archetype deck set " + decks + "/manifest.json for " + file);
            }
            v.archetypes = ArchetypeClassifier.load(decks, v);
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The archetype deck set {@value ArchetypeClassifier#DECK_SET} of a vocab file: next to it (the v1 vocab in
     * {@code decks/pauper/}), else in {@code ../pauper/} (the registry in {@code decks/cards/}). A deployment uses
     * the same {@code decks/} runtime layout.
     */
    public static Path deckSet(Path file) {
        final Path dir = file.toAbsolutePath().getParent();
        final Path sib = dir.resolve(ArchetypeClassifier.DECK_SET);
        return Files.exists(sib) ? sib : dir.resolveSibling("pauper").resolve(ArchetypeClassifier.DECK_SET);
    }

    /** The opponent-archetype classifier (null for a vocab built from a name list). */
    public ArchetypeClassifier archetypes() {
        return archetypes;
    }

    /** Sorted union of the card names in every {@code .dck} under {@code dirs} (all sections). */
    public static List<String> union(List<Path> dirs) throws IOException {
        final TreeSet<String> out = new TreeSet<>();
        for (Path d : dirs) {
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".dck")).toList()) {
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        final int sp = l.indexOf(' ');
                        if (sp > 0 && l.substring(0, sp).chars().allMatch(Character::isDigit)) {
                            final int bar = l.indexOf('|');
                            out.add(l.substring(sp + 1, bar < 0 ? l.length() : bar).trim());
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    public int id(String name) {
        return ids.getOrDefault(name, UNK);
    }

    /**
     * The card's printed name (split cards keep "A // B"), so a half on the stack keeps the card's id. A token made from
     * a token script is {@code token:<script>} when the vocab has it (the registry) unless its name has a v1 id (< 1024,
     * kept so an encoder-8 model reads it as before), else its name, as in the v1 vocab (a token copy of a card is that
     * card; anything else is UNK).
     */
    public int id(Card c) {
        final IPaperCard pc = c.getPaperCard();
        if (!c.isToken()) {
            return id(pc != null ? pc.getName() : c.getName());
        }
        final int byName = id(c.getName());
        if (byName < UNK) {
            return byName; // a v1 name (Llanowar Mentor's "Llanowar Elves" token): its v1 id, as the encoder-8 vocab
        }
        if (registry && pc instanceof PaperToken pt) {
            final Integer t = ids.get(TOKEN + tokenScript(pt));
            if (t != null) {
                return t;
            }
        }
        return byName;
    }

    /** The token script name of a token printing (its image file name up to the first '|'). */
    public static String tokenScript(PaperToken pt) {
        final String f = pt.getImageFilename(1);
        final int bar = f.indexOf('|');
        return bar < 0 ? f : f.substring(0, bar);
    }

    /** Number of names. */
    public int size() {
        return ids.size();
    }

    /** Id space: the largest id + 1, at least {@link #ROWS}. */
    public int rows() {
        return byId.length;
    }

    /** The name of {@code id}, or null if unused. */
    public String name(int id) {
        return id >= 0 && id < byId.length ? byId[id] : null;
    }

    /** Names in id order. */
    public List<String> names() {
        final List<String> out = new ArrayList<>();
        for (String n : byId) {
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }
}
