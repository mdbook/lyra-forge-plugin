package mtgai.humanseat;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The self-updating layout of the packaged builds (human seat, Forge Table). Their bootstrap launchers run
 * {@code UpdateCheck --install-builds DIR --channel humanseat|forge-table [--previous] [--no-update-check]} from the
 * current build's {@code lib/}, then start the build {@code app/current} names. The layout:
 *
 * <pre>
 * DIR/            the bootstrap launchers, README.txt (they rarely change; an update never touches them)
 *   app/current   one line: the id of the build to run (its built_at in UTC as yyyyMMdd-HHmmss)
 *   app/previous  the build that ran before it, for --previous (absent until the first update)
 *   app/.update-state  after a failed install: which build, how many attempts, when (the backoff)
 *   app/ID/       a build: version.json, lib/ (with mtgai-java.jar), launch.sh, launch.cmd, neural/, user-seed/, ...
 *   user/         what survives updates: Forge's user folder, its cache, the model in use (neural/), game logs
 * </pre>
 *
 * <p>All of it runs under {@code app/.lock}; a second start that finds the lock held changes nothing. First the temp
 * files of a killed run go. Then, unless offline or switching back, a newer build is installed if the channel's
 * pointer ({@code <channel>/latest/version.json}) has a later built_at than {@code app/<current>/version.json}:
 * <ol>
 * <li>the pointer must carry a valid signature ({@link UpdateCheck#pointer}): checked before any field is read;
 * <li>the pointer's zip_url must be {@code PACKAGES/<channel>/<folder>/<zip>}, https to the pinned host as for every
 *     URL ({@link UpdateCheck#pinned}), no redirects; the body is capped at the pointer's size (else {@link #BUILD_MAX})
 *     and gets {@link #BUILD_BUDGET};
 * <li>it is streamed to {@code app/ID.zip.tmp}, must match the pointer's size and sha256, and is then kept as
 *     {@code app/ID.zip} until the build is unpacked (an install that fails later reuses it, sha256 checked again);
 * <li>the zip is the package as a new player downloads it, so the build is its {@code <top>/app/ID/}: that is unpacked
 *     into {@code app/ID.tmp/}, the rest (the bootstrap launchers, which an update never replaces) skipped. Every entry
 *     must be a plain file or folder below the zip's one top folder (the zip's own name): no absolute path, no
 *     {@code ..}, no backslash or colon, no symlink;
 * <li>the result must be complete ({@link #REQUIRED}) with the pointer's built_at in its version.json;
 * <li>{@code app/ID.ready} marks the checked unpack (the zip goes); {@code app/ID.tmp/} is renamed to {@code app/ID/}
 *     (retried for {@link #MOVE_PATIENCE}: a Windows virus scanner may hold the fresh files; if it still fails, the
 *     checked unpack stays for the next start); only then {@code previous} and {@code current} are rewritten, each as
 *     a temp file renamed over the old one; then "Updated to build ID".
 * </ol>
 * Any failure after the pointer prints one line, leaves {@code current} as it was and is recorded in
 * {@code app/.update-state}: the same build is tried again only after 1 h, then 6 h, then daily (a newer pointer starts
 * afresh). A pointer that cannot be fetched (offline, timeout) is silent; an unsigned or badly signed one prints one
 * line. Builds other than current, previous and any newer than current (left by --previous) are then pruned. --previous
 * points current at the previous build and leaves previous as it is, so a second --previous changes nothing. Last,
 * the current build's
 * {@code user-seed/} fills in what {@code user/} lacks (never overwriting), its bundled model replaces the one in
 * {@code user/neural/} if that is missing, older or for another encoder, and the model update of {@link UpdateCheck}
 * runs on {@code user/neural/}.
 */
final class BuildInstall {
    static final Duration BUILD_BUDGET = Duration.ofMinutes(10);
    /** Body cap for a build zip whose pointer gives no size; a larger size is refused. */
    static final long BUILD_MAX = 600L << 20;
    /** Unpacked bytes of one build, at most. */
    static final long UNPACKED_MAX = 2L << 30;
    /** How long a rename may be retried (Windows: a virus scanner holding fresh files). */
    static final Duration MOVE_PATIENCE = Duration.ofSeconds(60);
    static final String STATE = ".update-state";
    static final String CURRENT = "current";
    static final String PREVIOUS = "previous";
    /** What a build must have: the bootstrap reads version.json, starts a launcher and runs UpdateCheck from lib/. */
    static final List<String> REQUIRED = List.of("version.json", "launch.sh", "launch.cmd", "lib/mtgai-java.jar");
    static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern BUILD = Pattern.compile("\\d{8}-\\d{6}");
    /** What a killed run leaves: temp files, and an unpacked build that was not checked ({@code ID.tmp} without ID.ready). */
    private static final Pattern TEMP = Pattern.compile("(\\d{8}-\\d{6}(\\.zip)?|current|previous|\\.update-state)\\.tmp");
    /** Kept between starts for the pending build only: its verified zip, its checked unpack and that unpack's marker. */
    private static final Pattern KEPT = Pattern.compile("(\\d{8}-\\d{6})\\.(zip|tmp|ready)");
    private static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]*";
    private static final Pattern ZIP = Pattern.compile(NAME + "\\.zip");

    private final UpdateCheck check;
    private final boolean verbose;

    BuildInstall(UpdateCheck check, boolean verbose) {
        this.check = check;
        this.verbose = verbose;
    }

    /** Everything before the bootstrap starts {@code app/<current>/}; see the class comment. Never throws. */
    void launch(Path dir, String channel, boolean previous, boolean offline) {
        final Path top = dir.toAbsolutePath().normalize();
        final Path app = top.resolve("app");
        try (FileChannel ch = FileChannel.open(app.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = tryLock(ch)) {
            if (lock == null) {
                skipped("another start holds app/.lock", null);
                return;
            }
            sweep(app);
            String current = read(app, CURRENT);
            if (previous) {
                current = switchBack(app, current);
            } else if (!offline) {
                current = update(app, channel, current);
            }
            final Backoff pending = Backoff.read(app);
            prune(app, current, readOrNull(app, PREVIOUS), pending == null ? null : pending.build());
            try {
                seed(app.resolve(current), top.resolve("user"));
            } catch (IOException | RuntimeException e) {
                check.out.println("Could not set up the user folder (" + reason(e) + ")");
            }
            if (!offline) {
                check.modelUpdate(top.resolve("user").resolve("neural"), verbose);
            }
        } catch (IOException | RuntimeException e) {
            skipped("build check skipped", e);
        }
    }

    /** The lock, or null if another start holds it (another process: null; this JVM: an exception, made null). */
    private static FileLock tryLock(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    /** The id to run: a newer build once installed, else {@code current}. */
    private String update(Path app, String channel, String current) {
        final Map<String, String> ptr;
        final Instant built;
        try {
            final Instant have = builtAt(app.resolve(current));
            ptr = check.pointer(UpdateCheck.buildPointer(channel));
            built = Instant.parse(UpdateCheck.required(ptr, "built_at"));
            if (!built.isAfter(have) || id(built).equals(current)) {
                return current; // nothing newer (a replayed older pointer, however well signed, changes nothing)
            }
        } catch (UpdateCheck.Unsigned e) {
            check.out.println("Update check refused (" + e.getMessage() + "); playing build " + current);
            return current;
        } catch (IOException | RuntimeException e) {
            skipped("build check skipped", e);
            return current;
        }
        final String id = id(built);
        final Backoff failed = Backoff.read(app);
        if (failed != null && failed.build().equals(id) && Instant.now().isBefore(failed.next())) {
            skipped("build " + id + " failed " + failed.attempts() + " time(s); next try after " + failed.next(), null);
            return current;
        }
        try {
            final Path dir = app.resolve(id);
            if (!complete(dir, built)) { // else still here from before a --previous
                deleteTree(dir);
                install(app, channel, ptr, id, built);
            }
            write(app, PREVIOUS, current);
            write(app, CURRENT, id);
            Files.deleteIfExists(app.resolve(STATE));
            check.out.println("Updated to build " + id);
            return id;
        } catch (IOException | RuntimeException e) {
            check.out.println("Update to build " + id + " failed (" + reason(e) + "); playing build " + current);
            Backoff.failed(app, id, failed);
            return current;
        }
    }

    /**
     * {@code app/.update-state}: the build whose install failed, how many times, and when last. The next try waits
     * 1 h after the first failure, 6 h after the second, a day after any later one.
     */
    record Backoff(String build, int attempts, Instant last) {
        Instant next() {
            return last.plus(attempts <= 1 ? Duration.ofHours(1) : attempts == 2 ? Duration.ofHours(6) : Duration.ofDays(1));
        }

        /** The recorded failure, or null if there is none (or it is unreadable: then the build is simply tried). */
        static Backoff read(Path app) {
            try {
                final Map<String, String> m = UpdateCheck.flatJson(Files.readString(app.resolve(STATE)));
                return new Backoff(UpdateCheck.field(m, "build", BUILD), (int) UpdateCheck.number(m, "attempts"),
                        Instant.parse(UpdateCheck.required(m, "last")));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /** Records one more failure of {@code id} (the first, if {@code was} is about another build). Best effort. */
        static void failed(Path app, String id, Backoff was) {
            final int attempts = was != null && was.build().equals(id) ? was.attempts() + 1 : 1;
            try {
                final Path tmp = app.resolve(STATE + ".tmp");
                Files.writeString(tmp, String.format(Locale.ROOT, "{\"build\": \"%s\", \"attempts\": %d, \"last\": \"%s\"}%n",
                        id, attempts, Instant.now().truncatedTo(ChronoUnit.SECONDS)));
                move(tmp, app.resolve(STATE), null, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                // then the next start tries again
            }
        }
    }

    /**
     * Puts the pointer's build in {@code app/<id>/}: from a checked unpack an earlier start left ({@code app/<id>.tmp/}
     * with {@code app/<id>.ready}), else from the zip, downloading it unless an earlier start left it verified.
     */
    private void install(Path app, String channel, Map<String, String> ptr, String id, Instant built) throws IOException {
        final Path tmp = app.resolve(id + ".tmp");
        final Path ready = app.resolve(id + ".ready");
        if (!Files.isRegularFile(ready) || !complete(tmp, built)) {
            deleteTree(tmp);
            Files.deleteIfExists(ready);
            final String zipName = UpdateCheck.field(ptr, "zip", ZIP);
            final URI url = zipUrl(channel, ptr.get("zip_url"), zipName);
            final String sha = UpdateCheck.field(ptr, "sha256", UpdateCheck.SHA256);
            final long size = ptr.get("size") == null ? -1 : UpdateCheck.number(ptr, "size");
            if (size > BUILD_MAX) {
                throw new IOException("the pointer says " + size + " bytes");
            }
            final Path zip = app.resolve(id + ".zip");
            if (!verified(zip, size, sha)) {
                Files.deleteIfExists(zip);
                final Path part = app.resolve(id + ".zip.tmp");
                try {
                    download(url, size, part, sha, id);
                    move(part, zip, null, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(part);
                }
            }
            try {
                unpack(zip, zipName.substring(0, zipName.length() - ".zip".length()), "app/" + id + "/", tmp, UNPACKED_MAX);
                checkComplete(tmp, built);
            } catch (IOException | RuntimeException e) {
                deleteTree(tmp); // the verified zip stays for the next try
                throw e;
            }
            Files.writeString(ready, "");
            Files.deleteIfExists(zip);
        }
        // if this still fails (a scanner holding the files for over a minute), tmp + ready stay for the next start
        move(tmp, app.resolve(id), check.out, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(ready);
    }

    /** Whether {@code zip} exists with the pointer's size (if given) and sha256. */
    private static boolean verified(Path zip, long size, String sha) {
        try (InputStream in = Files.newInputStream(zip)) {
            if (size >= 0 && Files.size(zip) != size) {
                return false;
            }
            final MessageDigest md = sha256();
            in.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), md));
            return HexFormat.of().formatHex(md.digest()).equals(sha);
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code zipUrl} if it is exactly {@code PACKAGES/<channel>/<folder>/<zipName>}, else throws. */
    static URI zipUrl(String channel, String zipUrl, String zipName) throws IOException {
        final URI url = UpdateCheck.pinned(zipUrl);
        final String want = Pattern.quote(UpdateCheck.PACKAGES + channel + "/") + NAME + "/" + Pattern.quote(zipName);
        if (!zipUrl.matches(want)) {
            throw new IOException("refusing zip url " + zipUrl + " (want " + UpdateCheck.PACKAGES + channel + "/.../" + zipName + ")");
        }
        return url;
    }

    /** Streams the zip to {@code to} with a progress line, then checks its size and sha256. */
    private void download(URI url, long size, Path to, String sha, String id) throws IOException {
        final MessageDigest md = sha256();
        check.out.print("Downloading update " + id
                + (size < 0 ? "" : String.format(Locale.ROOT, " (%.0f MB)", size / 1e6)) + "...");
        try (OutputStream o = new Progress(new DigestOutputStream(Files.newOutputStream(to, StandardOpenOption.CREATE_NEW), md), size)) {
            check.http.download(url, size < 0 ? BUILD_MAX : size, Instant.now().plus(BUILD_BUDGET), o);
        } finally {
            check.out.println();
        }
        if (size >= 0 && Files.size(to) != size) {
            throw new IOException("got " + Files.size(to) + " bytes, the pointer says " + size);
        }
        if (!HexFormat.of().formatHex(md.digest()).equals(sha)) {
            throw new IOException("sha256 mismatch");
        }
    }

    /** Prints " 10%", " 20%", ... as the bytes pass each tenth of {@code size} (nothing if the size is unknown). */
    private final class Progress extends FilterOutputStream {
        private final long size;
        private long n;
        private int tenths;

        Progress(OutputStream out, long size) {
            super(out);
            this.size = size;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count(len);
        }

        private void count(int len) {
            n += len;
            while (size > 0 && tenths < 10 && n * 10 / size > tenths) {
                check.out.print(" " + ++tenths * 10 + "%");
            }
        }
    }

    /**
     * Unpacks the folder {@code top/sub} of {@code zip} into {@code into} (which must not exist), at most {@code cap}
     * bytes (as declared, then as read). Throws on any entry that is outside {@code top/}, not a plain file or folder,
     * or not a plain relative path, unpacked or not.
     */
    static void unpack(Path zip, String top, String sub, Path into, long cap) throws IOException {
        final Path dir = into.toAbsolutePath().normalize();
        // first every entry is checked, then the build's are written
        final Map<String, Path> files = new LinkedHashMap<>(); // entry -> where it goes; a folder's entry ends in "/"
        final Set<String> seen = new HashSet<>();
        for (Entry e : centralDirectory(zip)) {
            final String rel = relative(e.name, top);
            if (!seen.add(e.name)) {
                throw new IOException("duplicate entry " + e.name);
            }
            if (e.type != 0 && e.type != 0x4000 && e.type != 0x8000) {
                throw new IOException("not a plain file or folder: " + e.name);
            }
            if (rel.startsWith(sub) && !rel.equals(sub)) { // the rest is not part of the build, or its folder itself
                final Path target = dir.resolve(rel.substring(sub.length()));
                if (!target.normalize().startsWith(dir)) {
                    throw new IOException("bad entry " + e.name); // relative() already refuses this; belt and braces
                }
                files.put(e.name, target);
            }
        }
        try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            if (zf.size() != seen.size()) {
                throw new IOException("zip directory mismatch");
            }
            long declared = 0;
            for (String name : files.keySet()) {
                final ZipEntry ze = zf.getEntry(name);
                declared += ze == null ? 0 : Math.max(0, ze.getSize());
            }
            if (declared > cap) {
                throw new IOException("unpacks to more than " + cap + " bytes");
            }
            Files.createDirectory(dir);
            long total = 0;
            for (Map.Entry<String, Path> f : files.entrySet()) {
                if (f.getKey().endsWith("/")) {
                    Files.createDirectories(f.getValue());
                    continue;
                }
                final ZipEntry ze = zf.getEntry(f.getKey());
                if (ze == null || ze.isDirectory()) {
                    throw new IOException("zip directory mismatch at " + f.getKey());
                }
                Files.createDirectories(f.getValue().getParent());
                try (InputStream in = zf.getInputStream(ze);
                     OutputStream o = Files.newOutputStream(f.getValue(), StandardOpenOption.CREATE_NEW)) {
                    final byte[] buf = new byte[1 << 16];
                    for (int k; (k = in.read(buf)) > 0; ) {
                        total += k;
                        if (total > cap) {
                            throw new IOException("unpacks to more than " + cap + " bytes");
                        }
                        o.write(buf, 0, k);
                    }
                }
            }
        }
    }

    /**
     * The path of zip entry {@code name} below its top folder {@code top} ("" for the folder itself), or throws:
     * the entry must start with {@code top/}, and every segment after it be a plain name.
     */
    static String relative(String name, String top) throws IOException {
        if (!name.startsWith(top + "/") || name.chars().anyMatch(c -> c < 0x20 || c == '\\' || c == ':')) {
            throw new IOException("bad entry " + name);
        }
        final String rel = name.substring(top.length() + 1);
        final String[] parts = rel.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            final String p = parts[i];
            if (p.equals(".") || p.equals("..") || (p.isEmpty() && i < parts.length - 1)) {
                throw new IOException("bad entry " + name);
            }
        }
        return rel;
    }

    /** A central directory entry: its name and, if made on Unix, its file type bits (0x4000 folder, 0x8000 file). */
    private record Entry(String name, int type) { }

    /**
     * The entries of the zip's central directory, read directly because {@link ZipFile} does not expose the Unix mode
     * that marks a symlink. Plain zips only: no zip64, no multi-disk (more than 65534 entries or 4 GB is refused).
     */
    private static List<Entry> centralDirectory(Path zip) throws IOException {
        try (FileChannel ch = FileChannel.open(zip)) {
            final long size = ch.size();
            final ByteBuffer tail = read(ch, Math.max(0, size - (22 + 0xFFFF)), (int) Math.min(size, 22 + 0xFFFF));
            int eocd = tail.limit() - 22;
            while (eocd >= 0 && tail.getInt(eocd) != 0x06054b50) {
                eocd--;
            }
            if (eocd < 0) {
                throw new IOException("not a zip");
            }
            final int count = tail.getShort(eocd + 10) & 0xFFFF;
            final long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
            final long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
            if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdSize > size || (tail.getShort(eocd + 4) | tail.getShort(eocd + 6)) != 0) {
                throw new IOException("unsupported zip (zip64 or multi-disk)");
            }
            final ByteBuffer cd = read(ch, cdOffset, (int) cdSize);
            final List<Entry> entries = new ArrayList<>(count);
            int p = 0;
            for (int k = 0; k < count; k++) {
                if (p + 46 > cd.limit() || cd.getInt(p) != 0x02014b50) {
                    throw new IOException("bad zip directory");
                }
                final int madeBy = (cd.getShort(p + 4) & 0xFFFF) >> 8;
                final int nameLen = cd.getShort(p + 28) & 0xFFFF;
                final int skip = (cd.getShort(p + 30) & 0xFFFF) + (cd.getShort(p + 32) & 0xFFFF);
                final int mode = cd.getInt(p + 38) >>> 16;
                if (p + 46 + nameLen > cd.limit()) {
                    throw new IOException("bad zip directory");
                }
                final byte[] name = new byte[nameLen];
                cd.get(p + 46, name);
                entries.add(new Entry(new String(name, StandardCharsets.UTF_8), madeBy == 3 ? mode & 0xF000 : 0));
                p += 46 + nameLen + skip;
            }
            return entries;
        }
    }

    private static ByteBuffer read(FileChannel ch, long at, int len) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (ch.read(b, at + b.position()) < 0) {
                throw new IOException("truncated zip");
            }
        }
        return b.flip();
    }

    /** Throws unless {@code build} has every {@link #REQUIRED} file (and, if {@code built} is given, that built_at). */
    static void checkComplete(Path build, Instant built) throws IOException {
        for (String f : REQUIRED) {
            if (!Files.isRegularFile(build.resolve(f), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("incomplete build: no " + f);
            }
        }
        if (built != null && !builtAt(build).equals(built)) {
            throw new IOException("its version.json says built_at " + builtAt(build) + ", the pointer " + built);
        }
    }

    private static boolean complete(Path build, Instant built) {
        try {
            checkComplete(build, built);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static Instant builtAt(Path build) throws IOException {
        return Instant.parse(UpdateCheck.required(UpdateCheck.flatJson(Files.readString(build.resolve("version.json"))), "built_at"));
    }

    static String id(Instant built) {
        return ID.format(built);
    }

    /**
     * Points {@code current} at {@code previous}, which stays: a second --previous is a no-op. The build left stays on
     * disk (prune keeps builds newer than current), so the next update to it needs no download. Returns the id to run.
     */
    private String switchBack(Path app, String current) throws IOException {
        final String prev = readOrNull(app, PREVIOUS);
        if (current.equals(prev)) {
            check.out.println("Already on the previous build " + current);
            return current;
        }
        if (prev == null || !complete(app.resolve(prev), null)) {
            check.out.println("No previous build to go back to; playing build " + current);
            return current;
        }
        write(app, CURRENT, prev);
        check.out.println("Switched back to build " + prev + " (from " + current + ")");
        return prev;
    }

    /** The build id in {@code app/<name>}; throws unless it names a folder in {@code app/}. */
    static String read(Path app, String name) throws IOException {
        final String id = Files.readString(app.resolve(name)).trim();
        if (!BUILD.matcher(id).matches() || !Files.isDirectory(app.resolve(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("app/" + name + " names no build: " + id);
        }
        return id;
    }

    private static String readOrNull(Path app, String name) {
        try {
            return read(app, name);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Points {@code app/<name>} at {@code id}: a temp file renamed over the old one. */
    private static void write(Path app, String name, String id) throws IOException {
        final Path tmp = app.resolve(name + ".tmp");
        Files.writeString(tmp, id);
        move(tmp, app.resolve(name), null, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * {@link Files#move}, retried with growing pauses (0.1 s doubling to 5 s) for up to {@link #MOVE_PATIENCE}: on
     * Windows a virus scanner may hold a fresh file open. {@code note}, if given, hears once that it is waiting.
     */
    private static void move(Path from, Path to, PrintStream note, CopyOption... options) throws IOException {
        final Instant end = Instant.now().plus(MOVE_PATIENCE);
        for (long pause = 100; ; pause = Math.min(2 * pause, 5000)) {
            try {
                Files.move(from, to, options);
                return;
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException | NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                if (Instant.now().plusMillis(pause).isAfter(end)) {
                    throw e;
                }
                if (note != null && pause == 100) {
                    note.println("Waiting for the new build's files to be released (a virus scan?)...");
                }
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    /**
     * What a killed run left in {@code app/}: temp files ({@code ID.zip.tmp}, {@code current.tmp}, ...), an unpack that
     * was not checked ({@code ID.tmp/} without {@code ID.ready}) and a marker without its unpack.
     */
    private static void sweep(Path app) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(app)) {
            for (Path p : ds) {
                final String name = p.getFileName().toString();
                final Matcher kept = KEPT.matcher(name);
                if (kept.matches() && kept.group(2).equals("tmp")) {
                    if (!Files.isRegularFile(app.resolve(kept.group(1) + ".ready"))) {
                        deleteTree(p);
                    }
                } else if (kept.matches() && kept.group(2).equals("ready")) {
                    if (!Files.isDirectory(app.resolve(kept.group(1) + ".tmp"))) {
                        Files.deleteIfExists(p);
                    }
                } else if (TEMP.matcher(name).matches()) {
                    deleteTree(p);
                }
            }
        }
    }

    /**
     * Build folders other than current, previous and any newer than current (left by --previous) go, and so does what
     * an earlier start kept for a build other than {@code pending} (the one in app/.update-state). Best effort
     * (Windows refuses to delete a running build's files).
     */
    private static void prune(Path app, String current, String previous, String pending) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(app)) {
            for (Path p : ds) {
                final String name = p.getFileName().toString();
                final Matcher kept = KEPT.matcher(name);
                if (BUILD.matcher(name).matches() && !name.equals(current) && !name.equals(previous)
                        && name.compareTo(current) < 0) {
                    deleteTree(p);
                } else if (kept.matches() && !kept.group(1).equals(pending)) {
                    deleteTree(p);
                }
            }
        }
    }

    /** Deletes {@code p} and, if it is a real folder, everything in it (links are removed, not followed). Best effort. */
    static void deleteTree(Path p) {
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
            // nothing to delete
        } catch (IOException ignored) {
            // retried by the next sweep or prune
        }
    }

    /**
     * Fills in what {@code user/} lacks from the build's {@code user-seed/} (never overwriting anything), and puts the
     * build's model in {@code user/neural/} if that has none, an older one, or one for another encoder.
     */
    static void seed(Path build, Path user) throws IOException {
        final Path seed = build.resolve("user-seed");
        if (Files.isDirectory(seed)) {
            for (Path f : files(seed)) {
                final Path to = user.resolve(seed.relativize(f).toString());
                if (Files.notExists(to, LinkOption.NOFOLLOW_LINKS)) {
                    copy(f, to);
                }
            }
        }
        final Path bundled = build.resolve("neural");
        // the all-cards registry is seeded on its own: an install whose model is already current would never get it
        // through the model branch below (copied when missing or different; ids only ever append, an old model reads
        // the new ids as UNK)
        final Path registry = bundled.resolve(NeuralSeat.REGISTRY_FILE);
        if (Files.isRegularFile(registry)) {
            final Path to = user.resolve("neural").resolve(NeuralSeat.REGISTRY_FILE);
            if (Files.notExists(to, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(registry, to) != -1) {
                copy(registry, to);
            }
        }
        if (!Files.isRegularFile(bundled.resolve("latest.json"))) {
            return;
        }
        final Map<String, String> want = UpdateCheck.flatJson(Files.readString(bundled.resolve("latest.json")));
        final Path live = user.resolve("neural");
        String had = null;
        try {
            final Map<String, String> have = UpdateCheck.flatJson(Files.readString(live.resolve("latest.json")));
            had = UpdateCheck.field(have, "file", UpdateCheck.POLICY);
            if (UpdateCheck.number(have, "version") >= UpdateCheck.number(want, "version")
                    && Objects.equals(have.get("encoder_version"), want.get("encoder_version"))
                    && Files.isRegularFile(live.resolve(had))) {
                return; // the model there is this build's or a newer one it can play
            }
        } catch (IOException | RuntimeException e) {
            // none yet, or unreadable: seed
        }
        for (Path f : files(bundled)) {
            if (!f.equals(bundled.resolve("latest.json"))) {
                copy(f, live.resolve(bundled.relativize(f).toString()));
            }
        }
        copy(bundled.resolve("latest.json"), live.resolve("latest.json")); // last: it names the policy copied above
        UpdateCheck.prune(live, UpdateCheck.field(want, "file", UpdateCheck.POLICY), had);
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    /** Copies {@code from} over {@code to} through a temp file next to it, so a killed run leaves no half file. */
    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        final Path tmp = to.resolveSibling(to.getFileName() + ".seed.tmp");
        Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
        move(tmp, to, null, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void skipped(String what, Exception e) {
        if (verbose) {
            System.err.println("[update] " + what + (e == null ? "" : ": " + e));
        }
    }

    private static String reason(Exception e) {
        return e instanceof HttpTimeoutException ? "timed out" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
