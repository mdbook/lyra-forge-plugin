package mtgai.match;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.util.MyRandom;
import mtgai.bootstrap.ForgeBootstrap;
import mtgai.controller.LobbyPlayerExternal;
import mtgai.controller.PlayerControllerExternal;
import mtgai.controller.SeatStats;
import mtgai.decision.DecisionOracle;

/**
 * Runs one seeded two-player game headless: two decks, two seats (external policy or stock
 * AI), turn/decision/wall caps enforced cooperatively on the game thread ({@link Watchdog} /
 * {@link MatchAbort}), a dedicated game thread with a large stack, a crash bundle on any exception or
 * cap, and the outcome + full GameLog.
 *
 * <p>Determinism: {@code MyRandom} is thread-local (upstream Forge PR 11285) and is seeded on the game thread
 * with the game's seed, a fresh {@link Match} per game, so a game is a pure function of (engine,
 * card data, decks, seed, oracle answers).
 */
public final class HeadlessMatch {
    private HeadlessMatch() {
    }

    /** One seat: display name of the agent, and a factory for its oracle (null = stock Forge AI). */
    public record Seat(String agent, Deck deck, String deckName, Supplier<DecisionOracle> oracle) {
    }

    public static final class Config {
        public long seed = 1;
        public Seat[] seats = new Seat[2];
        public int turnCap = 60;
        public int decisionCap = 20_000;
        public long wallCapMillis = 120_000;
        public PlayerControllerExternal.Exposure exposure = PlayerControllerExternal.Exposure.COLLAPSED;
        /** Encoder 8 decision groups the external seats own (default: system property {@code mtgai.control}, unset = none). */
        public mtgai.controller.Control control = mtgai.controller.Control.fromSystemProperty();
        public long stackBytes = 8L << 20;
        /** null: no crash bundles. */
        public Path crashDir;
        /** Write a bundle when a turn cap truncates the game (true in eval/bench: caps are rare there). */
        public boolean bundleOnTurnCap = true;
        public GameRecord.Listener listener;
        /** Pre-built oracles (e.g. ScriptedOracle) override seats[i].oracle(). */
        public DecisionOracle[] oracles;
        /** Optional start state (a .pzl [state] section), applied after mulligans. */
        public FixtureState fixture;
        /** For the crash bundle / game record: model version per seat (0 = non-model), game id, fork commit. */
        public long[] modelVersions = {0, 0};
        public String gameId;
        /** Forge fork commit (hex); null = {@code -Dmtgai.engineCommit}. */
        public String engineCommit;
        /** Per seat handicapped start ({@link Handicap}); null = none (the default: both seats play the normal game). */
        public Handicap[] handicaps = {null, null};
        /** Diagnostics / tests: called with the created game before it starts, on the caller's thread (null = off). */
        public java.util.function.Consumer<Game> onGame;
        /**
         * Forge game type (null = Constructed, 20 life). Commander / Brawl / TinyLeaders / Oathbreaker: each deck's
         * [Commander] section starts in the command zone and the starting life follows Forge's variant rules
         * ({@code RegisteredPlayer.forVariants}: Commander 40, Brawl 25 in a two-player game). Null rather than
         * {@code GameType.Constructed} so that building a Config does not initialise {@code GameType} (its
         * constructor reads the Localizer) before {@code ForgeBootstrap} has run.
         */
        public GameType gameType;
    }

    public static final class Result {
        public long seed;
        public String[] agents = new String[2];
        public String[] decks = new String[2];
        /** -1 = draw / truncated. */
        public int winnerSeat = -1;
        public int firstSeat = -1;
        public int turns;
        public String endReason;
        public MatchAbort.Reason abort;
        public String exception;
        public long wallMillis;
        public SeatStats[] stats = {new SeatStats(), new SeatStats()};
        public List<String> log = new ArrayList<>();
        public GameRecord record;
        public Path crashBundle;
        public int decisions;
        /** Per seat: names of the spells it cast (GameLog "Add To Stack: X cast NAME"), for the dead-card share. */
        @SuppressWarnings("unchecked")
        public java.util.Set<String>[] cast = new java.util.Set[] {new java.util.TreeSet<>(), new java.util.TreeSet<>()};
        /** normalised GameLog sha256, when the caller computed it (bench --rows). */
        public String logHash;
        /** Non-null when the game thread wedged (no decision past the join timeout) and was left running. */
        public Thread wedgedThread;

        public boolean clean() {
            return exception == null && abort == null;
        }

        public long activationFailures() {
            return stats[0].activationFailures + stats[1].activationFailures;
        }
    }

