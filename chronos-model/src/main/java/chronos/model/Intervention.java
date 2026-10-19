package chronos.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberate change applied at a branch point (Section 19). Every intervention is recorded as an INTERVENTION_APPLIED
 * event on env with id iv:&lt;id&gt; (D-25). All types here are realistic (none edits node state), so none is synthetic.
 *
 * Types and parameters:
 *   REMOVE_FAULT   ruleId                      remove a fault rule (a pending partition never starts; an active one is cleared)
 *   SHIFT_HEAL     ruleId, by                  move a partition's heal time by "by" ms (negative = heal earlier)
 *   DROP_MESSAGE   messageId                   drop a named message (in flight or still to be sent)
 *   DELAY_MESSAGE  messageId, by               delay a named message by "by" ms
 *   DUPLICATE_MESSAGE messageId                deliver a named message twice
 *   CRASH_NODE     node, at                    crash a node at a virtual time
 */
public record Intervention(String id, String type, Map<String, Object> params) {
    public static final String REMOVE_FAULT = "REMOVE_FAULT";
    public static final String SHIFT_HEAL = "SHIFT_HEAL";
    public static final String DROP_MESSAGE = "DROP_MESSAGE";
    public static final String DELAY_MESSAGE = "DELAY_MESSAGE";
    public static final String DUPLICATE_MESSAGE = "DUPLICATE_MESSAGE";
    public static final String CRASH_NODE = "CRASH_NODE";

    public Intervention {
        if (id == null || id.isBlank() || id.contains("#")) throw new IllegalArgumentException("bad intervention id: " + id);
        params = Collections.unmodifiableMap(Canon.normalizeMap(params));
    }

    public String eventId() {
        return "iv:" + id;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("params", params);
        return m;
    }

    public static Intervention fromMap(Map<String, Object> m) {
        return new Intervention((String) m.get("id"), (String) m.get("type"), Canon.map(m.get("params")));
    }

    public static Intervention removeFault(String id, String ruleId) {
        return new Intervention(id, REMOVE_FAULT, Map.of("ruleId", ruleId));
    }

    public static Intervention shiftHeal(String id, String ruleId, long by) {
        return new Intervention(id, SHIFT_HEAL, Map.of("ruleId", ruleId, "by", by));
    }

    public static Intervention dropMessage(String id, String messageId) {
        return new Intervention(id, DROP_MESSAGE, Map.of("messageId", messageId));
    }

    public static Intervention delayMessage(String id, String messageId, long by) {
        return new Intervention(id, DELAY_MESSAGE, Map.of("messageId", messageId, "by", by));
    }

    public static Intervention duplicateMessage(String id, String messageId) {
        return new Intervention(id, DUPLICATE_MESSAGE, Map.of("messageId", messageId));
    }

    public static Intervention crashNode(String id, String node, long at) {
        return new Intervention(id, CRASH_NODE, Map.of("node", node, "at", at));
    }
}
