package mtgai.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mtgai.decision.Candidate;
import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;

/**
 * Opt-in per-decision debug log: one JSON line per oracle decision with the game id, seat,
 * window (turn, phase, own turn), kind, context, the candidate labels, the policy logits (when the oracle is a
 * model: {@link DecisionOracle#lastOutput()}), the chosen index, logp, value and whether the answer was sampled
 * from the policy. Every candidate listed is legal (the seat only offers legal ones, the action mask). Shared writer,
 * one line per synchronized write; costs a string build per decision, so off by default ({@code --decision-log}).
 */
public final class DecisionLog extends ForwardingOracle {
    private static final Gson GSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create();
    private final Writer out;
    private final String game;
    private int n;

    public DecisionLog(DecisionOracle inner, Writer out, String game) {
        super(inner);
        this.out = out;
        this.game = game;
    }

    @Override
    public void onGameStart(long seed, int seat) {
        n = 0;
        inner.onGameStart(seed, seat);
    }

    @Override
    public DecisionResponse decide(DecisionRequest req) {
        final DecisionResponse r = inner.decide(req);
        final float[] raw = inner.lastOutput();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("game", game);
        m.put("seat", req.seat());
        m.put("n", n++);
        m.put("turn", req.turn());
        m.put("phase", req.phase());
        m.put("own_turn", req.ownTurn());
        m.put("kind", req.kind().name());
        m.put("context", req.context());
        final List<String> cands = new ArrayList<>(req.size());
        for (Candidate c : req.candidates()) {
            cands.add(c.toString());
        }
        m.put("candidates", cands);
        if (raw != null && raw.length == req.size() + 1) {
            final float[] logits = new float[req.size()];
            System.arraycopy(raw, 0, logits, 0, logits.length);
            m.put("logits", logits);
            m.put("model_value", raw[req.size()]);
        } else {
            m.put("logits", null);
        }
        m.put("chosen", r.index());
        m.put("logp", r.logp());
        m.put("value", r.value());
        m.put("policy", r.policy());
        final Map<String, Object> search = inner.lastInfo();
        if (search != null) { // PIMC: searched or fallback, sims, ms, per-candidate values
            m.put("search", search);
        }
        final String line = GSON.toJson(m);
        synchronized (out) {
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return r;
    }
}
