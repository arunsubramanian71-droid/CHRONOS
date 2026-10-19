package chronos.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** An event id together with its logical stamp: what a message, timer, fault or last-writer entry carries. */
public record Ref(String id, Stamp stamp) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("s", stamp.toMap());
        return m;
    }

    public static Ref fromMap(Map<String, Object> m) {
        return new Ref(Canon.str(m.get("id")), Stamp.fromMap(Canon.map(m.get("s"))));
    }
}
