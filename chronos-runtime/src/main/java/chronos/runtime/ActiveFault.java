package chronos.runtime;

import chronos.model.Canon;
import chronos.model.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A fault that is, or was, in force: a partition between groups, or a crashed node. Needed for drops and OMISSION edges. */
final class ActiveFault {
    final String kind; // "partition" or "crash"
    final String ruleId;
    final Ref ref;     // the FAULT_INJECTED event (or NODE_CRASHED for crash scope sources)
    final long start;
    Long end;
    Ref clearRef;   // the FAULT_CLEARED event, once the fault has ended
    Map<String, Object> extra = new TreeMap<>();  // kind-specific data (slow link: sender, receiver, factor)
    Long extendedFrom;   // a partition whose heal an intervention moved later: from when the extra drops are the intervention's doing
    Ref extender;        // that intervention's event
    Long shortenedFrom;  // a partition whose heal an intervention moved earlier: until when the original run would still have dropped deliveries
    Ref shortener;       // that intervention's event
    Ref alterer;         // an intervention that moved this fault's end (earlier or later) or removed it
    long origEnd = Long.MAX_VALUE; // when the original run's fault would have ended (Long.MAX_VALUE: never); meaningful only with alterer
    /** Partition: node -> virtual time of the latest delivery to it that this partition dropped (the evidence of a silenced message, DEV-03). */
    final Map<String, Long> lastDropTo = new TreeMap<>();
    final List<List<String>> groups;
    final String node;

    ActiveFault(String kind, String ruleId, Ref ref, long start, Long end, List<List<String>> groups, String node) {
        this.kind = kind;
        this.ruleId = ruleId;
        this.ref = ref;
        this.start = start;
        this.end = end;
        this.groups = groups;
        this.node = node;
    }

    /** True if a and b are on different sides of this partition. */
    boolean cuts(String a, String b) {
        if (!kind.equals("partition")) return false;
        int ga = groupOf(a), gb = groupOf(b);
        return ga >= 0 && gb >= 0 && ga != gb;
    }

    /** True if the node is on one side of this partition and some other node is on another side (the literal D-23 scope). */
    boolean cutsOffFromAnother(String n) {
        return kind.equals("partition") && groupOf(n) >= 0 && groups.size() >= 2;
    }

    int groupOf(String n) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).contains(n)) return i;
        return -1;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", kind);
        m.put("rule", ruleId);
        m.put("ref", ref.toMap());
        m.put("start", start);
        if (end != null) m.put("end", end);
        if (clearRef != null) m.put("clearRef", clearRef.toMap());
        if (!extra.isEmpty()) m.put("extra", extra);
        if (extendedFrom != null) m.put("extendedFrom", extendedFrom);
        if (extender != null) m.put("extender", extender.toMap());
        if (shortenedFrom != null) m.put("shortenedFrom", shortenedFrom);
        if (shortener != null) m.put("shortener", shortener.toMap());
        if (alterer != null) {
            m.put("alterer", alterer.toMap());
            m.put("origEnd", origEnd);
        }
        if (!lastDropTo.isEmpty()) m.put("lastDropTo", lastDropTo);
        m.put("groups", groups);
        if (node != null) m.put("node", node);
        return m;
    }

    static ActiveFault fromMap(Map<String, Object> m) {
        List<List<String>> groups = new ArrayList<>();
        for (Object g : Canon.list(m.get("groups"))) {
            List<String> l = new ArrayList<>();
            for (Object o : Canon.list(g)) l.add((String) o);
            groups.add(l);
        }
        ActiveFault f = new ActiveFault((String) m.get("kind"), (String) m.get("rule"), Ref.fromMap(Canon.map(m.get("ref"))),
                Canon.num(m.get("start")), m.containsKey("end") ? Canon.num(m.get("end")) : null, groups, (String) m.get("node"));
        if (m.containsKey("clearRef")) f.clearRef = Ref.fromMap(Canon.map(m.get("clearRef")));
        if (m.containsKey("extra")) f.extra = new TreeMap<>(Canon.map(m.get("extra")));
        if (m.containsKey("extendedFrom")) f.extendedFrom = Canon.num(m.get("extendedFrom"));
        if (m.containsKey("extender")) f.extender = Ref.fromMap(Canon.map(m.get("extender")));
        if (m.containsKey("shortenedFrom")) f.shortenedFrom = Canon.num(m.get("shortenedFrom"));
        if (m.containsKey("shortener")) f.shortener = Ref.fromMap(Canon.map(m.get("shortener")));
        if (m.containsKey("lastDropTo")) for (Map.Entry<String, Object> e : Canon.map(m.get("lastDropTo")).entrySet()) f.lastDropTo.put(e.getKey(), Canon.num(e.getValue()));
        if (m.containsKey("alterer")) {
            f.alterer = Ref.fromMap(Canon.map(m.get("alterer")));
            f.origEnd = Canon.num(m.get("origEnd"));
        }
        return f;
    }
}
