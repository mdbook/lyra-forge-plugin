package mtgai.ops;

import mtgai.decision.DecisionOracle;
import mtgai.decision.DecisionRequest;
import mtgai.decision.DecisionResponse;

/** Times every {@code decide} of a policy seat into {@link WorkerMetrics#inference} (the /metrics inference ms). */
public final class TimedOracle extends ForwardingOracle {
    private final WorkerMetrics metrics;

    public TimedOracle(DecisionOracle inner, WorkerMetrics metrics) {
        super(inner);
        this.metrics = metrics;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        final long t0 = System.nanoTime();
        try {
            return inner.decide(request);
        } finally {
            metrics.inference(System.nanoTime() - t0);
        }
    }
}
