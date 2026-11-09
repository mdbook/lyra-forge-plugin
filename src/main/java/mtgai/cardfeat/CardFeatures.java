package mtgai.cardfeat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.item.PaperCard;
import forge.item.PaperToken;
import mtgai.bootstrap.ForgeBootstrap;
import mtgai.encode.CardVocab;

/**
 * {@code mtgai cardfeatures} (observation encoder 6 and later): every card and token
 * script through Forge's {@code CardFactory} -> {@link CardAtoms} bags -> the append-only atom registry
 * {@code cardatoms.tsv} and the per-card-id table {@code cardfeat-<cardsfolder_sha[:16]>.bin} for the training code's
 * content path. Offline (dev), never on a worker.
 *
 * <p>Folding: an atom seen on fewer than {@code --min-freq} (5) cards of the whole pool maps to its namespace bucket
 * ({@code A.api:<rare>}, {@code A.ValidTgts.<rare>}, {@code #A.<rare>} ...) unless the registry already has it (the
 * registry is append-only: an index, once given, is never reused or renumbered; later runs append).
 *
 * <p>{@code cardfeat} binary, little-endian: {@code "MTGCFEAT"}, u32 format 1, 32 B cardsfolder sha256, 32 B vocab file
 * sha256 (the v1 vocab's, or the card registry's for the encoder-9 all-cards table), u32 rows ({@link CardVocab#ROWS}
 * for the v1 vocab, the registry's id space for the all-cards table), u32 registry size, u32 max atoms per row; then per row (card id 0 ..
 * rows-1): u16 name length + UTF-8 name, u16 n, n x (i32 registry index, f32 value). Row 0 (none) and UNK are empty;
 * presence atoms have value 1, magnitude atoms ({@code #...}) the raw number. A row keeps at most {@code --max-atoms}
 * (64) atoms, the most frequent ones.
 */
public final class CardFeatures {
    private CardFeatures() {
    }

    public static final String REGISTRY = "cardatoms.tsv";
    static final String HEAD = "idx\tatom\tkind\tfreq";

    /** Namespace bucket of a rare atom: up to the first ':' or '=' (inclusive), else up to the last '.'. */
    static String bucket(String atom) {
        int cut = -1;
        for (int i = 0; i < atom.length(); i++) {
            final char ch = atom.charAt(i);
            if (ch == ':' || ch == '=') {
                cut = i + 1;
                break;
            }
        }
        if (cut < 0) {
            cut = atom.lastIndexOf('.') + 1;
        }
        return (cut > 0 ? atom.substring(0, cut) : atom.startsWith("#") ? "#" : "") + "<rare>";
    }

    static String kind(String atom) {
        final boolean mag = atom.startsWith("#");
        return atom.endsWith("<rare>") ? (mag ? "magbucket" : "bucket") : mag ? "mag" : "atom";
    }

    /** Append-only registry: atom -> index (0 = padding). */
    static final class Registry {
        final LinkedHashMap<String, Integer> idx = new LinkedHashMap<>();
        final Map<String, Integer> freq = new HashMap<>();

        static Registry load(Path p) throws IOException {
            final Registry r = new Registry();
            if (Files.exists(p)) {
                final List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (String l : lines.subList(1, lines.size())) {
                    final String[] f = l.split("\t");
                    if (Integer.parseInt(f[0]) != r.idx.size()) {
                        throw new IllegalStateException(p + ": registry index " + f[0] + " out of order");
                    }
                    r.idx.put(f[1], Integer.parseInt(f[0]));
                }
            }
            r.idx.putIfAbsent("<pad>", 0);
            return r;
        }

        int get(String atom) {
            return idx.computeIfAbsent(atom, a -> idx.size());
        }

