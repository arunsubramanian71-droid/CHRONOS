package chronos.cli;

import chronos.analysis.Recording;
import chronos.store.TimelineStore;

import java.nio.file.Path;
import java.util.Map;

/** Helper for the crash-safety test: records a very long run into a store until the process is killed. */
public final class KillMe {
    public static void main(String[] args) {
        TimelineStore store = new TimelineStore(Path.of(args[0]));
        Recording.record(store, new TestRegistry(), "T0",
                Recording.Spec.of("gossip", 5).withParams(Map.of("nodes", "6", "horizon", "50000000")).withCheckpointInterval(100000));
    }
}
