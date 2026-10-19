package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.CounterfactualEngine.Verdict;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The known-answer bug library (Section 25): scenarios with a planted cause that CHRONOS must find, and a control. */
class KnownAnswerTest {
    @TempDir
    Path tmp;

    private long posBeforeFirstFault(Env env, String tl) {
        List<Event> es = env.events(tl);
        for (Event e : es) if (e.kind() == EventKind.FAULT_INJECTED) return env.manager.roundToBoundary(tl, e.seq() - 1);
        throw new AssertionError("no fault in " + tl);
    }

    // ------------------------------------------------------------------------------------------ KA-0 control
    @Test
    void ka0_aCorrectProtocolNeverViolatesItsInvariantUnderManyRandomFaultPlans() {
        TestRegistry reg = new TestRegistry();
        long[] faultsFired = new long[1];
        int[] crashes = new int[1], partitions = new int[1], drops = new int[1];
        Campaign.Check check = run -> {
            List<String> problems = new ArrayList<>();
            List<Event> ev = run.log().events();
            // the laws hold on every run
            problems.addAll(CausalGraph.build(ev).selfCheck(150));
            ReplayEngine.Result r = new ReplayEngine(MemoryTimelines.of(run), reg).verify(run.header().timelineId());
            if (!r.ok()) problems.add("replay: " + r.summary());
            synchronized (faultsFired) {
                for (Event e : ev) {
                    if (e.kind() == EventKind.FAULT_INJECTED) faultsFired[0]++;
                    if (e.kind() == EventKind.NODE_CRASHED) crashes[0]++;
                    if (e.kind() == EventKind.MESSAGE_DROPPED) drops[0]++;
                    if (e.kind() == EventKind.FAULT_INJECTED && "PARTITION".equals(e.payload().get("action"))) partitions[0]++;
                }
            }
            return problems;
        };
        List<Campaign.Entry> res = Campaign.run(reg, "control", Map.of(), 1, 150, 4, true, null, check);
        assertEquals(150, res.size());
        for (Campaign.Entry e : res) {
            assertFalse(e.violated(), "KA-0 must never violate: seed " + e.seed() + " plan " + e.plan().toList());
            assertTrue(e.problems().isEmpty(), "seed " + e.seed() + ": " + e.problems());
            assertNotEquals(TimelineStatus.ABORTED, e.status());
        }
        assertTrue(faultsFired[0] > 150, "the chaos plans actually injected faults: " + faultsFired[0]);
        assertTrue(crashes[0] > 10 && partitions[0] > 10 && drops[0] > 50, "crashes " + crashes[0] + ", partitions " + partitions[0] + ", drops " + drops[0]);
    }

