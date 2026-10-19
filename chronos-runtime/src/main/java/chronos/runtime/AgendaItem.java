package chronos.runtime;

import chronos.model.Canon;

import java.util.Collections;
import java.util.Map;

/**
 * One pending item of the agenda (Section 9): a message delivery, a timer firing, a scripted client input or a scheduled
 * fault action. Items are ordered by the stable key (due time, target node index, event-kind rank, identity) and never by
 * insertion order or hash codes, so an unrelated extra item in an alternate timeline cannot reshuffle its neighbours.
 */
public record AgendaItem(long due, int nodeIdx, Kind kind, String identity, Map<String, Object> data) implements Comparable<AgendaItem> {

    /** Event-kind rank: at the same instant and node, a client input goes first, then deliveries, then timers; faults target env. */
    public enum Kind {
        FAULT(0), CLIENT_INPUT(1), DELIVERY(2), TIMER(3);
        final int rank;

        Kind(int rank) {
            this.rank = rank;
        }
    }

    public AgendaItem {
        data = Collections.unmodifiableMap(Canon.normalizeMap(data));
    }

    @Override
    public int compareTo(AgendaItem o) {
        int c = Long.compare(due, o.due);
        if (c != 0) return c;
        c = Integer.compare(nodeIdx, o.nodeIdx);
        if (c != 0) return c;
        c = Integer.compare(kind.rank, o.kind.rank);
        if (c != 0) return c;
        return identity.compareTo(o.identity);
    }

    public AgendaItem withDue(long newDue) {
        return new AgendaItem(newDue, nodeIdx, kind, identity, data);
    }

    public AgendaItem withData(Map<String, Object> newData) {
        return new AgendaItem(due, nodeIdx, kind, identity, newData);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.TreeMap<>();
        m.put("due", due);
        m.put("node", (long) nodeIdx);
        m.put("kind", kind.name());
        m.put("id", identity);
        m.put("data", data);
        return m;
    }

    public static AgendaItem fromMap(Map<String, Object> m) {
        return new AgendaItem(Canon.num(m.get("due")), (int) Canon.num(m.get("node")), Kind.valueOf((String) m.get("kind")),
                (String) m.get("id"), Canon.map(m.get("data")));
    }
}
