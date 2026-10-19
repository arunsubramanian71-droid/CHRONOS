package chronos.analysis;

import chronos.model.*;
import chronos.runtime.MemoryLog;

import java.util.*;

/** Builds small hand-made timelines with correct clocks, for exact comparator and graph cases. Nodes plus env, one event per step group. */
final class TraceBuilder {
    private final String id;
    private final List<String> nodes;
    private final ClockService clocks;
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<String, Event> byId = new HashMap<>();
    private final MemoryLog log = new MemoryLog();
    private long time;
    private long seq;
    private final Map<String, Event> last = new HashMap<>();

    TraceBuilder(String id, String... nodes) {
        this.id = id;
        this.nodes = List.of(nodes);
        this.clocks = new ClockService(nodes.length);
    }

    TraceBuilder at(long t) {
        time = t;
        return this;
    }

    /** An event on a node; parents are given as "kind:id" pairs; a PROGRAM_ORDER link to the node's previous event is added automatically. */
    Event node(String node, EventKind kind, Map<String, Object> payload, String... parents) {
        int n = counters.merge(node, 1, Integer::sum);
        return add(node + "#" + n, nodes.indexOf(node), node, kind, payload, parents);
    }

    Event env(String eventId, EventKind kind, Map<String, Object> payload, String... parents) {
        return add(eventId, nodes.size(), "env", kind, payload, parents);
    }

    private Event add(String eventId, int slot, String node, EventKind kind, Map<String, Object> payload, String... parents) {
        List<Parent> ps = new ArrayList<>();
        List<Stamp> in = new ArrayList<>();
        Event prev = last.get(node);
        if (prev != null) {
            ps.add(new Parent(prev.eventId(), EdgeKind.PROGRAM_ORDER));
            in.add(prev.stamp());
        }
        for (String p : parents) {
            String[] kv = p.split(":", 2);
            Event pe = byId.get(kv[1]);
            if (pe == null) throw new IllegalArgumentException("unknown parent " + kv[1]);
            ps.add(new Parent(pe.eventId(), EdgeKind.valueOf(kv[0])));
            in.add(pe.stamp());
        }
        Stamp st = clocks.stamp(slot, in);
        Event e = new Event(eventId, seq, id, node, kind, time, st.lamport(), st.vector(), ps, payload, "sd-" + node, null, null);
        seq++;
        byId.put(eventId, e);
        last.put(node, e);
        log.append(List.of(e));
        return e;
    }

    MemoryTimelines finish(MemoryTimelines into, TimelineStatus status) {
        List<String> nl = nodes;
        TimelineHeader h = new TimelineHeader(id, null, -1, "hand-built", Map.of(), 1, nl, FaultPlan.empty(), List.of(), 1000, true, Formats.FORMAT_VERSION, "1", "fp", null);
        String viol = null, inv = null;
        for (Event e : log.events()) if (e.kind() == EventKind.INVARIANT_VIOLATED) { viol = e.eventId(); inv = (String) e.payload().get("invariant"); break; }
        log.finish(new TimelineSummary(status, seq, seq - 1, time, "end", viol, inv, Map.of(), "h"));
        into.add(h, log);
        return into;
    }
}
