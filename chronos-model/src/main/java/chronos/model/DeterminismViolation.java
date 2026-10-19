package chronos.model;

import java.util.List;

/**
 * Replay produced something different from the recording (Section 15, step 6). The report carries the expected and actual
 * event, a field-by-field difference, the last matching event and likely causes, and cites event ids (NFR-16).
 */
public class DeterminismViolation extends RuntimeException {
    private final Event expected;
    private final Event actual;
    private final String lastMatchingEventId;
    private final List<String> differences;

    public DeterminismViolation(String message, Event expected, Event actual, String lastMatchingEventId, List<String> differences) {
        super(message);
        this.expected = expected;
        this.actual = actual;
        this.lastMatchingEventId = lastMatchingEventId;
        this.differences = List.copyOf(differences);
    }

    public Event expected() {
        return expected;
    }

    public Event actual() {
        return actual;
    }

    public String lastMatchingEventId() {
        return lastMatchingEventId;
    }

    public List<String> differences() {
        return differences;
    }

    /** First event id involved, for tests and reports. */
    public String firstMismatchId() {
        return expected != null ? expected.eventId() : actual != null ? actual.eventId() : null;
    }

    public String report() {
        StringBuilder sb = new StringBuilder("DETERMINISM VIOLATION: ").append(getMessage()).append('\n');
        sb.append("  last matching event : ").append(lastMatchingEventId).append('\n');
        sb.append("  expected            : ").append(expected == null ? "(none: the recording ended)" : expected.toLine()).append('\n');
        sb.append("  actual              : ").append(actual == null ? "(none: replay produced no event)" : actual.toLine()).append('\n');
        for (String d : differences) sb.append("  difference          : ").append(d).append('\n');
        sb.append("  likely cause        : ");
        boolean timing = differences.stream().anyMatch(d -> d.startsWith("virtualTime") || d.contains("deliverAt") || d.contains("due"));
        boolean payload = differences.stream().anyMatch(d -> d.startsWith("payload"));
        sb.append(timing ? "a timing difference suggests hidden use of time or randomness"
                : payload ? "a payload difference suggests application logic that depends on something outside the context"
                : "structure differs; check hash-order dependence or hidden state").append('\n');
        return sb.toString();
    }
}
