package chronos.runtime;

import chronos.model.IdentityHash;
import chronos.sdk.Scenario;

/** Record mode: answers come from the seeded, identity-keyed policy (the scenario's links and the seed). */
public final class RecordDecisionSource implements DecisionSource {
    private final Scenario scenario;
    private final long seed;

    public RecordDecisionSource(Scenario scenario, long seed) {
        this.scenario = scenario;
        this.seed = seed;
    }

    @Override
    public long latency(String messageId, String from, String to, String type, long sendTime) {
        long base = scenario.baseLatency(from, to);
        long jitter = scenario.latencyJitter();
        if (base < 0 || jitter < 0) throw new IllegalStateException("negative latency configured for " + from + "->" + to);
        return jitter == 0 ? base : base + IdentityHash.bounded(seed, jitter + 1, "latency", messageId);
    }

    @Override
    public long random(String node, String stream, long ordinal, long computed) {
        return computed;
    }

    @Override
    public boolean probabilisticFires(String ruleId, String messageId, long perMille) {
        return IdentityHash.bounded(seed, 1000, "prob", ruleId, messageId) < perMille;
    }

    @Override
    public String mode() {
        return "record";
    }
}
