package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.MemoryLog;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Analyser mutation tests (Section 25): copy a log, change one event, and require the verifier to fail at exactly that event
 * AND the comparator to name exactly that event as the (only) causal root. Every event of several recordings is perturbed in turn.
 */
class AnalyserMutationTest {
    @TempDir
    Path tmp;

    /** A copy of a stored timeline in memory under the given id, with event k perturbed (k < 0: unperturbed). */
    private static void copyInto(MemoryTimelines mem, TimelineStore store, String from, String as, int k) {
        List<Event> events = new ArrayList<>();
        for (Event e : store.events(from)) events.add(e.withPosition(e.seq(), as));
        if (k >= 0) {
            Event o = events.get(k);
            Map<String, Object> pl = new TreeMap<>(o.payload());
            pl.put("tamper", 1L);
            events.set(k, new Event(o.eventId(), o.seq(), o.timelineId(), o.nodeId(), o.kind(), o.virtualTime(), o.lamport(), o.vector(), o.parents(), pl,
                    o.stateDigestAfter(), o.stateAfter(), o.origin()));
        }
        MemoryLog log = new MemoryLog();
        long start = 0;
        for (long end : store.groupEnds(from)) {
            log.append(events.subList((int) start, (int) end + 1));
            start = end + 1;
        }
        log.finish(store.summary(from));
        mem.add(store.header(from).renamed(as), log);
    }

    private void perturbEveryEvent(String scenario, long seed, Map<String, String> params, FaultPlan plan, int stride) {
        Env env = new Env(tmp.resolve(scenario + seed));
        env.record(scenario, seed, params, plan, Boolean.FALSE);
        String t0 = env.last;
        int n = env.events(t0).size();
        int tested = 0;
        for (int k = 0; k < n; k += stride) {
            MemoryTimelines mem = new MemoryTimelines();
            copyInto(mem, env.store, t0, "T0", -1);
            copyInto(mem, env.store, t0, "M", k);
            // the verifier fails at exactly that event
            ReplayEngine.Result r = new ReplayEngine(mem, env.registry).verify("M");
            assertFalse(r.ok(), scenario + " event " + k);
            assertEquals(k, r.violation().actual().seq(), scenario + ": first mismatch");
            // the comparator names exactly that event as the root
            DivergenceReport d = new TimelineComparator(mem).compare("T0", "M", false);
            // the changed event is a root in each timeline's own pass (taint is per timeline, D-16): the same event id on both sides, nothing else
            assertEquals(Set.of(env.events(t0).get(k).eventId()), new TreeSet<>(d.roots().stream().map(TimelineComparator.Root::eventId).toList()), scenario + " event " + k + ": " + d.text());
            assertEquals(env.events(t0).get(k).eventId(), d.primary().eventId(), scenario + " event " + k);
            assertEquals(TimelineComparator.EventClass.CHANGED, d.primary().cls());
            assertTrue(d.unexplained().isEmpty());
            tested++;
        }
        assertEquals((n + stride - 1) / stride, tested);
        assertTrue(tested >= 6, "tested " + tested);
        // and the unperturbed copy is identical
        MemoryTimelines same = new MemoryTimelines();
        copyInto(same, env.store, t0, "T0", -1);
        copyInto(same, env.store, t0, "M", -1);
        assertTrue(new TimelineComparator(same).compare("T0", "M", false).identical());
        assertTrue(new ReplayEngine(same, env.registry).verify("M").ok());
    }

    @Test
    void perturbingAnyEventOfTheFlagshipRunIsCaughtByTheVerifierAndNamedByTheComparator() {
        perturbEveryEvent("re", 42, Map.of(), null, 1);
    }

    @Test
    void theSameHoldsOnTheOtherKnownAnswerScenarios() {
        perturbEveryEvent("ka2", 1, Map.of(), null, 1);
        perturbEveryEvent("ka3", 1, Map.of(), null, 1);
        perturbEveryEvent("pingpong", 1, Map.of(), null, 1);
    }

    @Test
    void theSameHoldsUnderChaosFaultPlansIncludingEnvironmentEvents() {
        for (long seed : new long[]{3, 11, 25}) {
            TestRegistry reg = new TestRegistry();
            FaultPlan plan = ChaosPlanGenerator.generate(reg.create("gossip", Map.of("nodes", "4", "horizon", "400")), seed, 400);
            perturbEveryEvent("gossip", seed, Map.of("nodes", "4", "horizon", "400"), plan, 7);
        }
    }
}
