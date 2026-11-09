package mtgai.onnx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import mtgai.encode.Observation;

/**
 * Batches ONNX forwards across the game threads of one JVM. A game thread calls
 * {@link #forward} and parks on a future; one dispatcher thread collects requests and runs a batch when
 * {@code maxBatch} requests are waiting or {@code maxWaitMicros} after the first one arrived, whichever comes
 * first. The batch is sorted by token count and split into length buckets ({@code bucket} tokens wide, 0 = one
 * bucket) so a long observation does not pad a batch of short ones. One batcher per {@link OnnxOracle.Model}
 * (= one session per model id).
 */
public final class DecisionBatcher implements AutoCloseable {
    private record Req(Observation obs, int tokens, CompletableFuture<float[]> out) {
    }

    private final OnnxOracle.Model model;
    private final int maxBatch;
    private final long maxWaitNanos;
    private final int bucket;
    private final LinkedBlockingQueue<Req> queue = new LinkedBlockingQueue<>();
    private final Thread dispatcher;
    private volatile boolean closed;

    // stats: forwards, requests, request-nanos inside session.run, token histogram (index = tokens, capped)
    public final AtomicLong forwards = new AtomicLong();
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong runNanos = new AtomicLong();
    public final long[] tokenHist = new long[512];
    public final long[] candHist = new long[65];

    public DecisionBatcher(OnnxOracle.Model model, int maxBatch, long maxWaitMicros, int bucket) {
        this.model = model;
        this.maxBatch = maxBatch;
        this.maxWaitNanos = maxWaitMicros * 1000;
        this.bucket = bucket;
        dispatcher = new Thread(this::loop, "mtgai-batcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    /** Called on a game thread: blocks until the batch containing this request has run. */
    public float[] forward(Observation o) {
        final Req r = new Req(o, Math.max(1, o.nObj() + o.nLib() + o.nOpp()), new CompletableFuture<>());
        queue.add(r);
        return r.out().join();
    }

    private void loop() {
        final List<Req> batch = new ArrayList<>();
        while (!closed) {
            try {
                final Req first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                final long deadline = System.nanoTime() + maxWaitNanos;
                while (batch.size() < maxBatch) {
                    final long left = deadline - System.nanoTime();
                    final Req r = left <= 0 ? queue.poll() : queue.poll(left, TimeUnit.NANOSECONDS);
                    if (r == null) {
                        break;
                    }
                    batch.add(r);
                }
                run(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                for (Req r : batch) {
                    r.out().completeExceptionally(e);
                }
            }
            batch.clear();
        }
    }

    private void run(List<Req> batch) {
        batch.sort(Comparator.comparingInt(Req::tokens));
        int from = 0;
        while (from < batch.size()) {
            int to = from + 1;
            final int key = bucket <= 0 ? 0 : (batch.get(from).tokens() - 1) / bucket;
            while (to < batch.size() && (bucket <= 0 || (batch.get(to).tokens() - 1) / bucket == key)) {
                to++;
            }
            final List<Req> part = batch.subList(from, to);
            final List<Observation> obs = new ArrayList<>(part.size());
            for (Req r : part) {
                obs.add(r.obs());
                tokenHist[Math.min(tokenHist.length - 1, r.tokens())]++;
                candHist[Math.min(candHist.length - 1, r.obs().nCand())]++;
            }
            final long t0 = System.nanoTime();
            final float[][] out = model.forwardBatch(obs);
            runNanos.addAndGet(System.nanoTime() - t0);
            forwards.incrementAndGet();
            requests.addAndGet(part.size());
            for (int i = 0; i < part.size(); i++) {
                part.get(i).out().complete(out[i]);
            }
            from = to;
        }
    }

    /** p-th percentile (0..100) of a histogram. */
    public static int percentile(long[] hist, double p) {
        long n = 0;
        for (long h : hist) {
            n += h;
        }
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc > 0 && acc >= Math.ceil(n * p / 100.0)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void close() {
        closed = true;
        dispatcher.interrupt();
    }
}
