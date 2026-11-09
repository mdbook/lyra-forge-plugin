package mtgai.encode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Opponent-archetype id for global byte 27 (encoder 4): the multinomial naive Bayes of the training code's
 * archetype module over the 12 dated deck lists, evaluated on the seat's OPP_SEEN
 * tokens only (public information: opponent cards whose face this seat has seen).
 *
 * <p>For archetype k with main-deck counts n_k(c) (N_k cards), p(c | k) = (n_k(c) + 1/2) / (N_k + V/2), V = number of
 * distinct cards over all main decks; ids 0 and UNK carry no evidence; uniform prior. id = argmax_k of
 * prod_c p(c | k)^seen(c), plus 1, in manifest order; ties go to the lowest k (numpy {@code argmax}); 0 when no
 * OPP_SEEN copies at all. The argmax is evaluated exactly (integers: (2n+1)^s against (2N+V)^S, cross-multiplied),
 * not in floating point, so the training code's Python reference agrees bit-for-bit, ties included.
 */
public final class ArchetypeClassifier {
    /** The dated deck set under the vocab's directory (lockstep with the training code's deck directory). */
    public static final String DECK_SET = "2026-09-28";
    private static final Pattern LINE = Pattern.compile("(\\d+)\\s+(.+)");

    private final List<String> decks;
    /** (K, ROWS) main-deck copies by card id. */
    private final int[][] counts;
    /** 2 N_k + V: the denominator of p(c | k) times 2. */
    private final BigInteger[] den;

    ArchetypeClassifier(List<String> decks, int[][] counts) {
        this.decks = List.copyOf(decks);
        this.counts = counts;
        int v = 0;
        for (int c = 0; c < CardVocab.ROWS; c++) {
            int tot = 0;
            for (int[] row : counts) {
                tot += row[c];
            }
            v += tot > 0 ? 1 : 0;
        }
        den = new BigInteger[counts.length];
        for (int k = 0; k < counts.length; k++) {
            int n = 0;
            for (int x : counts[k]) {
                n += x;
            }
            den[k] = BigInteger.valueOf(2L * n + v);
        }
    }

    /** {@code deckDir/manifest.json} + its {@code .dck} files (the [Main] section), ids from {@code vocab}. */
    public static ArchetypeClassifier load(Path deckDir, CardVocab vocab) {
        try {
            final List<String> ids = new ArrayList<>();
            final List<int[]> rows = new ArrayList<>();
            for (JsonElement d : JsonParser.parseString(Files.readString(deckDir.resolve("manifest.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("decks")) {
                ids.add(d.getAsJsonObject().get("id").getAsString());
                final int[] row = new int[CardVocab.ROWS];
                String sec = null;
                final Path dck = deckDir.resolve(d.getAsJsonObject().get("file").getAsString());
                for (String ln : Files.readAllLines(dck, StandardCharsets.UTF_8)) {
                    ln = ln.strip();
                    final Matcher m = LINE.matcher(ln);
                    if (ln.startsWith("[")) {
                        sec = ln.substring(1, ln.length() - 1);
                    } else if ("Main".equals(sec) && m.lookingAt()) {
                        // registry ids >= 1024 (encoder 9) fold into UNK, as in predict: the 12 decks are v1 names
                        row[Math.min(vocab.id(m.group(2).split("\\|", -1)[0]), CardVocab.UNK)] += Integer.parseInt(m.group(1));
                    }
                }
                rows.add(row);
            }
            return new ArchetypeClassifier(ids, rows.toArray(new int[0][]));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> decks() {
        return decks;
    }

    /**
     * From OPP_SEEN tokens (48 bytes each: card id bytes 0-1, copies byte 21), as written. Ids >= 1024 (registry ids of
     * cards outside the v1 vocab, encoder 9) count as UNK: no evidence, so the byte is the one the v1 vocab gives.
     */
    public int predict(byte[] oppSeen) {
        final int[] seen = new int[CardVocab.ROWS];
        for (int off = 0; off + Observation.OBJ <= oppSeen.length; off += Observation.OBJ) {
            final int id = (oppSeen[off] & 0xff) | (oppSeen[off + 1] & 0xff) << 8;
            seen[Math.min(id, CardVocab.UNK)] += oppSeen[off + 21] & 0xff;
        }
        return predict(seen);
    }

    /** {@code seen[c]} = opponent copies seen of card id c ({@link CardVocab#ROWS} entries). 0 = nothing seen. */
    public int predict(int[] seen) {
        long total = 0;
        int s = 0;
        for (int c = 0; c < seen.length; c++) {
            total += seen[c];
            if (c != 0 && c != CardVocab.UNK) {
                s += seen[c];
            }
        }
        if (total == 0) {
            return 0;
        }
        int best = -1;
        BigInteger bestNum = null;
        BigInteger bestDen = null;
        for (int k = 0; k < counts.length; k++) {
            BigInteger num = BigInteger.ONE;
            for (int c = 1; c < CardVocab.UNK; c++) {
                if (seen[c] != 0) {
                    num = num.multiply(BigInteger.valueOf(2L * counts[k][c] + 1).pow(seen[c]));
                }
            }
            final BigInteger d = den[k].pow(s);
            // strictly greater: num/d > bestNum/bestDen  <=>  num * bestDen > bestNum * d
            if (best < 0 || num.multiply(bestDen).compareTo(bestNum.multiply(d)) > 0) {
                best = k;
                bestNum = num;
                bestDen = d;
            }
        }
        return best + 1;
    }
}
