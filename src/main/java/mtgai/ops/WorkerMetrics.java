package mtgai.ops;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

import com.sun.net.httpserver.HttpServer;

import mtgai.match.HeadlessMatch;
import mtgai.match.MatchAbort;

/**
 * Per-worker counters + a Prometheus text endpoint: {@code GET /metrics} on a plain JDK
 * {@link HttpServer}. One instance per JVM, shared by its game threads ({@link #observe} after each game,
 * {@link TimedOracle} per policy decision). Every sample carries a constant {@code worker} label.
 *
 * <p>engine seconds = game wall seconds minus policy (ONNX forward + sampling) seconds, summed over game threads.
 * Extra gauges (model version, transport bytes, ...) are {@link #register}ed by whoever owns them.
 */
public final class WorkerMetrics {
    private final String worker;
    private final long startNanos = System.nanoTime();
    private final LongAdder games = new LongAdder();
    private final LongAdder exceptions = new LongAdder();
    private final LongAdder wedged = new LongAdder();
    private final LongAdder activationFailures = new LongAdder();
    private final LongAdder aiDoTrigger = new LongAdder();
    private final LongAdder decisions = new LongAdder();
    private final LongAdder gameNanos = new LongAdder();
    private final LongAdder inferenceNanos = new LongAdder();
    private final LongAdder inferenceCalls = new LongAdder();
    private final Map<MatchAbort.Reason, LongAdder> aborts = new EnumMap<>(MatchAbort.Reason.class);
    private final List<Thread> wedgedThreads = new ArrayList<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private final Map<String, Extra> extras = new LinkedHashMap<>();
    private final Map<String, LongAdder> matchups = new ConcurrentHashMap<>(); // rendered label set -> games
    private HttpServer server;

    private record Extra(String type, String help, DoubleSupplier value) {
    }

    public WorkerMetrics(String worker) {
        this.worker = worker;
        for (MatchAbort.Reason r : MatchAbort.Reason.values()) {
            aborts.put(r, new LongAdder());
        }
    }

