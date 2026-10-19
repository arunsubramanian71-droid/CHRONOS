package chronos.runtime;

import chronos.model.Canon;
import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.model.Ref;

import java.util.*;

/**
 * Fault Injector (C12): evaluates fault-plan triggers, keeps the progress of every rule, remembers which faults are active
 * and which were (for OMISSION edges, D-23) and reports coverage. Faults are applied only by the engine inside the step
 * cycle, never inside node code. Rule progress, per-sender message ordinals and the fault history are world state and are
 * captured by every checkpoint (Section 14).
 */
final class FaultInjector {
    private final Map<String, FaultRule> rules = new LinkedHashMap<>();
    private final Map<String, String> origins = new TreeMap<>();   // "ruleId" or "ruleId/clear" -> "iv:<id>"
    private final Map<String, Ref> ivRefs = new TreeMap<>();       // "iv:<id>" -> its INTERVENTION_APPLIED event
    private final Map<String, Long> firings = new TreeMap<>();
    private final Map<String, Long> ordinals = new TreeMap<>();    // per-sender message ordinals, key "from>to|type"
    private final List<ActiveFault> active = new ArrayList<>();
    private final List<ActiveFault> history = new ArrayList<>();

    FaultInjector(FaultPlan plan) {
        for (FaultRule r : plan.rules()) addRule(r, null);
    }

    void addRule(FaultRule r, String origin) {
        if (rules.containsKey(r.id())) throw new IllegalArgumentException("duplicate fault rule id: " + r.id());
        rules.put(r.id(), r);
        firings.putIfAbsent(r.id(), 0L);
        if (origin != null) origins.put(r.id(), origin);
    }

    void replaceRule(FaultRule r) {
        if (!rules.containsKey(r.id())) throw new IllegalArgumentException("unknown fault rule: " + r.id());
        rules.put(r.id(), r);
    }

    void removeRule(String id) {
        rules.remove(id);
        firings.remove(id);
        origins.remove(id);
        origins.remove(id + "/clear");
    }

    FaultRule rule(String id) {
        return rules.get(id);
    }

    Collection<FaultRule> rules() {
        return rules.values();
    }

    void setOrigin(String key, String ivEventId) {
        origins.put(key, ivEventId);
    }

    String origin(String key) {
        return origins.get(key);
    }

    void registerIntervention(String ivEventId, Ref ref) {
        ivRefs.put(ivEventId, ref);
    }

    Ref ivRef(String ivEventId) {
        return ivEventId == null ? null : ivRefs.get(ivEventId);
    }

    void fired(String ruleId) {
        firings.merge(ruleId, 1L, Long::sum);
    }

    Map<String, Long> coverage() {
        Map<String, Long> m = new TreeMap<>();
        for (String id : rules.keySet()) m.put(id, firings.getOrDefault(id, 0L));
        return m;
    }

    /** Message-triggered rules that match a message being sent. Counts the sender's ordinal first (checkpointed state). */
    List<FaultRule> matchSend(String msgId, String from, String to, String type, DecisionSource ds) {
        long ord = ordinals.merge(from + ">" + to + "|" + type, 1L, Long::sum);
        List<FaultRule> hit = new ArrayList<>();
        for (FaultRule r : rules.values()) {
            switch (r.trigger()) {
                case ON_MESSAGE_ID -> {
                    if (msgId.equals(r.str("messageId"))) hit.add(r);
                }
                case ON_NTH_MESSAGE -> {
                    if (matches(r, from, to, type) && ord == r.num("n")) hit.add(r);
                }
                case PROBABILISTIC -> {
                    if (matches(r, from, to, type) && ds.probabilisticFires(r.id(), msgId, r.num("perMille"))) hit.add(r);
                }
                default -> { }
            }
        }
        return hit;
    }

    private static boolean matches(FaultRule r, String from, String to, String type) {
        return wild(r.str("sender"), from) && wild(r.str("receiver"), to) && wild(r.str("type"), type);
    }

