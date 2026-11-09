package mtgai.onnx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;
import mtgai.encode.CardVocab;
import mtgai.encode.Observation;
import mtgai.encode.ObservationEncoder;

/**
 * The neural policy through ONNX Runtime (CPU): one request = one B=1 forward
 * (inputs {@code tokens/glob/cand/mask/hist}), the action sampled from the masked softmax (or its
 * argmax when {@link #greedy(boolean) greedy}: evaluation games), {@code logp}/{@code value} returned for the trajectory
 * record; with a {@link DecisionBatcher} the
 * forward runs in a batch across the JVM's game threads.
 *
 * <p>One {@link Model} (the loaded session) is shared by the oracles of both seats; {@link Model#reload()}
 * is called between games only and refuses a model whose {@code encoder_version} differs from the encoder's.
 */
public final class OnnxOracle implements DecisionOracle {
    private final Model model;
    private final CardVocab vocab;
    private final long salt;
    private final DecisionBatcher batcher;
    private Random rnd = new Random(0);
    private float[] last;
    private boolean greedy;

    public OnnxOracle(Model model, CardVocab vocab, long salt) {
        this(model, vocab, salt, null);
    }

    /** {@code batcher} non-null: forwards go through the JVM's {@link DecisionBatcher} (N game threads). */
    public OnnxOracle(Model model, CardVocab vocab, long salt, DecisionBatcher batcher) {
        this.model = model;
        this.vocab = vocab;
        this.salt = salt;
        this.batcher = batcher;
    }

    /**
     * Greedy: play the argmax (lowest index on a tie) instead of sampling. The RNG is still drawn once per decision, so
     * switching it changes no other random stream. Returns this.
     */
    public OnnxOracle greedy(boolean on) {
        greedy = on;
        return this;
    }

    public boolean greedy() {
        return greedy;
    }

    @Override
    public CardVocab observationVocab() {
        return vocab;
    }

