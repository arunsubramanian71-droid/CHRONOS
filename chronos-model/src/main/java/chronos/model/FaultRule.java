package chronos.model;

import java.util.*;

/**
 * One rule of a fault plan (Section 18). Every rule is keyed by time or by identity, never by a global counter (D-13).
 *
 * Parameters by action:
 *   PARTITION  at, duration (optional: no duration means it never heals), groups (list of lists of node names)
 *   CRASH      node, at          RESTART  node, at
 *   DROP / DELAY (by) / DUPLICATE   with trigger ON_MESSAGE_ID (messageId), ON_NTH_MESSAGE (sender, receiver, type, n)
 *                                   or PROBABILISTIC (sender, receiver, type, perMille)
 * A wildcard sender "*" is allowed only if the rule is flagged nonLocal, which voids law M8 for the run.
 */
public record FaultRule(String id, TriggerType trigger, FaultAction action, Map<String, Object> params, boolean nonLocal) {

    public enum TriggerType { AT_TIME, ON_MESSAGE_ID, ON_NTH_MESSAGE, PROBABILISTIC }

    public enum FaultAction { PARTITION, CRASH, RESTART, DROP, DELAY, DUPLICATE }

    public FaultRule {
        if (id == null || id.isBlank() || id.contains("#") || id.contains(":"))
            throw new IllegalArgumentException("fault rule id must be non-empty and contain neither '#' nor ':': " + id);
        params = Collections.unmodifiableMap(Canon.normalizeMap(params));
        validate(id, trigger, action, params, nonLocal);
    }

    private static void validate(String id, TriggerType t, FaultAction a, Map<String, Object> p, boolean nonLocal) {
        switch (t) {
            case AT_TIME -> {
                require(id, p, "at");
                if (a != FaultAction.PARTITION && a != FaultAction.CRASH && a != FaultAction.RESTART)
                    throw new IllegalArgumentException("rule " + id + ": a time trigger supports PARTITION, CRASH and RESTART only");
            }
            case ON_MESSAGE_ID -> {
                require(id, p, "messageId");
                messageAction(id, a);
                if (p.containsKey("maxFirings")) throw new IllegalArgumentException("rule " + id + ": maxFirings is only allowed on time-triggered rules (D-13)");
            }
            case ON_NTH_MESSAGE -> {
                for (String k : List.of("sender", "receiver", "type", "n")) require(id, p, k);
                messageAction(id, a);
                if (p.containsKey("maxFirings")) throw new IllegalArgumentException("rule " + id + ": maxFirings is only allowed on time-triggered rules (D-13)");
            }
            case PROBABILISTIC -> {
                for (String k : List.of("sender", "receiver", "type", "perMille")) require(id, p, k);
                messageAction(id, a);
            }
        }
        if (t != TriggerType.AT_TIME && p.containsKey("sender") && "*".equals(p.get("sender")) && !nonLocal)
            throw new IllegalArgumentException("rule " + id + ": a wildcard sender requires the nonLocal flag (D-13)");
        switch (a) {
            case PARTITION -> require(id, p, "groups");
            case CRASH, RESTART -> require(id, p, "node");
            case DELAY -> require(id, p, "by");
            default -> { }
        }
    }

    private static void messageAction(String id, FaultAction a) {
        if (a != FaultAction.DROP && a != FaultAction.DELAY && a != FaultAction.DUPLICATE)
            throw new IllegalArgumentException("rule " + id + ": message triggers support DROP, DELAY and DUPLICATE only");
    }

    private static void require(String id, Map<String, Object> p, String k) {
        if (!p.containsKey(k)) throw new IllegalArgumentException("rule " + id + " is missing parameter '" + k + "'");
    }

    public long num(String k) {
        return Canon.num(params.get(k));
    }

    public String str(String k) {
        return (String) params.get(k);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", id);
        m.put("trigger", trigger.name());
        m.put("action", action.name());
        m.put("params", params);
        m.put("nonLocal", nonLocal);
        return m;
    }

    public static FaultRule fromMap(Map<String, Object> m) {
        return new FaultRule((String) m.get("id"), TriggerType.valueOf((String) m.get("trigger")),
                FaultAction.valueOf((String) m.get("action")), Canon.map(m.get("params")), Boolean.TRUE.equals(m.get("nonLocal")));
    }

    // ----- convenience constructors used by scenarios and tests -----

    public static FaultRule partition(String id, long at, Long duration, List<List<String>> groups) {
        Map<String, Object> p = new TreeMap<>();
        p.put("at", at);
        if (duration != null) p.put("duration", duration);
        p.put("groups", groups);
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.PARTITION, p, false);
    }

    public static FaultRule crash(String id, String node, long at) {
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.CRASH, Map.of("node", node, "at", at), false);
    }

    public static FaultRule restart(String id, String node, long at) {
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.RESTART, Map.of("node", node, "at", at), false);
    }

    public static FaultRule onMessage(String id, FaultAction action, String messageId, Long by) {
        Map<String, Object> p = new TreeMap<>();
        p.put("messageId", messageId);
        if (by != null) p.put("by", by);
        return new FaultRule(id, TriggerType.ON_MESSAGE_ID, action, p, false);
    }

    public static FaultRule nth(String id, FaultAction action, String sender, String receiver, String type, long n, Long by) {
        Map<String, Object> p = new TreeMap<>();
        p.put("sender", sender);
        p.put("receiver", receiver);
        p.put("type", type);
        p.put("n", n);
        if (by != null) p.put("by", by);
        return new FaultRule(id, TriggerType.ON_NTH_MESSAGE, action, p, false);
    }

    public static FaultRule probabilistic(String id, FaultAction action, String sender, String receiver, String type, long perMille, Long by) {
        Map<String, Object> p = new TreeMap<>();
        p.put("sender", sender);
        p.put("receiver", receiver);
        p.put("type", type);
        p.put("perMille", perMille);
        if (by != null) p.put("by", by);
        return new FaultRule(id, TriggerType.PROBABILISTIC, action, p, "*".equals(sender));
    }
}
