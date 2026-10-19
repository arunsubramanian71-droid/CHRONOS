package chronos.sdk;

import java.util.List;

/**
 * The invariant checker's read-only view of the world after a step (C15, D-17). Every read is recorded by the engine, so
 * the fields an invariant read are known exactly; their last writers become the WITNESS edges of a violation.
 * In v1 invariants may read node state fields only.
 */
public interface WorldView {
    List<String> nodes();

    /** Reads a zone-qualified field (for example "volatile.role") of a node; null if the field is absent. */
    Object read(String node, String qualifiedField);
}
