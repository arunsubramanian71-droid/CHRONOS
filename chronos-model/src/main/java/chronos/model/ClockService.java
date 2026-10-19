package chronos.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clock Service (C4). Keeps one Lamport counter and one vector per slot; slot i belongs to the i-th declared node and the
 * last slot belongs to env, which follows exactly the same rules as a node (Section 11, D-07).
 *
 * Every event is stamped with {@link #stamp}: the incoming stamps are those of every causal parent that carries clocks
 * (a message's send event, a fault event, a witness, an omission source). The rule is the receive rule applied to all of
 * them: Lamport = max(own, incoming...) + 1; vector = slot-wise max(own, incoming...), then own slot + 1. With no incoming
 * stamps this is the local/send rule. So laws M3 and M4 hold for FAULT, WITNESS and OMISSION links as for messages.
 */
public final class ClockService {
    private final int slots;
    private final long[] lamport;
    private final int[][] vector;

    public ClockService(int nodeCount) {
        this.slots = nodeCount + 1;
        this.lamport = new long[slots];
        this.vector = new int[slots][slots];
    }

    public int slotCount() {
        return slots;
    }

    public Stamp stamp(int slot, Collection<Stamp> incoming) {
        long l = lamport[slot];
        int[] v = vector[slot];
        for (Stamp s : incoming) {
            l = Math.max(l, s.lamport());
            List<Integer> sv = s.vector();
            if (sv.size() != slots) throw new IllegalArgumentException("stamp has " + sv.size() + " slots, expected " + slots);
            for (int i = 0; i < slots; i++) v[i] = Math.max(v[i], sv.get(i));
        }
        v[slot]++;
        lamport[slot] = l + 1;
        return current(slot);
    }

    public Stamp current(int slot) {
        List<Integer> vs = new ArrayList<>(slots);
        for (int x : vector[slot]) vs.add(x);
        return new Stamp(lamport[slot], vs);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> all = new ArrayList<>();
        for (int i = 0; i < slots; i++) all.add(current(i).toMap());
        m.put("slots", all);
        return m;
    }

    public void restore(Map<String, Object> snap) {
        List<Object> all = Canon.list(snap.get("slots"));
        if (all.size() != slots) throw new IllegalArgumentException("clock snapshot has wrong slot count");
        for (int i = 0; i < slots; i++) {
            Stamp s = Stamp.fromMap(Canon.map(all.get(i)));
            lamport[i] = s.lamport();
            for (int j = 0; j < slots; j++) vector[i][j] = s.vector().get(j);
        }
    }
}
