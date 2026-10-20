package chronos.analysis;

import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.CodeFingerprint;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;
import chronos.store.TimelineWriter;

import java.util.List;
import java.util.Map;

/** Recording helpers: record a scenario run into the store or into memory, and the determinism check of FR-48. */
public final class Recording {
    private Recording() {}

    /** What to run: scenario by registry name, parameters, seed, optional plan and horizon overrides. */
    public record Spec(String scenario, Map<String, String> params, long seed, FaultPlan plan, Long horizon, Boolean stopOnViolation,
                       long checkpointInterval) {
        public static Spec of(String scenario, long seed) {
            return new Spec(scenario, Map.of(), seed, null, null, null, RunOptions.DEFAULT_CHECKPOINT_INTERVAL);
        }

        public Spec withParams(Map<String, String> p) {
            return new Spec(scenario, p, seed, plan, horizon, stopOnViolation, checkpointInterval);
        }

        public Spec withPlan(FaultPlan p) {
            return new Spec(scenario, params, seed, p, horizon, stopOnViolation, checkpointInterval);
        }

        public Spec withHorizon(Long h) {
            return new Spec(scenario, params, seed, plan, h, stopOnViolation, checkpointInterval);
        }

        public Spec withStopOnViolation(Boolean b) {
            return new Spec(scenario, params, seed, plan, horizon, b, checkpointInterval);
        }

        public Spec withCheckpointInterval(long k) {
            return new Spec(scenario, params, seed, plan, horizon, stopOnViolation, k);
        }
    }

    public static TimelineHeader headerFor(Scenario sc, Spec spec, String timelineId) {
        FaultPlan plan = spec.plan() != null ? spec.plan() : sc.defaultFaultPlan();
        long horizon = spec.horizon() != null ? spec.horizon() : sc.defaultHorizon();
        if (horizon < 0) throw new IllegalArgumentException("the horizon must not be negative (got " + horizon + " ms)");
        boolean stop = spec.stopOnViolation() != null ? spec.stopOnViolation() : sc.stopOnViolation();
        return new TimelineHeader(timelineId, null, -1, sc.name(), spec.params(), spec.seed(), sc.nodes().stream().map(n -> n.name()).toList(),
                plan, List.of(), horizon, stop, Formats.FORMAT_VERSION, sc.version(), CodeFingerprint.of(sc), null);
    }

    /** Runs a scenario and records it as a new root timeline in the store. */
    public static RunResult record(TimelineStore store, ScenarioRegistry registry, String timelineId, Spec spec) {
        Scenario sc = registry.create(spec.scenario(), spec.params());
        TimelineHeader h = headerFor(sc, spec, timelineId);
        TimelineWriter writer = store.openWriter(h);
        boolean finished = false;
        try {
            Recorder rec = new Recorder(timelineId, 0, List.of(writer));
            Engine e = Engine.create(sc, h.seed(), h.faultPlan(), new RunOptions(h.horizon(), h.stopOnViolation(), spec.checkpointInterval()),
                    new RecordDecisionSource(sc, h.seed()), rec);
            e.boot();
            RunResult result = e.runToEnd();
            finished = true;
            return result;
        } finally {
            writer.close();
            if (!finished) store.discardUnfinished(timelineId); // a refused run (an invalid fault rule) must not leave a half-written timeline in the store
        }
    }

    /** A run kept in memory: used by campaigns and the determinism check. */
    public record MemoryRun(MemoryLog log, RunResult result, TimelineHeader header) {}

    public static MemoryRun recordInMemory(ScenarioRegistry registry, String timelineId, Spec spec) {
        Scenario sc = registry.create(spec.scenario(), spec.params());
        TimelineHeader h = headerFor(sc, spec, timelineId);
        MemoryLog log = new MemoryLog();
        Recorder rec = new Recorder(timelineId, 0, List.of(log));
        Engine e = Engine.create(sc, h.seed(), h.faultPlan(), new RunOptions(h.horizon(), h.stopOnViolation(), spec.checkpointInterval()),
                new RecordDecisionSource(sc, h.seed()), rec);
        e.boot();
        return new MemoryRun(log, e.runToEnd(), h);
    }

    /** FR-48: runs a scenario twice and compares the log hashes (and, if they differ, finds the first differing event). */
    public record DeterminismReport(boolean identical, String hashA, String hashB, long events, String firstDifference) {}

    public static DeterminismReport determinismCheck(ScenarioRegistry registry, Spec spec) {
        MemoryRun a = recordInMemory(registry, "T-det", spec), b = recordInMemory(registry, "T-det", spec);
        String ha = LogHash.of(a.log().events()), hb = LogHash.of(b.log().events());
        String first = null;
        if (!ha.equals(hb)) {
            List<Event> ea = a.log().events(), eb = b.log().events();
            for (int i = 0; i < Math.min(ea.size(), eb.size()); i++)
                if (!ea.get(i).toLine().equals(eb.get(i).toLine())) {
                    first = ea.get(i).eventId() + " at position " + i;
                    break;
                }
            if (first == null) first = "logs differ in length (" + ea.size() + " vs " + eb.size() + ")";
        }
        return new DeterminismReport(ha.equals(hb), ha, hb, a.log().events().size(), first);
    }
}
