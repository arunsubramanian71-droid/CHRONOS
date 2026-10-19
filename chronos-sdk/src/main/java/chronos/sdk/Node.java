package chronos.sdk;

import java.util.Map;

/**
 * An application node (C1, FR-01): a single-threaded event handler. It reacts to start, message and timer (plus scripted
 * client input and, from Phase 12b, recovery after a crash). Every outside effect goes through the {@link NodeContext}.
 *
 * Determinism contract (Section 9): no wall-clock time, no unseeded randomness, no threads, no sleeping, no real I/O,
 * no identity-hashed collections, no static or shared mutable state. All memory lives in {@link NodeContext#state()}.
 * Implementations must therefore be effectively stateless objects: the runtime may create fresh instances when it restores
 * a checkpoint.
 */
public interface Node {
    default void onStart(NodeContext ctx) {}

    default void onMessage(NodeContext ctx, Message message) {}

    default void onTimer(NodeContext ctx, TimerFiring timer) {}

    /** Scripted external workload item (CLIENT_INPUT event). */
    default void onClientInput(NodeContext ctx, Map<String, Object> input) {}

    /** Recovery after a crash: the volatile zone is empty, the durable zone is intact (D-04, Phase 12b). */
    default void onRestart(NodeContext ctx) {}

    /** The codec that serialises this node's state canonically. */
    default StateCodec stateCodec() {
        return StateCodec.DEFAULT;
    }
}
