package chronos.sdk;

import chronos.model.FaultPlan;

import java.util.List;
import java.util.Map;

/**
 * A scenario (FR-06, D-22): a Java class registered under a name that declares the node set and their configuration, the
 * workload (scripted client inputs), the default fault plan and the invariants. A run records the scenario name,
 * parameters, seed and fault plan in the timeline header.
 */
public interface Scenario {
    String name();

    /** Declared code version (Q7, Section 15); recorded in the header and compared on replay with a warning on mismatch. */
    default String version() {
        return "1";
    }

    /** Nodes in declared order. */
    List<NodeSpec> nodes();

    /** Configuration visible to nodes through {@link NodeContext#config}. */
    default Map<String, String> config() {
        return Map.of();
    }

    default List<ClientInput> workload() {
        return List.of();
    }

    default FaultPlan defaultFaultPlan() {
        return FaultPlan.empty();
    }

    default List<Invariant> invariants() {
        return List.of();
    }

    default long defaultHorizon() {
        return 10_000;
    }

    /** Base one-way latency of a link in virtual milliseconds (FR-03). */
    default long baseLatency(String from, String to) {
        return 10;
    }

    /** Extra latency in [0, jitter] chosen by identity-keyed hashing of the message id (D-06). */
    default long latencyJitter() {
        return 0;
    }

    /** Whether the first invariant violation stops the run (Section 9, "Lifecycle and failure semantics"). */
    default boolean stopOnViolation() {
        return true;
    }
}
