package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.MemoryLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Chaos and determinism soak tests on every demo scenario (Section 25: "chaos soak", NFR-01). */
class SoakTest {
    @TempDir
    Path tmp;

    private static final TestRegistry REG = new TestRegistry();
    private static final Map<String, Map<String, String>> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("pingpong", Map.of());
        SCENARIOS.put("causal", Map.of());
        SCENARIOS.put("re", Map.of());
        SCENARIOS.put("re2", Map.of());
        SCENARIOS.put("ka2", Map.of());
        SCENARIOS.put("ka3", Map.of());
        SCENARIOS.put("control", Map.of());
        SCENARIOS.put("gossip", Map.of("nodes", "4", "horizon", "300"));
        SCENARIOS.put("twopairs", Map.of());
        SCENARIOS.put("tutorial", Map.of());
    }

    @Test
    void nfr01_everyDemoScenarioGivesByteIdenticalLogsRunTwiceOverManySeedsWithAndWithoutFaults() {
        int seeds = 200;
        for (Map.Entry<String, Map<String, String>> sc : SCENARIOS.entrySet())
            for (boolean chaos : new boolean[]{false, true}) {
                List<Campaign.Entry> a = Campaign.run(REG, sc.getKey(), sc.getValue(), 1, seeds, 4, chaos, 500L, null);
                List<Campaign.Entry> b = Campaign.run(REG, sc.getKey(), sc.getValue(), 1, seeds, 2, chaos, 500L, null);
                for (int i = 0; i < seeds; i++)
                    assertEquals(a.get(i).logHash(), b.get(i).logHash(), sc.getKey() + (chaos ? " chaos" : " default") + " seed " + a.get(i).seed());
            }
    }

    @Test
    void chaosPlansOnEveryDemoKeepTheLawsAndNeverCrashTheEngineOrTheApplications() {
        for (Map.Entry<String, Map<String, String>> sc : SCENARIOS.entrySet()) {
            Campaign.Check check = run -> {
                List<String> problems = new ArrayList<>();
                List<Event> ev = run.log().events();
                problems.addAll(CausalGraph.build(ev).selfCheck(120));
                if (ev.stream().anyMatch(e -> e.kind() == EventKind.NODE_FAILED))
                    problems.add("a node failed: " + ev.stream().filter(e -> e.kind() == EventKind.NODE_FAILED).findFirst().get().payload());
                Set<String> ids = new HashSet<>();
                for (Event e : ev) if (!ids.add(e.eventId())) problems.add("duplicate id " + e.eventId());
                ReplayEngine.Result r = new ReplayEngine(MemoryTimelines.of(run), REG).verify(run.header().timelineId());
                if (!r.ok()) problems.add("replay: " + r.summary());
                return problems;
            };
            List<Campaign.Entry> res = Campaign.run(REG, sc.getKey(), sc.getValue(), 1, Long.parseLong(System.getenv().getOrDefault("CHRONOS_SOAK_SEEDS", "150")), 6, true, 600L, check); // CHRONOS_SOAK_SEEDS=5000 for a long run
            for (Campaign.Entry e : res)
                assertTrue(e.problems().isEmpty(), sc.getKey() + " seed " + e.seed() + " plan " + e.plan().toList() + ": " + e.problems());
            if (sc.getKey().equals("control")) assertTrue(res.stream().noneMatch(Campaign.Entry::violated));
        }
    }

    @Test
    void steppedReplayWithBreakpointsStopsAtExactlyTheRightEvent_R3() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String tl = env.last;
        List<Event> events = env.events(tl);
        // breakpoint on an event predicate: the first delivery to b after virtual time 300
        ReplayEngine.PausedWorld w = env.replay.runUntil(tl, e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("b") && e.virtualTime() >= 300);
        Event expected = events.stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("b") && e.virtualTime() >= 300).findFirst().orElseThrow();
        assertEquals(expected.eventId(), w.hit().eventId());
        // the world is paused at the step boundary that ends the hit's step group, live and consistent with the log there
        long boundary = env.store.groupEnds(tl).stream().filter(g -> g >= expected.seq()).findFirst().orElseThrow();
        assertEquals(boundary, w.position());
        for (String n : env.store.header(tl).nodes()) {
            TimeTravelSession s = new TimeTravelSession(env.store, tl);
            s.goTo((int) boundary);
            assertEquals(Canon.json(s.stateOf(n)), Canon.json(w.engine().stateOf(n)), "state of " + n + " at the breakpoint");
        }
        // breakpoint on the invariant violation: it stops at the violation's step, and the paused engine can keep running to the same end
        ReplayEngine.PausedWorld v = env.replay.runUntil(tl, e -> e.kind() == EventKind.INVARIANT_VIOLATED);
        assertEquals("inv:single-primary#1", v.hit().eventId());
        assertEquals(events.stream().filter(e -> e.eventId().equals("inv:single-primary#1")).findFirst().orElseThrow().seq(), v.hit().seq());
        assertNotNull(v.engine());
        // a breakpoint that never matches replays the whole run and verifies it
        ReplayEngine.PausedWorld none = env.replay.runUntil(tl, e -> false);
        assertNull(none.hit());
        assertEquals(events.size() - 1, none.position());
        // breakpoints work on a branch too (the lineage is replayed with its interventions)
        long at = events.stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        var br = env.manager.branch(tl, at, List.of(Intervention.shiftHeal("h", "partition-pb", -200)), null, null);
        ReplayEngine.PausedWorld bw = env.replay.runUntil(br.timelineId(), e -> e.eventId().equals("fault:partition-pb#2"));
        assertEquals(500, bw.hit().virtualTime());
        assertEquals("iv:h", bw.hit().origin());
    }

    @Test
    void aTimelineRecordedUnderChaosCanBeBranchedAndReplayedThroughItsLineage() {
        Env env = new Env(tmp);
        int branched = 0;
        for (long seed = 1; seed <= 25; seed++) {
            var plan = ChaosPlanGenerator.generate(REG.create("re2", Map.of()), seed, 800);
            env.record("re2", seed, Map.of(), plan, Boolean.FALSE);
            String tl = env.last;
            assertTrue(env.replay.verify(tl).ok(), "seed " + seed);
            List<Event> ev = env.events(tl);
            long at = env.manager.roundToBoundary(tl, ev.size() / 3);
            var b = env.manager.branch(tl, at, List.of(), null, null);
            assertTrue(env.replay.verify(b.timelineId()).ok(), "no-op branch of seed " + seed);
            branched++;
        }
        assertEquals(25, branched);
    }
}
