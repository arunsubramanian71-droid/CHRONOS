package chronos.sdk;

import java.util.Optional;

/** A global rule that must always hold (Section 6). Breaking it is a failure and is recorded as INVARIANT_VIOLATED on env. */
public interface Invariant {
    String name();

    /** Returns a description of the violation, or empty if the rule holds. Read state only through the view. */
    Optional<String> check(WorldView view);
}
