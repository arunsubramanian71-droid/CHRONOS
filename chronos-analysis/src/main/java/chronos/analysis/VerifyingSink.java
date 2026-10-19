package chronos.analysis;

import chronos.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The replay verifier (Section 15, steps 5 to 7): an EventSink that, instead of storing, compares every regenerated event
 * with the recorded one: id, kind, node, virtual time, Lamport and vector, parent ids and edge kinds, payload, state digest,
 * recorded state and origin. On the first mismatch it raises a {@link DeterminismViolation} with the expected and actual
 * event, a field-by-field difference and the last matching event.
 */
public final class VerifyingSink implements EventSink {
    private final List<Event> recorded;
    private final Predicate<Event> breakpoint;
    private long verified;
    private String lastMatching = "(none)";
    private Event hit;

    public VerifyingSink(List<Event> recorded, Predicate<Event> breakpoint) {
        this.recorded = recorded;
        this.breakpoint = breakpoint;
    }

    @Override
    public void append(List<Event> group) {
        for (Event actual : group) {
            long pos = actual.seq();
            Event expected = pos >= 0 && pos < recorded.size() ? recorded.get((int) pos) : null;
            // fast path: the whole event (every field except the timeline id) equals the recorded one; the field-by-field diff is only
            // computed when something differs, so the check is exactly as strict but far cheaper on the common case
            List<String> diffs = expected == null ? List.of("recording ended: it has no event at position " + pos)
                    : expected.withPosition(actual.seq(), actual.timelineId()).equals(actual) ? List.of() : diff(expected, actual);
            if (!diffs.isEmpty()) {
                String first = diffs.get(0);
                String msg = "mismatch at " + actual.eventId() + ": " + friendly(first, actual);
                throw new DeterminismViolation(msg, expected, actual, lastMatching, diffs);
            }
            verified++;
            lastMatching = actual.eventId();
            if (hit == null && breakpoint != null && breakpoint.test(actual)) hit = actual;
        }
    }

    private static String friendly(String firstDiff, Event actual) {
        if (actual.kind() == EventKind.TIMER_SET && firstDiff.startsWith("payload.due: expected ")) {
            String[] parts = firstDiff.substring("payload.due: expected ".length()).split(" got ");
            return "expected a timer due at " + parts[0] + " ms, got " + parts[1] + " ms";
        }
        return firstDiff;
    }

    public long verified() {
        return verified;
    }

    public String lastMatching() {
        return lastMatching;
    }

    /** The first event that satisfied the breakpoint predicate, or null. */
    public Event hit() {
        return hit;
    }

    /** Field-by-field difference of two events; empty if they agree on everything except the timeline id. */
    public static List<String> diff(Event e, Event a) {
        List<String> d = new ArrayList<>();
        if (!e.eventId().equals(a.eventId())) d.add("eventId: expected " + e.eventId() + " got " + a.eventId());
        if (e.seq() != a.seq()) d.add("seq: expected " + e.seq() + " got " + a.seq());
        if (e.kind() != a.kind()) d.add("kind: expected " + e.kind() + " got " + a.kind());
        if (!e.nodeId().equals(a.nodeId())) d.add("node: expected " + e.nodeId() + " got " + a.nodeId());
        if (e.virtualTime() != a.virtualTime()) d.add("virtualTime: expected " + e.virtualTime() + " got " + a.virtualTime());
        if (e.lamport() != a.lamport()) d.add("lamport: expected " + e.lamport() + " got " + a.lamport());
        if (!e.vector().equals(a.vector())) d.add("vector: expected " + e.vector() + " got " + a.vector());
        if (!e.parents().equals(a.parents())) d.add("parents: expected " + e.parents() + " got " + a.parents());
        for (String k : new TreeSet<>(union(e.payload(), a.payload()))) {
            String x = Canon.json(e.payload().get(k)), y = Canon.json(a.payload().get(k));
            if (!x.equals(y)) d.add("payload." + k + ": expected " + x + " got " + y);
        }
        if (!java.util.Objects.equals(e.stateDigestAfter(), a.stateDigestAfter())) d.add("stateDigestAfter: expected " + e.stateDigestAfter() + " got " + a.stateDigestAfter());
        if (!java.util.Objects.equals(e.stateAfter() == null ? null : Canon.json(e.stateAfter()), a.stateAfter() == null ? null : Canon.json(a.stateAfter())))
            d.add("stateAfter differs");
        if (!java.util.Objects.equals(e.origin(), a.origin())) d.add("origin: expected " + e.origin() + " got " + a.origin());
        return d;
    }

    private static java.util.Set<String> union(Map<String, Object> a, Map<String, Object> b) {
        java.util.Set<String> s = new java.util.HashSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }
}