    // ------------------------------------------------------------------------------------------ KA-1 RE split brain
    @Test
    void ka1_splitBrain_rootIsThePartitionTheChainRunsThroughThePromotionAndHealingEarlyMakesTheFailureDisappear() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        s.goToViolation();
        List<Integer> chain = s.whyChains(s.cursor()).get(0);
        assertEquals("fault:partition-pb#1", env.events(t0).get(chain.get(0)).eventId());
        // the promotion is on the chain: the watchdog firing of b, whose state change made b a primary
        Event promo = env.events(t0).get(chain.get(1));
        assertEquals("b", promo.nodeId());
        assertEquals("PRIMARY", Canon.map(promo.stateAfter().get("volatile")).get("role"));
        CounterfactualEngine.Report rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, posBeforeFirstFault(env, t0),
                List.of(Intervention.removeFault("no-partition", "partition-pb")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, rep.verdict());
        // and the control: RE with no fault plan never fails
        env.record("re", 42, Map.of("partition", "off"));
        assertNull(env.store.summary(env.last).firstViolationEventId());
    }

    // ------------------------------------------------------------------------------------------ intervention search (FR-38)
    @Test
    void interventionSearch_findsThePlantedCauseAmongTheSuspectsAndEveryFindingIsARealBranch() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        InterventionSearch.Result r = new InterventionSearch(env.manager).search(t0, 64, 4, true);
        System.out.println(r.text());
        assertTrue(r.suspectsFound() >= 3 && r.suspectsTried() == r.suspectsFound());
        List<InterventionSearch.Finding> crit = r.critical();
        assertFalse(crit.isEmpty(), "something in RE's causal past is critical");
        // the planted cause (the partition rule) is critical; dropping single messages does not cure split brain
        assertTrue(crit.stream().anyMatch(f -> f.suspect().kind() == InterventionSearch.SuspectKind.FAULT_RULE && f.suspect().intervention().params().get("ruleId").equals("partition-pb")), r.text());
        // ranking: critical first, then closest to the violation first
        for (int i = 1; i < crit.size(); i++) assertTrue(crit.get(i - 1).suspect().distance() <= crit.get(i).suspect().distance());
        // each finding is a branch that exists, replays and has the verdict claimed
        for (InterventionSearch.Finding f : r.findings()) {
            String child = f.report().timelineId();
            assertTrue(new ReplayEngine(env.store, env.registry).verify(child).ok(), "branch " + child + " must replay");
            assertEquals(f.report().verdict(), new CounterfactualEngine(env.manager).assess(t0, child).verdict());
        }
        // the search is deterministic
        Env env2 = new Env(tmp.resolve("again"));
        env2.record("re", 42, Map.of());
        assertEquals(r.text(), new InterventionSearch(env2.manager).search(env2.last, 64, 1, false).text());
        // a run without a violation has nothing to search for
        env.record("re", 42, Map.of("partition", "off"));
        String ok = env.last;
        assertThrows(IllegalArgumentException.class, () -> new InterventionSearch(env.manager).suspects(ok));
    }

    @Test
    void interventionSearch_onTheLostUpdateNamesTheDelayedReplicateAndTheClientWrites() {
        Env env = new Env(tmp);
        env.record("ka2", 1, Map.of());
        InterventionSearch.Result r = new InterventionSearch(env.manager).search(env.last, 64, 4, false);
        assertTrue(r.critical().stream().anyMatch(f -> f.suspect().kind() == InterventionSearch.SuspectKind.FAULT_RULE
                && f.suspect().intervention().params().get("ruleId").equals("delay-first-replicate")), r.text());
        assertTrue(r.findings().stream().anyMatch(f -> f.suspect().kind() == InterventionSearch.SuspectKind.CLIENT_INPUT), "scripted client inputs are suspects too");
        assertEquals(List.of(), env.store.timelineIds().stream().filter(id -> !id.equals("T0")).toList(), "branches are removed unless --keep");
    }

    // ------------------------------------------------------------------------------------------ KA-2 lost update
    @Test
    void ka2_lostUpdate_rootIsTheDelayAndTheButForTestConfirmsIt() {
        Env env = new Env(tmp);
        env.record("ka2", 1, Map.of());
        String t0 = env.last;
        assertEquals("replicas-converge", env.store.summary(t0).firstViolationInvariant());
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        Event v = s.goToViolation();
        System.out.println(s.why(null));
        List<List<Integer>> chains = s.whyChains((int) v.seq());
        List<String> faultChain = chains.get(0).stream().map(p -> env.events(t0).get(p).eventId()).toList();
        assertEquals("fault:delay-first-replicate#1", faultChain.get(0), "root is the delay: " + faultChain);
        // the delayed replicate delivery has the delay as a FAULT parent, and b's stale write is the witness
        Event delayedDelivery = env.events(t0).stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().startsWith("fault:delay"))).findFirst().orElseThrow();
        assertEquals("b", delayedDelivery.nodeId());
        assertTrue(v.parents().stream().anyMatch(p -> p.kind() == EdgeKind.WITNESS && p.id().equals(delayedDelivery.eventId())));
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, posBeforeFirstFault(env, t0),
                List.of(Intervention.removeFault("no-delay", "delay-first-replicate")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, rep.verdict());
        // sufficiency: the same scenario without the delay never fails
        env.record("ka2", 1, Map.of("delay", "off"));
        assertNull(env.store.summary(env.last).firstViolationEventId());
        // without the delay the world quiesces at once with no violation, so even a very short horizon is conclusive
        var quick = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, posBeforeFirstFault(env, t0),
                List.of(Intervention.removeFault("no-delay2", "delay-first-replicate")), 100L, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, quick.verdict());
    }

    @Test
    void inconclusive_whenTheHorizonEndsBeforeTheFailureCouldHaveHappened() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("early", "partition-pb", -200)), 400L, null));
        assertEquals(Verdict.INCONCLUSIVE, rep.verdict(), rep.text());
        assertTrue(rep.explanation().contains("horizon"));
    }

    // ------------------------------------------------------------------------------------------ KA-3 blocked commit
    @Test
    void ka3_blockedCommit_rootIsTheCrashAndWithoutItNoParticipantStaysUndecided() {
        Env env = new Env(tmp);
        env.record("ka3", 1, Map.of());
        String t0 = env.last;
        assertEquals("no-blocked-participant", env.store.summary(t0).firstViolationInvariant());
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        Event v = s.goToViolation();
        assertEquals(230, v.virtualTime());
        System.out.println(s.why(null));
        List<String> chain = s.whyChains((int) v.seq()).get(0).stream().map(p -> env.events(t0).get(p).eventId()).toList();
        assertEquals("fault:crash-coord#1", chain.get(0));
        Event timeout = env.events(t0).get(s.whyChains((int) v.seq()).get(0).get(1));
        assertEquals(EventKind.TIMER_FIRED, timeout.kind());
        assertTrue(timeout.parents().stream().anyMatch(p -> p.kind() == EdgeKind.OMISSION && p.id().equals("fault:crash-coord#1")), "D-23: crash scope, the participant had heard from the coordinator");
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, posBeforeFirstFault(env, t0),
                List.of(Intervention.removeFault("no-crash", "crash-coord")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, rep.verdict());
        env.record("ka3", 1, Map.of("crash", "off"));
        assertEquals(TimelineStatus.COMPLETED, env.store.summary(env.last).status());
        TimeTravelSession ok = new TimeTravelSession(env.store, env.last);
        for (String p : List.of("part1", "part2")) assertEquals("COMMITTED", Canon.map(ok.stateOf(p).get("volatile")).get("state"));
        // sufficiency: crashing the coordinator in the healthy run introduces the failure
        long early = env.events(env.last).stream().filter(e -> e.virtualTime() > 25).findFirst().orElseThrow().seq() - 1;
        var intro = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(env.last, env.manager.roundToBoundary(env.last, early),
                List.of(Intervention.crashNode("crash-it", "coord", 35)), null, null));
        assertEquals(Verdict.DIFFERENT_FAILURE, intro.verdict());
        assertEquals("no-blocked-participant", env.store.summary(intro.timelineId()).firstViolationInvariant());
    }

    // ------------------------------------------------------------------------------------------ KA-4 overdetermined
    @Test
    void ka4_overdetermined_removingEitherPartitionAloneReportsFailurePersists() {
        Env env = new Env(tmp);
        env.record("re2", 42, Map.of());
        String t0 = env.last;
        long at = posBeforeFirstFault(env, t0);
        CounterfactualEngine cf = new CounterfactualEngine(env.manager);
        var r1 = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.removeFault("rm1", "partition-p-b1")), null, null));
        var r2 = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.removeFault("rm2", "partition-p-b2")), null, null));
        assertEquals(Verdict.FAILURE_PERSISTS, r1.verdict(), r1.text());
        assertEquals(Verdict.FAILURE_PERSISTS, r2.verdict(), r2.text());
        System.out.println(r1.text());
        // removing both together makes the failure disappear: the developer must combine interventions
        var both = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.removeFault("rm3", "partition-p-b1"), Intervention.removeFault("rm4", "partition-p-b2")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, both.verdict());
        // the automated search lists both causes (Phase 14b)
        CounterfactualEngine.CauseSearch cs = cf.findCriticalFaultSets(t0, 2, 2);
        assertEquals(1, cs.minimalCriticalSets().size(), cs.tried().toString());
        assertEquals(new TreeSet<>(List.of("partition-p-b1", "partition-p-b2")), cs.minimalCriticalSets().get(0));
        assertEquals(Verdict.FAILURE_PERSISTS, cs.tried().get(new TreeSet<>(List.of("partition-p-b1"))));
        assertEquals(Verdict.FAILURE_PERSISTS, cs.tried().get(new TreeSet<>(List.of("partition-p-b2"))));
    }

    // ------------------------------------------------------------------------------------------ the other verdicts
    @Test
    void noEffect_whenTheInterventionChangedNothingTheNodesDidNotNotice() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        // the heartbeat p sends at t=400 would be dropped by the partition anyway
        String hb400 = env.events(t0).stream().filter(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && "Heartbeat".equals(e.payload().get("type")) && e.virtualTime() == 400).findFirst().orElseThrow().eventId();
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.dropMessage("anyway", hb400)), null, null));
        assertEquals(Verdict.NO_EFFECT, rep.verdict(), rep.text());
        // a bad intervention is refused with a clear message, and leaves no half-written timeline behind
        assertThrows(IllegalArgumentException.class, () -> new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.removeFault("x", "no-such-rule")), null, null)));
    }
}
