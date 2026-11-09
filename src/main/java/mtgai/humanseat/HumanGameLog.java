package mtgai.humanseat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameType;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import mtgai.decision.Candidate;
import mtgai.decision.DecisionKind;
import mtgai.decision.DecisionRequest;
import mtgai.match.GameRecord;
import mtgai.traj.TrajectoryWriter;

/**
 * Human seat: the local record of every game a {@link LobbyPlayerNeural} seat plays, so
 * testers can send their games back ({@code Send logs.cmd} zips the folder). One instance per {@link Game} (shared when
 * both seats are Neural), subscribed to the game's event bus; the game-over event appends ONE row to
 * {@value NeuralSeat#GAMES_FILE} and the game's buffered policy decisions to {@value NeuralSeat#DECISIONS_FILE}, both in
 * {@code -Dmtgai.neural.games} (default {@code userdata/mtgai-games}; empty = no files).
 *
 * <p>Row field names follow the self-play worker rows ({@code SelfPlay.row}: winner / first / turns / end / ms /
 * decisions / model_versions / decks / agents / version; {@code GameArchive.entry}: id) where they mean the same thing;
 * per-seat lists are in registered-player order (Forge's player 1 = index 0). Decision lines follow
 * {@code ops.DecisionLog} (game / seat / n / turn / phase / own_turn / kind / chosen). No personal data: the human
 * seat is recorded as {@code "human"}, never by name.
 *
 * <p>Logging must never break or slow a game: decisions are buffered in memory (one short string each), files are
 * written once at game over, and every failure is caught and reported on stderr.
 */
final class HumanGameLog {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Map<Game, HumanGameLog> OPEN = new WeakHashMap<>();
    private static final Object WRITE_LOCK = new Object();

    private final Game game;
    private final NeuralSeat seat;
    private final Path dir;
    private final String id;
    /** Consent marker created at game start ({@link GameUpload#markGame}); false = this game writes no files. */
    private final boolean marked;
    private final Instant started = Instant.now();
    private final long startNanos = System.nanoTime();
    /** Model decisions per player id. */
    private final Map<Integer, Integer> decisions = new HashMap<>();
    private final StringBuilder decisionLines = new StringBuilder();
    /** The Neural seats' trajectory records, by seat index (0 / 1). */
    private final Map<Integer, GameRecord> records = new HashMap<>();
    /** The human seat's captured decisions ({@link HumanSeatCapture}); null = none / capture off. */
    HumanSeatCapture.Seat human;
    private int n;
    private int firstPlayerId = -1;
    private boolean done;
    private boolean warned;

    private HumanGameLog(Game game, NeuralSeat seat) {
        this.game = game;
        this.seat = seat;
        final String d = System.getProperty(NeuralSeat.GAMES_PROPERTY, "userdata/mtgai-games");
        this.dir = d.isBlank() ? null : Path.of(d);
        this.id = ID_TIME.format(started) + "-" + String.format(Locale.ROOT, "%06x", ThreadLocalRandom.current().nextInt(1 << 24));
        this.marked = GameUpload.markGame(id);
    }

    /** The log of {@code game}, created and subscribed to its events on first use; null (and a stderr line) on failure. */
    static HumanGameLog forGame(Game game, NeuralSeat seat) {
        try {
            synchronized (OPEN) {
                HumanGameLog log = OPEN.get(game);
                if (log == null) {
                    log = new HumanGameLog(game, seat);
                    game.subscribeToEvents(log);
                    OPEN.put(game, log);
                }
                return log;
            }
        } catch (Throwable t) {
            System.err.println("[mtg-ai] game log disabled for this game: " + t);
            return null;
        }
    }

    /** The log of {@code game} if a Neural seat already opened one, else null (never creates). */
    static HumanGameLog existing(Game game) {
        synchronized (OPEN) {
            return OPEN.get(game);
        }
    }

    /** The trajectory record of the Neural seat {@code seatIndex}, written as a chunk at game over. */
    void addRecord(int seatIndex, GameRecord record) {
        synchronized (records) {
            records.put(seatIndex, record);
        }
    }

