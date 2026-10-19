package chronos.sdk;

import chronos.model.Canon;

import java.util.*;

/**
 * Everything a node remembers (Section 14): plain, canonically encodable data in two zones.
 * The volatile zone is lost when the node crashes; the durable zone survives a crash and stands for disk (D-04).
 * Field names seen by invariants and the last-writer map are zone-qualified: "volatile.role", "durable.log".
 *
 * Values must be long, string, boolean, list or string-keyed map (Canon refuses everything else).
 */
public final class NodeState {
    private final TreeMap<String, Object> volatileZone = new TreeMap<>();
    private final TreeMap<String, Object> durableZone = new TreeMap<>();

    // ----- volatile zone -----
    public Object get(String field) {
        return volatileZone.get(field);
    }

    public void put(String field, Object value) {
        volatileZone.put(field, Canon.normalize(value));
    }

    public long getLong(String field, long dflt) {
        Object o = volatileZone.get(field);
        return o == null ? dflt : ((Number) o).longValue();
    }

    public String getString(String field, String dflt) {
        Object o = volatileZone.get(field);
        return o == null ? dflt : (String) o;
    }

    /** The map stored under a volatile field, created empty if absent. Mutating it mutates the state. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String field) {
        Object o = volatileZone.get(field);
        if (o == null) {
            o = new TreeMap<String, Object>();
            volatileZone.put(field, o);
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String field) {
        Object o = volatileZone.get(field);
        if (o == null) {
            o = new ArrayList<Object>();
            volatileZone.put(field, o);
        }
        return (List<Object>) o;
    }

    // ----- durable zone -----
    public Object getDurable(String field) {
        return durableZone.get(field);
    }

    public void putDurable(String field, Object value) {
        durableZone.put(field, Canon.normalize(value));
    }

    public long getDurableLong(String field, long dflt) {
        Object o = durableZone.get(field);
        return o == null ? dflt : ((Number) o).longValue();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDurableMap(String field) {
        Object o = durableZone.get(field);
        if (o == null) {
            o = new TreeMap<String, Object>();
            durableZone.put(field, o);
        }
        return (Map<String, Object>) o;
    }

    // ----- zone-qualified access (used by invariants and the engine) -----
    public Object read(String qualified) {
        if (qualified.startsWith("volatile.")) return volatileZone.get(qualified.substring(9));
        if (qualified.startsWith("durable.")) return durableZone.get(qualified.substring(8));
        throw new IllegalArgumentException("field names are zone-qualified (volatile.x or durable.x): " + qualified);
    }

    /** Zone-qualified names of every present top-level field, sorted. */
    public SortedSet<String> fieldNames() {
        SortedSet<String> s = new TreeSet<>();
        for (String k : volatileZone.keySet()) s.add("volatile." + k);
        for (String k : durableZone.keySet()) s.add("durable." + k);
        return s;
    }

    public void wipeVolatile() {
        volatileZone.clear();
    }

    public Map<String, Object> volatileFields() {
        return volatileZone;
    }

    public Map<String, Object> durableFields() {
        return durableZone;
    }

    /** Canonical, fully detached copy: {"durable": {...}, "volatile": {...}}. */
    public Map<String, Object> toCanonical() {
        return StateCodec.DEFAULT.encode(this);
    }

    @Override
    public String toString() {
        return Canon.json(toCanonical());
    }
}
