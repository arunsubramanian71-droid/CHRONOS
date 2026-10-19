package chronos.runtime;

import chronos.model.Canon;
import chronos.model.Ref;
import chronos.sdk.Node;
import chronos.sdk.NodeState;

import java.util.*;

/** The runtime's record of one node: the application object, its state, lifecycle, counters and pending timers. */
final class NodeSlot {
    record PendingTimer(String id, String name, long due, long setAt, Ref setRef) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("due", due);
            m.put("setAt", setAt);
            m.put("ref", setRef.toMap());
            return m;
        }

        static PendingTimer fromMap(Map<String, Object> m) {
            return new PendingTimer((String) m.get("id"), (String) m.get("name"), Canon.num(m.get("due")),
                    Canon.num(m.get("setAt")), Ref.fromMap(Canon.map(m.get("ref"))));
        }
    }

    final String name;
    final int idx;
    final Node node;
    NodeState state = new NodeState();
    boolean crashed;
    boolean paused;     // frozen: its agenda items are held until resumeAt
    long resumeAt;
    long skew;          // clock-skew fault: the offset added to the time the node reads (logical clocks are unaffected)
    long counter;
    Ref last;
    Ref startRef;
    Ref crashRef;
    final TreeMap<String, Long> randomStates = new TreeMap<>();
    final TreeMap<String, Long> randomOrdinals = new TreeMap<>();
    final TreeSet<String> heardFrom = new TreeSet<>();
    final TreeMap<String, PendingTimer> timers = new TreeMap<>();
    TreeMap<String, String> fieldDigests = new TreeMap<>();

    NodeSlot(String name, int idx, Node node) {
        this.name = name;
        this.idx = idx;
        this.node = node;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("name", name);
        m.put("crashed", crashed);
        m.put("paused", paused);
        m.put("resumeAt", resumeAt);
        m.put("skew", skew);
        m.put("counter", counter);
        if (last != null) m.put("last", last.toMap());
        if (startRef != null) m.put("start", startRef.toMap());
        if (crashRef != null) m.put("crashRef", crashRef.toMap());
        m.put("state", node.stateCodec().encode(state));
        m.put("rs", randomStates);
        m.put("ro", randomOrdinals);
        m.put("heard", new ArrayList<>(heardFrom));
        List<Object> tl = new ArrayList<>();
        for (PendingTimer t : timers.values()) tl.add(t.toMap());
        m.put("timers", tl);
        return m;
    }

    void restore(Map<String, Object> m) {
        crashed = Boolean.TRUE.equals(m.get("crashed"));
        paused = Boolean.TRUE.equals(m.get("paused"));
        resumeAt = m.containsKey("resumeAt") ? Canon.num(m.get("resumeAt")) : 0;
        skew = m.containsKey("skew") ? Canon.num(m.get("skew")) : 0;
        counter = Canon.num(m.get("counter"));
        last = m.containsKey("last") ? Ref.fromMap(Canon.map(m.get("last"))) : null;
        startRef = m.containsKey("start") ? Ref.fromMap(Canon.map(m.get("start"))) : null;
        crashRef = m.containsKey("crashRef") ? Ref.fromMap(Canon.map(m.get("crashRef"))) : null;
        state = node.stateCodec().decode(Canon.map(m.get("state")));
        for (Map.Entry<String, Object> e : Canon.map(m.get("rs")).entrySet()) randomStates.put(e.getKey(), Canon.num(e.getValue()));
        for (Map.Entry<String, Object> e : Canon.map(m.get("ro")).entrySet()) randomOrdinals.put(e.getKey(), Canon.num(e.getValue()));
        for (Object o : Canon.list(m.get("heard"))) heardFrom.add((String) o);
        for (Object o : Canon.list(m.get("timers"))) {
            PendingTimer t = PendingTimer.fromMap(Canon.map(o));
            timers.put(t.id(), t);
        }
    }
}
