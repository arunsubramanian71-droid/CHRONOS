package chronos.model;

import java.util.*;

/**
 * One rule of a fault plan (Section 18). Every rule is keyed by time or by identity, never by a global counter (D-13).
 *
 * Parameters by action:
 *   PARTITION  at, duration (optional: no duration means it never heals), groups (list of lists of node names)
 *   CRASH      node, at          RESTART  node, at
 *   PAUSE      node, at, duration   the node freezes, its items are held until it resumes
 *   CLOCK_SKEW node, at, offset, duration (optional)   the time the node READS changes; logical clocks do not
 *   SLOW_LINK  sender, receiver, factor, at, duration (optional)   deliveries on one link take factor times as long
 *   AFTER_EVENT trigger: any of the time-triggered actions with 'event' (an event id) instead of 'at'
 *   ON_STATE trigger: the same actions, fired the first time a node's state field equals a value
 *                     (watchNode, watchField zone-qualified such as volatile.role, watchValue)
 *   REORDER    (ON_NTH_MESSAGE) swaps the n-th and the (n+1)-th message of sender, receiver, type
 *   DROP / DELAY (by) / DUPLICATE   with trigger ON_MESSAGE_ID (messageId), ON_NTH_MESSAGE (sender, receiver, type, n)
 *                                   or PROBABILISTIC (sender, receiver, type, perMille)
 * A wildcard sender "*" is allowed only if the rule is flagged nonLocal, which voids law M8 for the run.
 */
public record FaultRule(String id, TriggerType trigger, FaultAction action, Map<String, Object> params, boolean nonLocal) {

    /** AFTER_EVENT fires immediately after a named event has been recorded ("crash b right after p#40"). */
    public enum TriggerType { AT_TIME, AFTER_EVENT, ON_STATE, ON_MESSAGE_ID, ON_NTH_MESSAGE, PROBABILISTIC }

    public enum FaultAction { PARTITION, CRASH, RESTART, PAUSE, CLOCK_SKEW, SLOW_LINK, DROP, DELAY, DUPLICATE, REORDER }

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
                timed(id, a);
            }
            case AFTER_EVENT -> {
                require(id, p, "event");
                timed(id, a);
            }
            case ON_STATE -> {
                for (String k : List.of("watchNode", "watchField", "watchValue")) require(id, p, k);
                String f = (String) p.get("watchField");
                if (!f.startsWith("volatile.") && !f.startsWith("durable."))
                    throw new IllegalArgumentException("rule " + id + ": watchField must be zone-qualified (volatile.x or durable.x)");
                timed(id, a);
            }
            case ON_MESSAGE_ID -> {
                require(id, p, "messageId");
                messageAction(id, a);
                if (a == FaultAction.REORDER) throw new IllegalArgumentException("rule " + id + ": REORDER needs an ON_NTH_MESSAGE trigger");
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
                if (a == FaultAction.REORDER) throw new IllegalArgumentException("rule " + id + ": REORDER needs an ON_NTH_MESSAGE trigger");
            }
        }
        if ((t == TriggerType.ON_NTH_MESSAGE || t == TriggerType.PROBABILISTIC) && p.containsKey("sender") && "*".equals(p.get("sender")) && !nonLocal)
            throw new IllegalArgumentException("rule " + id + ": a wildcard sender requires the nonLocal flag (D-13)");
        switch (a) {
            case PARTITION -> require(id, p, "groups");
            case CRASH, RESTART -> require(id, p, "node");
            case PAUSE -> {
                require(id, p, "node");
                require(id, p, "duration");
            }
            case CLOCK_SKEW -> {
                require(id, p, "node");
                require(id, p, "offset");
            }
            case SLOW_LINK -> {
                for (String k : List.of("sender", "receiver", "factor")) require(id, p, k);
                if (Canon.num(p.get("factor")) < 1) throw new IllegalArgumentException("rule " + id + ": factor must be at least 1");
            }
            case DELAY -> require(id, p, "by");
            default -> { }
        }
    }

    private static void timed(String id, FaultAction a) {
        if (a == FaultAction.DROP || a == FaultAction.DELAY || a == FaultAction.DUPLICATE || a == FaultAction.REORDER)
            throw new IllegalArgumentException("rule " + id + ": a time or event trigger supports PARTITION, CRASH, RESTART, PAUSE, CLOCK_SKEW and SLOW_LINK only");
    }

    private static void messageAction(String id, FaultAction a) {
        if (a != FaultAction.DROP && a != FaultAction.DELAY && a != FaultAction.DUPLICATE && a != FaultAction.REORDER)
            throw new IllegalArgumentException("rule " + id + ": message triggers support DROP, DELAY, DUPLICATE and REORDER only");
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
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed FaultRule: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static FaultRule fromMapUnchecked(Map<String, Object> m) {
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

    public static FaultRule pause(String id, String node, long at, long duration) {
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.PAUSE, Map.of("node", node, "at", at, "duration", duration), false);
    }

    public static FaultRule clockSkew(String id, String node, long at, long offset, Long duration) {
        Map<String, Object> p = new TreeMap<>();
        p.put("node", node);
        p.put("at", at);
        p.put("offset", offset);
        if (duration != null) p.put("duration", duration);
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.CLOCK_SKEW, p, false);
    }

    public static FaultRule slowLink(String id, String sender, String receiver, long factor, long at, Long duration) {
        Map<String, Object> p = new TreeMap<>();
        p.put("sender", sender);
        p.put("receiver", receiver);
        p.put("factor", factor);
        p.put("at", at);
        if (duration != null) p.put("duration", duration);
        return new FaultRule(id, TriggerType.AT_TIME, FaultAction.SLOW_LINK, p, false);
    }

    public static FaultRule reorder(String id, String sender, String receiver, String type, long n) {
        return nth(id, FaultAction.REORDER, sender, receiver, type, n, null);
    }

    /** A time-triggered action that instead fires right after the named event has been recorded. */
    public static FaultRule after(String id, String eventId, FaultAction action, Map<String, Object> actionParams) {
        Map<String, Object> p = new TreeMap<>(actionParams);
        p.remove("at");
        p.put("event", eventId);
        return new FaultRule(id, TriggerType.AFTER_EVENT, action, p, false);
    }

    /** A time-triggered action that fires the first time a node's state field equals a value ("crash p when its role is PRIMARY"). */
    public static FaultRule onState(String id, String watchNode, String watchField, Object watchValue, FaultAction action, Map<String, Object> actionParams) {
        Map<String, Object> p = new TreeMap<>(actionParams);
        p.remove("at");
        p.put("watchNode", watchNode);
        p.put("watchField", watchField);
        p.put("watchValue", watchValue);
        return new FaultRule(id, TriggerType.ON_STATE, action, p, false);
    }
}