        void write(Path p) throws IOException {
            final List<String> out = new ArrayList<>();
            out.add(HEAD);
            idx.forEach((a, i) -> out.add(i + "\t" + a + "\t" + (i == 0 ? "pad" : kind(a)) + "\t" + freq.getOrDefault(a, 0)));
            Files.write(p, out, StandardCharsets.UTF_8);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * A card through {@code CardFactory} with no owner and no game. The id must be >= 0: CardFactory builds a face's
     * SVars, triggers, statics, replacements, keywords and abilities only for non-negative ids (negative = view-only).
     */
    static Card card(forge.item.IPaperCard pc) {
        return CardFactory.getCard(pc, null, NEXT_ID.getAndIncrement(), null);
    }

    /** A named card's bag (its first printing). */
    static Map<String, Double> bag(PaperCard pc) {
        return CardAtoms.of(card(pc)).atoms();
    }

    /**
     * All card names (unique) and token scripts -> bags; failures are counted, not fatal. {@code names}, if given,
     * receives every card name and token script name, failures included (the card registry's input).
     */
    static Map<String, Map<String, Double>> pool(PrintStream log, int[] failures, List<List<String>> names) {
        final ForgeBootstrap fb = ForgeBootstrap.get();
        final TreeMap<String, Map<String, Double>> out = new TreeMap<>();
        final Collection<PaperCard> cards = fb.data().getCommonCards().getUniqueCards();
        if (names != null) {
            names.add(cards.stream().map(PaperCard::getName).toList());
            names.add(List.copyOf(fb.data().getAllTokens().getRules().keySet()));
        }
        for (PaperCard pc : cards) {
            try {
                out.put(pc.getName(), bag(pc));
            } catch (RuntimeException e) {
                failures[0]++;
                log.println("cardfeatures: FAIL " + pc.getName() + ": " + e + " at "
                        + java.util.Arrays.stream(e.getStackTrace()).limit(6).map(Object::toString).toList());
            }
        }
        final TreeMap<String, CardRules> tokens = new TreeMap<>(fb.data().getAllTokens().getRules());
        for (Map.Entry<String, CardRules> e : tokens.entrySet()) {
            try {
                final PaperToken pt = new PaperToken(e.getValue(), null, e.getKey(), "", "");
                out.put(CardVocab.TOKEN + e.getKey(), CardAtoms.of(card(pt)).atoms());
            } catch (RuntimeException ex) {
                failures[1]++;
                log.println("cardfeatures: FAIL token " + e.getKey() + ": " + ex);
            }
        }
        return out;
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sha256 of res/cardsfolder/cardsfolder.zip (the chunk header's cardsfolder_sha), else of the sorted script tree. */
    public static byte[] cardsfolderSha(Path res) throws IOException {
        final Path zip = res.resolve("cardsfolder/cardsfolder.zip");
        if (Files.exists(zip)) {
            return sha256(Files.readAllBytes(zip));
        }
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final Path dir = res.resolve("cardsfolder");
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                md.update(dir.relativize(p).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(p));
            }
        }
        return md.digest();
    }

    /**
     * {@code cardfeatures --vocab FILE --out DIR [--min-freq 5] [--max-atoms 64] [--pool-dump FILE]}: the v1 table
     * ({@code cardfeat-<sha16>.bin} + {@code .txt}, rows 0..1023, the vocab's ids).
     *
     * <p>{@code cardfeatures --registry FILE [--vocab V1] --out DIR ...} (encoder 9): first appends every pool card and
     * token script the card registry {@code FILE} lacks ({@link CardVocab#appendRegistry}; a missing FILE is seeded
     * with the v1 vocab {@code V1}), rewriting FILE, then writes a row for every registry id: {@code allcards-<sha16>.bin.gz} +
     * {@code .txt.gz} (the same encoding, gzip; rows = the registry's id space; the header's vocab sha is the registry
     * file's) + {@code .json}. A card Forge fails to build gets an empty row.
     */
    public static int run(Path vocabFile, Path registryFile, Path outDir, int minFreq, int maxAtoms, Path poolDump,
                          PrintStream log) throws IOException {
        final long t0 = System.currentTimeMillis();
        final ForgeBootstrap fb = ForgeBootstrap.get();
        final List<String> names = vocabFile == null ? List.of()
                : Files.readAllLines(vocabFile, StandardCharsets.UTF_8).stream().filter(s -> !s.isEmpty()).toList();
        final int[] failures = {0, 0};
        final List<List<String>> poolNames = new ArrayList<>();
        final Map<String, Map<String, Double>> pool = pool(log, failures, poolNames);
        final long tPool = System.currentTimeMillis() - t0;
        final byte[] cfSha = cardsfolderSha(fb.res());
        final String hex = HexFormat.of().formatHex(cfSha);
        final CardVocab vocab;
        int appended = 0;
        if (registryFile != null) {
            final List<String> before = Files.exists(registryFile)
                    ? Files.readAllLines(registryFile, StandardCharsets.UTF_8) : List.of();
            if (before.isEmpty() && names.isEmpty()) {
                throw new IllegalArgumentException("new registry " + registryFile + ": --vocab (the v1 names) seeds it");
            }
            final List<String> after = CardVocab.appendRegistry(before, names, poolNames.get(0), poolNames.get(1),
                    hex.substring(0, 16));
            appended = after.size() - (before.isEmpty() ? 1 + names.size() : before.size());
            if (!after.equals(before)) {
                Files.createDirectories(registryFile.toAbsolutePath().getParent());
                Files.write(registryFile, after, StandardCharsets.UTF_8);
            }
            vocab = CardVocab.registry(after);
        } else {
            vocab = new CardVocab(names);
        }

        final Map<String, Integer> freq = new HashMap<>();
        for (Map<String, Double> b : pool.values()) {
            b.keySet().forEach(a -> freq.merge(a, 1, Integer::sum));
        }
        Files.createDirectories(outDir);
        final Path regPath = outDir.resolve(REGISTRY);
        final Registry reg = Registry.load(regPath);
        final int regBefore = reg.idx.size();
        // map every atom (kept or bucket); new registry entries appended in sorted order (deterministic)
        final Map<String, String> target = new HashMap<>();
        final TreeSet<String> fresh = new TreeSet<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            final String a = e.getKey();
            final String t = reg.idx.containsKey(a) || e.getValue() >= minFreq ? a : bucket(a);
            target.put(a, t);
            if (!reg.idx.containsKey(t)) {
                fresh.add(t);
            }
        }
        fresh.forEach(reg::get);
        reg.freq.clear();
        for (Map<String, Double> b : pool.values()) {
            final TreeSet<String> ts = new TreeSet<>();
            b.keySet().forEach(a -> ts.add(target.get(a)));
            ts.forEach(t -> reg.freq.merge(t, 1, Integer::sum));
        }
        reg.write(regPath);
        final Map<Integer, Integer> idxFreq = new HashMap<>();
        reg.freq.forEach((a, f) -> idxFreq.put(reg.idx.get(a), f));

        // per-id rows for the vocab (ids 1..n; 0 and UNK stay empty) / for every registry id
        final int rows = vocab.rows();
        final List<Map<Integer, Double>> table = new ArrayList<>();
        final List<String> rowNames = new ArrayList<>();
        final List<String> dump = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        int truncated = 0;
        for (int id = 0; id < rows; id++) {
            final String name = vocab.name(id) == null ? "" : vocab.name(id);
            rowNames.add(name);
            final Map<Integer, Double> row = new LinkedHashMap<>();
            if (!name.isEmpty()) {
                Map<String, Double> b = pool.get(name);
                if (b == null && !name.startsWith(CardVocab.TOKEN)) {
                    final PaperCard pc = fb.data().getCommonCards().getCard(name);
                    try {
                        b = pc == null ? null : bag(pc);
                    } catch (RuntimeException e) {
                        if (registryFile == null) {
                            throw e;
                        }
                        // the registry keeps an id for a card Forge fails to build (pool() logged it): empty row
                    }
                }
                if (b == null) {
                    missing.add(name);
                } else {
                    dump.add(id + "\t" + CardAtoms.dumpLine(name, b));
                    final Map<Integer, Double> merged = new HashMap<>();
                    for (Map.Entry<String, Double> e : b.entrySet()) {
                        final String t = target.getOrDefault(e.getKey(), bucket(e.getKey()));
                        final int i = reg.get(t);
                        merged.merge(i, e.getValue(), (x, y) -> Math.abs(y) > Math.abs(x) ? y : x);
                    }
                    final List<Integer> keep = new ArrayList<>(merged.keySet());
                    keep.sort(Comparator.comparingInt((Integer i) -> -idxFreq.getOrDefault(i, 0))
                            .thenComparingInt(i -> i));
                    if (keep.size() > maxAtoms) {
                        truncated++;
                    }
                    keep.stream().limit(maxAtoms).sorted().forEach(i -> row.put(i, merged.get(i)));
                }
            }
            table.add(row);
        }
        if (reg.idx.size() != regBefore + fresh.size()) {
            reg.write(regPath); // a vocab card outside the pool added a bucket
        }
        final Path idFile = registryFile != null ? registryFile : vocabFile;
        final byte[] vocabSha = sha256(Files.readAllBytes(idFile));
        final String stem = (registryFile != null ? "allcards-" : "cardfeat-") + hex.substring(0, 16);
        final byte[] binBytes = encode(cfSha, vocabSha, reg.idx.size(), maxAtoms, rowNames, table);
        final byte[] txtBytes = (String.join("\n", dump) + (dump.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8);
        final Path bin = outDir.resolve(stem + ".bin" + (registryFile != null ? ".gz" : ""));
        Files.write(bin, registryFile != null ? gzip(binBytes) : binBytes);
        Files.write(outDir.resolve(stem + ".txt" + (registryFile != null ? ".gz" : "")),
                registryFile != null ? gzip(txtBytes) : txtBytes);
        if (poolDump != null) {
            final List<String> all = new ArrayList<>();
            pool.forEach((n, b) -> all.add(CardAtoms.dumpLine(n, b)));
            Files.write(poolDump, all, StandardCharsets.UTF_8);
        }

        // stats
        final int nCards = (int) pool.keySet().stream().filter(n -> !n.startsWith("token:")).count();
        final List<Integer> per = pool.entrySet().stream().filter(e -> !e.getKey().startsWith("token:"))
                .map(e -> (int) e.getValue().keySet().stream().filter(a -> !a.contains("sub:")).count()).sorted().toList();
        final Map<Object, Integer> bagCount = new HashMap<>();
        pool.forEach((n, b) -> {
            if (!n.startsWith("token:")) {
                bagCount.merge(new TreeSet<>(b.keySet().stream().filter(a -> !a.startsWith("#") && !a.contains("sub:")).toList()), 1,
                        Integer::sum);
            }
        });
        final long unique = pool.entrySet().stream().filter(e -> !e.getKey().startsWith("token:"))
                .filter(e -> bagCount.get(new TreeSet<>(e.getValue().keySet().stream()
                        .filter(a -> !a.startsWith("#") && !a.contains("sub:")).toList())) == 1).count();
        final long kept = freq.values().stream().filter(f -> f >= minFreq).count();
        final long buckets = reg.idx.keySet().stream().filter(a -> a.endsWith("<rare>")).count();
        final int vocabAtoms = (int) table.stream().flatMap(r -> r.keySet().stream()).distinct().count();
        final Map<String, Object> st = new LinkedHashMap<>();
        st.put("cardsfolder_sha", hex);
        st.put("vocab", idFile.getFileName().toString());
        st.put("vocab_sha", HexFormat.of().formatHex(vocabSha));
        st.put("bin", bin.getFileName().toString());
        st.put("cards", nCards);
        st.put("token_scripts", pool.size() - nCards);
        st.put("card_failures", failures[0]);
        st.put("token_failures", failures[1]);
        st.put("distinct_atoms", freq.size());
        st.put("atoms_freq_ge_min", kept);
        st.put("min_freq", minFreq);
        st.put("registry_size", reg.idx.size());
        st.put("registry_appended", reg.idx.size() - regBefore);
        st.put("bucket_atoms", buckets);
        st.put("atoms_per_card_excl_subtypes", Map.of("median", per.get(per.size() / 2), "p90", per.get((int) (.9 * per.size())),
                "p99", per.get((int) (.99 * per.size())), "max", per.getLast()));
        st.put("unique_bag_cards", unique);
        st.put("vocab_rows", vocab.size());
        if (registryFile != null) {
            st.put("table_rows", rows);
            st.put("card_ids_appended", appended);
        }
        st.put("vocab_missing", missing);
        st.put("vocab_rows_truncated", truncated);
        st.put("vocab_distinct_registry_atoms", vocabAtoms);
        st.put("max_atoms", maxAtoms);
        st.put("pool_ms", tPool);
        st.put("total_ms", System.currentTimeMillis() - t0);
        final String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(st);
        Files.writeString(outDir.resolve(stem + ".json"), json + "\n");
        log.println(json);
        // a pool card that fails to build only loses its frequency vote; the registry keeps its id (empty row)
        return missing.isEmpty() || registryFile != null ? 0 : 1;
    }

    /** gzip with a fixed header (mtime 0): the same bytes in, the same bytes out. */
    static byte[] gzip(byte[] b) throws IOException {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(bo)) {
            z.write(b);
        }
        return bo.toByteArray();
    }

    static byte[] encode(byte[] cfSha, byte[] vocabSha, int regSize, int maxAtoms, List<String> names,
                         List<Map<Integer, Double>> table) throws IOException {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final DataOutputStream o = new DataOutputStream(bo);
        o.write("MTGCFEAT".getBytes(StandardCharsets.US_ASCII));
        o.write(le(4).putInt(1).array());
        o.write(cfSha);
        o.write(vocabSha);
        o.write(le(12).putInt(table.size()).putInt(regSize).putInt(maxAtoms).array());
        for (int r = 0; r < table.size(); r++) {
            final byte[] nm = names.get(r).getBytes(StandardCharsets.UTF_8);
            o.write(le(2).putShort((short) nm.length).array());
            o.write(nm);
            final Map<Integer, Double> row = table.get(r);
            o.write(le(2).putShort((short) row.size()).array());
            for (Map.Entry<Integer, Double> e : row.entrySet()) {
                o.write(le(8).putInt(e.getKey()).putFloat((float) (double) e.getValue()).array());
            }
        }
        o.flush();
        return bo.toByteArray();
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }
}
