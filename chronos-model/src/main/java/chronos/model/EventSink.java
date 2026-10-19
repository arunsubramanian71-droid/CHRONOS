package chronos.model;

import java.util.List;

/**
 * Where the recorder sends events (Section 23): an in-memory list, the live causal graph, the store's asynchronous writer.
 * One call to {@link #append} is one atomic step group (FR-10): a crash can never leave half a step in the log.
 */
public interface EventSink {
    void append(List<Event> group);

    default void checkpoint(WorldImage image) {}

    default void finish(TimelineSummary summary) {}

    default void close() {}
}
