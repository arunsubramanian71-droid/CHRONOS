package chronos.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberate change applied at a branch point (Section 19). Every intervention is recorded as an INTERVENTION_APPLIED
 * event on env with id iv:&lt;id&gt; (D-25). All types are realistic except PATCH_STATE, which edits node state and marks the timeline synthetic.
 *
 * Types and parameters:
 *   REMOVE_FAULT   ruleId                      remove a fault rule (a pending partition never starts; an active one is cleared)
 *   SHIFT_HEAL     ruleId, by                  move a partition's heal time by "by" ms (negative = heal earlier)
 *   DROP_MESSAGE   messageId                   drop a named message (in flight or still to be sent)
 *   DELAY_MESSAGE  messageId, by               delay a named message by "by" ms
 *   DUPLICATE_MESSAGE messageId                deliver a named message twice
 *   CRASH_NODE     node, at                    crash a node at a virtual time
 *   RESTART_NODE   node, at                    restart a crashed node at a virtual time
 *   PAUSE_NODE     node, at, duration          freeze a node (its items are held until it resumes)
 *   SKEW_CLOCK     node, at, offset [duration] change the time a node reads (logical clocks do not change)
 *   ADD_INPUT      node, at, body              add a scripted client input
 *   REMOVE_INPUT   index                       remove the index-th scripted client input (if it has not happened yet)
 *   SWAP_DELIVERIES a, b                       swap the delivery times of two in-flight messages (reorder)
 *   CONFIG_PATCH   key, value                  change a configuration value from the branch point on
 *   RESEED_STREAM  node, stream, seed          replace one node's random stream
 *   PATCH_STATE    node, field, value          edit a node's state: SYNTHETIC, the timeline is permanently marked synthetic
 */
public record Intervention(String id, String type, Map<String, Object> params) {
    public static final String REMOVE_FAULT = "REMOVE_FAULT";
    public static final String SHIFT_HEAL = "SHIFT_HEAL";
    public static final String DROP_MESSAGE = "DROP_MESSAGE";
    public static final String DELAY_MESSAGE = "DELAY_MESSAGE";
    public static final String DUPLICATE_MESSAGE = "DUPLICATE_MESSAGE";
    public static final String CRASH_NODE = "CRASH_NODE";
    public static final String RESTART_NODE = "RESTART_NODE";
    public static final String PAUSE_NODE = "PAUSE_NODE";
    public static final String SKEW_CLOCK = "SKEW_CLOCK";
    public static final String ADD_INPUT = "ADD_INPUT";
    public static final String REMOVE_INPUT = "REMOVE_INPUT";
    public static final String SWAP_DELIVERIES = "SWAP_DELIVERIES";
    public static final String CONFIG_PATCH = "CONFIG_PATCH";
    public static final String RESEED_STREAM = "RESEED_STREAM";
    public static final String PATCH_STATE = "PATCH_STATE";

    public Intervention {
        if (id == null || id.isBlank() || id.contains("#")) throw new IllegalArgumentException("bad intervention id: " + id);
        params = Collections.unmodifiableMap(Canon.normalizeMap(params));
    }

    /** True for interventions that may produce situations the real system could never reach (Section 19). */
    public boolean synthetic() {
        return type.equals(PATCH_STATE);
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
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed Intervention: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static Intervention fromMapUnchecked(Map<String, Object> m) {
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

    public static Intervention restartNode(String id, String node, long at) {
        return new Intervention(id, RESTART_NODE, Map.of("node", node, "at", at));
    }

    public static Intervention pauseNode(String id, String node, long at, long duration) {
        return new Intervention(id, PAUSE_NODE, Map.of("node", node, "at", at, "duration", duration));
    }

    public static Intervention skewClock(String id, String node, long at, long offset, Long duration) {
        Map<String, Object> p = new TreeMap<>();
        p.put("node", node);
        p.put("at", at);
        p.put("offset", offset);
        if (duration != null) p.put("duration", duration);
        return new Intervention(id, SKEW_CLOCK, p);
    }

    public static Intervention addInput(String id, String node, long at, Map<String, Object> body) {
        return new Intervention(id, ADD_INPUT, Map.of("node", node, "at", at, "body", body));
    }

    public static Intervention removeInput(String id, long index) {
        return new Intervention(id, REMOVE_INPUT, Map.of("index", index));
    }

    public static Intervention swapDeliveries(String id, String a, String b) {
        if (a.equals(b)) throw new IllegalArgumentException("intervention " + id + ": a message cannot be swapped with itself");
        return new Intervention(id, SWAP_DELIVERIES, Map.of("a", a, "b", b));
    }

    public static Intervention configPatch(String id, String key, String value) {
        return new Intervention(id, CONFIG_PATCH, Map.of("key", key, "value", value));
    }

    public static Intervention reseedStream(String id, String node, String stream, long seed) {
        return new Intervention(id, RESEED_STREAM, Map.of("node", node, "stream", stream, "seed", seed));
    }

    public static Intervention patchState(String id, String node, String qualifiedField, Object value) {
        return new Intervention(id, PATCH_STATE, Map.of("node", node, "field", qualifiedField, "value", value));
    }
}