    private static boolean wild(String pattern, String v) {
        return "*".equals(pattern) || pattern.equals(v);
    }

    void activate(ActiveFault f) {
        active.add(f);
    }

    void close(ActiveFault f, long endTime) {
        f.end = endTime;
        active.remove(f);
        history.add(f);
    }

    ActiveFault activePartition(String ruleId) {
        for (ActiveFault f : active) if (f.kind.equals("partition") && f.ruleId.equals(ruleId)) return f;
        return null;
    }

    ActiveFault activeCrash(String node) {
        for (ActiveFault f : active) if (f.kind.equals("crash") && node.equals(f.node)) return f;
        return null;
    }

    ActiveFault partitionCutting(String a, String b) {
        for (ActiveFault f : active) if (f.cuts(a, b)) return f;
        return null;
    }

    /**
     * Faults that silenced a node's inputs while one of its timers waited (D-23): a fault touches a node if, for a partition,
     * the node sits on one side and has heard from a node on the other side before, or, for a crash, the node has received
     * messages from the crashed node before. The fault must have been in force at some time in [setAt, fireAt].
     */
    List<ActiveFault> omissionSources(NodeSlot slot, long setAt, long fireAt) {
        List<ActiveFault> out = new ArrayList<>();
        List<ActiveFault> all = new ArrayList<>(history);
        all.addAll(active);
        for (ActiveFault f : all) {
            if (f.start > fireAt) continue;
            if (f.end != null && f.end <= setAt) continue;
            if (touches(slot, f)) out.add(f);
        }
        return out;
    }

    private static boolean touches(NodeSlot slot, ActiveFault f) {
        if (f.kind.equals("crash")) return !slot.name.equals(f.node) && slot.heardFrom.contains(f.node);
        int g = f.groupOf(slot.name);
        if (g < 0) return false;
        for (String heard : slot.heardFrom) {
            int hg = f.groupOf(heard);
            if (hg >= 0 && hg != g) return true;
        }
        return false;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        List<Object> rl = new ArrayList<>();
        for (FaultRule r : rules.values()) rl.add(r.toMap());
        m.put("rules", rl);
        m.put("origins", origins);
        Map<String, Object> ivs = new TreeMap<>();
        for (Map.Entry<String, Ref> e : ivRefs.entrySet()) ivs.put(e.getKey(), e.getValue().toMap());
        m.put("ivRefs", ivs);
        m.put("firings", firings);
        m.put("ordinals", ordinals);
        List<Object> al = new ArrayList<>();
        for (ActiveFault f : active) al.add(f.toMap());
        m.put("active", al);
        List<Object> hl = new ArrayList<>();
        for (ActiveFault f : history) hl.add(f.toMap());
        m.put("history", hl);
        return m;
    }

    static FaultInjector restore(Map<String, Object> m) {
        FaultInjector fi = new FaultInjector(FaultPlan.empty());
        for (Object o : Canon.list(m.get("rules"))) {
            FaultRule r = FaultRule.fromMap(Canon.map(o));
            fi.rules.put(r.id(), r);
        }
        for (Map.Entry<String, Object> e : Canon.map(m.get("origins")).entrySet()) fi.origins.put(e.getKey(), (String) e.getValue());
        for (Map.Entry<String, Object> e : Canon.map(m.get("ivRefs")).entrySet()) fi.ivRefs.put(e.getKey(), Ref.fromMap(Canon.map(e.getValue())));
        for (Map.Entry<String, Object> e : Canon.map(m.get("firings")).entrySet()) fi.firings.put(e.getKey(), Canon.num(e.getValue()));
        for (Map.Entry<String, Object> e : Canon.map(m.get("ordinals")).entrySet()) fi.ordinals.put(e.getKey(), Canon.num(e.getValue()));
        for (Object o : Canon.list(m.get("active"))) fi.active.add(ActiveFault.fromMap(Canon.map(o)));
        for (Object o : Canon.list(m.get("history"))) fi.history.add(ActiveFault.fromMap(Canon.map(o)));
        return fi;
    }
}
