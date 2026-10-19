package chronos.analysis;

import chronos.model.Canon;
import chronos.model.Event;
import chronos.model.EventKind;
import chronos.runtime.DecisionSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pinned continuation policy (D-15, Stretch). After the branch point the default policy, Continue, gives every message the
 * delay the parent's seeded policy would give it, so a message sent at a different time arrives at a different time. Pinned
 * follows the parent's original order "wherever it still makes sense": a message that the parent also sent (same id, sender,
 * receiver and type) is delivered at the instant the parent delivered it, if that instant has not already passed; otherwise,
 * and for every message the parent never sent, it falls back to Continue.
 *
 * Only the base delay is pinned. Fault effects (delay, slow link, partition) apply on top, as always. Random draws and
 * probabilistic fault decisions are identity-keyed already, so they are unchanged. The policy affects only what the branch
 * records from now on; replaying the branch reads the recorded delivery times, so replay never needs to know it was Pinned.
 */
public final class PinnedPolicy implements DecisionSource {
    private record Sent(String from, String to, String type, long deliverAt) {}

    private final DecisionSource continuePolicy;
    private final Map<String, Sent> parentSends = new HashMap<>();

    public PinnedPolicy(DecisionSource continuePolicy, List<Event> parentEvents) {
        this.continuePolicy = continuePolicy;
        for (Event e : parentEvents)
            if (e.kind() == EventKind.MESSAGE_SENT)
                parentSends.put(e.eventId(), new Sent(e.nodeId(), (String) e.payload().get("to"), (String) e.payload().get("type"), Canon.num(e.payload().get("deliverAt"))));
    }

    @Override
    public long latency(String messageId, String from, String to, String type, long sendTime) {
        Sent s = parentSends.get(messageId);
        if (s != null && s.from().equals(from) && s.to().equals(to) && s.type().equals(type) && s.deliverAt() >= sendTime) return s.deliverAt() - sendTime;
        return continuePolicy.latency(messageId, from, to, type, sendTime);
    }

    @Override
    public long random(String node, String stream, long ordinal, long computed) {
        return continuePolicy.random(node, stream, ordinal, computed);
    }

    @Override
    public boolean probabilisticFires(String ruleId, String messageId, long perMille) {
        return continuePolicy.probabilisticFires(ruleId, messageId, perMille);
    }

    @Override
    public String mode() {
        return "pinned";
    }
}
