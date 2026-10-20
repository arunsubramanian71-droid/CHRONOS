package chronos.runtime;

import chronos.model.Event;
import chronos.model.EventSink;
import chronos.model.LogHash;
import chronos.model.TimelineSummary;
import chronos.model.WorldImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Event Recorder (C5): the one choke point that assigns sequence numbers, collects the events of each step as an atomic
 * group and passes them to every sink through the EventSink interface (Section 21). Positions are assigned only here,
 * strictly increasing; a branch continues numbering after its branch point.
 */
public final class Recorder {
    private String timelineId;
    private long nextSeq;
    private List<EventSink> sinks;
    private LogHash hash = new LogHash();
    private boolean hashing = true;

    public Recorder(String timelineId, long startSeq, List<EventSink> sinks) {
        this.timelineId = timelineId;
        this.nextSeq = startSeq;
        this.sinks = new ArrayList<>(sinks);
    }

    public List<Event> commit(List<Event> group) {
        if (group.isEmpty()) return List.of();
        List<Event> out = new ArrayList<>(group.size());
        for (Event e : group) out.add(e.withPosition(nextSeq++, timelineId));
        for (EventSink s : sinks) s.append(out);
        if (hashing) for (Event e : out) hash.add(e);
        return out;
    }

    public void checkpoint(WorldImage image) {
        for (EventSink s : sinks) s.checkpoint(image);
    }

    public void finish(TimelineSummary summary) {
        for (EventSink s : sinks) s.finish(summary);
    }

    /** Position the next event will get; the last recorded position is this minus one. */
    public long nextSeq() {
        return nextSeq;
    }

    public String timelineId() {
        return timelineId;
    }

    /** A verifying replay does not use the recorder's hash (the verdict carries the hash of the recorded log), so it can skip the work. */
    public void setHashing(boolean on) {
        this.hashing = on;
    }

    /** Hash of the events recorded through this recorder (a branch's own events only); empty if hashing was switched off. */
    public String ownLogHash() {
        return hash.hex();
    }

    /** Moves the recorder to a new timeline at the branch point: new id, new sinks, same position counter. */
    public void switchTo(String newTimelineId, List<EventSink> newSinks) {
        this.timelineId = newTimelineId;
        this.sinks = new ArrayList<>(newSinks);
        this.hash = new LogHash();
        this.hashing = true; // the replay up to the branch point ran with hashing off; the branch's own events are what the summary's hash covers
    }

    public void closeSinks() {
        for (EventSink s : sinks) s.close();
    }
}