    /** After every game. */
    public void observe(HeadlessMatch.Result r) {
        games.increment();
        gameNanos.add(r.wallMillis * 1_000_000L);
        decisions.add(r.decisions);
        activationFailures.add(r.activationFailures());
        aiDoTrigger.add(r.stats[0].aiDoTriggerOutsideTargeting + r.stats[1].aiDoTriggerOutsideTargeting);
        if (r.abort != null) {
            aborts.get(r.abort).increment();
        }
        if (r.wedgedThread != null) {
            wedged.increment();
            synchronized (wedgedThreads) {
                wedgedThreads.add(r.wedgedThread);
            }
        } else if (r.exception != null) {
            exceptions.increment();
        }
        synchronized (recent) {
            final long now = System.nanoTime();
            recent.addLast(now);
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000_000_000L) {
                recent.removeFirst();
            }
        }
    }

    /**
     * One finished game seen from the learner's seat: {@code oppKind} self / pool / forge_default / forge_profile /
     * random / other, {@code mode} train / eval, {@code result} win / loss / draw. One count per game.
     */
    public void matchup(String learnerDeck, String oppDeck, String oppKind, String mode, String result) {
        matchups.computeIfAbsent("learner_deck=\"" + esc(learnerDeck) + "\",opp_deck=\"" + esc(oppDeck)
                + "\",opp_kind=\"" + esc(oppKind) + "\",mode=\"" + esc(mode) + "\",result=\"" + esc(result) + "\"",
                k -> new LongAdder()).increment();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** One policy forward (+ sampling), in nanoseconds. */
    public void inference(long nanos) {
        inferenceNanos.add(nanos);
        inferenceCalls.increment();
    }

    /** Game threads left running by a wedged engine (HeadlessMatch's join timeout) that are still alive. */
    public int wedgedAlive() {
        synchronized (wedgedThreads) {
            wedgedThreads.removeIf(t -> !t.isAlive());
            return wedgedThreads.size();
        }
    }

    public long games() {
        return games.sum();
    }

    /** Adds a sample; {@code type} is {@code counter} or {@code gauge}; {@code name} without the mtgai_ prefix. */
    public synchronized WorkerMetrics register(String name, String type, String help, DoubleSupplier value) {
        extras.put(name, new Extra(type, help, value));
        return this;
    }

    public String render() {
        final StringBuilder sb = new StringBuilder(2048);
        final double up = (System.nanoTime() - startNanos) / 1e9;
        final double gameS = gameNanos.sum() / 1e9;
        final double infS = inferenceNanos.sum() / 1e9;
        final long dec = decisions.sum();
        final long calls = inferenceCalls.sum();
        final double rate;
        synchronized (recent) {
            final long now = System.nanoTime();
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000_000_000L) {
                recent.removeFirst();
            }
            rate = recent.size() / Math.min(60.0, Math.max(1e-9, up));
        }
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        final Runtime rt = Runtime.getRuntime();
        sample(sb, "uptime_seconds", "gauge", "seconds since the worker started", up);
        sample(sb, "games_total", "counter", "games finished (any end)", games.sum());
        sample(sb, "games_per_second", "gauge", "games finished over the last 60 s", rate);
        sample(sb, "exceptions_total", "counter", "games ended by an exception (wedged excluded)", exceptions.sum());
        sample(sb, "wedged_games_total", "counter", "games whose game thread stopped deciding past the join timeout",
                wedged.sum());
        sample(sb, "wedged_threads", "gauge", "wedged game threads still alive (supervisor restarts the JVM)", wedgedAlive());
        header(sb, "aborts_total", "counter", "games truncated by a cooperative watchdog cap");
        for (Map.Entry<MatchAbort.Reason, LongAdder> e : aborts.entrySet()) {
            line(sb, "aborts_total", "reason=\"" + e.getKey().name().toLowerCase(Locale.ROOT) + "\"", e.getValue().sum());
        }
        sample(sb, "activation_failures_total", "counter", "chosen PRIORITY candidates the pipeline refused (must be 0)",
                activationFailures.sum());
        sample(sb, "ai_dotrigger_outside_total", "counter", "AI doTrigger outside targeting (must be 0)", aiDoTrigger.sum());
        sample(sb, "decisions_total", "counter", "oracle decisions (both seats)", dec);
        sample(sb, "game_seconds_total", "counter", "game wall time summed over game threads", gameS);
        sample(sb, "inference_seconds_total", "counter", "policy forward + sampling time", infS);
        sample(sb, "inference_calls_total", "counter", "policy forwards", calls);
        sample(sb, "engine_seconds_total", "counter", "game wall minus policy time", Math.max(0, gameS - infS));
        sample(sb, "inference_ms_per_decision", "gauge", "mean policy ms per policy decision (lifetime)",
                calls == 0 ? 0 : infS * 1e3 / calls);
        sample(sb, "engine_ms_per_decision", "gauge", "mean engine ms per decision (lifetime)",
                dec == 0 ? 0 : Math.max(0, gameS - infS) * 1e3 / dec);
        sample(sb, "gc_collections_total", "counter", "JVM GC collections (all collectors)", gcCount);
        sample(sb, "gc_pause_seconds_total", "counter", "JVM GC time (all collectors)", gcMs / 1e3);
        sample(sb, "heap_used_bytes", "gauge", "JVM heap in use", rt.totalMemory() - rt.freeMemory());
        header(sb, "matchup_games_total", "counter", "finished games by learner deck, opponent deck/kind, mode and result");
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(matchups).entrySet()) {
            line(sb, "matchup_games_total", e.getKey(), e.getValue().sum());
        }
        final Map<String, Extra> ex;
        synchronized (this) {
            ex = new LinkedHashMap<>(extras);
        }
        for (Map.Entry<String, Extra> e : ex.entrySet()) {
            sample(sb, e.getKey(), e.getValue().type(), e.getValue().help(), e.getValue().value().getAsDouble());
        }
        return sb.toString();
    }

    private void sample(StringBuilder sb, String name, String type, String help, double v) {
        header(sb, name, type, help);
        line(sb, name, null, v);
    }

    private static void header(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP mtgai_").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE mtgai_").append(name).append(' ').append(type).append('\n');
    }

    private void line(StringBuilder sb, String name, String labels, double v) {
        sb.append("mtgai_").append(name).append("{worker=\"").append(worker).append('"');
        if (labels != null) {
            sb.append(',').append(labels);
        }
        sb.append("} ");
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            sb.append(String.format(Locale.ROOT, "%.6g", v));
        }
        sb.append('\n');
    }

    /** Serves {@code GET /metrics} on {@code port} (0 = ephemeral; see {@link #port()}), all interfaces. */
    public synchronized WorkerMetrics serve(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", ex -> {
            final byte[] body = render().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        server.setExecutor(null); // the dispatcher thread answers; a scrape is a few KB
        // started from a daemon thread so the dispatcher inherits daemon status: /metrics never keeps a failing
        // worker JVM alive (systemd must see it exit to restart it)
        final Thread t = new Thread(server::start, "mtgai-metrics-start");
        t.setDaemon(true);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return this;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
