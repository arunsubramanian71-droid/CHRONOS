package chronos.analysis;

import chronos.model.FaultPlan;
import chronos.model.TimelineStatus;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Chaos campaign (FR-37, Section 18): runs many seeds, each with a generated fault plan, and lists the timelines that ended
 * in an invariant violation. This is how a failing baseline to debug is found. Runs may use a fixed worker pool (the parallel
 * runner of the counterfactual engine, Phase 13b); every run is an isolated world and the results are returned in seed order,
 * so the report does not depend on the number of threads (law M12).
 */
public final class Campaign {
    private Campaign() {}

    public record Entry(long seed, FaultPlan plan, TimelineStatus status, String violation, long events, String logHash, List<String> problems) {
        public boolean violated() {
            return violation != null;
        }
    }

    /** An extra per-run check (for example the laws of Section 25); returns descriptions of any problems. */
    public interface Check extends Function<Recording.MemoryRun, List<String>> {}

    public static List<Entry> run(ScenarioRegistry registry, String scenario, Map<String, String> params, long fromSeed, long toSeed, int threads,
                                  boolean chaos, Long horizon, Check check) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<Entry>> fs = new ArrayList<>();
            for (long seed = fromSeed; seed <= toSeed; seed++) {
                long s = seed;
                fs.add(pool.submit(() -> {
                    Scenario sc = registry.create(scenario, params);
                    long h = horizon != null ? horizon : sc.defaultHorizon();
                    FaultPlan plan = chaos ? ChaosPlanGenerator.generate(sc, s, h) : sc.defaultFaultPlan();
                    Recording.Spec spec = Recording.Spec.of(scenario, s).withParams(params).withPlan(plan).withHorizon(h).withCheckpointInterval(0);
                    Recording.MemoryRun run = Recording.recordInMemory(registry, "C" + s, spec);
                    List<String> problems = check == null ? List.of() : check.apply(run);
                    return new Entry(s, plan, run.result().status(), run.result().firstViolationInvariant(), run.result().eventCount(),
                            run.result().logHash(), problems);
                }));
            }
            List<Entry> out = new ArrayList<>();
            for (Future<Entry> f : fs) out.add(f.get());
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }
}
