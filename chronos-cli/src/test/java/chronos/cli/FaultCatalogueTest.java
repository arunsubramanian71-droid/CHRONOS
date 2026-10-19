package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.CounterfactualEngine.Verdict;
import chronos.model.*;
import chronos.model.FaultRule.FaultAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The rest of the fault catalogue (Section 18) and intervention catalogue (Section 19), through the real store, replay and comparator. */
class FaultCatalogueTest {
    @TempDir
    Path tmp;

    private Env env() {
        return new Env(tmp);
    }

    private static List<Event> byKind(List<Event> ev, EventKind k) {
        return ev.stream().filter(e -> e.kind() == k).toList();
    }

    private void assertReplays(Env env, String tl) {
        ReplayEngine.Result r = env.replay.verify(tl);
        assertTrue(r.ok(), tl + ": " + r.summary());
        for (long c : env.store.checkpointSeqs(tl)) {
            ReplayEngine.Result rc = env.replay.verifyFrom(tl, c);
            assertTrue(rc.ok(), tl + " from checkpoint " + c + ": " + rc.summary());
        }
    }

    // ------------------------------------------------------------------------------------------------ pause
    @Test
    void aPausedPrimaryCausesASplitBrainLikeALongGcPause_andTheOmissionEdgeBlamesThePause() {
        Env env = env();
        FaultPlan plan = FaultPlan.of(FaultRule.pause("gc-pause", "p", 300, 400));
        env.record("re", 42, Map.of("partition", "off"), plan, Boolean.FALSE);
        String t0 = env.last;
        List<Event> ev = env.events(t0);
        Event paused = byKind(ev, EventKind.NODE_PAUSED).get(0), resumed = byKind(ev, EventKind.NODE_RESUMED).get(0);
        assertEquals("p", paused.nodeId());
        assertEquals(300, paused.virtualTime());
        assertEquals(700, resumed.virtualTime());
        assertTrue(paused.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:gc-pause#1")));
        assertTrue(resumed.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:gc-pause#2")));
        // p runs nothing while frozen, and the items it held run only after the resume
        for (Event e : ev)
            if (e.nodeId().equals("p") && e.seq() > paused.seq() && e.seq() < resumed.seq()) fail("p ran while paused: " + e);
        Event firstHeld = ev.stream().filter(e -> e.nodeId().equals("p") && e.seq() > resumed.seq()).findFirst().orElseThrow();
        assertEquals(700, firstHeld.virtualTime(), "held items run at the resume time");
        // b hears nothing from the frozen p, its watchdog fires with an OMISSION edge from the pause fault (pause scope, D-23)
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        s.goToViolation();
        List<Event> chain = s.whyChains(s.cursor()).get(0).stream().map(ev::get).toList();
        assertEquals("fault:gc-pause#1", chain.get(0).eventId());
        assertTrue(chain.get(1).parents().stream().anyMatch(p -> p.kind() == EdgeKind.OMISSION && p.id().equals("fault:gc-pause#1")));
        assertReplays(env, t0);
        // removing the pause makes the failure disappear
        long at = env.manager.roundToBoundary(t0, ev.stream().filter(e -> e.eventId().equals("fault:gc-pause#1")).findFirst().orElseThrow().seq() - 1);
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.removeFault("no-pause", "gc-pause")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, rep.verdict());
        assertReplays(env, rep.timelineId());
    }

    @Test
    void removingAnActivePauseResumesTheNodeImmediately() {
        Env env = env();
        env.record("re", 42, Map.of("partition", "off"), FaultPlan.of(FaultRule.pause("gc-pause", "p", 300, 400)), Boolean.FALSE);
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.kind() == EventKind.NODE_PAUSED).findFirst().orElseThrow().seq();
        at = env.manager.roundToBoundary(t0, at);
        var br = env.manager.branch(t0, at, List.of(Intervention.removeFault("resume-now", "gc-pause")), null, null);
        List<Event> child = env.events(br.timelineId());
        Event resumed = child.stream().filter(e -> e.kind() == EventKind.NODE_RESUMED).findFirst().orElseThrow();
        assertEquals(300, resumed.virtualTime());
        assertEquals("iv:resume-now", resumed.origin());
        assertReplays(env, br.timelineId());
    }