    public static Result play(Config cfg) {
        ForgeBootstrap.get();
        final GameType gameType = cfg.gameType == null ? GameType.Constructed : cfg.gameType;
        final Result res = new Result();
        res.seed = cfg.seed;
        final Watchdog watchdog = new Watchdog(cfg.turnCap, cfg.decisionCap, cfg.wallCapMillis);
        final GameRecord record = new GameRecord(cfg.listener);
        res.record = record;

        final List<RegisteredPlayer> players = new ArrayList<>();
        final LobbyPlayerExternal[] lobby = new LobbyPlayerExternal[2];
        final DecisionOracle[] oracles = new DecisionOracle[2];
        for (int i = 0; i < 2; i++) {
            final Seat s = cfg.seats[i];
            res.agents[i] = s.agent();
            res.decks[i] = s.deckName();
            oracles[i] = cfg.oracles != null && cfg.oracles[i] != null ? cfg.oracles[i]
                    : s.oracle() == null ? null : s.oracle().get();
            // stock-AI seat named forge-cautious / -reckless / -experimental: that Forge personality; else "Default"
            lobby[i] = new LobbyPlayerExternal("s" + i + "-" + s.agent(), oracles[i], i, cfg.exposure, watchdog,
                    record, res.stats[i], oracles[i] == null ? LobbyPlayerExternal.profileFor(s.agent()) : "Default");
            lobby[i].control(cfg.control);
            final Handicap h = cfg.handicaps == null ? null : cfg.handicaps[i];
            lobby[i].handicap(h); // MULL6 / DRAW: answered by the seat's controller
            final Deck deck = new Deck(s.deck(), s.deck().getName());
            final RegisteredPlayer rp = gameType == GameType.Constructed ? new RegisteredPlayer(deck)
                    : RegisteredPlayer.forVariants(2, EnumSet.of(gameType), deck, null, false, null, null);
            if (h == Handicap.LIFE14) {
                rp.setStartingLife(Handicap.LIFE);
            }
            rp.setPlayer(lobby[i]);
            players.add(rp);
        }

        final GameRules rules = new GameRules(gameType);
        rules.setAppliedVariants(EnumSet.of(gameType));
        rules.setGamesPerMatch(1);
        final Match match = new Match(rules, players, "mtgai");
        final Game game = match.createGame();
        game.setNoGUIUser();
        if (cfg.onGame != null) {
            cfg.onGame.accept(game);
        }

        final Throwable[] thrown = new Throwable[1];
        final long t0 = System.nanoTime();
        final Thread t = new Thread(null, () -> {
            MyRandom.setRandom(new Random(cfg.seed));
            for (int i = 0; i < 2; i++) {
                if (oracles[i] != null) {
                    oracles[i].onGameStart(cfg.seed, i);
                }
            }
            try {
                if (cfg.fixture != null) {
                    match.startGame(game, () -> cfg.fixture.apply(game));
                } else {
                    match.startGame(game);
                }
            } catch (Throwable e) {
                thrown[0] = e;
            }
        }, "Game-mtgai-" + cfg.seed, cfg.stackBytes); // "Game" prefix: ThreadUtil.isGameThread()
        t.setDaemon(true);
        t.start();
        try {
            // cooperative caps end the game from the inside; this join timeout only catches a wedged engine
            t.join(cfg.wallCapMillis > 0 ? cfg.wallCapMillis * 2 + 10_000 : 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        res.wallMillis = (System.nanoTime() - t0) / 1_000_000;
        if (t.isAlive()) {
            res.wedgedThread = t;
            thrown[0] = new IllegalStateException("game thread wedged (no decision for "
                    + res.wallMillis + " ms); thread left running");
        }

        final Throwable e = thrown[0];
        if (e instanceof MatchAbort a) {
            res.abort = a.reason();
        } else if (e != null) {
            res.exception = e.toString();
        }
        if (!game.isGameOver()) {
            game.setGameOver(GameEndReason.Draw);
        }
        final GameOutcome outcome = game.getOutcome();
        res.turns = game.getPhaseHandler().getTurn();
        res.endReason = res.abort != null ? "abort:" + res.abort : e != null ? "exception"
                : outcome == null ? "none" : String.valueOf(outcome.getWinCondition());
        if (outcome != null && !outcome.isDraw() && res.abort == null && e == null) {
            final String winner = outcome.getWinningLobbyPlayer().getName();
            res.winnerSeat = winner.equals(lobby[0].getName()) ? 0 : winner.equals(lobby[1].getName()) ? 1 : -1;
        }
        final Player first = game.getPhaseHandler().getPlayerTurn() == null ? null : firstPlayer(game);
        if (first != null) {
            res.firstSeat = first.getName().equals(lobby[0].getName()) ? 0 : 1;
        }
        for (GameLogEntry le : game.getGameLog().getAllEntries()) {
            // the forced draw after an abort credits a random seat in "Match Result:"; not part of the game
            if (res.abort != null && le.toString().startsWith("Match Result:")) {
                continue;
            }
            res.log.add(le.toString());
            for (int i = 0; i < 2; i++) {
                final String pre = "Add To Stack: " + lobby[i].getName() + " cast ";
                final String l = le.toString();
                if (l.startsWith(pre)) {
                    final int tg = l.indexOf(" targeting ", pre.length());
                    res.cast[i].add(tg < 0 ? l.substring(pre.length()) : l.substring(pre.length(), tg));
                }
            }
        }
        res.decisions = record.actions().size();

        final boolean bundle = e != null && (!(e instanceof MatchAbort a) || a.reason() != MatchAbort.Reason.TURN_CAP
                || cfg.bundleOnTurnCap);
        if (bundle && cfg.crashDir != null) {
            res.crashBundle = writeCrashBundle(cfg, res, e);
        }
        return res;
    }

    private static Player firstPlayer(Game game) {
        // the player who took turn 1: GameLog's first TURN entry names them; fall back to registered order
        for (GameLogEntry le : game.getGameLog().getAllEntries()) {
            if (le.type() == forge.game.GameLogEntryType.TURN) {
                for (Player p : game.getRegisteredPlayers()) {
                    if (le.message().contains(p.getName())) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ crash bundle

    static Path writeCrashBundle(Config cfg, Result res, Throwable e) {
        try {
            Files.createDirectories(cfg.crashDir);
            final String kind = e instanceof MatchAbort a ? a.reason().name().toLowerCase() : "exception";
            final Path f = cfg.crashDir.resolve((cfg.gameId == null ? "" : cfg.gameId + "-") + "seed" + cfg.seed + "-" + kind
                    + "-" + System.currentTimeMillis() + ".txt");
            final StringBuilder sb = new StringBuilder();
            sb.append("# mtg-ai crash bundle\n");
            sb.append("game: ").append(cfg.gameId == null ? "-" : cfg.gameId).append('\n');
            sb.append("seed: ").append(cfg.seed).append('\n');
            sb.append("reason: ").append(e).append('\n');
            sb.append("engine_commit: ").append(cfg.engineCommit != null && !cfg.engineCommit.isEmpty() ? cfg.engineCommit
                    : System.getProperty("mtgai.engineCommit", "unknown")).append('\n');
            sb.append("model_versions: ").append(cfg.modelVersions[0]).append(' ').append(cfg.modelVersions[1])
                    .append("  (0 = non-model seat)\n");
            sb.append("res: ").append(ForgeBootstrap.get().res()).append('\n');
            sb.append("caps: turns=").append(cfg.turnCap).append(" decisions=").append(cfg.decisionCap)
                    .append(" wallMs=").append(cfg.wallCapMillis).append(" exposure=").append(cfg.exposure).append('\n');
            sb.append("turns: ").append(res.turns).append("  wall_ms: ").append(res.wallMillis).append('\n');
            for (int i = 0; i < 2; i++) {
                sb.append("seat ").append(i).append(": agent=").append(res.agents[i]).append(" deck=")
                        .append(res.decks[i]).append('\n');
                final Deck d = cfg.seats[i].deck();
                for (Map.Entry<PaperCard, Integer> en : d.getMain()) {
                    sb.append("    ").append(en.getValue()).append(' ').append(en.getKey().getName()).append('\n');
                }
            }
            sb.append("\n## action-index log (seat kind chosen/candidates), ")
                    .append(res.record.actions().size()).append(" decisions\n");
            for (int i = 0; i < 2; i++) {
                sb.append("seat").append(i).append(": ").append(res.record.script(i)).append('\n');
            }
            for (GameRecord.Action a : res.record.actions()) {
                sb.append(a).append('\n');
            }
            sb.append("\n## last 200 GameLog lines\n");
            final List<String> log = res.log;
            for (String l : log.subList(Math.max(0, log.size() - 200), log.size())) {
                sb.append(l).append('\n');
            }
            sb.append("\n## stack trace\n");
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
            if (res.wedgedThread != null) { // the useful stack is the stuck game thread's, not the watcher's
                sb.append("\n## wedged game thread ").append(res.wedgedThread.getName()).append(" (state ")
                        .append(res.wedgedThread.getState()).append(")\n");
                for (StackTraceElement el : res.wedgedThread.getStackTrace()) {
                    sb.append("\tat ").append(el).append('\n');
                }
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
            return f;
        } catch (IOException io) {
            System.err.println("crash bundle write failed: " + io);
            return null;
        }
    }

    // ------------------------------------------------------------------ determinism

    private static final Pattern IDS = Pattern.compile("\\(\\d+\\)|\\[\\d+\\]|#\\d+");

    /** GameLog lines with card/object ids stripped (for golden hashes). */
    public static List<String> normalise(List<String> log) {
        final List<String> out = new ArrayList<>(log.size());
        for (String l : log) {
            out.add(IDS.matcher(l).replaceAll(""));
        }
        return out;
    }

    public static String hash(List<String> lines) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String l : lines) {
                md.update(l.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
