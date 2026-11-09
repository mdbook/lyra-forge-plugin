package mtgai.ops;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import mtgai.match.HeadlessMatch;

/**
 * The canonical game record, archived for every game as one JSON line: (engine_commit,
 * cardsfolder_sha, deck_manifest_sha, seed, per-seat model_version, action-index list) plus what a replay needs
 * to rebuild the match (deck paths, agents, exposure, turn/decision caps, card vocab) and what it checks against
 * (winner, turns, end, normalised GameLog hash). A few KB per game; {@code mtgai replay --game ID} reads it.
 * Thread-safe append.
 */
public final class GameArchive implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final BufferedWriter w;

    public GameArchive(Path file) throws IOException {
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Game id as used by records, crash bundles and {@code replay --game}: {@code w<worker>-g<game>}. */
    public static String id(long worker, long game) {
        return "w" + worker + "-g" + game;
    }

    /**
     * The record of one finished game. {@code decks}: the deck file paths as the worker loaded them; hashes raw
     * (hex-encoded here); {@code vocab}: the CardVocab path the policy seats encoded with (null = none).
     */
    public static Map<String, Object> entry(String id, long worker, long game, HeadlessMatch.Config c, HeadlessMatch.Result r,
                                            List<String> decks, String vocab, byte[] engineCommit, byte[] cardsSha,
                                            byte[] deckSha) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("worker", worker);
        m.put("game", game);
        m.put("seed", c.seed);
        m.put("engine_commit", hex(engineCommit));
        m.put("cardsfolder_sha", hex(cardsSha));
        m.put("deck_manifest_sha", hex(deckSha));
        m.put("decks", decks);
        m.put("agents", List.of(r.agents[0], r.agents[1]));
        m.put("model_versions", List.of(c.modelVersions[0], c.modelVersions[1]));
        m.put("vocab", vocab);
        m.put("exposure", c.exposure.name());
        m.put("turn_cap", c.turnCap);
        m.put("decision_cap", c.decisionCap);
        m.put("actions", List.of(r.record.script(0), r.record.script(1)));
        m.put("winner", r.winnerSeat);
        m.put("turns", r.turns);
        m.put("end", r.endReason);
        m.put("log_hash", r.logHash != null ? r.logHash : HeadlessMatch.hash(HeadlessMatch.normalise(r.log)));
        if (c.handicaps != null && (c.handicaps[0] != null || c.handicaps[1] != null)) { // replay re-applies them
            final List<String> hc = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                hc.add(c.handicaps[i] == null ? null : c.handicaps[i].id);
            }
            m.put("handicaps", hc);
        }
        if (c.control != null && !c.control.none()) { // encoder 8 decision groups: replay re-applies them
            m.put("control", c.control.toString());
        }
        return m;
    }

    public void append(Map<String, Object> entry) throws IOException {
        final String line = GSON.toJson(entry);
        synchronized (w) {
            w.write(line);
            w.newLine();
            w.flush();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (w) {
            w.close();
        }
    }

    private static String hex(byte[] b) {
        return b == null ? null : HexFormat.of().formatHex(b);
    }

    /**
     * Finds a record by id ({@code w3-g17}) or bare game number ({@code 17}, must be unique) in a JSONL file or
     * every {@code *.jsonl} of a directory.
     */
    public static JsonObject find(Path fileOrDir, String id) throws IOException {
        final List<Path> files = new ArrayList<>();
        if (Files.isDirectory(fileOrDir)) {
            try (Stream<Path> s = Files.list(fileOrDir)) {
                s.filter(p -> p.toString().endsWith(".jsonl")).sorted().forEach(files::add);
            }
        } else {
            files.add(fileOrDir);
        }
        final boolean bare = id.chars().allMatch(Character::isDigit);
        JsonObject hit = null;
        for (Path f : files) {
            try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                for (String l = br.readLine(); l != null; l = br.readLine()) {
                    if (l.isBlank() || !l.contains("\"actions\"")) {
                        continue;
                    }
                    final JsonObject o = JsonParser.parseString(l).getAsJsonObject();
                    final boolean match = bare ? o.get("game").getAsLong() == Long.parseLong(id) : id.equals(o.get("id").getAsString());
                    if (match) {
                        if (hit != null && bare) {
                            throw new IllegalArgumentException("game " + id + " is ambiguous (" + hit.get("id") + ", "
                                    + o.get("id") + "): pass the full id");
                        }
                        hit = o;
                        if (!bare) {
                            return hit;
                        }
                    }
                }
            }
        }
        if (hit == null) {
            throw new IllegalArgumentException("no game record " + id + " in " + fileOrDir);
        }
        return hit;
    }
}
