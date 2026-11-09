package mtgai.humanseat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Launch-time update check for the packaged builds (human seat, Forge Table), run by their launchers before the game:
 * {@code java -cp "lib/*" mtgai.humanseat.UpdateCheck --neural neural --channel humanseat|forge-table}.
 *
 * <p>Model: if the published pointer ({@value #MODEL_POINTER}: version, file, sha256, encoder_version, optional size,
 * and url, which must lie under {@code mtgai-model/<version>/} of the same package registry) names a newer version
 * with the same encoder_version as {@code neural/latest.json}, the policy file is downloaded, checked against the
 * pointer's sha256, moved into {@code neural/} and {@code latest.json} is rewritten to it. The previous policy file
 * stays as the fallback (older ones are pruned). Build: if the channel's {@code version.json} pointer has a later
 * {@code built_at} than the package's own {@code version.json}, prints the zip URL. Zips are never downloaded.
 *
 * <p>HTTPS to {@value #HOST} only, for every URL including the ones read from pointers; no redirects; bodies capped
 * ({@link #POINTER_MAX}, {@link #MODEL_MAX} or twice the pointer's size). Stale temp files are swept first. Each pointer
 * fetch gets {@link #CONNECT} to connect and {@link #POINTER_BUDGET} in all, from its request (JVM start not counted);
 * any pointer problem (offline, timeout, bad pointer) skips silently. The model download gets {@link #MODEL_BUDGET},
 * announced by a "Downloading model" line; if it fails or times out, one line says so, the current model stays and
 * {@code neural/} is untouched. Always exits 0. {@code --no-update-check} or {@code MTGAI_NO_UPDATE_CHECK=1} skips
 * the check; {@code --verbose} says why a check was skipped. Plain JDK only: the launchers run it with just
 * {@code lib/} on the class path.
 *
 * <p>Every pointer is signed: {@code <pointer>.sig} next to it holds the base64 Ed25519 signature over the pointer's
 * exact bytes, made by the sign-pointers job of the release pipeline. {@link #pointer}
 * fetches both (same pinning, caps and budget) and checks the signature against the public key baked into this jar
 * ({@value #PUBLIC_KEY}) before reading any field. A
 * missing or bad signature, or a jar without the key, means no update: one line says so and nothing changes. There
 * is no unsigned fallback and no switch to skip the check.
 *
 * <p>That is the old layout. Packages in the self-updating layout run it from their bootstrap launcher as
 * {@code java -cp "app/<current>/lib/*" mtgai.humanseat.UpdateCheck --install-builds . --channel C [--previous]
 * [--no-update-check]}: newer builds are installed instead of announced and the model lives in {@code user/neural/};
 * see {@link BuildInstall}.
 */
public final class UpdateCheck {
    /**
     * The package registry builds and models are published to. A distributor sets both to their own GitLab generic
     * package registry (and replaces {@code update-signing.pub} with their own signing key); the {@code .invalid}
     * placeholder never resolves, so the check skips silently.
     */
    static final String HOST = "updates.invalid";
    static final String PACKAGES = "https://" + HOST + "/api/v4/projects/0/packages/generic/";
    static final String MODEL_POINTER = PACKAGES + "mtgai-model/latest/latest.json";
    static final Duration CONNECT = Duration.ofSeconds(3);
    static final Duration POINTER_BUDGET = Duration.ofSeconds(5);
    static final Duration MODEL_BUDGET = Duration.ofSeconds(120);
    /** Body caps: a pointer; a model whose pointer gives no size (else twice its size, up to {@link #SIZE_MAX}). */
    static final long POINTER_MAX = 64 << 10;
    static final long MODEL_MAX = 64L << 20;
    static final long SIZE_MAX = 512L << 20;
    static final String SKIP_ENV = "MTGAI_NO_UPDATE_CHECK";
    /** The resource holding the key pointers must be signed with: base64 X.509 SubjectPublicKeyInfo DER, Ed25519. */
    static final String PUBLIC_KEY = "/update-signing.pub";
    static final long SIG_MAX = 1024;
    static final Pattern POLICY = Pattern.compile("policy-\\d+\\.onnx");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DECK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** The network, faked by the tests. Returns the body of a 200 answer; anything else throws. */
    interface Http {
        byte[] get(URI url, long maxBytes, Instant deadline) throws IOException;

        /** The same, streamed into {@code sink} (a build zip is too big to hold in memory). */
        default void download(URI url, long maxBytes, Instant deadline, OutputStream sink) throws IOException {
            sink.write(get(url, maxBytes, deadline));
        }
    }

    final Http http;
    final PrintStream out;
    /** The key pointer signatures must verify against; null (no key in the jar) refuses every pointer. */
    private final PublicKey key;

    UpdateCheck(Http http, PrintStream out, PublicKey key) {
        this.http = http;
        this.out = out;
        this.key = key;
    }

    /** A pointer whose signature is missing or does not verify. */
    static final class Unsigned extends IOException {
        Unsigned(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        try {
            run(args, System.getenv(), new JdkHttp(), System.out, bakedKey());
        } catch (Throwable ignored) {
            // never stand between the player and the game
        }
        System.exit(0);
    }

    static void run(String[] args, Map<String, String> env, Http http, PrintStream out, PublicKey key) {
        Path neural = Path.of("neural");
        Path versionFile = Path.of("version.json");
        Path top = null;
        String channel = null;
        boolean verbose = false;
        boolean previous = false;
        boolean skip = "1".equals(env.get(SKIP_ENV));
        boolean usage = false;
        for (int i = 0; i < args.length && !usage; i++) {
            switch (args[i]) {
                case "--no-update-check" -> skip = true;
                case "--neural" -> neural = Path.of(args[++i]);
                case "--channel" -> channel = args[++i];
                case "--version-file" -> versionFile = Path.of(args[++i]);
                case "--install-builds" -> top = Path.of(args[++i]);
                case "--previous" -> previous = true;
                case "--verbose" -> verbose = true;
                default -> usage = true;
            }
        }
        if (usage || (top == null ? previous : channel == null)) {
            System.err.println("usage: UpdateCheck [--neural DIR] [--channel humanseat|forge-table] [--version-file FILE]"
                    + " [--verbose] [--no-update-check]\n"
                    + "       UpdateCheck --install-builds DIR --channel humanseat|forge-table [--previous] [--verbose]"
                    + " [--no-update-check]");
            return;
        }
        final UpdateCheck check = new UpdateCheck(http, out, key);
        if (top != null) {
            new BuildInstall(check, verbose).launch(top, channel, previous, skip);
            return;
        }
        if (skip) {
            return;
        }
        if (channel != null) {
            try {
                final String url = check.newBuild(channel, versionFile);
                if (url != null) {
                    out.println("New build available: " + url);
                }
            } catch (Unsigned e) {
                out.println("[update] build check refused: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                if (verbose) {
                    System.err.println("[update] build check skipped: " + e);
                }
            }
        }
        check.modelUpdate(neural, verbose);
    }

    /** {@link #updateModel}, printing its line; a pointer problem is silent (said with {@code verbose}). */
    void modelUpdate(Path neural, boolean verbose) {
        try {
            final String line = updateModel(neural);
            if (line != null) {
                out.println(line);
            }
        } catch (Unsigned e) {
            out.println("[update] model check refused (" + e.getMessage() + "); keeping the model you have");
        } catch (IOException | RuntimeException e) {
            if (verbose) {
                System.err.println("[update] model check skipped: " + e);
            }
        }
    }

    /**
     * Swaps in a newer published model. Returns the {@code [update]} line, or null if nothing newer this seat can play
     * is published or its download failed (that failure is printed). Throws on a pointer problem. Either way short of
     * success, {@code neural/} is untouched.
     */
    String updateModel(Path neural) throws IOException {
        sweep(neural);
        final Map<String, String> local = flatJson(Files.readString(neural.resolve("latest.json")));
        final String body = signedPointer(pinned(MODEL_POINTER));
        final Map<String, String> ptr = flatJson(body);
        final long have = number(local, "version");
        final long version = number(ptr, "version");
        final long encoder = number(local, "encoder_version");
        if (version <= have || number(ptr, "encoder_version") != encoder) {
            return null; // nothing newer, or a model this build's encoder would refuse
        }
        final String file = field(ptr, "file", POLICY);
        final String sha = field(ptr, "sha256", SHA256);
        final String previous = local.get("file");
        if (file.equals(previous)) {
            throw new IOException("pointer v" + version + " reuses the current file name " + file);
        }
        final URI url = pinned(ptr.get("url"));
        final String dir = PACKAGES + "mtgai-model/" + version + "/";
        if (!url.toString().startsWith(dir) || !url.normalize().equals(url) || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IOException("refusing model url " + url + " (want " + dir + "...)");
        }
        final List<String> decks = decks(body); // kept in latest.json; absent stays absent
        final long size = ptr.get("size") == null ? -1 : number(ptr, "size");
        if (size > SIZE_MAX) {
            throw new IOException("pointer v" + version + " says " + size + " bytes");
        }
        out.println(String.format(Locale.ROOT, "[update] Downloading model v%d%s...", version,
                size < 0 ? "" : String.format(Locale.ROOT, " (%.1f MB)", size / 1e6)));
        try {
            final long cap = size < 0 ? MODEL_MAX : 2 * size;
            install(neural, http.get(url, cap, Instant.now().plus(MODEL_BUDGET)), file, sha, version, encoder, decks);
        } catch (IOException | RuntimeException e) {
            out.println("[update] model v" + version + " not installed (" + (e instanceof HttpTimeoutException
                    ? "timed out" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "), playing v" + have);
            return null;
        }
        prune(neural, file, previous);
        return "[update] model v" + have + " -> v" + version;
    }

    /**
     * The pointer's {@code decks} (the decks the model pilots, strongest first: plain deck names), or null if it has
     * none; throws if it is anything else.
     */
    static List<String> decks(String pointer) throws IOException {
        final List<String> decks;
        try {
            decks = new FlatJson(pointer).strings("decks");
        } catch (IllegalArgumentException e) {
            throw new IOException("bad decks: " + e.getMessage());
        }
        if (decks != null && !decks.stream().allMatch(d -> DECK.matcher(d).matches())) {
            throw new IOException("bad deck name in " + decks);
        }
        return decks;
    }

    /** Verifies the downloaded {@code model} and moves it + its latest.json into {@code neural/}; nothing on failure. */
    private static void install(Path neural, byte[] model, String file, String sha, long version, long encoder,
            List<String> decks) throws IOException {
        if (!sha256(model).equals(sha)) {
            throw new IOException("sha256 mismatch");
        }
        final Path target = neural.resolve(file);
        final Path tmpModel = Files.createTempFile(neural, ".update-", ".tmp");
        final Path tmpMeta = Files.createTempFile(neural, ".update-", ".tmp");
        boolean placed = false;
        try {
            Files.write(tmpModel, model);
            Files.writeString(tmpMeta, String.format(Locale.ROOT,
                    "{\"version\": %d, \"file\": \"%s\", \"sha256\": \"%s\", \"encoder_version\": %d%s}%n",
                    version, file, sha, encoder, decks == null ? ""
                            : ", \"decks\": [" + String.join(", ", decks.stream().map(d -> "\"" + d + "\"").toList()) + "]"));
            Files.move(tmpModel, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            placed = true;
            Files.move(tmpMeta, neural.resolve("latest.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            if (placed) {
                Files.deleteIfExists(target);
            }
            throw e;
        } finally {
            Files.deleteIfExists(tmpModel);
            Files.deleteIfExists(tmpMeta);
        }
    }

    /**
     * The fields of the signed pointer at {@code url}: fetches it and {@code url.sig} (both within
     * {@link #POINTER_BUDGET}), verifies the signature over the exact bytes, and only then parses them. A pointer that
     * cannot be fetched throws a plain IOException; a missing or bad signature throws {@link Unsigned}.
     */
    Map<String, String> pointer(URI url) throws IOException {
        return flatJson(signedPointer(url));
    }

    /** The text of the signed pointer at {@code url}, once its signature verifies; see {@link #pointer}. */
    String signedPointer(URI url) throws IOException {
        final Instant deadline = Instant.now().plus(POINTER_BUDGET);
        final byte[] body = http.get(url, POINTER_MAX, deadline);
        final byte[] sig;
        try {
            sig = Base64.getDecoder().decode(new String(http.get(pinned(url + ".sig"), SIG_MAX, deadline),
                    StandardCharsets.US_ASCII).trim());
        } catch (IOException | IllegalArgumentException e) {
            throw new Unsigned("no signature for " + url.getPath() + ": " + e.getMessage());
        }
        if (!verify(key, body, sig)) {
            throw new Unsigned(key == null ? "this build has no update key" : "bad signature on " + url.getPath());
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Whether {@code sig} is {@code key}'s Ed25519 signature over {@code body}; false without a key. */
    static boolean verify(PublicKey key, byte[] body, byte[] sig) {
        if (key == null) {
            return false;
        }
        try {
            final Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(body);
            return v.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** The public key in the {@value #PUBLIC_KEY} resource of this jar, or null if there is none or it is unreadable. */
    static PublicKey bakedKey() {
        try (InputStream in = UpdateCheck.class.getResourceAsStream(PUBLIC_KEY)) {
            return in == null ? null : publicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** An Ed25519 public key from base64 X.509 SubjectPublicKeyInfo DER. */
    static PublicKey publicKey(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    /** The newer build's zip URL if the channel's pointer was built after this package, else null. */
    String newBuild(String channel, Path versionFile) throws IOException {
        final URI pointer = buildPointer(channel);
        if (!Files.isRegularFile(versionFile)) {
            return null;
        }
        final Instant built = Instant.parse(required(flatJson(Files.readString(versionFile)), "built_at"));
        final Map<String, String> ptr = pointer(pointer);
        if (!Instant.parse(required(ptr, "built_at")).isAfter(built)) {
            return null;
        }
        return pinned(ptr.get("zip_url")).toString();
    }

    /** The channel's build pointer, {@code <channel>/latest/version.json}. */
    static URI buildPointer(String channel) throws IOException {
        return pinned(switch (channel) {
            case "humanseat", "forge-table" -> PACKAGES + channel + "/latest/version.json";
            default -> throw new IllegalArgumentException("unknown channel " + channel);
        });
    }

    /** {@code url} if it is https://{@value #HOST}/..., else throws. */
    static URI pinned(String url) throws IOException {
        if (url == null) {
            throw new IOException("no url");
        }
        final URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("bad url " + url);
        }
        if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost()) || u.getRawUserInfo() != null
                || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new IOException("refusing " + url + " (only https://" + HOST + "/)");
        }
        return u;
    }

    /** Temp files a killed run left behind ({@link #install} writes .update-*.tmp next to the model). Best effort. */
    private static void sweep(Path neural) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(neural, ".update-*.tmp")) {
            for (Path f : ds) {
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // retried next start
        }
    }

    /** Old policy files go; the new one and the one it replaced stay. Best effort. */
    static void prune(Path neural, String keep, String previous) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(neural, "policy-*.onnx")) {
            for (Path f : ds) {
                final String name = f.getFileName().toString();
                if (POLICY.matcher(name).matches() && !name.equals(keep) && !name.equals(previous)) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException ignored) {
            // a leftover file costs disk space, nothing else
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String required(Map<String, String> m, String key) throws IOException {
        final String v = m.get(key);
        if (v == null) {
            throw new IOException("no " + key);
        }
        return v;
    }

    static long number(Map<String, String> m, String key) throws IOException {
        return Long.parseLong(required(m, key));
    }

    static String field(Map<String, String> m, String key, Pattern want) throws IOException {
        final String v = required(m, key);
        if (!want.matcher(v).matches()) {
            throw new IOException("bad " + key + " " + v);
        }
        return v;
    }

    /** Top-level members of a JSON object: strings unescaped, numbers / literals as their text, nested values skipped. */
    static Map<String, String> flatJson(String s) {
        return new FlatJson(s).object();
    }

    private static final class FlatJson {
        private final String s;
        private int i;

        FlatJson(String s) {
            this.s = s;
        }

        Map<String, String> object() {
            final Map<String, String> m = new HashMap<>();
            expect('{');
            if (peek() == '}') {
                i++;
            } else {
                while (true) {
                    final String k = string();
                    expect(':');
                    m.put(k, value());
                    final char c = peek();
                    i++;
                    if (c == '}') {
                        break;
                    }
                    if (c != ',') {
                        throw bad();
                    }
                }
            }
            if (peek() != 0) {
                throw bad();
            }
            return m;
        }

        /** The top-level member {@code key} as a list of strings, null if absent; throws unless it is such an array. */
        List<String> strings(String key) {
            List<String> found = null;
            expect('{');
            if (peek() == '}') {
                i++;
            } else {
                while (true) {
                    final String k = string();
                    expect(':');
                    if (k.equals(key)) {
                        found = new ArrayList<>();
                        expect('[');
                        if (peek() == ']') {
                            i++;
                        } else {
                            while (true) {
                                found.add(string());
                                final char c = peek();
                                i++;
                                if (c == ']') {
                                    break;
                                }
                                if (c != ',') {
                                    throw bad();
                                }
                            }
                        }
                    } else {
                        value();
                    }
                    final char c = peek();
                    i++;
                    if (c == '}') {
                        break;
                    }
                    if (c != ',') {
                        throw bad();
                    }
                }
            }
            if (peek() != 0) {
                throw bad();
            }
            return found;
        }

        private String value() {
            final char c = peek();
            if (c == '"') {
                return string();
            }
            if (c == '{' || c == '[') {
                skipNested();
                return null;
            }
            final int start = i;
            while (i < s.length() && "+-.0123456789eEtruefalsn".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i == start) {
                throw bad();
            }
            final String t = s.substring(start, i);
            return t.equals("null") ? null : t;
        }

        private void skipNested() {
            int depth = 0;
            do {
                final char c = peek();
                if (c == '"') {
                    string();
                    continue;
                }
                if (c == 0) {
                    throw bad();
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
                i++;
            } while (depth > 0);
        }

        private String string() {
            expect('"');
            final StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw bad();
                }
                final char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw bad();
                }
                final char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw bad();
                        }
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        /** The next non-blank char (0 at the end), not consumed. */
        private char peek() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i < s.length() ? s.charAt(i) : 0;
        }

        private void expect(char c) {
            if (peek() != c) {
                throw bad();
            }
            i++;
        }

        private IllegalArgumentException bad() {
            return new IllegalArgumentException("bad JSON at " + i);
        }
    }

    /** JDK {@link HttpClient}: no redirects, {@link #CONNECT} to connect, the whole exchange bounded by the deadline. */
    static final class JdkHttp implements Http {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT)
                .build();

        @Override
        public byte[] get(URI url, long maxBytes, Instant deadline) throws IOException {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            download(url, maxBytes, deadline, buf);
            return buf.toByteArray();
        }

        @Override
        public void download(URI url, long maxBytes, Instant deadline, OutputStream sink) throws IOException {
            final HttpRequest req = HttpRequest.newBuilder(url).timeout(left(url, deadline))
                    .header("User-Agent", "mtgai-update-check").GET().build();
            // a declared oversize body is not read at all; an undeclared one is aborted once it passes the cap
            final HttpResponse.BodyHandler<Long> handler = info -> info.statusCode() == 200
                    && info.headers().firstValueAsLong("Content-Length").orElse(0) <= maxBytes
                    ? new Capped(maxBytes, sink) : HttpResponse.BodySubscribers.<Long>replacing(null);
            final CompletableFuture<HttpResponse<Long>> f = client.sendAsync(req, handler);
            final HttpResponse<Long> r;
            try {
                r = f.get(left(url, deadline).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new HttpTimeoutException("GET " + url + ": out of time");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (r.statusCode() != 200) {
                throw new IOException("GET " + url + ": HTTP " + r.statusCode());
            }
            if (r.body() == null) {
                throw new IOException("GET " + url + ": more than " + maxBytes + " bytes");
            }
        }

        /**
         * Writes the body into {@code sink} and completes with its length; cancels the exchange and fails once the
         * body passes {@code max} bytes or the sink fails. A truncated body fails through {@link #onError}.
         */
        private static final class Capped implements HttpResponse.BodySubscriber<Long> {
            private final long max;
            private final OutputStream sink;
            private final CompletableFuture<Long> body = new CompletableFuture<>();
            private Flow.Subscription subscription;
            private long n;

            Capped(long max, OutputStream sink) {
                this.max = max;
                this.sink = sink;
            }

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> items) {
                try {
                    for (ByteBuffer b : items) {
                        if (body.isDone()) {
                            return;
                        }
                        if (n + b.remaining() > max) {
                            throw new IOException("body over " + max + " bytes");
                        }
                        final byte[] chunk = new byte[b.remaining()];
                        b.get(chunk);
                        sink.write(chunk);
                        n += chunk.length;
                    }
                } catch (IOException e) {
                    subscription.cancel();
                    body.completeExceptionally(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                body.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                body.complete(n);
            }

            @Override
            public CompletableFuture<Long> getBody() {
                return body;
            }
        }

        private static Duration left(URI url, Instant deadline) throws HttpTimeoutException {
            final Duration d = Duration.between(Instant.now(), deadline);
            if (d.toMillis() <= 0) {
                throw new HttpTimeoutException("GET " + url + ": out of time");
            }
            return d;
        }
    }
}
