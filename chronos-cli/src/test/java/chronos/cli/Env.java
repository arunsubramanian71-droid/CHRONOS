package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.RunResult;
import chronos.store.TimelineStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared test fixture: a fresh store in a temp directory, the demo registry and a timeline manager. */
final class Env {
    final Path dir;
    final TimelineStore store;
    final TestRegistry registry = new TestRegistry();
    final TimelineManager manager;
    final ReplayEngine replay;

    Env(Path dir) {
        this.dir = dir;
        this.store = new TimelineStore(dir);
        this.manager = new TimelineManager(store, registry).withCheckpointInterval(10);
        this.replay = manager.replayEngine();
    }

    RunResult record(String scenario, long seed, Map<String, String> params) {
        return record(scenario, seed, params, null);
    }

    RunResult record(String scenario, long seed, Map<String, String> params, FaultPlan plan) {
        return record(scenario, seed, params, plan, null);
    }

    /** stop = Boolean.FALSE records the run to its horizon even after an invariant violation. */
    RunResult record(String scenario, long seed, Map<String, String> params, FaultPlan plan, Boolean stop) {
        String id = manager.allocateRootId();
        last = id;
        Recording.Spec spec = Recording.Spec.of(scenario, seed).withParams(params).withPlan(plan).withStopOnViolation(stop).withCheckpointInterval(checkpointInterval);
        return Recording.record(store, registry, id, spec);
    }

    String last;
    long checkpointInterval = 10;

    List<Event> events(String id) {
        return store.events(id);
    }
}
