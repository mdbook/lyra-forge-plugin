package mtgai.traj;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import mtgai.encode.Observation;

/**
 * Trajectory records, schema 2 (schema 1 = the same without the ORACLE section, still
 * writable for the golden test): little-endian, packed; one chunk per seat per game, written at game end; files
 * {@code *.mtgt} written to a temp name and renamed. The golden test builds its fixtures through {@link #chunk} and
 * checks the SHA-256 of the binary output for schemas 1 and 2.
 *
 * <p>Schema 3 (search targets) = schema 2 + an IMPROVED section after
 * ORACLE in every record ({@code n_pi} u8, 0 or {@code n_cand}, then {@code n_pi} f32 pi'). {@link #chunk(Header, List)}
 * writes it only for a chunk with at least one SEARCHED step; every other chunk stays schema 2, byte-identical.
 */
public final class TrajectoryWriter {
    /** The schema of a chunk without SEARCHED steps (all of them unless a worker runs search targets). */
    public static final int SCHEMA_VERSION = 2;
    /** The schema of a chunk with at least one SEARCHED step (IMPROVED section). */
    public static final int SCHEMA_SEARCHED = 3;
    public static final int HEADER_BYTES = 136;
    public static final int HEAD_BYTES = 64;
    public static final int TERMINAL = 1;
    public static final int TRUNCATED = 2;
    public static final int NOT_POLICY = 4;
    /** The action is the imitation teacher's pick (TeacherOracle), always with NOT_POLICY. */
    public static final int TEACHER = 8;
    /** The action is a search's pick and the record carries pi' (schema 3 IMPROVED), always with NOT_POLICY. */
    public static final int SEARCHED = 16;
    /**
     * The action is a HUMAN's pick, recorded from the human seat of a game against the Neural seat (bit 5):
     * always with NOT_POLICY | TEACHER, so readers that do not know it train on it as a teacher pick.
     */
    public static final int HUMAN = 32;
    private static final int[] CAPS = {192, 28, 32, mtgai.encode.ObservationEncoder.HIST_RECORD, 64};
    private static final int ORC_CAP = 64;

    private TrajectoryWriter() {
    }

    /** Chunk header fields; hashes are raw bytes (20 / 32 / 32), zero-padded if shorter. */
    public record Header(int encoderVersion, int seat, int outcome, long workerId, long gameId, long seed,
                         long modelVersion0, long modelVersion1, byte[] engineCommit, byte[] cardsfolderSha,
                         byte[] deckManifestSha) {
    }

    /**
     * One decision record. {@code mask} bit i = candidate i legal. {@code improved} (schema 3 IMPROVED): pi'
     * over the {@code n_cand} candidates of a SEARCHED record, else null.
     */
    public record Step(Observation obs, int action, int flags, long mask, float logp, float value, float reward, long ply,
                       float[] improved) {
        public Step(Observation obs, int action, int flags, long mask, float logp, float value, float reward, long ply) {
            this(obs, action, flags, mask, logp, value, reward, ply, null);
        }

        public Step withEnd(int endFlags, float endReward) {
            return new Step(obs, action, flags | endFlags, mask, logp, value, endReward, ply, improved);
        }
    }

    /** v1: the seat lists only legal candidates. */
    public static long fullMask(int nCand) {
        return nCand == 64 ? -1L : (1L << nCand) - 1;
    }

    /**
     * The steps of one finished game with the final one marked TERMINAL (or TRUNCATED) carrying the outcome as its
     * reward; {@code steps} must be non-empty. Shared by self-play and the human-seat game upload.
     */
    public static List<Step> ended(List<Step> steps, boolean truncated, int outcome) {
        final List<Step> out = new java.util.ArrayList<>(steps);
        final int last = out.size() - 1;
        out.set(last, out.get(last).withEnd(truncated ? TRUNCATED : TERMINAL, outcome));
        return out;
    }

    /** Schema 3 if any step carries pi' (SEARCHED), else {@link #SCHEMA_VERSION}. */
    public static byte[] chunk(Header h, List<Step> steps) {
        boolean searched = false;
        for (Step s : steps) {
            searched |= s.improved() != null;
        }
        return chunk(searched ? SCHEMA_SEARCHED : SCHEMA_VERSION, h, steps);
    }

