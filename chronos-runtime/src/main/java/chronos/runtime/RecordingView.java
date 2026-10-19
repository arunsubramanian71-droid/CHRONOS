package chronos.runtime;

import chronos.sdk.WorldView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The recording accessor (D-17): every field an invariant reads is remembered, so its last writers can become witnesses. */
final class RecordingView implements WorldView {
    private final Map<String, NodeSlot> slots;
    private final List<String> order;
    private final Set<String> reads = new LinkedHashSet<>(); // "node|volatile.field"

    RecordingView(Map<String, NodeSlot> slots, List<String> order) {
        this.slots = slots;
        this.order = order;
    }

    @Override
    public List<String> nodes() {
        return order;
    }

    @Override
    public Object read(String node, String qualifiedField) {
        NodeSlot s = slots.get(node);
        if (s == null) throw new IllegalArgumentException("unknown node: " + node);
        reads.add(node + "|" + qualifiedField);
        return s.state.read(qualifiedField);
    }

    List<String> reads() {
        return new ArrayList<>(reads);
    }
}
