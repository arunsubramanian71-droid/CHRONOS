package chronos.runtime;

/**
 * The one seam for all nondeterminism (Section 9, D-19). The engine never decides anything by itself: it asks. Record mode
 * answers from a seeded policy; replay mode answers from the recorded events; branch mode answers from the parent's log up
 * to the branch point and from the policy afterwards. The same engine runs in all three modes, so replay correctness is a
 * property of one engine, not of a second code path. There is no separate decision log: each decision is stored as a field
 * of the event it influenced (a send event stores its delivery time; a random draw is an event with its value).
 */
public interface DecisionSource {
    /** Delivery delay in virtual ms of a message (before any fault). Identity-keyed by the message id (D-06). */
    long latency(String messageId, String from, String to, String type, long sendTime);

    /**
     * Value of the ordinal-th draw (0-based) of a node's random stream. {@code computed} is what the node's own seeded
     * generator produces; record mode returns it, replay mode returns the recorded value.
     */
    long random(String node, String stream, long ordinal, long computed);

    /** Whether a probabilistic rule hits a message; decided by a hash of (seed, message id, rule id), never a shared stream. */
    boolean probabilisticFires(String ruleId, String messageId, long perMille);

    /** Short name for reports: "record", "replay", "branch". */
    String mode();
}
