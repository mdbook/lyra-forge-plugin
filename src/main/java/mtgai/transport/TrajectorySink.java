package mtgai.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import mtgai.traj.TrajectoryWriter;

/**
 * Where a finished game's trajectory chunks go. One call = one game: the chunks
 * (one per recorded seat, {@link TrajectoryWriter#chunk}) and the file name the file-based learner expects
 * ({@code w%02d-v%06d-g%06d.mtgt}). {@link FileSink} writes that file; {@link ZmqSink} sends the same bytes as
 * one JeroMQ PUSH message, so a learner-side PULL receiver can spool byte-identical files.
 *
 * <p>Implementations are thread-safe (N game threads per JVM share one sink).
 */
public interface TrajectorySink extends AutoCloseable {
    void accept(String name, List<byte[]> chunks) throws IOException;

    /**
     * One game plus its match row ({@code meta}, the {@code --eval-out} JSON line: agents, opponent, winner, ...), so a
     * learner that only sees the transport knows who the recorded seat played and how it ended (curriculum,
     * match rows, eval). A file sink drops it (the row goes to {@code --eval-out}); {@link ZmqSink} sends it as a
     * middle frame.
     */
    default void accept(String name, List<byte[]> chunks, String meta) throws IOException {
        accept(name, chunks);
    }

    @Override
    default void close() {
    }

    /** {@code tcp://host:port} (or {@code ipc://}, {@code inproc://}) = ZMQ PUSH; anything else = a directory. */
    static TrajectorySink open(String spec) throws IOException {
        if (spec.startsWith("tcp://") || spec.startsWith("ipc://") || spec.startsWith("inproc://")) {
            return new ZmqSink(spec, Integer.getInteger("mtgai.zmq.hwm", 64));
        }
        return new FileSink(Path.of(spec));
    }

    /** The concatenated chunks: the exact bytes of the {@code .mtgt} file / the ZMQ payload frame. */
    static byte[] concat(List<byte[]> chunks) {
        int n = 0;
        for (byte[] c : chunks) {
            n += c.length;
        }
        final byte[] out = new byte[n];
        int at = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, at, c.length);
            at += c.length;
        }
        return out;
    }

    /** One {@code .mtgt} file per game in a directory (atomic temp + rename). */
    final class FileSink implements TrajectorySink {
        private final Path dir;

        public FileSink(Path dir) throws IOException {
            this.dir = Files.createDirectories(dir);
        }

        @Override
        public void accept(String name, List<byte[]> chunks) throws IOException {
            TrajectoryWriter.write(dir.resolve(name), chunks);
        }
    }
}
