package mtgai.transport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * JeroMQ PUSH to the learner's PULL. The worker <b>connects out</b>; the learner binds.
 * One game = one two-frame message: frame 0 the UTF-8 file name
 * ({@code w%02d-v%06d-g%06d.mtgt}), frame 1 the concatenated chunks, byte-identical to what {@link
 * TrajectorySink.FileSink} writes. With a match row ({@link #accept(String, List, String)}, what {@code selfplay}
 * sends) the message has three frames: name, the row as UTF-8 JSON, the chunks; the payload stays the last frame, so
 * a receiver reading {@code frames[0]} / {@code frames[-1]} handles both forms. ZMQ reconnects by itself; while the
 * learner is away messages queue up to the send high-water mark ({@code -Dmtgai.zmq.hwm}, default 64 games, ~20 MB),
 * then {@link #accept} blocks: the worker stops producing rather than piling up data the learner's staleness policy would drop anyway.
 */
public final class ZmqSink implements TrajectorySink {
    private final ZContext ctx = new ZContext(1);
    private final ZMQ.Socket push;
    private final String endpoint;
    public final AtomicLong messages = new AtomicLong();
    public final AtomicLong bytes = new AtomicLong();
    /** Time spent inside send (queueing; large when the learner is gone and the HWM is reached). */
    public final AtomicLong sendNanos = new AtomicLong();

    public ZmqSink(String endpoint, int hwm) {
        this.endpoint = endpoint;
        push = ctx.createSocket(SocketType.PUSH);
        push.setSndHWM(hwm);
        push.setLinger(Integer.getInteger("mtgai.zmq.lingerMs", 30_000)); // flush queued games on close
        push.setImmediate(false); // queue while not yet connected (learner starting later)
        push.connect(endpoint);
    }

    @Override
    public void accept(String name, List<byte[]> chunks) {
        accept(name, chunks, null);
    }

    @Override
    public void accept(String name, List<byte[]> chunks, String meta) {
        final byte[] payload = TrajectorySink.concat(chunks);
        final long t0 = System.nanoTime();
        synchronized (push) { // ZMQ sockets are not thread-safe; the frames of one game must stay together
            push.sendMore(name.getBytes(StandardCharsets.UTF_8));
            if (meta != null) {
                push.sendMore(meta.getBytes(StandardCharsets.UTF_8));
            }
            push.send(payload, 0);
        }
        sendNanos.addAndGet(System.nanoTime() - t0);
        messages.incrementAndGet();
        bytes.addAndGet(payload.length);
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        ctx.close(); // honours linger: queued messages are flushed (up to lingerMs) before the socket closes
    }
}
