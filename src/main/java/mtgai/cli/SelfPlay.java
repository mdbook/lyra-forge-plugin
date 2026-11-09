package mtgai.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import mtgai.bootstrap.ForgeBootstrap;
import mtgai.controller.LobbyPlayerExternal;
import mtgai.decision.DecisionOracle;
import mtgai.decision.RandomOracle;
import mtgai.decision.TeacherOracle;
import mtgai.encode.CardVocab;
import mtgai.encode.ObservationEncoder;
import mtgai.match.Handicap;
import mtgai.match.HeadlessMatch;
import mtgai.onnx.ModelSource;
import mtgai.onnx.OnnxOracle;
import mtgai.ops.GameArchive;
import mtgai.traj.TrajectoryWriter;
import mtgai.transport.TrajectorySink;

/**
 * Self-play worker ({@code mtgai selfplay}): plays seeded games with {@link OnnxOracle} and writes one {@code .mtgt}
 * file per game (a chunk per ONNX seat) to {@code --out}. {@code --vs random}: the ONNX policy plays one seat
 * (alternating) against {@link RandomOracle} (evaluation; trajectories only with {@code --out}). Every game appends
 * one JSON row to {@code --eval-out}. Stops at {@code --max-games} or when {@code <models>/STOP} exists.
 *
 * <p>{@code --threads N} game threads in one JVM, concurrent B=1 {@code session.run} (no batcher; measured
 * slower). {@code --threads} also takes a per-host map ({@code hostA=6,hostB=4,*=1})
 * so one shared config serves every host; {@code {host}} and {@code {worker}} in path options are
 * substituted.
 *
 * <p>Model reload rule: a background thread polls {@code --models/latest.json} and loads a new version
 * into a fresh ONNX session off the game threads; a game takes the current session when it starts and keeps it to
 * the end (reference-counted, the old session closes after its last game). At most {@code --games-per-version}
 * games start on one version per JVM; then game threads wait for the learner's next export.
 *
 * <p>Determinism: game {@code g} (claimed in order from one counter) has seed {@code seed-base + g}, the oracle
 * RNG is seeded from (seed, seat, worker id), so a game's log and bytes do not depend on the thread count
 * (tested). The counter resumes after the largest {@code game} in an existing
 * {@code --eval-out} (a restarted worker does not replay seeds; {@code --game-start} overrides).
 *
 * <p>Network transport: {@code --out tcp://LEARNER:5555} pushes each game's bytes to the learner's ZMQ
 * PULL instead of writing a file ({@link TrajectorySink}); {@code --models} also takes an {@code http(s)://} base
 * ({@link ModelSource}); {@code --metrics-port}, {@code --records}, {@code --decision-log}, {@code --max-wedged}:
 * {@link SelfPlayOps}.
 *
 * <p>Opponents (curriculum RandomOracle -> Forge Default -> OSFP pool): the learner names them in
 * {@code latest.json} as {@code "opponents": [{"name", "p", "train"?, "file"?, "sha256"?, "version"?}, ...]}; each game
 * draws one from the game seed (weights {@code p}). {@code self} = the current model in both seats (both recorded);
 * {@code random} / {@code forge-default} (stock AI; run the JVM with {@code -Dforge.mtgai.aiCastRemAIDeck=true},
 * which {@code Main} pins for selfplay) / {@code forge-cautious} / {@code forge-reckless} / {@code forge-experimental}
 * (the stock AI with that Forge profile, res/ai/*.ai; the row's {@code opponent} and {@code agents} carry the name) /
 * a frozen pool model ({@code file} + {@code sha256}, loaded by the background
 * loader) play the other seat, the current model takes seat {@code g % 2} and only its seat is recorded. No
 * {@code opponents} = {@code self}; {@code --vs random} overrides the list. {@code train: false} marks evaluation games:
 * they are played and reported the same, the learner only counts them. The row ({@code --eval-out}) says who played
 * ({@code opponent}, {@code learner_seat}, {@code model_versions}, {@code train}) and travels with the trajectory as the
 * ZMQ message's middle frame ({@link TrajectorySink#accept(String, List, String)}).
 *
 * <p>Evaluation games are played greedy (argmax, {@link OnnxOracle#greedy(boolean)}) by every ONNX seat, so win rates
 * vs a fixed opponent measure the policy's mode, not its sampling noise (+8 pp measured);
 * {@code --eval-greedy false} samples them like training games. The row carries {@code greedy}.
 *
 * <p>Handicapped starts (learner {@code --p-handicap}): an {@code opponents} entry may carry {@code "handicap": "life14" |
 * "mull6" | "draw"} ({@link Handicap}); the learner's seat of that game starts handicapped (a {@code self} game: seat
 * {@code g % 2}). The row carries {@code handicap} (null = none) and {@code handicap_seat} (-1). An entry whose
 * handicap this worker does not know is skipped; a worker older than this ignores the key and plays the game plain
 * (its row has no {@code handicap}: plain, which is what it was).
 *
 * <p>Imitation teacher games (learner {@code --p-teacher}): an entry with {@code "teacher":
 * "forge-default"} puts {@link TeacherOracle} (Forge Default through the seat's own decision surface) in the learner's
 * seat {@code g % 2} (a {@code self} entry too: the other seat is then the current model, played but not recorded).
 * Only the teacher's seat is recorded: its steps are NOT_POLICY, TEACHER where the answer is Forge's mapped choice, and
 * its header model version is 0 (a non-model seat). The row carries {@code teacher} (null = none), {@code teacher_seat}
 * (-1), and per seat {@code teacher_labels} and {@code delegate_misses}. An unknown teacher is skipped; a worker older
 * than this ignores the key and plays the entry as a normal game (its row has no {@code teacher}: normal, which is
 * what it was).
 *
 * <p>Other opponent decks (learner {@code --opp-decks}): a stock-AI entry named
 * {@code <forge agent>@<deck>} ({@code forge-default@blue-terror}) plays that agent (its AI profile) with
 * {@code <deck>.dck} from {@code --opp-deck-dir} (default: the directory of the first {@code -d} deck) in the opponent's
 * seat; the model keeps seat {@code g % 2} with its own deck. The row carries the full name in {@code opponent} and
 * {@code agents}, the real deck in {@code decks}, and {@code opp_deck} (null = none); the trajectory header's deck sha
 * is that of the two decks actually played. An entry whose deck does not load is dropped (logged once); a worker older
 * than this does not know the name and skips the entry (its share goes to the others).
 *
 * <p>Own decks (learner {@code --own-decks}): an entry with {@code "own_deck": "<deck>"} puts
 * {@code <deck>.dck} (the same loader and directory as {@code @<deck>}) in the learner's seat {@code g % 2}; the other
 * seat keeps its usual deck ({@code <deck2>} for an {@code <agent>@<deck2>} entry). A {@code self} entry: both seats
 * pilot it (a mirror). A frozen pool model keeps its usual deck; the current model's seat pilots {@code <deck>}. The row
 * carries {@code own_deck} (null = none) and {@code own_deck_seat} ({@code g % 2}; -1 = none); {@code decks}, the
 * trajectory header's deck sha and the game record ({@code replay}) name the decks actually played. A deck that does
 * not load drops the key (logged once) and the entry is played plain; so does a teacher entry's (a teacher game never
 * pilots another deck). A worker older than this ignores the key and plays the entry plain (its row has no
 * {@code own_deck}: plain, which is what it was). Handicaps combine with it.
 *
 * <p>Deck sets (learner {@code --deck-set}): with
 * {@code --deck-set <manifest.json>} (the learner's manifest; {@code file} paths relative to it) an entry named
 * {@code <forge agent>@*} plays a two-level draw from the game seed: the learner's seat {@code g % 2} pilots a deck drawn
 * from latest.json {@code own_pool} ({@code {"<deck id>": weight, ...}}), the stock AI one drawn from {@code opp_pool}.
 * A deck that is held out in the worker's manifest, not in it, fails its manifest sha256 or does not load is dropped
 * from the tables (logged once); with no usable deck in either table, or without {@code --deck-set}, the entry is
 * dropped (its share goes to the others). The row carries {@code own_deck} / {@code own_deck_seat} / {@code opp_deck}
 * as the decks actually played; /metrics counts these games under the deck labels {@code deckset}. A worker older than
 * this does not know the name, skips the entry and ignores the tables. A frozen pool model's entry named
 * {@code v<version>@*} (learner {@code --milestone-deck-set}) draws the same way, the model piloting the opp_pool deck;
 * a worker older than that plays it as a plain pool game (its row has no {@code own_deck}).
 *
 * <p>Search targets ({@link SearchTargets}): with
 * {@code --search-agent gumbel-<n>[-m<k>] --search-frac f} the recorded model seats of training games search a seeded
 * fraction f of their copy points; a searched step is SEARCHED | NOT_POLICY with pi' (schema 3 chunk), every other step
 * is the onnx seat's. The row carries {@code searched} and {@code search_ms} per seat (only with the flags on); /metrics
 * {@code mtgai_search_decisions_total} and {@code mtgai_search_seconds_total}. Flags off: bytes as before.
 */
