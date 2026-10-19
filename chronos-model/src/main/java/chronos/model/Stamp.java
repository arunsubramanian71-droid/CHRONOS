package chronos.model;

import java.util.List;
import java.util.Map;

/** Logical time of one event: its Lamport value and its vector with N + 1 slots (nodes in declared order, env last). */
public record Stamp(long lamport, List<Integer> vector) {
    public Stamp {
        vector = List.copyOf(vector);
    }

    public Map<String, Object> toMap() {
        return Map.of("l", lamport, "v", vector);
    }

    public static Stamp fromMap(Map<String, Object> m) {
        List<Integer> v = new java.util.ArrayList<>();
        for (Object o : Canon.list(m.get("v"))) v.add(((Number) o).intValue());
        return new Stamp(Canon.num(m.get("l")), v);
    }
}
