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
}