    /** One policy decision of the Neural seat of player {@code p}; {@code out} = the oracle's last forward (logits + value). */
    void onDecision(Player p, DecisionRequest req, int chosen, float[] out) {
        try {
            decisions.merge(p.getId(), 1, Integer::sum);
            float pChosen = Float.NaN;
            float pMax = Float.NaN;
            final int size = req.size();
            if (out != null && out.length == size + 1) {
                float max = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < size; i++) {
                    max = Math.max(max, out[i]);
                }
                double sum = 0;
                for (int i = 0; i < size; i++) {
                    sum += Math.exp(out[i] - max);
                }
                pChosen = (float) (Math.exp(out[chosen] - max) / sum);
                pMax = (float) (1.0 / sum);
            }
            final Candidate c = req.candidates().get(chosen);
            if (dir != null) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("game", id);
                m.put("seat", req.seat());
                m.put("n", n);
                m.put("turn", req.turn());
                m.put("phase", req.phase());
                m.put("own_turn", req.ownTurn());
                m.put("kind", req.kind().name());
                m.put("chosen", chosen);
                m.put("options", size);
                m.put("p", Float.isNaN(pChosen) ? null : round3(pChosen));
                m.put("p_max", Float.isNaN(pMax) ? null : round3(pMax));
                m.put("label", c.toString());
                decisionLines.append(GSON.toJson(m)).append('\n');
            }
            n++;
            // console: every model decision except passing priority (most of them, and the least interesting)
            if (seat.log() && !(req.kind() == DecisionKind.PRIORITY && c.type() == Candidate.Type.PASS)) {
                System.out.printf(Locale.ROOT, "[mtg-ai] turn %d %s -> %s%s (%d options)%n", req.turn(),
                        req.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '), c,
                        Float.isNaN(pChosen) ? "" : String.format(Locale.ROOT, ", p %.2f", pChosen), size);
            }
        } catch (Throwable t) {
            warn("decision", t);
        }
    }

    @Subscribe
    public void onTurn(GameEventTurnBegan e) {
        if (firstPlayerId < 0 && e.turnOwner() != null) {
            firstPlayerId = e.turnOwner().getId();
        }
    }

    @Subscribe
    public void onOutcome(GameEventGameOutcome e) {
        try {
            if (!done) {
                done = true;
                finish();
            }
        } catch (Throwable t) {
            warn("game", t);
        } finally {
            synchronized (OPEN) {
                OPEN.remove(game);
            }
        }
    }

    private void finish() throws IOException {
        final GameOutcome o = game.getOutcome();
        final List<Player> ps = game.getRegisteredPlayers();
        final List<String> agents = new ArrayList<>();
        final List<Long> versions = new ArrayList<>();
        final List<String> decks = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final List<Integer> mulligans = new ArrayList<>();
        int neuralSeat = -1;
        int winner = -1;
        int first = -1;
        for (int i = 0; i < ps.size(); i++) {
            final Player p = ps.get(i);
            final RegisteredPlayer rp = p.getRegisteredPlayer();
            final LobbyPlayer lp = rp.getPlayer();
            final String agent = agent(lp);
            agents.add(agent);
            final boolean neural = lp instanceof LobbyPlayerNeural;
            if (neural && neuralSeat < 0) {
                neuralSeat = i;
            }
            versions.add(neural ? seat.modelVersion() : 0L);
            decks.add(rp.getDeck() == null ? null : rp.getDeck().getName());
            counts.add(neural ? decisions.getOrDefault(p.getId(), 0) : null);
            mulligans.add(p.getStats().getMulliganCount());
            if (o != null && !o.isDraw() && o.getWinningPlayer() == rp) {
                winner = i;
            }
            if (p.getId() == firstPlayerId) {
                first = i;
            }
        }
        String opponent = null;
        for (int i = 0; i < agents.size(); i++) {
            if (i != neuralSeat) {
                opponent = agents.get(i);
                break;
            }
        }
        final Instant end = Instant.now();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ts", end.truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("started", started.truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("package", System.getProperty("mtgai.package"));
        m.put("version", seat.modelVersion());
        m.put("model_file", seat.modelFile());
        m.put("model_sha256", seat.modelSha256());
        m.put("encoder_version", seat.encoderVersion());
        m.put("greedy", seat.greedy());
        m.put("agents", agents);
        m.put("model_versions", versions);
        m.put("neural_seat", neuralSeat);
        m.put("opponent", opponent);
        m.put("decks", decks);
        m.put("winner", winner);
        m.put("first", first);
        m.put("turns", o == null ? game.getPhaseHandler().getTurn() : o.getLastTurnNumber());
        m.put("end", o == null ? "none" : String.valueOf(o.getWinCondition()));
        m.put("ms", (System.nanoTime() - startNanos) / 1_000_000);
        m.put("decisions", counts);
        m.put("mulligans", mulligans);
        if (human != null) {
            m.put("human_steps", human.record.steps(human.index).size());
            m.put("human_unmapped", human.unmapped);
            m.put("human_skipped", human.skipped);
        }
        m.put("game_type", String.valueOf(game.getRules().getGameType()));
        // Forge Table builds Constructed rules and applies Brawl as a variant, so game_type alone says "Constructed"
        m.put("format", game.getRules().hasAppliedVariant(GameType.Brawl) ? "brawl" : null);
        m.put("starting_life", ps.isEmpty() ? null : ps.get(0).getStartingLife());
        final String row = GSON.toJson(m) + "\n";

        String where = "not saved (-D" + NeuralSeat.GAMES_PROPERTY + " is empty)";
        // consent withdrawn at or since game start (router removed the marker's folder): write nothing at all
        final boolean keep = dir != null && marked && GameUpload.ownMarker(id);
        if (dir != null && !keep) {
            where = "not saved (upload consent withdrawn)";
        }
        if (keep) {
            synchronized (WRITE_LOCK) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(NeuralSeat.DECISIONS_FILE), decisionLines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.writeString(dir.resolve(NeuralSeat.GAMES_FILE), row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            decisionLines.setLength(0);
            where = "saved to " + dir.resolve(NeuralSeat.GAMES_FILE);
        }
        if (keep) {
            try {
                saveAndUpload(row, winner, versions, o == null);
            } catch (Throwable t) {
                warn("trajectory", t);
            }
        }
        final String result = winner < 0 ? "draw" : winner == neuralSeat ? "Neural won" : "Neural lost";
        System.out.printf(Locale.ROOT, "[mtg-ai] game over: %s (Neural = player %d, vs %s) after turn %s; game %s %s%n",
                result, neuralSeat + 1, opponent, m.get("turns"), id, where);
    }

    /**
     * Writes {@code traj/<id>.mtgt} (one chunk per Neural seat, via the self-play chunk code) and {@code traj/<id>.json}
     * (the games.jsonl row), each to a temp name then renamed, and queues the upload ({@link GameUpload}).
     * gameId = the first 8 bytes of SHA-256(id) as a big-endian long (stable, derived from the game id); workerId and
     * seed = 0; the engine / cardsfolder / deck-manifest hashes are zero (not available in the human-seat context).
     * The human seat has a chunk only with consent ({@link HumanSeatCapture#enabled()}): {@link HumanSeatCapture} records
     * its main decisions as NOT_POLICY | TEACHER | HUMAN steps (imitation targets).
     */
    private void saveAndUpload(String row, int winner, List<Long> versions, boolean truncated) throws Exception {
        if (!GameUpload.ID.matcher(id).matches()) {
            throw new IllegalStateException("game id " + id + " is not an intake file name");
        }
        final Path traj = dir.resolve("traj");
        Files.createDirectories(traj);
        final long gameId = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)))
                .getLong();
        final List<byte[]> chunks = new ArrayList<>();
        synchronized (records) {
            for (Map.Entry<Integer, GameRecord> e : new java.util.TreeMap<>(records).entrySet()) {
                final int s = e.getKey();
                final List<TrajectoryWriter.Step> raw = e.getValue().steps(s);
                if (raw.isEmpty()) {
                    continue;
                }
                final int outcome = winner < 0 ? 0 : winner == s ? 1 : -1;
                final long v0 = versions.size() > 0 ? versions.get(0) : 0;
                final long v1 = versions.size() > 1 ? versions.get(1) : 0;
                chunks.add(TrajectoryWriter.chunk(new TrajectoryWriter.Header(seat.encoderVersion(), s, outcome, 0,
                        gameId, 0, v0, v1, null, null, null), TrajectoryWriter.ended(raw, truncated, outcome)));
            }
        }
        if (!chunks.isEmpty()) {
            TrajectoryWriter.write(traj.resolve(id + ".mtgt"), chunks);
        }
        final Path json = traj.resolve(id + ".json");
        final Path tmp = traj.resolve(id + ".json.tmp");
        Files.writeString(tmp, row, StandardCharsets.UTF_8);
        Files.move(tmp, json, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        GameUpload.flushAsync(traj, build());
    }

    /** The {@code X-Mtgai-Build} value: package, model version and file, encoder version. */
    private String build() {
        return "package=" + System.getProperty("mtgai.package", "dev") + "; model=v" + seat.modelVersion() + " "
                + seat.modelFile() + "; encoder=" + seat.encoderVersion();
    }

    /** The agent name of a seat, in worker vocabulary: {@code onnx}, {@code forge-<profile>}, or {@code human}. */
    private static String agent(LobbyPlayer lp) {
        if (lp instanceof LobbyPlayerNeural) {
            return "onnx";
        }
        if (lp instanceof LobbyPlayerAi ai) {
            final String profile = ai.getAiProfile();
            return "forge-" + (profile == null || profile.isEmpty() ? "default" : profile.toLowerCase(Locale.ROOT));
        }
        return "human";
    }

    private static double round3(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private void warn(String what, Throwable t) {
        if (!"decision".equals(what) || !warned) {
            warned |= "decision".equals(what);
            System.err.println("[mtg-ai] could not log the " + what + " (the game goes on): " + t);
        }
    }
}
