package mtgai.transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pushes existing {@code .mtgt} files through {@link ZmqSink}, no Forge needed (the cross-language round-trip
 * test in the learner's test suite, and the transport throughput bench):
 * <pre>java -cp mtgai-java-*-all.jar mtgai.transport.PushFiles tcp://127.0.0.1:5555 [--repeat N] FILE...</pre>
 * Each file goes out as one message under its own file name ({@code --repeat}: suffix {@code -rK} before the
 * extension). Prints messages, bytes and the wall rate.
 */
public final class PushFiles {
    private PushFiles() {
    }

    public static void main(String[] argv) throws Exception {
        String endpoint = null;
        int repeat = 1;
        final List<Path> files = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) {
            if (argv[i].equals("--repeat")) {
                repeat = Integer.parseInt(argv[++i]);
            } else if (endpoint == null) {
                endpoint = argv[i];
            } else {
                files.add(Path.of(argv[i]));
            }
        }
        if (endpoint == null || files.isEmpty()) {
            System.err.println("usage: PushFiles ENDPOINT [--repeat N] FILE...");
            System.exit(2);
        }
        final List<byte[]> data = new ArrayList<>();
        for (Path f : files) {
            data.add(Files.readAllBytes(f));
        }
        final long t0 = System.nanoTime();
        try (ZmqSink sink = new ZmqSink(endpoint, Integer.getInteger("mtgai.zmq.hwm", 64))) {
            for (int r = 0; r < repeat; r++) {
                for (int i = 0; i < files.size(); i++) {
                    final String name = files.get(i).getFileName().toString();
                    sink.accept(repeat == 1 ? name : name.replace(".mtgt", "") + "-r" + r + ".mtgt", List.of(data.get(i)));
                }
            }
            final double s = (System.nanoTime() - t0) / 1e9;
            System.out.printf(Locale.ROOT, "pushed %d messages, %d bytes in %.3f s (%.0f msg/s, %.1f MB/s, send-side)%n",
                    sink.messages.get(), sink.bytes.get(), s, sink.messages.get() / s, sink.bytes.get() / s / 1e6);
        }
    }
}
