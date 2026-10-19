package chronos.analysis;

import chronos.model.*;
import chronos.runtime.MemoryLog;

import java.util.*;

/**
 * A {@link LogReader} over timelines held in memory (root timelines only). It lets campaigns and soak tests replay and analyse
 * thousands of runs without touching the disk; it answers exactly the same questions as the store.
 */
public final class MemoryTimelines implements LogReader {
    private final Map<String, TimelineHeader> headers = new TreeMap<>();
    private final Map<String, MemoryLog> logs = new TreeMap<>();

    public void add(TimelineHeader header, MemoryLog log) {
        headers.put(header.timelineId(), header);
        logs.put(header.timelineId(), log);
    }

    public static MemoryTimelines of(Recording.MemoryRun run) {
        MemoryTimelines m = new MemoryTimelines();
        m.add(run.header(), run.log());
        return m;
    }

    @Override
    public List<String> timelineIds() {
        return new ArrayList<>(headers.keySet());
    }

    @Override
    public TimelineHeader header(String id) {
        TimelineHeader h = headers.get(id);
        if (h == null) throw new UnknownTimelineException(id);
        return h;
    }

    @Override
    public TimelineSummary summary(String id) {
        return log(id).summary();
    }

    @Override
    public List<Event> events(String id) {
        return log(id).events();
    }

    @Override
    public List<Long> groupEnds(String id) {
        return log(id).groupEnds();
    }

    @Override
    public Optional<WorldImage> checkpointAtOrBefore(String id, long seq) {
        WorldImage best = null;
        for (WorldImage w : log(id).checkpoints()) if (w.seq() <= seq && (best == null || w.seq() > best.seq())) best = w;
        return Optional.ofNullable(best);
    }

    @Override
    public List<Long> checkpointSeqs(String id) {
        return log(id).checkpoints().stream().map(WorldImage::seq).sorted().toList();
    }

    private MemoryLog log(String id) {
        MemoryLog l = logs.get(id);
        if (l == null) throw new UnknownTimelineException(id);
        return l;
    }
}
