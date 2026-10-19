package chronos.sdk;

import chronos.model.Canon;

import java.util.Map;
import java.util.TreeMap;

/**
 * The state codec a node declares through the Node API (D-04): canonical encode and decode of its state, so the runtime
 * never guesses how to serialise a node's memory. Equal states must encode to equal bytes (Section 14, state contract).
 */
public interface StateCodec {
    Map<String, Object> encode(NodeState state);

    NodeState decode(Map<String, Object> encoded);

    /** The default codec: {"volatile": {...}, "durable": {...}} with sorted keys. */
    StateCodec DEFAULT = new StateCodec() {
        @Override
        public Map<String, Object> encode(NodeState state) {
            Map<String, Object> m = new TreeMap<>();
            m.put("volatile", Canon.normalize(state.volatileFields()));
            m.put("durable", Canon.normalize(state.durableFields()));
            return m;
        }

        @Override
        public NodeState decode(Map<String, Object> encoded) {
            NodeState s = new NodeState();
            for (Map.Entry<String, Object> e : Canon.map(encoded.get("volatile")).entrySet()) s.put(e.getKey(), e.getValue());
            for (Map.Entry<String, Object> e : Canon.map(encoded.get("durable")).entrySet()) s.putDurable(e.getKey(), e.getValue());
            return s;
        }
    };
}
