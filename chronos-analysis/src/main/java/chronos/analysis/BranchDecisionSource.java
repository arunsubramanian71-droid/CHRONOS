package chronos.analysis;

import chronos.runtime.DecisionSource;

import java.util.function.LongSupplier;

/**
 * Branch mode of the decision source (D-19): answers come from the parent's log up to the branch point, then from the
 * seeded policy (plus the interventions, which the engine applies itself). The policy is the same scheduling policy, the
 * same seed and the same identity-keyed randomness as the parent, so unaffected parts of the system behave identically
 * (locality, Section 19).
 */
public final class BranchDecisionSource implements DecisionSource {
    private final DecisionSource replay;
    private final DecisionSource policy;
    private final long branchSeq;
    private final LongSupplier nextSeq;

    public BranchDecisionSource(DecisionSource replay, DecisionSource policy, long branchSeq, LongSupplier nextSeq) {
        this.replay = replay;
        this.policy = policy;
        this.branchSeq = branchSeq;
        this.nextSeq = nextSeq;
    }

    private DecisionSource current() {
        return nextSeq.getAsLong() <= branchSeq ? replay : policy;
    }

    @Override
    public long latency(String messageId, String from, String to, String type, long sendTime) {
        return current().latency(messageId, from, to, type, sendTime);
    }

    @Override
    public long random(String node, String stream, long ordinal, long computed) {
        return current().random(node, stream, ordinal, computed);
    }

    @Override
    public boolean probabilisticFires(String ruleId, String messageId, long perMille) {
        return current().probabilisticFires(ruleId, messageId, perMille);
    }

    @Override
    public String mode() {
        return "branch";
    }
}