final class SelfPlay {
    private SelfPlay() {
    }

    static int run(Main.Args a) throws Exception {
        final String host = hostName();
        final int worker = a.intOr("--worker-id", 0);
        // --models DIR or http(s)://BASE/ (ModelSource); --out DIR or tcp://LEARNER:5555 (TrajectorySink)
        final ModelSource models = ModelSource.open(sub(a.req("--models"), host, worker));
        final TrajectorySink out = a.get("--out") == null ? null : TrajectorySink.open(sub(a.get("--out"), host, worker));
        final String vocabPath = sub(a.req("--vocab"), host, worker);
        final CardVocab vocab = CardVocab.load(Path.of(vocabPath));
        final String[] decks = a.req("-d").split(",");
        for (int i = 0; i < decks.length; i++) {
            decks[i] = sub(decks[i], host, worker);
        }
        final int perVersion = a.intOr("--games-per-version", 32);
        final long maxGames = a.longOr("--max-games", Long.MAX_VALUE);
        final long seedBase = a.longOr("--seed-base", 1_000_000L * (worker + 1));
        final boolean vsRandom = "random".equals(a.get("--vs"));
        final boolean evalGreedy = Boolean.parseBoolean(a.getOr("--eval-greedy", "true"));
        final Path rows = a.get("--eval-out") == null ? null : Path.of(sub(a.get("--eval-out"), host, worker));
        final int threads = threads(a.getOr("--threads", "1"), host);
        final String crashDir = a.get("--crash-dir") == null ? null : sub(a.get("--crash-dir"), host, worker);
        if (rows != null && rows.getParent() != null) {
            Files.createDirectories(rows.getParent());
        }
        final long gameStart = a.longOr("--game-start", resumeAfter(rows));
        final SearchTargets st = SearchTargets.of(a); // null = off (validated here: a bad flag fails at startup)
        if (st != null) {
            System.out.println("selfplay: search targets " + st);
        }

        final HeadlessMatch.Seat[] seats = new HeadlessMatch.Seat[2];
        final String[] deckTexts = new String[2];
        for (int i = 0; i < 2; i++) {
            final Path p = Path.of(decks[i]);
            seats[i] = new HeadlessMatch.Seat("onnx", Main.loadDeck(p), Main.deckName(p), null);
            deckTexts[i] = Files.readString(p);
        }
        final byte[] deckSha = manifestSha(deckTexts[0], deckTexts[1]);
        // learner --opp-decks: "forge-default@<deck>" pilots <deck>.dck from here (default: the -d decks' snapshot dir)
        final OppDecks oppDecks = new OppDecks(Path.of(sub(a.getOr("--opp-deck-dir",
                String.valueOf(Path.of(decks[0]).toAbsolutePath().getParent())), host, worker)), deckTexts);
        // learner --deck-set: "<agent>@*" entries draw both decks from latest.json's own_pool / opp_pool (null = dropped)
        final DeckSet deckSet = a.get("--deck-set") == null ? null
                : DeckSet.load(Path.of(sub(a.get("--deck-set"), host, worker)), deckTexts);
        final Path zip = ForgeBootstrap.get().res().resolve("cardsfolder/cardsfolder.zip");
        final byte[] cardsSha = Files.exists(zip) ? MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)) : new byte[32];
        final String commit = a.getOr("--engine-commit", System.getProperty("mtgai.engineCommit", ""));
        final byte[] engine = commit.length() == 40 ? HexFormat.of().parseHex(commit) : new byte[20];

        System.out.printf(Locale.ROOT, "selfplay: host %s worker %d, %d threads, games from %d (seed %d), %d/version%n",
                host, worker, threads, gameStart, seedBase + gameStart, perVersion);
        final Source src = new Source(models, perVersion, gameStart, maxGames, a.intOr("--poll-ms", 200), seedBase,
                vsRandom ? List.of(Opponent.RANDOM) : null, oppDecks, deckSet);
        final SelfPlayOps ops = new SelfPlayOps(a, host, worker, src, out);
        final java.util.concurrent.atomic.AtomicLong searchedTotal = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong searchNanosTotal = new java.util.concurrent.atomic.AtomicLong();
        if (st != null) {
            ops.metrics.register("search_decisions_total", "counter", "decisions answered by the search (SEARCHED steps)",
                            searchedTotal::get)
                    .register("search_seconds_total", "counter", "time in training-mode search past the root forward",
                            () -> searchNanosTotal.get() / 1e9);
        }
        final Thread loader = new Thread(src::loadLoop, "mtgai-model-loader");
        loader.setDaemon(true);
        loader.start();

        final Object rowLock = new Object();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger played = new AtomicInteger();
        final long w0 = System.nanoTime();
        final List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final Thread th = new Thread(() -> {
                try {
                    for (Claim cl; (cl = src.claim()) != null; ) {
                        try {
                            final long g = cl.game;
                            final long v = cl.model.model.version();
                            final HeadlessMatch.Config c = Main.baseConfig(a);
                            if (crashDir != null) {
                                c.crashDir = Path.of(crashDir);
                            }
                            c.seed = seedBase + g;
                            c.bundleOnTurnCap = false;
                            c.gameId = GameArchive.id(worker, g);
                            c.engineCommit = commit;
                            final Opponent opp = cl.opp;
                            final boolean self = opp.self();
                            // imitation teacher game: the teacher takes the learner's seat (a self entry too)
                            final boolean teach = opp.teacher() != null;
                            final int learnerSeat = self && !teach ? -1 : (int) (g % 2);
                            // learner --deck-set: both decks drawn at claim time from this version's tables
                            final OppDecks.Entry[] ds = cl.decks();
                            // another deck in the opponent's seat (learner --opp-decks; the loader kept only loaded ones)
                            final OppDecks.Entry od = ds != null ? ds[1]
                                    : opp.oppDeck() == null ? null : oppDecks.get(opp.oppDeck());
                            if (opp.oppDeck() != null && od == null) {
                                throw new IllegalStateException("opponent deck " + opp.oppDeck() + " not loaded");
                            }
                            final int odSeat = od == null ? -1 : 1 - learnerSeat;
                            // the learner's seat pilots another deck (learner --own-decks; a self game: both seats;
                            // never a teacher game, opponents() drops it there; the loader kept only loaded ones)
                            final OppDecks.Entry own = ds != null ? ds[0]
                                    : opp.ownDeck() == null ? null : oppDecks.get(opp.ownDeck());
                            if (opp.ownDeck() != null && own == null) {
                                throw new IllegalStateException("own deck " + opp.ownDeck() + " not loaded");
                            }
                            final int ownSeat = own == null ? -1 : self ? (int) (g % 2) : learnerSeat;
                            c.oracles = new DecisionOracle[2];
                            c.seats = new HeadlessMatch.Seat[2];
                            final String[] agents = new String[2];
                            final boolean[] rec = new boolean[2];
                            final boolean greedy = evalGreedy && !opp.train();
                            // handicapped start: the learner's seat (a self game: seat g % 2, the other is the model too)
                            final Handicap hc = Handicap.of(opp.handicap());
                            final int hcSeat = hc == null ? -1 : self ? (int) (g % 2) : learnerSeat;
                            if (hc != null) {
                                c.handicaps[hcSeat] = hc;
                            }
                            // search targets: the training games' recorded model seats search a fraction of copy points
                            final mtgai.search.GumbelOracle[] searchers = new mtgai.search.GumbelOracle[2];
                            for (int i = 0; i < 2; i++) {
                                DecisionOracle o;
                                if (teach && i == learnerSeat) { // recorded, model version 0 (not a model seat)
                                    o = new TeacherOracle(vocab);
                                    agents[i] = o.name();
                                    rec[i] = true;
                                } else if (self || i == learnerSeat) {
                                    if (st != null && opp.train() && !teach) { // same policy + RNG as the onnx seat
                                        searchers[i] = st.oracle(cl.model.model, vocab, worker);
                                        o = searchers[i];
                                    } else {
                                        o = new OnnxOracle(cl.model.model, vocab, worker).greedy(greedy);
                                    }
                                    agents[i] = "onnx"; // replay replays the recorded answers of an "onnx" seat
                                    c.modelVersions[i] = v;
                                    rec[i] = !teach; // a self teacher game: the model's seat is the opponent only
                                } else if (cl.oppModel != null) { // frozen pool model: played, not recorded
                                    o = new OnnxOracle(cl.oppModel.model, vocab, worker).greedy(greedy);
                                    agents[i] = "onnx";
                                    c.modelVersions[i] = cl.oppModel.model.version();
                                } else if (mtgai.search.PimcOracle.isPimcAgent(opp.agent())) {
                                    // search anchor (eval opponent only: play it with train:false)
                                    o = new mtgai.search.PimcOracle(mtgai.search.PimcOracle.configFor(opp.agent(), null));
                                    agents[i] = opp.name();
                                } else if (LobbyPlayerExternal.isForgeAgent(opp.agent())) {
                                    // stock PlayerControllerAi; forge-cautious etc. = that AI profile; "@deck" = its deck
                                    o = null;
                                    agents[i] = opp.name();
                                } else {
                                    o = new RandomOracle();
                                    agents[i] = "random";
                                }
                                c.oracles[i] = o == null ? null : ops.wrap(o, agents[i].equals("onnx"), c.gameId);
                            }
                            // each seat's deck: own deck (learner's seat; a self game: both), opponent deck, else -d's
                            final OppDecks.Entry[] pilot = new OppDecks.Entry[2];
                            for (int i = 0; i < 2; i++) {
                                pilot[i] = own != null && (self || i == ownSeat) ? own : i == odSeat ? od : null;
                            }
                            final String[] gameDecks = decks.clone();
                            final String[] gameTexts = deckTexts.clone();
                            for (int i = 0; i < 2; i++) {
                                final OppDecks.Entry e = pilot[i];
                                c.seats[i] = e != null
                                        ? new HeadlessMatch.Seat(agents[i], e.deck(), e.name(), null)
                                        : new HeadlessMatch.Seat(agents[i], seats[i].deck(), seats[i].deckName(), null);
                                if (e != null) {
                                    gameDecks[i] = e.path();
                                    gameTexts[i] = e.text();
                                }
                            }
                            final byte[] gameDeckSha = pilot[0] == null && pilot[1] == null ? deckSha
                                    : manifestSha(gameTexts[0], gameTexts[1]);
                            final HeadlessMatch.Result r = HeadlessMatch.play(c);
                            final String row = row(r, worker, g, v, c.seed, agents, c.modelVersions, opp, learnerSeat,
                                    c.seats, greedy, hc, hcSeat, teach ? learnerSeat : -1, ownSeat,
                                    own == null ? null : own.name(), od == null ? null : od.name(),
                                    st == null ? null : searchers);
                            for (mtgai.search.GumbelOracle s : searchers) {
                                if (s != null) {
                                    searchedTotal.addAndGet(s.searchedThisGame());
                                    searchNanosTotal.addAndGet(s.searchNanosThisGame());
                                }
                            }
                            final int view = learnerSeat >= 0 ? learnerSeat : (int) (g % 2); // a self game: g % 2
                            ops.metrics.matchup(ds != null ? "deckset" : c.seats[view].deckName(),
                                    ds != null ? "deckset" : c.seats[1 - view].deckName(),
                                    oppKind(opp.name()), greedy ? "eval" : "train",
                                    r.winnerSeat == view ? "win" : r.winnerSeat < 0 ? "draw" : "loss");
                            if (out != null) {
                                out.accept(String.format("w%02d-v%06d-g%06d.mtgt", worker, v, g),
                                        chunks(r, c.modelVersions, rec, worker, g, c.seed, engine, cardsSha, gameDeckSha,
                                                cl.model.model.encoder()), row);
                            }
                            ops.afterGame(c, r, worker, g, gameDecks, vocabPath, engine, cardsSha, gameDeckSha);
                            if (rows != null) {
                                synchronized (rowLock) {
                                    try (BufferedWriter w = Files.newBufferedWriter(rows, StandardCharsets.UTF_8,
                                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                                        w.write(row);
                                        w.newLine();
                                    }
                                }
                            }
                            played.incrementAndGet();
                        } finally {
                            cl.model.release();
                            if (cl.oppModel != null) {
                                cl.oppModel.release();
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    src.fail(e);
                }
            }, "mtgai-selfplay-" + t);
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) {
            th.join();
        }
        src.close();
        ops.close();
        if (out != null) {
            out.close(); // ZMQ: flushes queued games (linger) before the JVM exits
        }
        final long ms = Math.max(1, (System.nanoTime() - w0) / 1_000_000);
        System.out.printf(Locale.ROOT, "selfplay: %d games in %d ms (%.3f games/s, %d threads)%n", played.get(), ms,
                played.get() * 1000.0 / ms, threads);
        System.out.println("peak RSS " + Main.peakRss());
        if (failure.get() != null) {
            throw new IllegalStateException("selfplay failed", failure.get());
        }
        return 0;
    }

    /**
     * One chunk per recorded seat ({@code rec}); the header carries both seats' model versions (0 = not a model) and
     * {@code encoder}: the current model's encoder_version (an encoder-9 model on this encoder-10 jar: 9, so its learner
     * keeps the chunks; the observations are exact input for it, {@link ObservationEncoder#runs}).
     */
    static List<byte[]> chunks(HeadlessMatch.Result r, long[] versions, boolean[] rec, int worker, long g, long seed,
                               byte[] engine, byte[] cardsSha, byte[] deckSha, int encoder) {
        final boolean truncated = r.abort != null || r.exception != null;
        final List<byte[]> chunks = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            final List<TrajectoryWriter.Step> raw = r.record.steps(s);
            if (!rec[s] || raw.isEmpty()) {
                continue;
            }
            final int outcome = r.winnerSeat < 0 ? 0 : r.winnerSeat == s ? 1 : -1;
            final List<TrajectoryWriter.Step> steps = TrajectoryWriter.ended(raw, truncated, outcome);
            chunks.add(TrajectoryWriter.chunk(new TrajectoryWriter.Header(encoder, s, outcome,
                    worker, g, seed, versions[0], versions[1], engine, cardsSha, deckSha), steps));
        }
        return chunks;
    }

    /** The /metrics {@code opp_kind} of an opponent name (latest.json {@code opponents[].name}). */
    static String oppKind(String name) {
        if (name.equals("self")) {
            return "self";
        }
        if (name.matches("v\\d+") || isPoolDeckSetName(name)) {
            return "pool";
        }
        if (name.equals("forge-default") || name.startsWith("forge-default@")) {
            return "forge_default";
        }
        if (name.startsWith("forge-")) {
            return "forge_profile";
        }
        if (mtgai.search.PimcOracle.isPimcAgent(name)) {
            return "pimc";
        }
        return name.equals("random") ? "random" : "other";
    }

    static String row(HeadlessMatch.Result r, int worker, long g, long v, long seed, String[] agents, long[] versions,
                      Opponent opp, int learnerSeat, HeadlessMatch.Seat[] seats, boolean greedy, Handicap hc,
                      int hcSeat, // hc / hcSeat: the game's handicapped start and its seat (null / -1 = none)
                      int teacherSeat, // the imitation teacher's seat (-1 = none; the teacher is opp.teacher())
                      int ownSeat, // the learner's seat piloting ownDeck (-1 = none; a self game: g % 2)
                      String ownDeck, String oppDeck, // the decks played instead of the -d ones (null = none)
                      mtgai.search.GumbelOracle[] searchers) { // search targets on: per seat (null = none); off: null
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("worker", worker);
        m.put("game", g);
        m.put("version", v);
        m.put("seed", seed);
        m.put("agents", List.of(agents[0], agents[1]));
        m.put("winner", r.winnerSeat);
        m.put("first", r.firstSeat);
        m.put("turns", r.turns);
        m.put("end", r.endReason);
        m.put("ms", r.wallMillis);
        m.put("decisions", List.of(r.stats[0].decisions(), r.stats[1].decisions()));
        m.put("activation_failures", List.of(r.stats[0].activationFailures, r.stats[1].activationFailures));
        m.put("ai_dotrigger_outside", List.of(r.stats[0].aiDoTriggerOutsideTargeting,
                r.stats[1].aiDoTriggerOutsideTargeting));
        m.put("land_turns_eligible", List.of(r.stats[0].landTurnsEligible, r.stats[1].landTurnsEligible));
        m.put("land_turns_played", List.of(r.stats[0].landTurnsPlayed, r.stats[1].landTurnsPlayed));
        m.put("land_turns_eligible_completed", List.of(r.stats[0].landTurnsEligibleCompleted,
                r.stats[1].landTurnsEligibleCompleted));
        m.put("land_turns_played_completed", List.of(r.stats[0].landTurnsPlayedCompleted,
                r.stats[1].landTurnsPlayedCompleted));
        m.put("land_missed_windows", List.of(r.stats[0].landMissedWindows, r.stats[1].landMissedWindows));
        m.put("exception", r.exception);
        m.put("log_hash", HeadlessMatch.hash(HeadlessMatch.normalise(r.log)));
        m.put("model_versions", List.of(versions[0], versions[1]));
        m.put("decks", List.of(seats[0].deckName(), seats[1].deckName()));
        m.put("opponent", opp.name());
        m.put("opp_deck", oppDeck); // learner --opp-decks / --deck-set: the deck the stock AI piloted (null = -d's)
        m.put("learner_seat", learnerSeat);
        m.put("train", opp.train());
        m.put("greedy", greedy);
        m.put("madness_unpayable", List.of(r.stats[0].madnessUnpayable, r.stats[1].madnessUnpayable));
        m.put("optional_cost_unpayable", List.of(r.stats[0].optionalCostUnpayable, r.stats[1].optionalCostUnpayable));
        m.put("handicap", hc == null ? null : hc.id);
        m.put("handicap_seat", hcSeat);
        m.put("teacher", teacherSeat < 0 ? null : opp.teacher());
        m.put("teacher_seat", teacherSeat);
        m.put("teacher_labels", List.of(r.stats[0].teacherLabels, r.stats[1].teacherLabels));
        m.put("delegate_misses", List.of(r.stats[0].delegateMisses(), r.stats[1].delegateMisses()));
        m.put("own_deck", ownSeat < 0 ? null : ownDeck); // learner --own-decks / --deck-set: the deck the learner piloted
        m.put("own_deck_seat", ownSeat);
        if (searchers != null) { // search targets: SEARCHED steps and search wall ms per seat (only when search is on)
            final List<Object> n = new ArrayList<>();
            final List<Object> ms = new ArrayList<>();
            for (mtgai.search.GumbelOracle s : searchers) {
                n.add(s == null ? 0 : s.searchedThisGame());
                ms.add(s == null ? 0L : s.searchNanosThisGame() / 1_000_000);
            }
            m.put("searched", n);
            m.put("search_ms", ms);
        }
        return Json.write(m);
    }

    // ------------------------------------------------------------------ config helpers

    /** {@code "6"} or a per-host map {@code "hostA=6,hostB=4[,*=1]"}. */
    static int threads(String spec, String host) {
        if (!spec.contains("=")) {
            return Math.max(1, Integer.parseInt(spec.trim()));
        }
        Integer any = null;
        for (String kv : spec.split(",")) {
            final String[] p = kv.split("=", 2);
            final String k = p[0].trim();
            final int n = Math.max(1, Integer.parseInt(p[1].trim()));
            if (k.equals(host)) {
                return n;
            }
            if (k.equals("*")) {
                any = n;
            }
        }
        if (any == null) {
            throw new IllegalArgumentException("--threads " + spec + " has no entry for host " + host + " (add *=N)");
        }
        return any;
    }

    static String sub(String s, String host, int worker) {
        return s.replace("{host}", host).replace("{worker}", String.format("%02d", worker));
    }

    /** Kernel hostname (= systemd's %H), else the resolver's. */
    static String hostName() {
        try {
            final Path p = Path.of("/proc/sys/kernel/hostname");
            if (Files.isReadable(p)) {
                return Files.readString(p).trim();
            }
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static final Pattern GAME = Pattern.compile("\"game\":(\\d+)");

    /** Bytes {@link #resumeAfter} reads from the end of a rows file before falling back to a full scan. */
    static final long RESUME_TAIL = 64L << 20;

    /**
     * 1 + the largest {@code game} in an existing rows file, else 0. Rows append at game end with ids from one
     * shared counter, so the largest id sits near the end: scan only the last {@link #RESUME_TAIL} bytes, and stream
     * the whole file only if that tail holds no row. Never loads the file into memory: rows files grow without bound
     * (a 5.9 GB {@code games-w30.jsonl} OOMed a -Xmx5g worker on every start, 2026-11-01).
     */
    static long resumeAfter(Path rows) throws IOException {
        return resumeAfter(rows, RESUME_TAIL);
    }

    static long resumeAfter(Path rows, long tail) throws IOException {
        if (rows == null || !Files.exists(rows)) {
            return 0;
        }
        final long size = Files.size(rows);
        long max = -1;
        if (size > tail) {
            final java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) tail);
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(rows)) {
                ch.position(size - tail);
                while (buf.hasRemaining() && ch.read(buf) >= 0) {
                    // fill the tail buffer
                }
            }
            final String s = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            for (String l : s.substring(s.indexOf('\n') + 1).split("\n")) { // first line may be partial: skip it
                max = Math.max(max, game(l));
            }
            if (max >= 0) {
                return max + 1;
            }
        }
        try (java.io.BufferedReader r = Files.newBufferedReader(rows, StandardCharsets.UTF_8)) {
            for (String l; (l = r.readLine()) != null; ) {
                max = Math.max(max, game(l));
            }
        }
        return max + 1;
    }

    /** The row's {@code game} id (first match on the line, as rows write it first), else -1. */
    private static long game(String line) {
        final Matcher m = GAME.matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    // ------------------------------------------------------------------ search targets

    /**
     * {@code --search-agent gumbel-<n>[-m<k>]} + {@code --search-frac f}:
     * the training games' recorded model seats are {@link mtgai.search.GumbelOracle#training} oracles searching a
     * fraction f of their copy points (network rollouts; {@code --gumbel-depth}, {@code --gumbel-depth-decisions},
     * {@code --search-threads} as for the bench agent). Both flags or neither.
     */
    record SearchTargets(String agent, mtgai.search.PimcOracle.Config cfg, int m, double frac) {
        /** The flags' search targets, null when off; any inconsistent or out-of-range value throws (startup). */
        static SearchTargets of(Main.Args a) {
            final String agent = a.get("--search-agent");
            final String fs = a.get("--search-frac");
            final double f;
            try {
                f = fs == null ? 0 : Double.parseDouble(fs);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--search-frac " + fs + ": not a number");
            }
            if (!(f >= 0 && f <= 1)) {
                throw new IllegalArgumentException("--search-frac " + fs + " not in [0, 1]");
            }
            if (agent == null) {
                if (f > 0) {
                    throw new IllegalArgumentException("--search-frac " + fs + " without --search-agent");
                }
                return null;
            }
            if (!mtgai.search.GumbelOracle.isGumbelAgent(agent) || agent.endsWith("-forge")) {
                throw new IllegalArgumentException("--search-agent " + agent
                        + ": want gumbel-<n>[-m<k>] (network rollouts; -forge is not a training target)");
            }
            if (f == 0) {
                throw new IllegalArgumentException("--search-agent " + agent + " with --search-frac 0 (or unset)");
            }
            final SearchTargets st = new SearchTargets(agent,
                    mtgai.search.GumbelOracle.configFor(agent, Main.gumbelConfig(a)),
                    mtgai.search.GumbelOracle.mFor(agent), f);
            if (st.m < 2 || st.cfg.budget < 2) {
                throw new IllegalArgumentException("--search-agent " + agent + ": needs n >= 2 and m >= 2");
            }
            // >1 runs sims on the JVM-wide pool shared by every game thread; a timed-out sim keeps running on the
            // game's session (cancel doesn't stop Forge). Parallelism comes from game threads. Also catches an
            // inherited --pimc-threads (gumbelConfig falls back to it).
            if (st.cfg.threads != 1) {
                throw new IllegalArgumentException("--search-agent " + agent + ": needs --search-threads 1, got "
                        + st.cfg.threads);
            }
            return st;
        }

        /** A training-mode searcher for one seat of one game, on the game's session (the same policy as an onnx seat). */
        mtgai.search.GumbelOracle oracle(OnnxOracle.Model model, CardVocab vocab, int salt) {
            final mtgai.search.PimcOracle.Config c = cfg.copy();
            c.net = () -> new OnnxOracle(model, vocab, salt);
            return mtgai.search.GumbelOracle.training(c, m, frac);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s m %d frac %s depthTurns %d depthDecisions %d searchThreads %d", agent, m,
                    frac, cfg.depthTurns, cfg.depthDecisions, cfg.threads);
        }
    }

    // ------------------------------------------------------------------ opponents

    /**
     * One {@code latest.json} {@code opponents} entry; a frozen pool model has {@code file} + {@code sha256};
     * {@code handicap} ({@link Handicap} id, null = none) = the learner's seat starts handicapped in these games;
     * {@code teacher} ({@link TeacherOracle#known}, null = none) = the imitation teacher plays the learner's seat;
     * {@code ownDeck} (a deck name in --opp-deck-dir, null = none) = the learner's seat pilots that deck.
     */
    record Opponent(String name, double p, boolean train, String file, String sha256, long version, String handicap,
                    String teacher, String ownDeck) {
        static final Opponent SELF = new Opponent("self", 1, true, null, null, 0);
        static final Opponent RANDOM = new Opponent("random", 1, true, null, null, 0);

        Opponent(String name, double p, boolean train, String file, String sha256, long version) {
            this(name, p, train, file, sha256, version, null, null, null);
        }

        /** This entry played plain: its {@code own_deck} dropped (a deck that does not load). */
        Opponent withoutOwnDeck() {
            return new Opponent(name, p, train, file, sha256, version, handicap, teacher, null);
        }

        boolean self() {
            return name.equals("self");
        }

        boolean model() {
            return file != null;
        }

        /** The agent without an {@code @<deck>} suffix ({@code forge-default@elves} -> {@code forge-default}). */
        String agent() {
            return LobbyPlayerExternal.baseAgent(name);
        }

        /**
         * The deck a {@code <stock Forge agent>@<deck>} opponent pilots (learner {@code --opp-decks}); null = the worker's
         * own other deck. Only stock-AI names ({@link #isOppDeckAgent}): a pool model never pilots another deck.
         */
        String oppDeck() {
            return isOppDeckAgent(name) ? name.substring(name.indexOf('@') + 1) : null;
        }

        /**
         * A {@code <stock Forge agent>@*} entry (learner --deck-set) or a frozen pool model's {@code v<version>@*}
         * (learner --milestone-deck-set): both decks drawn from the deck-set tables, the opponent's seat piloting the
         * opp_pool one.
         */
        boolean deckSet() {
            return isDeckSetAgent(name) || model() && isPoolDeckSetName(name);
        }
    }

    /** {@code v<version>@*}: a frozen pool model's deck-set entry (learner {@code --milestone-deck-set}). */
    static boolean isPoolDeckSetName(String name) {
        return name.matches("v\\d+@\\*");
    }

    /** True for {@code <stock Forge agent>@*} (learner {@code --deck-set}). */
    static boolean isDeckSetAgent(String name) {
        return name.endsWith("@*") && LobbyPlayerExternal.isForgeAgent(name.substring(0, name.length() - 2));
    }

    /** An opponent deck name (a file name stem in --opp-deck-dir; no paths). */
    private static final Pattern OPP_DECK = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    /** True for {@code <stock Forge agent>@<deck>} (learner {@code --opp-decks}). */
    static boolean isOppDeckAgent(String name) {
        final int at = name.indexOf('@');
        return at > 0 && LobbyPlayerExternal.isForgeAgent(name.substring(0, at))
                && OPP_DECK.matcher(name.substring(at + 1)).matches();
    }

    /** The deck manifest sha of a game (the learner's deck-pair key): SHA-256 of seat 0's .dck text + seat 1's. */
    static byte[] manifestSha(String seat0, String seat1) throws java.security.NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest((seat0 + seat1).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decks a stock-AI opponent pilots instead of the worker's own other deck (learner {@code --opp-decks}) or the
     * learner's seat pilots instead of its own (learner {@code --own-decks}): {@code <dir>/<name>.dck}, loaded once; a
     * deck that fails to load is remembered as absent and logged once.
     */
    static final class OppDecks {
        /**
         * A loaded deck ({@code text}: its .dck text); {@code sha(s)}: the game's deck manifest sha with this deck in seat
         * {@code s} and the worker's own deck in the other.
         */
        record Entry(String name, Deck deck, String path, String text, byte[] sha0, byte[] sha1) {
            byte[] sha(int seat) {
                return seat == 0 ? sha0 : sha1;
            }
        }

        private final Path dir;
        private final String[] own;
        private final Map<String, Optional<Entry>> cache = new HashMap<>();

        /** {@code own}: the .dck texts of the worker's own seat-0 / seat-1 decks (for the manifest sha). */
        OppDecks(Path dir, String[] own) {
            this.dir = dir;
            this.own = own.clone();
        }

        /** The deck {@code name}, null if it is not a valid name or does not load. */
        synchronized Entry get(String name) {
            return cache.computeIfAbsent(name, this::load).orElse(null);
        }

        private Optional<Entry> load(String name) {
            final Path p = dir.resolve(name + ".dck");
            try {
                if (!OPP_DECK.matcher(name).matches()) {
                    throw new IllegalArgumentException("not a deck name");
                }
                return Optional.of(entry(name, p, Files.readString(p), own));
            } catch (Exception e) {
                System.err.println("selfplay: opponent/own deck " + name + " (" + p + "): " + e
                        + "; its @deck entries are skipped, its own_deck entries play plain");
                return Optional.empty();
            }
        }

        static Entry entry(String name, Path p, String text, String[] own) throws Exception {
            final Deck d = Main.loadDeck(p);
            System.out.println("selfplay: opponent deck " + name + " from " + p);
            return new Entry(name, d, p.toString(), text, manifestSha(text, own[1]), manifestSha(own[0], text));
        }
    }

    /**
     * The decks of a deck-set manifest (learner {@code --deck-set}; {@code decks[]}: {@code id}, {@code file} relative to
     * the manifest, {@code sha256} of the file, {@code heldout}), loaded on first use, and the per-version weight tables
     * of latest.json. A held-out deck is never loaded, so never drawn, whatever the tables say.
     */
    static final class DeckSet {
        private final Path dir;
        private final Map<String, JsonObject> decks;
        private final String[] own;
        private final Map<String, Optional<OppDecks.Entry>> cache = new HashMap<>();

        private DeckSet(Path dir, Map<String, JsonObject> decks, String[] own) {
            this.dir = dir;
            this.decks = decks;
            this.own = own.clone();
        }

        /** {@code own}: the .dck texts of the worker's own seat-0 / seat-1 decks (for {@link OppDecks.Entry}'s shas). */
        static DeckSet load(Path manifest, String[] own) throws IOException {
            final Map<String, JsonObject> decks = new HashMap<>();
            for (JsonElement e : com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject()
                    .getAsJsonArray("decks")) {
                decks.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
            System.out.println("selfplay: deck set " + manifest + ", " + decks.size() + " decks");
            return new DeckSet(manifest.toAbsolutePath().getParent(), decks, own);
        }

        /** The deck {@code id}; null if it is held out, not in the manifest, fails its sha256 or does not load. */
        synchronized OppDecks.Entry get(String id) {
            return cache.computeIfAbsent(id, this::load).orElse(null);
        }

        private Optional<OppDecks.Entry> load(String id) {
            final JsonObject d = decks.get(id);
            try {
                if (d == null || !OPP_DECK.matcher(id).matches()) {
                    throw new IllegalArgumentException("not a deck of the manifest");
                }
                if (d.has("heldout") && d.get("heldout").getAsBoolean()) {
                    throw new IllegalArgumentException("held out: never played");
                }
                final Path p = dir.resolve(d.get("file").getAsString()).normalize();
                final byte[] b = Files.readAllBytes(p);
                final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
                if (d.has("sha256") && !sha.equalsIgnoreCase(d.get("sha256").getAsString())) {
                    throw new IllegalStateException("sha256 " + sha + " is not the manifest's");
                }
                return Optional.of(OppDecks.entry(id, p, new String(b, StandardCharsets.UTF_8), own));
            } catch (Exception e) {
                System.err.println("selfplay: deck-set deck " + id + ": " + e + "; never drawn");
                return Optional.empty();
            }
        }

        /** latest.json's {@code own_pool} / {@code opp_pool} over the decks that load; null if either has none. */
        Tables tables(JsonObject meta) {
            final Tables t = new Tables(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            for (int side = 0; side < 2; side++) {
                final String key = side == 0 ? "own_pool" : "opp_pool";
                if (meta == null || !meta.has(key) || !meta.get(key).isJsonObject()) {
                    return null;
                }
                for (Map.Entry<String, JsonElement> e : meta.getAsJsonObject(key).entrySet()) {
                    final JsonElement v = e.getValue();
                    final double w = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : 0;
                    final OppDecks.Entry d = w > 0 && Double.isFinite(w) ? get(e.getKey()) : null;
                    if (d != null) {
                        (side == 0 ? t.own : t.opp).add(d);
                        (side == 0 ? t.ownW : t.oppW).add(w);
                    }
                }
            }
            return t.own.isEmpty() || t.opp.isEmpty() ? null : t;
        }
    }

    /** One version's deck-set weight tables (decks that load, weights > 0). */
    record Tables(List<OppDecks.Entry> own, List<Double> ownW, List<OppDecks.Entry> opp, List<Double> oppW) {
        /** Two-level weighted draw from the game seed: {own deck, opponent deck}; same seed + tables = same decks. */
        OppDecks.Entry[] draw(long seed) {
            final SplittableRandom r = new SplittableRandom(seed ^ 0x6465636BL);
            return new OppDecks.Entry[] {draw(own, ownW, r.nextDouble()), draw(opp, oppW, r.nextDouble())};
        }

        private static OppDecks.Entry draw(List<OppDecks.Entry> ds, List<Double> w, double u) {
            double x = u * w.stream().mapToDouble(Double::doubleValue).sum();
            for (int i = 0; i < ds.size(); i++) {
                x -= w.get(i);
                if (x < 0) {
                    return ds.get(i);
                }
            }
            return ds.get(ds.size() - 1);
        }
    }

    /**
     * The {@code opponents} of a {@code latest.json}; none (or nothing usable) = self-play. Unknown kinds are skipped,
     * so is an entry with a {@code handicap} or {@code teacher} this worker does not know (never played as a plain game
     * under its name), and a teacher entry marked {@code train: false} (a teacher game is never an evaluation game).
     * An {@code own_deck} on a teacher entry is ignored (the entry is kept, a plain teacher game); whether the deck
     * loads is checked by {@link #playable}.
     */
    static List<Opponent> opponents(JsonObject meta) {
        final List<Opponent> out = new ArrayList<>();
        if (meta != null && meta.has("opponents")) {
            for (JsonElement e : meta.getAsJsonArray("opponents")) {
                final JsonObject o = e.getAsJsonObject();
                final String name = o.get("name").getAsString();
                final double p = o.has("p") ? o.get("p").getAsDouble() : 1.0;
                final boolean train = !o.has("train") || o.get("train").getAsBoolean();
                final String hc = o.has("handicap") && !o.get("handicap").isJsonNull() ? o.get("handicap").getAsString() : null;
                final String tc = o.has("teacher") && !o.get("teacher").isJsonNull() ? o.get("teacher").getAsString() : null;
                String od = o.has("own_deck") && !o.get("own_deck").isJsonNull() ? o.get("own_deck").getAsString() : null;
                if (od != null && tc != null) { // a teacher game never pilots another deck: played as a plain teacher game
                    System.err.println("selfplay: ignoring own_deck on a teacher entry " + o);
                    od = null;
                }
                if (hc != null && !Handicap.known(hc)) {
                    System.err.println("selfplay: skipping opponent with unknown handicap " + o);
                } else if (tc != null && (!TeacherOracle.known(tc) || !train)) {
                    System.err.println("selfplay: skipping opponent with unknown or non-training teacher " + o);
                } else if (o.has("file")) { // a pool model's "v<version>@*" draws both decks: an own_deck means nothing
                    out.add(new Opponent(name, p, train, o.get("file").getAsString(), o.get("sha256").getAsString(),
                            o.get("version").getAsLong(), hc, tc, isPoolDeckSetName(name) ? null : od));
                } else if (name.equals("self") || name.equals("random") || LobbyPlayerExternal.isForgeAgent(name)
                        || isOppDeckAgent(name) || isDeckSetAgent(name) || mtgai.search.PimcOracle.isPimcAgent(name)) {
                    // a deck-set entry draws its own deck from the tables: an own_deck there means nothing
                    out.add(new Opponent(name, p, train, null, null, 0, hc, tc, isDeckSetAgent(name) ? null : od));
                } else {
                    System.err.println("selfplay: skipping unknown opponent " + o);
                }
            }
        }
        out.removeIf(o -> !(o.p() > 0));
        return out.isEmpty() ? List.of(Opponent.SELF) : List.copyOf(out);
    }

    /**
     * The entries a worker can play, as a fresh mutable list: an {@code <agent>@<deck>} entry whose deck does not load
     * is dropped (--opp-decks); an {@code own_deck} that does not load is dropped from its entry, which is then played
     * plain (--own-decks; the deck is logged once by {@link OppDecks}). {@code decks} null = no deck loads.
     */
    static List<Opponent> playable(List<Opponent> ops, OppDecks decks) {
        return playable(ops, decks, null);
    }

    /** ... and a {@code <agent>@*} entry (learner --deck-set) is dropped without deck-set {@code tables}. */
    static List<Opponent> playable(List<Opponent> ops, OppDecks decks, Tables tables) {
        final List<Opponent> out = new ArrayList<>();
        for (Opponent o : ops) {
            if (o.oppDeck() != null && (decks == null || decks.get(o.oppDeck()) == null) || o.deckSet() && tables == null) {
                continue;
            }
            out.add(o.ownDeck() != null && (decks == null || decks.get(o.ownDeck()) == null) ? o.withoutOwnDeck() : o);
        }
        return out;
    }

    /** Weighted draw from the game seed: the same game and list give the same opponent on any thread count. */
    static Opponent pick(List<Opponent> ops, long seed) {
        double total = 0;
        for (Opponent o : ops) {
            total += o.p();
        }
        double x = new SplittableRandom(seed ^ 0x6F70706FL).nextDouble() * total;
        for (Opponent o : ops) {
            x -= o.p();
            if (x < 0) {
                return o;
            }
        }
        return ops.get(ops.size() - 1);
    }

    // ------------------------------------------------------------------ model source

    /** One loaded ONNX version; closed when the source and every game using it have released it. */
    static final class Loaded {
        final OnnxOracle.Model model;
        private final AtomicInteger refs = new AtomicInteger(1);

        Loaded(OnnxOracle.Model model) {
            this.model = model;
        }

        void retain() {
            refs.incrementAndGet();
        }

        void release() {
            if (refs.decrementAndGet() == 0) {
                try {
                    model.close();
                } catch (Exception e) {
                    // closing a session we no longer use; nothing to do
                }
            }
        }
    }

    /** A game to play: the current model, the opponent drawn for it and (frozen pool opponent) its model. */
    record Claim(Loaded model, long game, Opponent opp, Loaded oppModel,
                 OppDecks.Entry[] decks) { // a deck-set entry's drawn {own, opponent} decks; else null
    }

    /** Game counter + current model + the games-per-version gate, shared by the game threads and the loader. */
    static final class Source {
        private final ModelSource models;
        /** {@code <models>/STOP} for a model dir; an HTTP source has none (stop with --max-games or a signal). */
        private final Path stop;
        private final int perVersion;
        private final long maxGames;
        private final int pollMs;
        private Loaded current;
        /** Opponents of the current version; frozen pool models by file (only models that loaded are listed). */
        private List<Opponent> opponents = List.of(Opponent.SELF);
        private Map<String, Loaded> frozen = new HashMap<>();
        private final List<Opponent> fixed;
        /** Opponent decks of {@code <agent>@<deck>} entries (null: such entries are dropped). */
        private final OppDecks oppDecks;
        /** Decks of {@code <agent>@*} entries (null: such entries are dropped) and the current version's tables. */
        private final DeckSet deckSet;
        private Tables tables;
        private final long seedBase;
        private int onCurrent;
        private long next;
        private boolean done;
        private Throwable failure;
        final java.util.concurrent.atomic.AtomicLong loads = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong loadErrors = new java.util.concurrent.atomic.AtomicLong();
        volatile long lastLoadMs;

        Source(ModelSource models, int perVersion, long start, long maxGames, int pollMs) {
            this(models, perVersion, start, maxGames, pollMs, 0, null);
        }

        /** {@code fixed}: opponents that override {@code latest.json}'s ({@code --vs random}); null = the learner's. */
        Source(ModelSource models, int perVersion, long start, long maxGames, int pollMs, long seedBase,
               List<Opponent> fixed) {
            this(models, perVersion, start, maxGames, pollMs, seedBase, fixed, null);
        }

        /** {@code oppDecks}: where {@code <agent>@<deck>} entries find their deck (null = such entries are dropped). */
        Source(ModelSource models, int perVersion, long start, long maxGames, int pollMs, long seedBase,
               List<Opponent> fixed, OppDecks oppDecks) {
            this(models, perVersion, start, maxGames, pollMs, seedBase, fixed, oppDecks, null);
        }

        /** {@code deckSet}: where {@code <agent>@*} entries draw their decks (null = such entries are dropped). */
        Source(ModelSource models, int perVersion, long start, long maxGames, int pollMs, long seedBase,
               List<Opponent> fixed, OppDecks oppDecks, DeckSet deckSet) {
            this.models = models;
            this.seedBase = seedBase;
            this.fixed = fixed;
            this.oppDecks = oppDecks;
            this.deckSet = deckSet;
            if (fixed != null) {
                opponents = fixed;
            }
            this.stop = models instanceof ModelSource.Dir d ? d.dir().resolve("STOP") : null;
            this.perVersion = perVersion;
            this.next = start;
            this.maxGames = maxGames;
            this.pollMs = pollMs;
        }

        /** The next game to play and the model to play it with, or null when the worker should stop. */
        synchronized Claim claim() throws InterruptedException {
            while (true) {
                if (failure != null) {
                    throw new IllegalStateException("model loader failed", failure);
                }
                if (done || next >= maxGames || (stop != null && Files.exists(stop))) {
                    done = true;
                    notifyAll();
                    return null;
                }
                if (current != null && onCurrent < perVersion) {
                    onCurrent++;
                    current.retain();
                    final Opponent opp = pick(opponents, seedBase + next);
                    final Loaded om = opp.model() ? frozen.get(opp.file()) : null;
                    if (om != null) {
                        om.retain();
                    }
                    // playable() kept a deck-set entry only with this version's tables
                    final OppDecks.Entry[] ds = opp.deckSet() ? tables.draw(seedBase + next) : null;
                    return new Claim(current, next++, opp, om, ds);
                }
                wait(pollMs);
            }
        }

        synchronized void fail(Throwable e) {
            if (failure == null) {
                failure = e;
            }
            done = true;
            notifyAll();
        }

        synchronized long loadedVersion() {
            return current == null ? -1 : current.model.version();
        }

        private synchronized boolean isDone() {
            return done;
        }

        /** Background: load each new latest.json version into a fresh session, swap it in for the next game start. */
        void loadLoop() {
            try {
                while (!isDone()) {
                    OnnxOracle.Model m = null;
                    List<Opponent> ops = null;
                    Tables tb = null;
                    final Map<String, Loaded> fz = new HashMap<>();
                    try { // an unreachable/slow model server is transient: keep playing the current model, retry
                        final JsonObject meta = models.latest();
                        final long lv = meta == null ? -1 : meta.get("version").getAsLong();
                        if (lv >= 0 && lv != loadedVersion()) {
                            final long t0 = System.nanoTime();
                            m = models.load(meta, ObservationEncoder.ENCODER_VERSION); // sha256 + encoder_version checked
                            tb = deckSet == null || fixed != null ? null : deckSet.tables(meta);
                            ops = playable(fixed != null ? fixed : opponents(meta), oppDecks, tb);
                            for (Opponent o : List.copyOf(ops)) { // frozen pool models: reuse loaded ones, load new ones
                                if (!o.model() || fz.containsKey(o.file())) {
                                    continue;
                                }
                                Loaded l = frozen.get(o.file());
                                if (l == null) {
                                    try {
                                        l = new Loaded(models.loadFile(o.file(), o.sha256(), o.version()));
                                    } catch (IOException | RuntimeException e) {
                                        loadErrors.incrementAndGet();
                                        System.err.println("opponent " + o.name() + " from " + models.describe() + ": " + e);
                                        ops.remove(o);
                                        continue;
                                    }
                                }
                                fz.put(o.file(), l);
                            }
                            lastLoadMs = (System.nanoTime() - t0) / 1_000_000;
                        }
                    } catch (java.io.IOException e) {
                        loadErrors.incrementAndGet();
                        System.err.println("model source " + models.describe() + ": " + e);
                    }
                    if (m != null) {
                        loads.incrementAndGet();
                        final Loaded l = new Loaded(m);
                        final List<Loaded> old = new ArrayList<>();
                        synchronized (this) {
                            if (done) { // closed while loading
                                old.add(l);
                                for (Map.Entry<String, Loaded> e : fz.entrySet()) {
                                    if (frozen.get(e.getKey()) != e.getValue()) {
                                        old.add(e.getValue());
                                    }
                                }
                            } else {
                                if (current != null) {
                                    old.add(current);
                                }
                                for (Map.Entry<String, Loaded> e : frozen.entrySet()) {
                                    if (!fz.containsKey(e.getKey())) {
                                        old.add(e.getValue());
                                    }
                                }
                                current = l;
                                frozen = fz;
                                opponents = ops.isEmpty() ? List.of(Opponent.SELF) : List.copyOf(ops);
                                tables = tb;
                                onCurrent = 0;
                                notifyAll();
                            }
                        }
                        for (Loaded o : old) {
                            o.release();
                        }
                        continue;
                    }
                    synchronized (this) {
                        if (!done) {
                            wait(pollMs);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                fail(e);
            }
        }

        /** After the game threads are done: stop the loader, drop the source's hold on the current model. */
        void close() {
            final List<Loaded> old = new ArrayList<>();
            synchronized (this) {
                done = true;
                notifyAll();
                if (current != null) {
                    old.add(current);
                }
                old.addAll(frozen.values());
                current = null;
                frozen = new HashMap<>();
            }
            for (Loaded l : old) {
                l.release();
            }
        }
    }
}