    public static byte[] chunk(int schema, Header h, List<Step> steps) {
        if (schema < 1 || schema > 3) {
            throw new IllegalArgumentException("schema " + schema);
        }
        int payload = 0;
        for (Step s : steps) {
            final Observation o = s.obs();
            payload += HEAD_BYTES + o.obj().length + o.lib().length + o.opp().length + o.hist().length + o.cand().length
                    + (schema >= 2 ? 2 + o.orc().length : 0)
                    + (schema >= 3 ? 1 + 4 * (s.improved() == null ? 0 : s.improved().length) : 0);
        }
        final ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES + payload).order(ByteOrder.LITTLE_ENDIAN);
        b.put("MTGT".getBytes(StandardCharsets.US_ASCII));
        b.putShort((short) schema).putShort((short) HEADER_BYTES).putShort((short) h.encoderVersion());
        b.put((byte) h.seat()).put((byte) h.outcome());
        b.putInt((int) h.workerId()).putLong(h.gameId()).putLong(h.seed());
        b.putInt((int) h.modelVersion0()).putInt((int) h.modelVersion1());
        b.putInt(steps.size()).putInt(payload);
        putFixed(b, h.engineCommit(), 20);
        putFixed(b, h.cardsfolderSha(), 32);
        putFixed(b, h.deckManifestSha(), 32);
        b.putInt(0);
        for (Step s : steps) {
            final Observation o = s.obs();
            final int[] n = {o.nObj(), o.nLib(), o.nOpp(), o.nHist(), o.nCand()};
            for (int i = 0; i < n.length; i++) {
                if (n[i] > CAPS[i]) {
                    throw new IllegalArgumentException("section " + i + ": " + n[i] + " > cap " + CAPS[i]);
                }
            }
            final int nc = n[4];
            if (nc < 1 || s.action() < 0 || s.action() >= nc || (nc < 64 && s.mask() >>> nc != 0)
                    || (s.mask() >>> s.action() & 1) == 0) {
                throw new IllegalArgumentException("bad action/mask/n_cand: " + s.action() + " "
                        + Long.toHexString(s.mask()) + " " + nc);
            }
            b.put((byte) o.kind());
            for (int v : n) {
                b.put((byte) v);
            }
            b.put((byte) s.action()).put((byte) s.flags()).putLong(s.mask());
            b.putFloat(s.logp()).putFloat(s.value()).putFloat(s.reward()).putInt((int) s.ply());
            if (o.glob().length != Observation.GLOBAL) {
                throw new IllegalArgumentException("global must be 32 bytes");
            }
            b.put(o.glob()).put(o.obj()).put(o.lib()).put(o.opp()).put(o.hist()).put(o.cand());
            if (schema >= 2) {
                if (o.nOrc() > ORC_CAP) {
                    throw new IllegalArgumentException("oracle section: " + o.nOrc() + " > cap " + ORC_CAP);
                }
                b.put((byte) o.nOrc()).put((byte) o.orcFlags()).put(o.orc());
            }
            checkImproved(schema, s, nc);
            if (schema >= 3) {
                final float[] pi = s.improved();
                b.put((byte) (pi == null ? 0 : pi.length));
                if (pi != null) {
                    for (float p : pi) {
                        b.putFloat(p);
                    }
                }
            }
        }
        return b.array();
    }

    /**
     * Schema 3: SEARCHED iff pi' present, SEARCHED implies NOT_POLICY, pi' has {@code n_cand} entries >= 0, 0 on
     * masked-out candidates, summing to 1 within 1e-5; a schema below 3 cannot carry pi' (never dropped silently).
     */
    private static void checkImproved(int schema, Step s, int nc) {
        final float[] pi = s.improved();
        final boolean flag = (s.flags() & SEARCHED) != 0;
        if (pi == null) {
            if (flag) {
                throw new IllegalArgumentException("SEARCHED step without pi'");
            }
            return;
        }
        if (schema < 3) {
            throw new IllegalArgumentException("schema " + schema + " cannot carry pi' (IMPROVED is schema 3)");
        }
        if (!flag || (s.flags() & NOT_POLICY) == 0) {
            throw new IllegalArgumentException("a step with pi' must be SEARCHED | NOT_POLICY: flags " + s.flags());
        }
        if (pi.length != nc) {
            throw new IllegalArgumentException("pi' has " + pi.length + " entries for " + nc + " candidates");
        }
        double sum = 0;
        for (int i = 0; i < nc; i++) {
            if (!(pi[i] >= 0) || (s.mask() >>> i & 1) == 0 && pi[i] != 0) {
                throw new IllegalArgumentException("pi'[" + i + "] = " + pi[i] + " (mask " + Long.toHexString(s.mask()) + ")");
            }
            sum += pi[i];
        }
        if (Math.abs(sum - 1) > 1e-5) {
            throw new IllegalArgumentException("pi' sums to " + sum);
        }
    }

    private static void putFixed(ByteBuffer b, byte[] v, int size) {
        final byte[] out = new byte[size];
        if (v != null) {
            System.arraycopy(v, 0, out, 0, Math.min(size, v.length));
        }
        b.put(out);
    }

    /** Concatenate chunks into {@code file} atomically (temp + rename). */
    public static void write(Path file, List<byte[]> chunks) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            for (byte[] c : chunks) {
                out.write(c);
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
