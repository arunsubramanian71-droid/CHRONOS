package chronos.model;

/** A causal link from a parent event (by id) with its edge kind. */
public record Parent(String id, EdgeKind kind) {}
