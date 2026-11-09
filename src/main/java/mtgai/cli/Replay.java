package mtgai.cli;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mtgai.bootstrap.ForgeBootstrap;
import mtgai.controller.PlayerControllerExternal;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.decision.ScriptedOracle;
import mtgai.encode.CardVocab;
import mtgai.encode.Observation;
import mtgai.match.HeadlessMatch;
import mtgai.onnx.ModelSource;
import mtgai.onnx.OnnxOracle;
import mtgai.ops.DecisionLog;
import mtgai.ops.GameArchive;

/**
 * {@code mtgai replay --game ID [--records FILE|DIR] [--debug FILE|-] [--models DIR|URL] [--deck-dir DIR] [-v]}:
 * re-drives the seeded engine through {@link ScriptedOracle} from the canonical game record
 * ({@link GameArchive}) and checks winner, turns, end and the normalised GameLog hash against it. Policy seats are
 * encoded exactly as in the original game (same vocab), so the replay takes the same code path. {@code --debug}
 * writes the per-decision log ({@link DecisionLog}); with {@code --models} the recorded model version
 * ({@code policy-NNNNNN.onnx}, if still published) is re-run on every policy decision so the log carries logits and
 * value. The wall cap is off (it is the one non-deterministic cap): a game that ended on it replays to the end of
 * its script. Exit 0 = the replay reproduced the record.
 */
final class Replay {
    private Replay() {
    }

    static int run(Main.Args a) throws Exception {
        final JsonObject rec = GameArchive.find(Path.of(a.getOr("--records", "data/games")), a.req("--game"));
        final String id = rec.get("id").getAsString();
        final JsonArray deckArr = rec.getAsJsonArray("decks");
        final JsonArray agentArr = rec.getAsJsonArray("agents");
        final JsonArray versions = rec.getAsJsonArray("model_versions");
        final JsonArray actions = rec.getAsJsonArray("actions");
        final String vocabPath = a.getOr("--vocab", rec.has("vocab") && !rec.get("vocab").isJsonNull()
                ? rec.get("vocab").getAsString() : null);
        final CardVocab vocab = vocabPath == null ? null : CardVocab.load(Path.of(vocabPath));
        final ModelSource models = a.get("--models") == null ? null : ModelSource.open(a.get("--models"));

        final HeadlessMatch.Config c = Main.baseConfig(a);
        c.seed = rec.get("seed").getAsLong();
        c.exposure = PlayerControllerExternal.Exposure.valueOf(rec.get("exposure").getAsString());
        c.turnCap = rec.get("turn_cap").getAsInt();
        c.decisionCap = rec.get("decision_cap").getAsInt();
        c.wallCapMillis = 0;
        c.bundleOnTurnCap = false;
        c.gameId = id + "-replay";
        // encoder 8 decision groups the game was played with (absent = none: a record from before encoder 8, or flags off)
        c.control = mtgai.controller.Control.parse(rec.has("control") ? rec.get("control").getAsString() : null);
        if (rec.has("handicaps")) { // a handicapped start (selfplay --p-handicap): same start, same script
            final JsonArray hc = rec.getAsJsonArray("handicaps");
            for (int i = 0; i < 2; i++) {
                c.handicaps[i] = hc.get(i).isJsonNull() ? null : mtgai.match.Handicap.of(hc.get(i).getAsString());
            }
        }
        c.seats = new HeadlessMatch.Seat[2];
        c.oracles = new DecisionOracle[2];
        final StringBuilder deckText = new StringBuilder();
        final Writer debug = a.get("--debug") == null ? null : "-".equals(a.get("--debug"))
                ? new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
                : Files.newBufferedWriter(Path.of(a.get("--debug")), StandardCharsets.UTF_8);
        final List<OnnxOracle.Model> loaded = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Path p = Path.of(deckArr.get(i).getAsString());
            if (a.get("--deck-dir") != null) {
                p = Path.of(a.get("--deck-dir")).resolve(p.getFileName());
            }
            deckText.append(Files.readString(p));
            final String agent = agentArr.get(i).getAsString();
            final long v = versions.get(i).getAsLong();
            c.modelVersions[i] = v;
            c.seats[i] = new HeadlessMatch.Seat(agent, Main.loadDeck(p), Main.deckName(p), null);
            if (mtgai.controller.LobbyPlayerExternal.isForgeAgent(mtgai.controller.LobbyPlayerExternal.baseAgent(agent))) {
                continue; // stock AI seat: no oracle, it replays itself from the seed
            }
            final boolean teacher = agent.startsWith("teacher-"); // imitation teacher: recorded (encoded) like a policy seat
            if ("delegating".equals(agent) || teacher) {
                System.err.println("warning: seat " + i + " was '" + agent + "': its AI hints may draw from the game RNG, so"
                        + " a scripted replay can diverge");
            }
            final List<Integer> script = new ArrayList<>();
            for (JsonElement e : actions.get(i).getAsJsonArray()) {
                script.add(e.getAsInt());
            }
            OnnxOracle.Model model = null;
            if (models != null && v > 0) {
                final String file = String.format(Locale.ROOT, "policy-%06d.onnx", v);
                try {
                    model = new OnnxOracle.Model(v, models.fetch(file));
                    loaded.add(model);
                } catch (Exception e) {
                    System.err.println("seat " + i + ": no logits (" + file + " from " + models.describe() + ": " + e + ")");
                }
            }
            DecisionOracle o = new Seat(new ScriptedOracle(script, null), "onnx".equals(agent) || teacher ? vocab : null,
                    model, agent);
            if ("onnx".equals(agent) && vocab == null) {
                System.err.println("warning: seat " + i + " was a policy seat but no vocab is known: not encoding,"
                        + " the replay may take a different path");
            }
            if (debug != null) {
                o = new DecisionLog(o, debug, id);
            }
            c.oracles[i] = o;
        }
        checkHash("deck_manifest_sha", rec, MessageDigest.getInstance("SHA-256")
                .digest(deckText.toString().getBytes(StandardCharsets.UTF_8)));
        final Path zip = ForgeBootstrap.get().res().resolve("cardsfolder/cardsfolder.zip");
        if (Files.exists(zip)) {
            checkHash("cardsfolder_sha", rec, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        }
        final String commit = a.getOr("--engine-commit", System.getProperty("mtgai.engineCommit", ""));
        if (commit.length() == 40) {
            checkHash("engine_commit", rec, HexFormat.of().parseHex(commit));
        }

        final HeadlessMatch.Result r = HeadlessMatch.play(c);
        final String hash = HeadlessMatch.hash(HeadlessMatch.normalise(r.log));
        if (debug != null) {
            debug.flush();
            if (!"-".equals(a.get("--debug"))) {
                debug.close();
            }
        }
        for (OnnxOracle.Model m : loaded) {
            m.close();
        }
        if (a.flag("-v")) {
            r.log.forEach(System.out::println);
        }
        final String recEnd = rec.get("end").getAsString();
        final boolean wallCapGame = recEnd.equals("abort:WALL_CAP");
        final boolean exhaustedOk = wallCapGame && r.exception != null && r.exception.contains("script exhausted");
        final boolean winnerOk = r.winnerSeat == rec.get("winner").getAsInt();
        final boolean turnsOk = r.turns == rec.get("turns").getAsInt();
        final boolean endOk = r.endReason.equals(recEnd) || exhaustedOk;
        // a cap-aborted game's last GameLog line ("Match Result: ...") credits an unseeded winner:
        // its log hash is not reproducible, so it is reported but not required
        final boolean aborted = recEnd.startsWith("abort:");
        final boolean hashOk = hash.equals(rec.get("log_hash").getAsString());
        System.out.printf(Locale.ROOT, "replay %s seed %d: winner %d (rec %d) %s | turns %d (rec %d) %s | end %s (rec %s) %s"
                        + " | log hash %s%s%n", id, c.seed, r.winnerSeat, rec.get("winner").getAsInt(), ok(winnerOk), r.turns,
                rec.get("turns").getAsInt(), ok(turnsOk), r.endReason, recEnd, ok(endOk), ok(hashOk),
                r.exception != null && !exhaustedOk ? " | exception " + r.exception : "");
        if (wallCapGame) {
            System.out.println("note: the recorded game hit the wall cap; the replay stops where its script ends, so "
                    + "the log hash and turn count need not match");
            return winnerOk && endOk ? 0 : 1;
        }
        if (aborted && !hashOk) {
            System.out.println("note: cap-aborted game: the log hash is not reproducible by design (unseeded Match Result line)");
        }
        return winnerOk && turnsOk && endOk && (hashOk || aborted) ? 0 : 1;
    }

