package mtgai.onnx;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Where published models come from: a directory, or an HTTP base URL serving the
 * same layout ({@code latest.json} = {version, file, sha256, encoder_version} + {@code policy-NNNNNN.onnx}),
 * e.g. the learner's model server. Read-only. {@link #load} is the worker's background
 * load step: fetch, verify (sha256 of the bytes, {@code encoder_version} equal to the worker's encoder) and build
 * the ORT session off the game threads; the self-play loop swaps it in at the next game start.
 */
public interface ModelSource {
    /** The current {@code latest.json}, or null if none is published yet (or the server is unreachable). */
    JsonObject latest() throws IOException;

    byte[] fetch(String file) throws IOException;

    String describe();

    /** Version named by {@code latest.json} right now, -1 if none (or unreachable). */
    default long latestVersion() throws IOException {
        final JsonObject m = latest();
        return m == null ? -1 : m.get("version").getAsLong();
    }

    /**
     * Fetches and verifies the model {@code latest.json} names and builds its session. Throws
     * {@link IllegalStateException} on an {@code encoder_version} other than {@code encoderVersion} (or an older one its
     * observations are exact input for, {@link mtgai.encode.ObservationEncoder#runs}: an encoder-9 model on the
     * encoder-10 jar) or a sha256 mismatch; null if nothing is published.
     */
    default OnnxOracle.Model load(int encoderVersion) throws IOException {
        return load(latest(), encoderVersion);
    }

    /** {@link #load(int)} for an already-read {@code latest.json} ({@code meta}; null = nothing published). */
    default OnnxOracle.Model load(JsonObject meta, int encoderVersion) throws IOException {
        if (meta == null) {
            return null;
        }
        final long v = meta.get("version").getAsLong();
        final int ev = meta.get("encoder_version").getAsInt();
        if (!mtgai.encode.ObservationEncoder.runs(ev, encoderVersion)) { // equal, or an older one it reads exactly
            throw new IllegalStateException("refusing model v" + v + " from " + describe() + ": encoder_version " + ev
                    + " != encoder " + encoderVersion);
        }
        return loadFile(meta.get("file").getAsString(), meta.get("sha256").getAsString(), v).encoder(ev);
    }

    /**
     * Fetches {@code file}, checks its sha256 and builds the session as model {@code version}: a frozen pool opponent
     * named in {@code latest.json}'s {@code opponents}, or the current model via {@link #load}.
     */
    default OnnxOracle.Model loadFile(String file, String sha256, long version) throws IOException {
        final byte[] bytes = fetch(file);
        final String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (!sha.equals(sha256)) {
            throw new IllegalStateException("sha256 mismatch for model v" + version + " " + file + " from " + describe());
        }
        return new OnnxOracle.Model(version, bytes);
    }

    /** {@code http(s)://...} = {@link Http}; anything else = a {@link Dir}. */
    static ModelSource open(String spec) {
        return spec.startsWith("http://") || spec.startsWith("https://") ? new Http(URI.create(spec)) : new Dir(Path.of(spec));
    }

    record Dir(Path dir) implements ModelSource {
        @Override
        public JsonObject latest() throws IOException {
            try {
                return JsonParser.parseString(Files.readString(dir.resolve("latest.json"))).getAsJsonObject();
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        @Override
        public byte[] fetch(String file) throws IOException {
            return Files.readAllBytes(dir.resolve(file));
        }

        @Override
        public String describe() {
            return dir.toString();
        }
    }

    /** JDK {@link HttpClient}, no framework. A connection failure on {@code latest.json} reads as "nothing new". */
    final class Http implements ModelSource {
        private final URI base;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        public Http(URI base) {
            this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        }

        @Override
        public JsonObject latest() throws IOException {
            final HttpResponse<String> r;
            try {
                r = client.send(req("latest.json"), HttpResponse.BodyHandlers.ofString());
            } catch (java.net.ConnectException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (r.statusCode() == 404) {
                return null;
            }
            if (r.statusCode() != 200) {
                throw new IOException("GET latest.json: HTTP " + r.statusCode());
            }
            return JsonParser.parseString(r.body()).getAsJsonObject();
        }

        @Override
        public byte[] fetch(String file) throws IOException {
            try {
                final HttpResponse<byte[]> r = client.send(req(file), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) {
                    throw new IOException("GET " + file + ": HTTP " + r.statusCode());
                }
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        private HttpRequest req(String file) {
            return HttpRequest.newBuilder(base.resolve(file)).timeout(Duration.ofSeconds(60))
                    .header("Cache-Control", "no-cache").GET().build();
        }

        @Override
        public String describe() {
            return base.toString();
        }
    }
}
