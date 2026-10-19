package chronos.runtime;

import chronos.model.Event;
import chronos.model.EventSink;
import chronos.model.TimelineSummary;
import chronos.model.WorldImage;

import java.util.ArrayList;
import java.util.List;

/** In-memory sink: the recorded events, the step-group boundaries, the checkpoints and the final summary. */
public final class MemoryLog implements EventSink {
    private final List<Event> events = new ArrayList<>();
    private final List<Long> groupEnds = new ArrayList<>();
    private final List<WorldImage> checkpoints = new ArrayList<>();
    private TimelineSummary summary;

    @Override
    public void append(List<Event> group) {
        events.addAll(group);
        groupEnds.add(group.get(group.size() - 1).seq());
    }

    @Override
    public void checkpoint(WorldImage image) {
        checkpoints.add(image);
    }

    @Override
    public void finish(TimelineSummary s) {
        this.summary = s;
    }

    public List<Event> events() {
        return events;
    }

    public List<Long> groupEnds() {
        return groupEnds;
    }

    public List<WorldImage> checkpoints() {
        return checkpoints;
    }

    public TimelineSummary summary() {
        return summary;
    }
}
