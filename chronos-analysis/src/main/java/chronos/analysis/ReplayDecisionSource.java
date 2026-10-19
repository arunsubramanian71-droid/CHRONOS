package chronos.analysis;

import chronos.model.Canon;
import chronos.model.Event;
import chronos.model.EventKind;
import chronos.runtime.DecisionSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replay mode of the decision source (D-19): every answer is read back from the recorded events. A message's latency is its
 * recorded delivery time minus its send time; a random draw is the recorded value; a probabilistic fault fires iff a
 * FAULT_INJECTED event for that rule and message was recorded. It lives in the analysis layer because it reads stored logs
 * (through the recorded events, which come from a {@code LogReader}).
 *
 * If the engine asks something the recording never answered (hidden nondeterminism made it do something new), the fallback
 * policy answers and the verifying sink reports the first event that differs, with its field-by-field difference.
 */
public final class ReplayDecisionSource implements DecisionSource {
    private final Map<String, Event> sends = new HashMap<>();
    private final Map<String, Long> randoms = new HashMap<>();
    private final Set<String> faultHits = new HashSet<>();
    private final DecisionSource fallback;

    public ReplayDecisionSource(List<Event> recorded, DecisionSource fallback) {
        this.fallback = fallback;
        for (Event e : recorded) {
            if (e.kind() == EventKind.MESSAGE_SENT) sends.put(e.eventId(), e);
            else if (e.kind() == EventKind.RANDOM_DRAWN)
                randoms.put(e.nodeId() + "|" + e.payload().get("stream") + "|" + e.payload().get("ordinal"), Canon.num(e.payload().get("value")));
            else if (e.kind() == EventKind.FAULT_INJECTED && e.payload().containsKey("messageId"))
                faultHits.add(e.payload().get("rule") + "|" + e.payload().get("messageId"));
        }
    }

    @Override
    public long latency(String messageId, String from, String to, String type, long sendTime) {
        Event send = sends.get(messageId);
        if (send == null) return fallback.latency(messageId, from, to, type, sendTime);
        return Canon.num(send.payload().get("deliverAt")) - send.virtualTime();
    }

    @Override
    public long random(String node, String stream, long ordinal, long computed) {
        Long v = randoms.get(node + "|" + stream + "|" + ordinal);
        return v != null ? v : fallback.random(node, stream, ordinal, computed);
    }

    @Override
    public boolean probabilisticFires(String ruleId, String messageId, long perMille) {
        if (!sends.containsKey(messageId)) return fallback.probabilisticFires(ruleId, messageId, perMille);
        return faultHits.contains(ruleId + "|" + messageId);
    }

    @Override
    public String mode() {
        return "replay";
    }
}
