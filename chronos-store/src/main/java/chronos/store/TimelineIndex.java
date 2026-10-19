package chronos.store;

import chronos.model.Event;
import chronos.model.EventKind;

import java.util.*;

/**
 * Derived, rebuildable indexes over a resolved timeline (FR-18): by position (the list index itself), by node, by kind and by
 * virtual time. They are never the source of truth: they are rebuilt from the log whenever needed.
 */
public final class TimelineIndex {
    private final List<Event> events;
    private final Map<String, int[]> byNode = new TreeMap<>();
    private final Map<EventKind, int[]> byKind = new EnumMap<>(EventKind.class);
    private final Map<String, Integer> byId = new HashMap<>();

    public TimelineIndex(List<Event> events) {
        this.events = events;
        Map<String, List<Integer>> n = new TreeMap<>();
        Map<EventKind, List<Integer>> k = new EnumMap<>(EventKind.class);
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            n.computeIfAbsent(e.nodeId(), x -> new ArrayList<>()).add(i);
            k.computeIfAbsent(e.kind(), x -> new ArrayList<>()).add(i);
            byId.put(e.eventId(), i);
        }
        n.forEach((key, v) -> byNode.put(key, v.stream().mapToInt(Integer::intValue).toArray()));
        k.forEach((key, v) -> byKind.put(key, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    public int size() {
        return events.size();
    }

    public Event at(int position) {
        return events.get(position);
    }

    /** Positions of a node's events in order. */
    public int[] positionsOfNode(String node) {
        return byNode.getOrDefault(node, new int[0]);
    }

    public int[] positionsOfKind(EventKind kind) {
        return byKind.getOrDefault(kind, new int[0]);
    }

    /** Position of an event id, or -1. */
    public int positionOf(String eventId) {
        return byId.getOrDefault(eventId, -1);
    }

    /** First position whose virtual time is at least t (virtual time never decreases along the log), or size() if none. */
    public int firstAtOrAfter(long t) {
        int lo = 0, hi = events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).virtualTime() < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
