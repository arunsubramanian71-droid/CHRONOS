package chronos.model;

/**
 * Kinds of causal link (Section 12). Certain links say "because of"; potential links say "could have mattered".
 * Every kind except PROGRAM_ORDER and STEP_EFFECT (both inside one node) carries the clock values of its source event.
 */
public enum EdgeKind {
    PROGRAM_ORDER(false), STEP_EFFECT(true), MESSAGE(true), TIMER(true), FAULT(true), WITNESS(true), OMISSION(false);

    private final boolean certain;

    EdgeKind(boolean certain) {
        this.certain = certain;
    }

    public boolean certain() {
        return certain;
    }

    /** Path cost for explanation paths (Section 20 step 8): certain and OMISSION edges cost 1, other potential edges 2. */
    public int cost() {
        return (certain || this == OMISSION) ? 1 : 2;
    }
}