    @Override
    public void onGameStart(long seed, int seat) {
        rnd = new Random(seed * 0x9E3779B97F4A7C15L + seat * 7919L + salt);
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        final Observation o = (Observation) req.observation();
        final float[] out = batcher != null ? batcher.forward(o) : model.forward(o);
        last = out;
        final int n = o.nCand();
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, out[i]);
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.exp(out[i] - max);
        }
        final double lse = max + Math.log(sum);
        double u = rnd.nextDouble() * sum;
        int a = n - 1;
        for (int i = 0; i < n; i++) {
            u -= Math.exp(out[i] - max);
            if (u < 0) {
                a = i;
                break;
            }
        }
        if (greedy) {
            a = 0;
            for (int i = 1; i < n; i++) {
                if (out[i] > out[a]) {
                    a = i;
                }
            }
        }
        return DecisionResponse.sampled(a, (float) (out[a] - lse), out[n]);
    }

    @Override
    public String name() {
        return "onnx";
    }

    /** The last forward's output (logits[0..C) then value), for the per-decision debug log; game thread only. */
    @Override
    public float[] lastOutput() {
        return last;
    }

    /** The model this oracle plays (for its version). */
    public Model model() {
        return model;
    }

    /** The loaded ONNX session + its {@code latest.json} version. */
    public static final class Model implements AutoCloseable {
        private final OrtEnvironment env = OrtEnvironment.getEnvironment();
        private final Path dir;
        private OrtSession session;
        private long version = -1;
        private int encoder = ObservationEncoder.ENCODER_VERSION;

        public Model(Path dir) {
            this.dir = dir;
        }

        /**
         * A fixed, already-verified model ({@link ModelSource#load}: loaded in the background, swapped in at game
         * start); {@link #reload()} is not available on it.
         */
        public Model(long version, byte[] onnx) {
            this.dir = null;
            this.session = createSession(env, onnx);
            this.version = version;
        }

        static OrtSession createSession(OrtEnvironment env, byte[] bytes) {
            try {
                final OrtSession.SessionOptions so = new OrtSession.SessionOptions();
                so.setIntraOpNumThreads(Integer.getInteger("mtgai.ort.intraOp", 1)); // 1-2 threads is enough
                so.setInterOpNumThreads(1);
                return env.createSession(bytes, so);
            } catch (OrtException e) {
                throw new IllegalStateException(e);
            }
        }

        public long version() {
            return version;
        }

        /** The model's encoder_version (latest.json; this jar's encoder unless an older compatible one was loaded). */
        public int encoder() {
            return encoder;
        }

        /** Set by the loader from latest.json. */
        public Model encoder(int e) {
            encoder = e;
            return this;
        }

        /** Version named by latest.json right now, or -1 if there is none yet. */
        public long latestVersion() {
            final JsonObject m = latest();
            return m == null ? -1 : m.get("version").getAsLong();
        }

        private JsonObject latest() {
            if (dir == null) {
                return null;
            }
            final Path f = dir.resolve("latest.json");
            if (!Files.exists(f)) {
                return null;
            }
            try {
                return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Load the model latest.json names if it is new. Between games only. Returns the loaded version. */
        public long reload() {
            if (dir == null) {
                throw new IllegalStateException("fixed model v" + version + ": no directory to reload from");
            }
            final JsonObject m = latest();
            if (m == null) {
                throw new IllegalStateException("no latest.json in " + dir);
            }
            final int ev = m.get("encoder_version").getAsInt();
            if (!ObservationEncoder.runs(ev, ObservationEncoder.ENCODER_VERSION)) {
                throw new IllegalStateException("refusing model v" + m.get("version") + ": encoder_version " + ev
                        + " != encoder " + ObservationEncoder.ENCODER_VERSION);
            }
            encoder = ev;
            final long v = m.get("version").getAsLong();
            if (v == version) {
                return version;
            }
            try {
                final byte[] bytes = Files.readAllBytes(dir.resolve(m.get("file").getAsString()));
                final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!sha.equals(m.get("sha256").getAsString())) {
                    throw new IllegalStateException("sha256 mismatch for " + m.get("file"));
                }
                final OrtSession next = createSession(env, bytes);
                if (session != null) {
                    session.close();
                }
                session = next;
                version = v;
                return v;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (OrtException | NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** B = 1 forward: returns logits[0..C) followed by value at index C. */
        public float[] forward(Observation o) {
            return forwardBatch(List.of(o))[0];
        }

        /**
         * One forward over a batch: tokens and candidates zero-padded to the batch maxima (padding-invariant,
         * as the training code's tests check); per request logits[0..C_i) followed by the value.
         */
        public float[][] forwardBatch(List<Observation> obs) {
            final int b = obs.size();
            int t = 1;
            int c = 1;
            int h = 1;
            for (Observation o : obs) {
                t = Math.max(t, o.nObj() + o.nLib() + o.nOpp());
                c = Math.max(c, o.nCand());
                h = Math.max(h, o.actingHist().length / Observation.HIST);
            }
            final ByteBuffer tok = ByteBuffer.allocateDirect(b * t * Observation.OBJ);
            final ByteBuffer glob = ByteBuffer.allocateDirect(b * Observation.GLOBAL);
            final ByteBuffer cand = ByteBuffer.allocateDirect(b * c * Observation.CAND);
            final ByteBuffer mask = ByteBuffer.allocateDirect(b * c);
            final ByteBuffer hist = ByteBuffer.allocateDirect(b * h * Observation.HIST);
            for (int i = 0; i < b; i++) {
                final Observation o = obs.get(i);
                tok.position(i * t * Observation.OBJ);
                tok.put(o.obj()).put(o.lib()).put(o.opp());
                glob.put(o.glob());
                cand.position(i * c * Observation.CAND);
                cand.put(o.cand());
                for (int k = 0; k < c; k++) {
                    mask.put((byte) (k < o.nCand() ? 1 : 0));
                }
                hist.position(i * h * Observation.HIST);
                hist.put(o.actingHist());   // last MAX_HIST: a longer recorded HISTORY never reaches the policy
            }
            tok.rewind();
            glob.rewind();
            cand.rewind();
            mask.rewind();
            hist.rewind();
            try (OnnxTensor tokens = OnnxTensor.createTensor(env, tok, new long[] {b, t, Observation.OBJ}, OnnxJavaType.UINT8);
                 OnnxTensor g = OnnxTensor.createTensor(env, glob, new long[] {b, Observation.GLOBAL}, OnnxJavaType.UINT8);
                 OnnxTensor cd = OnnxTensor.createTensor(env, cand, new long[] {b, c, Observation.CAND}, OnnxJavaType.UINT8);
                 OnnxTensor m = OnnxTensor.createTensor(env, mask, new long[] {b, c}, OnnxJavaType.BOOL);
                 OnnxTensor hs = OnnxTensor.createTensor(env, hist, new long[] {b, h, Observation.HIST}, OnnxJavaType.UINT8);
                 OrtSession.Result r = session.run(Map.of("tokens", tokens, "glob", g, "cand", cd, "mask", m, "hist", hs))) {
                final float[][] logits = (float[][]) r.get(0).getValue();
                final float[] value = (float[]) r.get(1).getValue();
                final float[][] out = new float[b][];
                for (int i = 0; i < b; i++) {
                    final int ci = obs.get(i).nCand();
                    out[i] = new float[ci + 1];
                    System.arraycopy(logits[i], 0, out[i], 0, ci);
                    out[i][ci] = value[i];
                }
                return out;
            } catch (OrtException e) {
                throw new IllegalStateException(e);
            }
        }

        private static ByteBuffer direct(byte[] b) {
            final ByteBuffer d = ByteBuffer.allocateDirect(b.length);
            d.put(b).rewind();
            return d;
        }

        @Override
        public void close() throws OrtException {
            if (session != null) {
                session.close();
            }
        }
    }
}
