package mtgai.humanseat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sends the finished human-seat games to the game intake server and keeps the retry queue. The queue IS the
 * {@code traj/} directory next to {@code games.jsonl}: every {@code <id>.mtgt} / {@code <id>.json} still there is not
 * yet delivered. After delivery a file moves to {@code traj/sent/}; one the intake rejects for good (400 / 413) moves
 * to {@code traj/rejected/}; anything else (no network, 5xx, ...) stays and is tried again at the next game end and at
 * the next seat start, no more often than the backoff allows.
 *
 * <p>Wire format: {@code PUT <base>/v1/games/<filename>}, raw file body, {@code Content-Type: application/octet-stream},
 * {@code X-Mtgai-Build: <package/model info>}. 2xx or 409 (already there) = delivered. All of it runs on one background
 * daemon thread so a game never waits for the network; every failure is caught (stderr, never an exception).
 *
 * <p>Configuration: {@code -Dmtgai.neural.upload=<base url>}; unset or empty = off (opt-in). Off = the files are still
 * written, nothing is sent. The property is read at every call, never cached, so a per-game JVM can set it.
 *
 * <p>Consent ({@code -Dmtgai.neural.consent=<root>/<owner>/<match>}, set by the Forge Table router for consenting games
 * only; unset = no consent check): the recorder marks each game with an empty file {@code <match>/<id>} at game start
 * ({@link #markGame}); a queued file is sent only while some {@code <root>/<owner>/<match>/<id>} exists, else it is
 * deleted unsent. The router withdraws consent by removing the owner's folder, so a withdrawal covers live games and
 * ended games still waiting for a retry. Limit: a withdrawal between the check and the PUT cannot stop that one send.
 */
public final class GameUpload {
    public static final String UPLOAD_PROPERTY = "mtgai.neural.upload";
    public static final String CONSENT_PROPERTY = "mtgai.neural.consent";
    static final String SENT_DIR = "sent";
    static final String REJECTED_DIR = "rejected";
    /** The only names the intake accepts: the game id ({@code yyyyMMdd'T'HHmmss'Z'-xxxxxx}) + {@code .mtgt} / {@code .json}. */
    static final Pattern NAME = Pattern.compile("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{6}\\.(mtgt|json)$");
    static final Pattern ID = Pattern.compile("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{6}$");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long BACKOFF_START_MS = 30_000;
    private static final long BACKOFF_MAX_MS = 60 * 60_000;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "mtgai-game-upload");
        t.setDaemon(true);
        return t;
    });
    private static final Object LOCK = new Object();
    private static HttpClient client;
    /** Guarded by {@link #LOCK}: no attempt before this time (millis) unless forced; the delay doubles per failed flush. */
    private static long notBefore;
    private static long backoff = BACKOFF_START_MS;

    private GameUpload() {
    }

    /** The intake base URL, or null when uploading is off (property unset or empty). */
    static String baseUrl() {
        final String v = System.getProperty(UPLOAD_PROPERTY, "").trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    /** This game's consent folder ({@code <root>/<owner>/<match>}), or null when the consent property is unset. */
    static Path consentDir() {
        final String v = System.getProperty(CONSENT_PROPERTY, "").trim();
        return v.isEmpty() ? null : Path.of(v).toAbsolutePath();
    }

    /**
     * Marks game {@code id} as consented at game start. True = record it (consent property unset, or the marker was
     * created); false = consent withdrawn or unusable, record nothing. Never creates the match folder: a missing folder
     * means the router withdrew consent, and re-creating it would undo that.
     */
    public static boolean markGame(String id) {
        final Path d = consentDir();
        if (d == null) {
            return true;
        }
        if (id == null || !ID.matcher(id).matches()) {
            return false;
        }
        try {
            Files.createFile(d.resolve(id));
            return true;
        } catch (FileAlreadyExistsException e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Consent of a queued game: YES = send; NO = withdrawn, delete unsent; UNKNOWN = I/O trouble, keep and skip. */
    public enum Consent { YES, NO, UNKNOWN }

    /** True when THIS game still has its own marker (or the consent property is unset): the recorder's check. */
    public static boolean ownMarker(String id) {
        final Path d = consentDir();
        if (d == null) {
            return true;
        }
        return id != null && ID.matcher(id).matches() && Files.isRegularFile(d.resolve(id), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Consent of any queued game {@code id} (the queue holds other matches' and owners' retries too): YES when the
     * property is unset or a marker {@code <root>/<owner>/<match>/<id>} exists (root = the {@code consent} folder two
     * levels above this game's); NO when the walk completed without one; UNKNOWN on any other I/O error, so a transient
     * failure never deletes a consented game.
     */
    public static Consent consent(String id) {
        final Path d = consentDir();
        if (d == null) {
            return Consent.YES;
        }
        if (id == null || !ID.matcher(id).matches()) {
            return Consent.NO;
        }
        if (ownMarker(id)) {
            return Consent.YES;
        }
        final Path root = d.getParent() == null ? null : d.getParent().getParent();
        if (root == null || root.getFileName() == null || !"consent".equals(root.getFileName().toString())) {
            return Consent.UNKNOWN; // misconfigured property: neither send nor delete
        }
        try (DirectoryStream<Path> owners = Files.newDirectoryStream(root)) {
            for (Path owner : owners) {
                if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (DirectoryStream<Path> matches = Files.newDirectoryStream(owner)) {
                    for (Path match : matches) {
                        if (Files.isRegularFile(match.resolve(id), LinkOption.NOFOLLOW_LINKS)) {
                            return Consent.YES;
                        }
                    }
                } catch (NoSuchFileException e) {
                    // owner folder withdrawn mid-walk: no marker there
                }
            }
        } catch (NoSuchFileException e) {
            return Consent.NO;
        } catch (Throwable t) {
            return Consent.UNKNOWN;
        }
        return Consent.NO;
    }

    /** Queue a flush of {@code trajDir} on the background thread (honouring the backoff); returns at once. */
    public static Future<?> flushAsync(Path trajDir, String build) {
        return flushAsync(trajDir, build, false);
    }

    /** As {@link #flushAsync(Path, String)}; {@code force} ignores the backoff (tests). */
    public static Future<?> flushAsync(Path trajDir, String build, boolean force) {
        try {
            if (trajDir == null || baseUrl() == null) {
                return null;
            }
            return EXEC.submit(() -> {
                try {
                    flush(trajDir, build, force);
                } catch (Throwable t) {
                    System.err.println("[mtg-ai] game upload failed (will retry later): " + t);
                }
            });
        } catch (Throwable t) {
            System.err.println("[mtg-ai] game upload not started: " + t);
            return null;
        }
    }

    /** Test hook: forget the backoff. */
    public static void resetBackoff() {
        synchronized (LOCK) {
            notBefore = 0;
            backoff = BACKOFF_START_MS;
        }
    }

    /** Uploads what is queued in {@code trajDir}, {@code .mtgt} before {@code .json}; stops at the first retryable failure. */
    public static void flush(Path trajDir, String build, boolean force) {
        final String base = baseUrl();
        if (base == null || !Files.isDirectory(trajDir)) {
            return;
        }
        synchronized (LOCK) {
            if (!force && System.currentTimeMillis() < notBefore) {
                return;
            }
        }
        final List<Path> queue = new ArrayList<>();
        try (Stream<Path> s = Files.list(trajDir)) {
            s.filter(p -> NAME.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)).forEach(queue::add);
        } catch (IOException e) {
            System.err.println("[mtg-ai] could not list the game upload queue " + trajDir + ": " + e);
            return;
        }
        // oldest game first, the trajectory before its summary row
        queue.sort(Comparator.comparing((Path p) -> p.getFileName().toString().substring(0, 22))
                .thenComparing(p -> p.getFileName().toString().endsWith(".json")));
        // purge withdrawn games from the whole queue first, so a stuck send never leaves them on disk
        final List<Path> send = new ArrayList<>();
        for (Path f : queue) {
            final String fn = f.getFileName().toString();
            final Consent c = consent(fn.substring(0, fn.lastIndexOf('.')));
            if (c == Consent.YES) {
                send.add(f);
            } else if (c == Consent.NO) {
                try {
                    Files.deleteIfExists(f); // consent withdrawn: never sent
                } catch (IOException e) {
                    System.err.println("[mtg-ai] could not delete withdrawn game file " + f + ": " + e);
                }
            } // UNKNOWN: keep it queued, decide at the next flush
        }
        boolean failed = false;
        for (Path f : send) {
            if (!sendOne(base, f, trajDir, build)) {
                failed = true;
                break;
            }
        }
        synchronized (LOCK) {
            if (failed) {
                notBefore = System.currentTimeMillis() + backoff;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            } else {
                notBefore = 0;
                backoff = BACKOFF_START_MS;
            }
        }
    }

    /** True = settled (delivered or rejected, file moved); false = leave it and stop the flush. */
    private static boolean sendOne(String base, Path f, Path trajDir, String build) {
        final String name = f.getFileName().toString();
        try {
            final byte[] body = Files.readAllBytes(f);
            final HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/games/" + name))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Mtgai-Build", ascii(build))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            final int code = client().send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if ((code >= 200 && code < 300) || code == 409) {
                move(f, trajDir.resolve(SENT_DIR));
                return true;
            }
            if (code == 400 || code == 413) {
                System.err.println("[mtg-ai] the game intake rejected " + name + " (HTTP " + code + "); kept in "
                        + trajDir.resolve(REJECTED_DIR));
                move(f, trajDir.resolve(REJECTED_DIR));
                return true;
            }
            System.err.println("[mtg-ai] game upload of " + name + ": HTTP " + code + ", will retry later");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            System.err.println("[mtg-ai] game upload of " + name + " failed, will retry later: " + t);
            return false;
        }
    }

    private static void move(Path f, Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.move(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private static HttpClient client() {
        synchronized (LOCK) {
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build();
            }
            return client;
        }
    }

    /** Header values must be printable ASCII. */
    private static String ascii(String s) {
        final String v = s == null ? "unknown" : s.replaceAll("[^\\x20-\\x7e]", "?");
        return v.isBlank() ? "unknown" : v.toLowerCase(Locale.ROOT).equals("null") ? "unknown" : v;
    }
}
