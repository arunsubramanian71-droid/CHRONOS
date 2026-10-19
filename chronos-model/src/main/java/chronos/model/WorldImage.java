package chronos.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A checkpoint (Section 14): a complete, runnable image of the world at a step boundary, as plain canonical data.
 * "seq" is the log position of the last event the image includes. The runtime defines the content (node states, agenda,
 * clocks, counters, random streams, fault progress, last writers, ...); the store persists it opaquely.
 */
public record WorldImage(String timelineId, long seq, Map<String, Object> data) {
    public WorldImage {
        data = Collections.unmodifiableMap(Canon.normalizeMap(data));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("timelineId", timelineId);
        m.put("seq", seq);
        m.put("data", data);
        return m;
    }

    public static WorldImage fromMap(Map<String, Object> m) {
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed WorldImage: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static WorldImage fromMapUnchecked(Map<String, Object> m) {
        return new WorldImage((String) m.get("timelineId"), Canon.num(m.get("seq")), Canon.map(m.get("data")));
    }

    public String digest() {
        return Canon.digest(toMap());
    }
}
