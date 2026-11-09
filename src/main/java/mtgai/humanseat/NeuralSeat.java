package mtgai.humanseat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.Player;
import mtgai.controller.PlayerControllerExternal;
import mtgai.controller.SeatStats;
import mtgai.encode.CardVocab;
import mtgai.encode.ObservationEncoder;
import mtgai.match.GameRecord;
import mtgai.match.Watchdog;
import mtgai.onnx.ModelSource;
import mtgai.onnx.OnnxOracle;

/**
 * Human seat: the pinned model + card vocab the {@link LobbyPlayerNeural} seat plays with, loaded once per
 * JVM from {@code -Dmtgai.neural.dir} (default {@code neural}, relative to Forge's working directory), laid out like a
 * worker's model directory: {@code latest.json} ({version, file, sha256, encoder_version}) + {@code policy-NNNNNN.onnx},
 * plus {@code registry.tsv} (the all-cards ids; else {@code cardvocab-v1.txt}) with the archetype deck set next to it
 * ({@value mtgai.encode.ArchetypeClassifier#DECK_SET}/).
 *
 * <p>Each game gets a fresh {@link PlayerControllerExternal} exactly as in self-play (collapsed priority exposure,
 * {@link OnnxOracle}; greedy by default like eval games, {@code -Dmtgai.neural.greedy=false} samples) but with no caps:
 * a human game has no turn / decision / wall limit. {@code -Dmtgai.neural.log=true} prints the seat's policy decisions
 * (except passing priority) and a game-over line on the console. Every game is logged by {@link HumanGameLog} to
 * {@code -Dmtgai.neural.games} (default {@code userdata/mtgai-games}).
 */
public final class NeuralSeat {
    public static final String DIR_PROPERTY = "mtgai.neural.dir";
    public static final String VOCAB_FILE = "cardvocab-v1.txt";
    /** The all-cards (encoder 9) registry; preferred over {@link #VOCAB_FILE} when the directory has it. */
    public static final String REGISTRY_FILE = "registry.tsv";
    /** Directory for the per-game rows + decision lines; empty = don't write files. */
    public static final String GAMES_PROPERTY = "mtgai.neural.games";
    public static final String GAMES_FILE = "games.jsonl";
    public static final String DECISIONS_FILE = "decisions.jsonl";

    private static NeuralSeat instance;
    /** Policy decisions answered by the model since JVM start (all games; forced single answers excluded). */
    public static final AtomicLong DECISIONS = new AtomicLong();

    private final OnnxOracle.Model model;
    private final CardVocab vocab;
    private final boolean greedy;
    private final boolean log;
    private final Path dir;
    private final String file;
    private final String sha256;

    private NeuralSeat(Path dir) throws IOException {
        this.dir = dir;
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("no neural model directory " + dir.toAbsolutePath() + " (-D" + DIR_PROPERTY + ")");
        }
        final ModelSource src = new ModelSource.Dir(dir);
        final JsonObject meta = src.latest();
        if (meta == null) {
            throw new IllegalStateException("no latest.json in " + dir.toAbsolutePath());
        }
        this.model = src.load(meta, ObservationEncoder.ENCODER_VERSION);
        this.vocab = CardVocab.load(dir.resolve(vocabFile(dir)));
        this.greedy = Boolean.parseBoolean(System.getProperty("mtgai.neural.greedy", "true"));
        this.log = Boolean.getBoolean("mtgai.neural.log");
        this.file = meta.get("file").getAsString();
        this.sha256 = meta.has("sha256") ? meta.get("sha256").getAsString() : null;
        if (!System.getProperty(GAMES_PROPERTY, "userdata/mtgai-games").isBlank()) {
            // deliver games a previous run could not send (no-op unless -Dmtgai.neural.upload is set); never blocks
            GameUpload.flushAsync(Path.of(System.getProperty(GAMES_PROPERTY, "userdata/mtgai-games"), "traj"),
                    "package=" + System.getProperty("mtgai.package", "dev") + "; model=v" + model.version() + " " + file
                            + "; encoder=" + ObservationEncoder.ENCODER_VERSION);
        }
        System.out.printf(Locale.ROOT, "[mtg-ai] neural seat: model v%d (%s, sha256 %s), encoder %d, %s, from %s%n",
                model.version(), file, sha256 == null ? "?" : sha256.substring(0, Math.min(12, sha256.length())),
                ObservationEncoder.ENCODER_VERSION, greedy ? "greedy" : "sampled", dir.toAbsolutePath());
    }

    /** The card id file of a seat directory: the all-cards registry if shipped (Brawl, commanders), else the v1 vocab. */
    static String vocabFile(Path dir) {
        return Files.isRegularFile(dir.resolve(REGISTRY_FILE)) ? REGISTRY_FILE : VOCAB_FILE;
    }

    /** The JVM's seat data, loaded on first use; throws (with the reason) if the model or vocab cannot be loaded. */
    public static synchronized NeuralSeat get() {
        if (instance == null) {
            try {
                instance = new NeuralSeat(Path.of(System.getProperty(DIR_PROPERTY, "neural")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return instance;
    }

    public long modelVersion() {
        return model.version();
    }

    public Path dir() {
        return dir;
    }

    public String modelFile() {
        return file;
    }

    public String modelSha256() {
        return sha256;
    }

    /** The loaded model's encoder_version (an encoder-9 model on this encoder-10 jar: 9). */
    public int encoderVersion() {
        return model.encoder();
    }

    public boolean greedy() {
        return greedy;
    }

    /** Whether the console shows the seat's decisions ({@code -Dmtgai.neural.log=true}). */
    public boolean log() {
        return log;
    }

    public CardVocab vocab() {
        return vocab;
    }

    /** A fresh policy controller for {@code p} in {@code game} (one per game), as seat {@code seat} (0 or 1). */
    public PlayerControllerExternal controller(Game game, Player p, LobbyPlayer lobby, int seat) {
        try {
            HumanSeatCapture.installIfEnabled(); // upload consent or -Dmtgai.neural.captureHuman: the human's picks become steps
        } catch (LinkageError e) {
            // a forge build without HumanDecisionObserver (older mvn install): the Neural seat plays on, no capture
            System.err.println("[mtgai] human-seat capture unavailable: " + e);
        }
        final OnnxOracle oracle = new OnnxOracle(model, vocab, 0).greedy(greedy);
        oracle.onGameStart(System.nanoTime(), seat); // the seed only drives sampling (-Dmtgai.neural.greedy=false)
        final HumanGameLog gameLog = HumanGameLog.forGame(game, this);
        final GameRecord record = new GameRecord((req, chosen) -> {
            DECISIONS.incrementAndGet();
            if (gameLog != null) {
                gameLog.onDecision(p, req, chosen, oracle.lastOutput());
            }
        });
        if (gameLog != null) {
            gameLog.addRecord(seat, record);
        }
        return new PlayerControllerExternal(game, p, lobby, oracle, seat, PlayerControllerExternal.Exposure.COLLAPSED,
                new Watchdog(0, 0, 0), record, new SeatStats(), // encoder 8 groups: -Dmtgai.control (unset = none)
                mtgai.controller.Control.fromSystemProperty());
    }
}
