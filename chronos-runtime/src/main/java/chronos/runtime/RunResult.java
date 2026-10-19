package chronos.runtime;

import chronos.model.TimelineStatus;

import java.util.List;
import java.util.Map;

/** What a run produced: how it ended, the end-state digest, the first violation and the fault coverage (Section 18). */
public record RunResult(TimelineStatus status, long eventCount, long finalVirtualTime, String endStateDigest,
                        String firstViolationEventId, String firstViolationInvariant, Map<String, Long> faultCoverage,
                        String logHash) {

    /** Rules that never fired: "a fault plan that never triggers is a test that silently tested nothing" (Section 18). */
    public List<String> unfiredRules() {
        return faultCoverage.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
    }

    public boolean violated() {
        return firstViolationEventId != null;
    }
}
