package mtgai.cli;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import mtgai.decision.DecisionOracle;
import mtgai.match.HeadlessMatch;
import mtgai.ops.DecisionLog;
import mtgai.ops.GameArchive;
import mtgai.ops.TimedOracle;
import mtgai.ops.WorkerMetrics;
import mtgai.transport.TrajectorySink;
import mtgai.transport.ZmqSink;

/**
 * The operations hooks of {@code mtgai selfplay}, kept out of the game loop:
 * <ul>
 * <li>{@code --metrics-port P}: {@code GET /metrics} ({@link WorkerMetrics}, plus model and transport gauges);</li>
 * <li>{@code --records FILE}: the canonical game record of every game ({@link GameArchive}, for {@code mtgai replay});</li>
 * <li>{@code --decision-log FILE}: the opt-in per-decision debug log ({@link DecisionLog});</li>
 * <li>{@code --max-wedged N} (default 1): once N wedged game threads are alive the worker fails, so the JVM exits
 * non-zero and systemd ({@code Restart=on-failure}) replaces it; a wedged engine thread cannot be killed in-JVM.</li>
 * </ul>
 */
final class SelfPlayOps implements AutoCloseable {
    final WorkerMetrics metrics;
    private final GameArchive archive;
    private final Writer decisionLog;
    private final int maxWedged;

    SelfPlayOps(Main.Args a, String host, int worker, SelfPlay.Source src, TrajectorySink out) throws IOException {
        metrics = new WorkerMetrics(String.valueOf(worker));
        metrics.register("model_version", "gauge", "model version new games start on", src::loadedVersion)
                .register("model_loads_total", "counter", "models loaded (fetch + sha256 + session)", () -> src.loads.get())
                .register("model_load_errors_total", "counter", "transient model-source errors", () -> src.loadErrors.get())
                .register("model_load_ms", "gauge", "last background model load", () -> src.lastLoadMs);
        if (out instanceof ZmqSink z) {
            metrics.register("trajectories_sent_total", "counter", "games pushed to the learner", () -> z.messages.get())
                    .register("trajectories_sent_bytes_total", "counter", "bytes pushed", () -> z.bytes.get())
                    .register("trajectory_send_seconds_total", "counter", "time blocked in send (HWM reached = learner away)",
                            () -> z.sendNanos.get() / 1e9);
        }
        if (a.get("--metrics-port") != null) {
            metrics.serve(a.intOr("--metrics-port", 9400));
            System.out.println("selfplay: /metrics on port " + metrics.port());
        }
        archive = a.get("--records") == null ? null : new GameArchive(Path.of(SelfPlay.sub(a.get("--records"), host, worker)));
        decisionLog = a.get("--decision-log") == null ? null
                : Files.newBufferedWriter(Path.of(SelfPlay.sub(a.get("--decision-log"), host, worker)), StandardCharsets.UTF_8);
        maxWedged = a.intOr("--max-wedged", 1);
    }

    DecisionOracle wrap(DecisionOracle o, boolean policy, String gameId) {
        DecisionOracle w = policy ? new TimedOracle(o, metrics) : o;
        return decisionLog == null ? w : new DecisionLog(w, decisionLog, gameId);
    }

    void afterGame(HeadlessMatch.Config c, HeadlessMatch.Result r, int worker, long g, String[] decks, String vocab,
                   byte[] engine, byte[] cardsSha, byte[] deckSha) throws IOException {
        metrics.observe(r);
        if (archive != null) {
            archive.append(GameArchive.entry(c.gameId, worker, g, c, r, List.of(decks[0], decks[1]), vocab, engine, cardsSha,
                    deckSha));
        }
        final int wedged = metrics.wedgedAlive();
        if (wedged >= maxWedged) {
            throw new IllegalStateException(wedged + " wedged game thread(s) alive (--max-wedged " + maxWedged
                    + "): exiting so the supervisor restarts this worker");
        }
    }

    @Override
    public void close() throws IOException {
        metrics.stop();
        if (archive != null) {
            archive.close();
        }
        if (decisionLog != null) {
            decisionLog.close();
        }
    }
}
