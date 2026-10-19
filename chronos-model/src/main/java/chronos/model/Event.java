package chronos.model;

import java.util.*;

/**
 * An immutable record of one thing that happened (Section 10). The event log is the single source of truth.
 *
 * eventId   node name + per-node counter (A#2); events on env are keyed by what they concern (D-24).
 * seq       position in this timeline's log, assigned only by the recorder.
 * origin    optional: the intervention or fault rule that caused the event; part of the content digest (D-25).
 */
public record Event(String eventId, long seq, String timelineId, String nodeId, EventKind kind, long virtualTime,
                    long lamport, List<Integer> vector, List<Parent> parents, Map<String, Object> payload,
                    String stateDigestAfter, Map<String, Object> stateAfter, String origin) {

    public static final String ENV = "env";

    /** Payload fields that carry virtual time; the structural digest ignores them, the strict digest includes them. */
    public static final Set<String> TIMING_FIELDS = Set.of("deliverAt", "due", "at", "until", "healAt", "duration");

    public Event {
        vector = List.copyOf(vector);
        parents = List.copyOf(parents);
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(Canon.normalizeMap(payload));
        stateAfter = stateAfter == null ? null : Collections.unmodifiableMap(Canon.normalizeMap(stateAfter));
    }

    public Event withPosition(long newSeq, String newTimeline) {
        return new Event(eventId, newSeq, newTimeline, nodeId, kind, virtualTime, lamport, vector, parents, payload,
                stateDigestAfter, stateAfter, origin);
    }

    public Event withState(String digest, Map<String, Object> state) {
        return new Event(eventId, seq, timelineId, nodeId, kind, virtualTime, lamport, vector, parents, payload,
                digest, state, origin);
    }

    public Stamp stamp() {
        return new Stamp(lamport, vector);
    }

    public Ref ref() {
        return new Ref(eventId, stamp());
    }

    public boolean isEnv() {
        return ENV.equals(nodeId);
    }

    /**
     * Content digest (Section 10 rule 6): a hash of node, kind, canonical payload, parent ids, origin and state digest.
     * It excludes seq and timelineId. The strict variant also includes virtual time (and timing payload fields).
     * Parent ids exclude PROGRAM_ORDER links: for node events they are implied by the per-node counter, and for env
     * events they would make every event depend on which unrelated env events happened before it.
     */
    public String contentDigest(boolean strict) {
        Map<String, Object> m = new TreeMap<>();
        m.put("node", nodeId);
        m.put("kind", kind.name());
        Map<String, Object> p = new TreeMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet())
            if (strict || !TIMING_FIELDS.contains(e.getKey())) p.put(e.getKey(), e.getValue());
        m.put("payload", p);
        List<String> ps = new ArrayList<>();
        for (Parent par : parents) if (par.kind() != EdgeKind.PROGRAM_ORDER) ps.add(par.kind().name() + ":" + par.id());
        Collections.sort(ps);
        m.put("parents", ps);
        m.put("origin", origin == null ? "" : origin);
        m.put("sd", stateDigestAfter == null ? "" : stateDigestAfter);
        if (strict) m.put("vt", virtualTime);
        return Canon.digest(m);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", eventId);
        m.put("seq", seq);
        m.put("tl", timelineId);
        m.put("node", nodeId);
        m.put("kind", kind.name());
        m.put("vt", virtualTime);
        m.put("l", lamport);
        m.put("v", vector);
        List<Object> ps = new ArrayList<>();
        for (Parent par : parents) ps.add(List.of(par.id(), par.kind().name()));
        m.put("p", ps);
        m.put("pl", payload);
        if (stateDigestAfter != null) m.put("sd", stateDigestAfter);
        if (stateAfter != null) m.put("st", stateAfter);
        if (origin != null) m.put("o", origin);
        return m;
    }

    public String toLine() {
        return Canon.json(toMap());
    }

    @SuppressWarnings("unchecked")
    public static Event fromMap(Map<String, Object> m) {
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed Event: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static Event fromMapUnchecked(Map<String, Object> m) {
        try {
            List<Integer> v = new ArrayList<>();
            for (Object o : (List<Object>) m.get("v")) v.add(((Number) o).intValue());
            List<Parent> ps = new ArrayList<>();
            for (Object o : (List<Object>) m.get("p")) {
                List<Object> pair = (List<Object>) o;
                ps.add(new Parent((String) pair.get(0), EdgeKind.valueOf((String) pair.get(1))));
            }
            return new Event((String) m.get("id"), ((Number) m.get("seq")).longValue(), (String) m.get("tl"),
                    (String) m.get("node"), EventKind.valueOf((String) m.get("kind")), ((Number) m.get("vt")).longValue(),
                    ((Number) m.get("l")).longValue(), v, ps, (Map<String, Object>) m.get("pl"),
                    (String) m.get("sd"), (Map<String, Object>) m.get("st"), (String) m.get("o"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed event record: " + e, e);
        }
    }

    public static Event fromLine(String line) {
        return fromMap(Canon.parseMap(line));
    }

    @Override
    public String toString() {
        return eventId + " " + kind + "@" + virtualTime + " seq=" + seq;
    }
}
