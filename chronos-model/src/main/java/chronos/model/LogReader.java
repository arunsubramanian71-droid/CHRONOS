package chronos.model;

import java.util.List;
import java.util.Optional;

/**
 * Read access to recorded timelines (Section 23). Implemented by the store; the replay and branch decision sources and
 * the analysis components reach recorded history only through this interface (D-19).
 *
 * events() returns the RESOLVED history: a branch's shared prefix up to its branch point (read from its ancestors by
 * reference, D-12) followed by its own events, in position order.
 */
public interface LogReader {
    List<String> timelineIds();

    TimelineHeader header(String timelineId);

    /** Final summary, or null while a timeline is still being recorded. */
    TimelineSummary summary(String timelineId);

    List<Event> events(String timelineId);

    /** Positions (seq) of the last event of every atomic step group, i.e. the valid step boundaries. */
    List<Long> groupEnds(String timelineId);

    /** The latest checkpoint at or before a position, searching the shared prefix of ancestors as well. */
    Optional<WorldImage> checkpointAtOrBefore(String timelineId, long seq);

    List<Long> checkpointSeqs(String timelineId);

    /**
     * As {@link #checkpointAtOrBefore}, but only an image that is the world at the step boundary of its position. The image written when a
     * run finishes is taken after the run's trailing silent steps (agenda items that record no event, such as a fault rule for a crashed node) and
     * says so with {@code liveTime}; resuming a branch or a time-travel cursor from it would lose those pending items, so it is skipped in favour of
     * the next older image (DEV-52). A full verification may still start from it ({@link #checkpointAtOrBefore}).
     */
    default Optional<WorldImage> boundaryCheckpointAtOrBefore(String timelineId, long seq) {
        long at = seq;
        while (at >= 0) {
            Optional<WorldImage> c = checkpointAtOrBefore(timelineId, at);
            if (c.isEmpty() || !c.get().data().containsKey("liveTime")) return c;
            at = c.get().seq() - 1;
        }
        return Optional.empty();
    }
}
