package chronos.model;

import java.util.*;

/** Final facts about a completed timeline: status, counts, end-state digest, first violation, fault coverage. */
public record TimelineSummary(TimelineStatus status, long eventCount, long lastSeq, long finalVirtualTime, String endStateDigest,
                              String firstViolationEventId, String firstViolationInvariant, Map<String, Long> faultCoverage,
                              String logHash) {

    public TimelineSummary {
        faultCoverage = Collections.unmodifiableMap(new TreeMap<>(faultCoverage));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("status", status.name());
        m.put("eventCount", eventCount);
        m.put("lastSeq", lastSeq);
        m.put("finalVirtualTime", finalVirtualTime);
        m.put("endStateDigest", endStateDigest);
        if (firstViolationEventId != null) m.put("firstViolationEventId", firstViolationEventId);
        if (firstViolationInvariant != null) m.put("firstViolationInvariant", firstViolationInvariant);
        m.put("faultCoverage", faultCoverage);
        m.put("logHash", logHash);
        return m;
    }

    public static TimelineSummary fromMap(Map<String, Object> m) {
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed TimelineSummary: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static TimelineSummary fromMapUnchecked(Map<String, Object> m) {
        Map<String, Long> cov = new TreeMap<>();
        for (Map.Entry<String, Object> e : Canon.map(m.get("faultCoverage")).entrySet()) cov.put(e.getKey(), Canon.num(e.getValue()));
        return new TimelineSummary(TimelineStatus.valueOf((String) m.get("status")), Canon.num(m.get("eventCount")),
                Canon.num(m.get("lastSeq")), Canon.num(m.get("finalVirtualTime")), (String) m.get("endStateDigest"),
                (String) m.get("firstViolationEventId"), (String) m.get("firstViolationInvariant"), cov, (String) m.get("logHash"));
    }
}