    // ------------------------------------------------------------------------------------------------ clock skew
    @Test
    void clockSkewChangesWhatANodeReadsButNeverTheLogicalClocks() {
        Env env = env();
        env.record("clocky", 1, Map.of(), FaultPlan.empty(), null);
        String base = env.last;
        env.record("clocky", 1, Map.of(), FaultPlan.of(FaultRule.clockSkew("fast-clock", "a", 100, 5000, 200L)), null);
        String skewed = env.last;
        List<Event> a = env.events(base), b = env.events(skewed);
        List<Event> bNoEnv = b.stream().filter(e -> !e.isEnv()).toList();
        assertEquals(a.size(), bNoEnv.size() + 0 * b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).eventId(), bNoEnv.get(i).eventId());
            assertEquals(a.get(i).lamport(), bNoEnv.get(i).lamport(), "Lamport counts causal steps, not time: " + a.get(i).eventId());
            assertEquals(a.get(i).vector().subList(0, 2), bNoEnv.get(i).vector().subList(0, 2), "vector slots of the nodes are unchanged");
        }
        // what a read: the stored readings differ exactly while the fault is in force
        List<Long> readsA = reads(a), readsB = reads(b);
        assertEquals(readsA.size(), readsB.size());
        boolean skewedSome = false;
        for (int i = 0; i < readsA.size(); i++) {
            long diff = readsB.get(i) - readsA.get(i);
            assertTrue(diff == 0 || diff == 5000, "diff " + diff);
            skewedSome |= diff == 5000;
        }
        assertTrue(skewedSome);
        assertTrue(byKind(b, EventKind.FAULT_CLEARED).stream().anyMatch(e -> e.eventId().equals("fault:fast-clock#2") && e.virtualTime() == 300));
        assertReplays(env, skewed);
    }

    private static List<Long> reads(List<Event> ev) {
        List<Long> out = new ArrayList<>();
        for (Event e : ev)
            if (e.nodeId().equals("a") && e.stateAfter() != null && e.kind() == EventKind.TIMER_FIRED)
                out.add(Canon.num(Canon.map(e.stateAfter().get("volatile")).get("lastRead")));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ slow link
    @Test
    void aSlowLinkStretchesEveryDeliveryOnItAndTheDeliveriesCarryTheFaultAsParent() {
        Env env = env();
        env.record("ka2", 1, Map.of("delay", "off"), FaultPlan.of(FaultRule.slowLink("slow", "p", "b", 5, 0, null)), Boolean.FALSE);
        String tl = env.last;
        List<Event> ev = env.events(tl);
        List<Event> sends = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && "Replicate".equals(e.payload().get("type"))).toList();
        assertEquals(2, sends.size());
        for (Event send : sends) {
            Event delivered = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.payload().get("messageId").equals(send.eventId())).findFirst().orElseThrow();
            assertEquals(send.virtualTime() + 10 * 5, delivered.virtualTime(), "latency 10 ms x factor 5");
            assertTrue(delivered.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:slow#1")));
        }
        // the slow link on p->b leaves the other links alone (client -> p is still 10 ms)
        Event w = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("p")).findFirst().orElseThrow();
        assertEquals(20, w.virtualTime());
        assertReplays(env, tl);
    }

    // ------------------------------------------------------------------------------------------------ reorder
    @Test
    void reorderingTwoReplicatesReproducesTheLostUpdateWithoutAnyDelay() {
        Env env = env();
        FaultPlan plan = FaultPlan.of(FaultRule.reorder("swap-first-two", "p", "b", "Replicate", 1));
        env.record("ka2", 1, Map.of("delay", "off"), plan, null);
        String t0 = env.last;
        assertEquals("replicas-converge", env.store.summary(t0).firstViolationInvariant());
        List<Event> ev = env.events(t0);
        List<Event> deliveries = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("b")).toList();
        assertEquals(2, deliveries.size());
        assertTrue(Canon.str(deliveries.get(0).payload().get("messageId")).compareTo(Canon.str(deliveries.get(1).payload().get("messageId"))) > 0 || deliveries.get(0).virtualTime() < deliveries.get(1).virtualTime());
        Event second = deliveries.get(1);
        assertTrue(second.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:swap-first-two#1")), "the held message carries the reorder fault");
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        s.goToViolation();
        assertEquals("fault:swap-first-two#1", ev.get(s.whyChains(s.cursor()).get(0).get(0)).eventId());
        assertReplays(env, t0);
    }

    // ------------------------------------------------------------------------------------------------ after named event
    @Test
    void aRuleCanFireRightAfterANamedEvent() {
        Env env = env();
        FaultPlan plan = FaultPlan.of(FaultRule.after("crash-after-prepare", "coord#3", FaultAction.CRASH, Map.of("node", "coord")));
        env.record("ka3", 1, Map.of("crash", "off"), plan, null);
        String tl = env.last;
        List<Event> ev = env.events(tl);
        Event crash = byKind(ev, EventKind.NODE_CRASHED).get(0);
        assertEquals("coord", crash.nodeId());
        Event named = ev.stream().filter(e -> e.eventId().equals("coord#3")).findFirst().orElseThrow();
        assertEquals(named.virtualTime(), crash.virtualTime(), "immediately after the named event");
        assertTrue(crash.seq() > named.seq());
        Event fault = ev.stream().filter(e -> e.eventId().equals("fault:crash-after-prepare#1")).findFirst().orElseThrow();
        assertTrue(fault.parents().stream().anyMatch(p -> p.id().equals("coord#3") && p.kind() == EdgeKind.STEP_EFFECT), "the trigger that fired the rule is the fault's parent");
        assertEquals("no-blocked-participant", env.store.summary(tl).firstViolationInvariant());
        assertReplays(env, tl);
    }

    // ------------------------------------------------------------------------------------------------ regression: found by the intervention fuzzer
    @Test
    void aMessageCannotBeSwappedWithItself() {
        assertThrows(IllegalArgumentException.class, () -> Intervention.swapDeliveries("s", "b#5", "b#5"));
    }

    // ------------------------------------------------------------------------------------------------ on node state
    @Test
    void aRuleCanFireTheFirstTimeANodesStateSatisfiesAPredicate() {
        Env env = env();
        FaultPlan plan = FaultPlan.of(FaultRule.onState("crash-p-at-hb3", "p", "volatile.hb", 3L, FaultAction.CRASH, Map.of("node", "p")));
        env.record("re", 42, Map.of("partition", "off"), plan, Boolean.FALSE);
        String tl = env.last;
        List<Event> ev = env.events(tl);
        Event crash = byKind(ev, EventKind.NODE_CRASHED).get(0);
        assertEquals("p", crash.nodeId());
        // the trigger is the step in which p's heartbeat counter became 3: the third heartbeat timer firing
        Event trigger = ev.stream().filter(e -> e.nodeId().equals("p") && e.kind() == EventKind.TIMER_FIRED).skip(2).findFirst().orElseThrow();
        Event fault = ev.stream().filter(e -> e.eventId().equals("fault:crash-p-at-hb3#1")).findFirst().orElseThrow();
        assertTrue(fault.parents().stream().anyMatch(p -> p.id().equals(trigger.eventId()) && p.kind() == EdgeKind.STEP_EFFECT));
        assertEquals(trigger.virtualTime(), crash.virtualTime(), "immediately after the step that made the predicate true");
        // it fires once: p stays down (no restart rule) and b, hearing nothing, eventually promotes
        assertEquals(1, byKind(ev, EventKind.NODE_CRASHED).size());
        assertReplays(env, tl);
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("r", FaultRule.TriggerType.ON_STATE, FaultAction.CRASH, Map.of("node", "p", "watchNode", "p", "watchField", "hb", "watchValue", 1), false));
        assertThrows(IllegalArgumentException.class, () -> env.record("re", 1, Map.of(), FaultPlan.of(FaultRule.onState("x", "ghost", "volatile.a", 1L, FaultAction.CRASH, Map.of("node", "p"))), null));
    }

    // ------------------------------------------------------------------------------------------------ interventions
    @Test
    void pauseAndSkewInterventionsIntroduceAFailure_sufficiency() {
        Env env = env();
        env.record("re", 42, Map.of("partition", "off"), null, Boolean.FALSE);
        String t0 = env.last;
        assertNull(env.store.summary(t0).firstViolationEventId());
        long at = env.manager.roundToBoundary(t0, 30);
        long vt = env.events(t0).get((int) at).virtualTime();
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.pauseNode("gc", "p", vt + 10, 400)), null, null));
        assertEquals(Verdict.DIFFERENT_FAILURE, rep.verdict(), rep.text());
        assertEquals("single-primary", env.store.summary(rep.timelineId()).firstViolationInvariant());
        assertReplays(env, rep.timelineId());
        var sk = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.skewClock("skew", "b", vt + 5, 77, null)), null, null));
        assertEquals(Verdict.NO_EFFECT, sk.verdict(), "RE never reads the clock: skewing it changes nothing a node noticed");
        assertReplays(env, sk.timelineId());
    }

    @Test
    void aCrashedNodeCanBeRestartedByAnIntervention() {
        Env env = env();
        env.record("control", 3, Map.of(), FaultPlan.of(FaultRule.crash("kill-src", "src", 200)), Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, env.events(t0).stream().filter(e -> e.kind() == EventKind.NODE_CRASHED).findFirst().orElseThrow().seq());
        long vt = env.events(t0).get((int) at).virtualTime();
        var br = env.manager.branch(t0, at, List.of(Intervention.restartNode("revive", "src", vt + 100)), null, null);
        List<Event> child = env.events(br.timelineId());
        Event restarted = child.stream().filter(e -> e.kind() == EventKind.NODE_RESTARTED).findFirst().orElseThrow();
        assertEquals("iv_revive", restarted.parents().stream().filter(p -> p.kind() == EdgeKind.FAULT).map(p -> p.id().startsWith("fault:") ? "iv_revive" : p.id()).findFirst().orElseThrow());
        assertEquals("iv:revive", restarted.origin());
        assertTrue(child.stream().anyMatch(e -> e.nodeId().equals("src") && e.kind() == EventKind.MESSAGE_SENT && e.virtualTime() > vt + 100), "src works again after the restart");
        assertReplays(env, br.timelineId());
    }

    @Test
    void addingAndRemovingClientInputsAndSwappingDeliveries() {
        Env env = env();
        env.record("ka2", 1, Map.of("delay", "off"), null, null);
        String t0 = env.last;
        List<Event> ev = env.events(t0);
        // swap the two in-flight replicates: b applies them in the other order, the lost update appears
        Event second = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && "Replicate".equals(e.payload().get("type"))).skip(1).findFirst().orElseThrow();
        Event first = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && "Replicate".equals(e.payload().get("type"))).findFirst().orElseThrow();
        long at = env.manager.roundToBoundary(t0, second.seq());
        // the boundary must be after the second send and before the first delivery
        var swap = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.swapDeliveries("swap", first.eventId(), second.eventId())), null, null));
        assertEquals(Verdict.DIFFERENT_FAILURE, swap.verdict(), swap.text());
        assertReplays(env, swap.timelineId());
        // add a client input after the branch point: it appears with its origin and a FAULT edge from the intervention
        var add = env.manager.branch(t0, env.manager.roundToBoundary(t0, 5), List.of(Intervention.addInput("w3", "client", 100, Map.of("key", "y", "value", 9L))), null, null);
        Event input = env.events(add.timelineId()).stream().filter(e -> e.kind() == EventKind.CLIENT_INPUT && e.virtualTime() == 100).findFirst().orElseThrow();
        assertEquals("iv:w3", input.origin());
        assertTrue(input.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("iv:w3")));
        assertReplays(env, add.timelineId());
        // remove the second scripted input before it happens
        var rm = env.manager.branch(t0, env.manager.roundToBoundary(t0, 5), List.of(Intervention.removeInput("no-w2", 1)), null, null);
        assertEquals(1, byKind(env.events(rm.timelineId()), EventKind.CLIENT_INPUT).size());
        assertReplays(env, rm.timelineId());
        // removing an input that already happened is refused clearly
        assertThrows(IllegalArgumentException.class, () -> env.manager.branch(t0, env.store.groupEnds(t0).get(env.store.groupEnds(t0).size() - 1), List.of(Intervention.removeInput("late", 0)), null, null));
    }

    @Test
    void configPatchAndReseedChangeTheBehaviourFromTheBranchPointOn() {
        Env env = env();
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        // b's watchdog is re-armed with the configured timeout on every heartbeat, so the patch must come before the heartbeat that
        // arrives at t=210 (the one whose watchdog would otherwise fire at 510)
        long at = env.manager.roundToBoundary(t0, env.events(t0).stream().filter(e -> e.virtualTime() > 150).findFirst().orElseThrow().seq() - 1);
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.configPatch("slow-watchdog", "watchdog", "700")), null, null));
        assertEquals(Verdict.FAILURE_DISAPPEARS, rep.verdict(), rep.text());
        assertReplays(env, rep.timelineId());
        env.record("gossip", 3, Map.of("nodes", "4", "horizon", "300"), null, null);
        String g = env.last;
        long gAt = env.manager.roundToBoundary(g, 30);
        var rs = env.manager.branch(g, gAt, List.of(Intervention.reseedStream("again", "g0", "peer", 123456789L)), null, null);
        assertNotEquals(LogHash.of(env.events(g)), LogHash.of(env.events(rs.timelineId())));
        assertReplays(env, rs.timelineId());
    }

    @Test
    void aStateEditIsSyntheticAndSaysSoEverywhere() {
        Env env = env();
        env.record("re", 42, Map.of("partition", "off"), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, 30);
        var rep = new CounterfactualEngine(env.manager).run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.patchState("promote-b", "b", "volatile.role", "PRIMARY")), null, null));
        assertEquals(Verdict.DIFFERENT_FAILURE, rep.verdict());
        assertTrue(rep.synthetic(), "a timeline created with a state edit is permanently marked synthetic");
        assertTrue(rep.text().contains("synthetic"));
        String child = rep.timelineId();
        List<Event> ev = env.events(child);
        Event viol = byKind(ev, EventKind.INVARIANT_VIOLATED).get(0);
        assertTrue(viol.parents().stream().anyMatch(p -> p.kind() == EdgeKind.WITNESS && p.id().equals("iv:promote-b")), "the edit is the last writer of b's role, so it is a witness");
        // reconstruction sees the edit although no node step recorded it
        TimeTravelSession s = new TimeTravelSession(env.store, child);
        s.goTo(ev.stream().filter(e -> e.eventId().equals("iv:promote-b")).findFirst().orElseThrow().seq() > 0 ? (int) ev.stream().filter(e -> e.eventId().equals("iv:promote-b")).findFirst().orElseThrow().seq() : 0);
        assertEquals("PRIMARY", Canon.map(s.stateOf("b").get("volatile")).get("role"));
        assertReplays(env, child);
        // the mark is inherited by branches of a synthetic timeline
        var grand = env.manager.branch(child, env.manager.roundToBoundary(child, ev.size() - 1), List.of(), null, null);
        assertTrue(env.manager.isSynthetic(grand.timelineId()));
        assertFalse(env.manager.isSynthetic(t0));
    }

    @Test
    void lengtheningAPartitionMarksItsExtraDropsAsTheInterventionsDoing() {
        Env env = env();
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        for (long by : new long[]{150, 300}) {
            var br = env.manager.branch(t0, at, List.of(Intervention.shiftHeal("later" + by, "partition-pb", by)), null, null);
            DivergenceReport d = new TimelineComparator(env.store).compare(t0, br.timelineId(), false);
            assertEquals("iv:later" + by, d.primary().eventId());
            CausalGraph g = CausalGraph.build(env.events(br.timelineId()));
            BitSet future = g.causalFutureOf(List.of(g.pos("iv:later" + by)));
            // every branch root is in the intervention's causal future, or is a delivery to a node whose message the intervention caused to be dropped
            // (an input difference: that node's later events differ because an earlier delivery never happened)
            List<Event> child = env.events(br.timelineId());
            Set<String> dropTargets = new HashSet<>();
            for (Event e : child)
                if (e.kind() == EventKind.MESSAGE_DROPPED && ("iv:later" + by).equals(e.origin()))
                    dropTargets.add(Canon.str(child.stream().filter(x -> x.eventId().equals(Canon.str(e.payload().get("messageId")))).findFirst().orElseThrow().payload().get("to")));
            for (TimelineComparator.Root r : d.roots())
                if (r.side() == 'B' && !future.get(r.pos()))
                    assertTrue(dropTargets.contains(r.nodeId()) && (child.get(r.pos()).kind() == EventKind.MESSAGE_DELIVERED || child.get(r.pos()).kind() == EventKind.TIMER_FIRED),
                            "root " + r.eventId() + " is neither in the future of the lengthening nor explained by a drop it caused\n" + d.text());
            // the extra drops are marked as the intervention's doing
            List<Event> drops = env.events(br.timelineId()).stream().filter(e -> e.kind() == EventKind.MESSAGE_DROPPED && ("iv:later" + by).equals(e.origin())).toList();
            assertFalse(drops.isEmpty(), "by " + by);
            assertReplays(env, br.timelineId());
        }
    }

    @Test
    void theNewFaultKindsSurviveChaosSoakWithReplayAndBranching() {
        // chaos plans now draw from the whole catalogue; every run must stay lawful and replayable
        Env env = env();
        for (long seed = 1; seed <= 30; seed++) {
            var plan = ChaosPlanGenerator.generate(env.registry.create("re2", Map.of()), seed, 900);
            env.record("re2", seed, Map.of(), plan, Boolean.FALSE);
            String tl = env.last;
            assertTrue(CausalGraph.build(env.events(tl)).selfCheck(150).isEmpty(), "seed " + seed);
            assertTrue(env.replay.verify(tl).ok(), "seed " + seed + " " + plan.toList());
        }
    }

    @Test
    void invalidNewRulesAreRefusedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("r", FaultRule.TriggerType.AT_TIME, FaultAction.PAUSE, Map.of("node", "a", "at", 1), false));
        assertThrows(IllegalArgumentException.class, () -> FaultRule.slowLink("r", "a", "b", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("r", FaultRule.TriggerType.PROBABILISTIC, FaultAction.REORDER, Map.of("sender", "a", "receiver", "b", "type", "t", "perMille", 5), false));
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("r", FaultRule.TriggerType.AT_TIME, FaultAction.DROP, Map.of("at", 1), false));
        Env env = env();
        assertThrows(IllegalArgumentException.class, () -> env.record("re", 1, Map.of(), FaultPlan.of(FaultRule.pause("p", "ghost", 1, 5)), null));
        assertThrows(IllegalArgumentException.class, () -> env.record("re", 1, Map.of(), FaultPlan.of(FaultRule.slowLink("s", "p", "ghost", 2, 1, null)), null));
    }
}