    private static String ok(boolean b) {
        return b ? "ok" : "MISMATCH";
    }

    private static void checkHash(String field, JsonObject rec, byte[] now) {
        if (!rec.has(field) || rec.get(field).isJsonNull()) {
            return;
        }
        final String want = rec.get(field).getAsString();
        final String have = HexFormat.of().formatHex(now);
        if (!want.chars().allMatch(ch -> ch == '0') && !want.equals(have)) {
            System.err.println("warning: " + field + " differs from the record (" + have + " vs " + want
                    + "): the replay may diverge");
        }
    }

    /** A replayed seat: the scripted index, encoded like the original seat, optionally re-scored by the model. */
    private static final class Seat implements DecisionOracle {
        private final ScriptedOracle script;
        private final CardVocab vocab;
        private final OnnxOracle.Model model;
        private final String agent;
        private float[] last;

        Seat(ScriptedOracle script, CardVocab vocab, OnnxOracle.Model model, String agent) {
            this.script = script;
            this.vocab = vocab;
            this.model = model;
            this.agent = agent;
        }

        @Override
        public DecisionResponse decide(DecisionRequest req) {
            final DecisionResponse r = script.decide(req);
            last = null;
            if (model != null && req.observation() instanceof Observation o) {
                last = model.forward(o);
                final int n = o.nCand();
                double max = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < n; i++) {
                    max = Math.max(max, last[i]);
                }
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += Math.exp(last[i] - max);
                }
                return new DecisionResponse(r.index(), (float) (last[r.index()] - max - Math.log(sum)), last[n], false);
            }
            return r;
        }

        @Override
        public float[] lastOutput() {
            return last;
        }

        @Override
        public CardVocab observationVocab() {
            return vocab;
        }

        @Override
        public void onGameStart(long seed, int seat) {
            script.onGameStart(seed, seat);
        }

        @Override
        public String name() {
            return agent;
        }
    }
}
