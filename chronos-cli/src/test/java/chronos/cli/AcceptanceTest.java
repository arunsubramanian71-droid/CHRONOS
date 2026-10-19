package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The acceptance demonstrations AD-1 to AD-5 on the flagship RE application, run through the real store and engine. */
class AcceptanceTest {
    @TempDir
    Path tmp;

    @Test
    void ad1_reRunReportsTheSingleBrokenInvariantAndTheCausalChainFromPartitionToViolation() {
        Env env = new Env(tmp);
        RunResult r = env.record("re", 42, Map.of());
        assertEquals(TimelineStatus.VIOLATED, r.status());
        assertEquals("single-primary", r.firstViolationInvariant());
        assertEquals("inv:single-primary#1", r.firstViolationEventId());
        String t0 = env.last;
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        Event viol = s.goToViolation();
        assertEquals("env", viol.nodeId());
        assertEquals(510, viol.virtualTime(), "b's watchdog fires 300 ms after the last heartbeat it heard (sent at 200, delivered at 210)");
        String why = s.why(null);
        System.out.println(why);
        assertTrue(why.contains("fault:partition-pb#1"), why);
        assertTrue(why.contains("OMISSION"), why);
        assertTrue(why.contains("TIMER_FIRED") || why.contains("timer watchdog fires"), why);
        // the chain from the partition: fault --OMISSION--> watchdog firing --WITNESS--> violation
        List<List<Integer>> chains = s.whyChains((int) viol.seq());
        List<Integer> faultChain = chains.get(0);
        List<String> ids = faultChain.stream().map(p -> env.events(t0).get(p).eventId()).toList();
        assertEquals("fault:partition-pb#1", ids.get(0));
        assertEquals("inv:single-primary#1", ids.get(ids.size() - 1));
        assertEquals(3, ids.size());
        Event watchdog = env.events(t0).get(faultChain.get(1));
        assertEquals("b", watchdog.nodeId());
        assertEquals(EventKind.TIMER_FIRED, watchdog.kind());
        assertTrue(watchdog.parents().stream().anyMatch(p -> p.kind() == EdgeKind.OMISSION && p.id().equals("fault:partition-pb#1")));
    }

    @Test
    void ad2_replayReproducesTheLogAndTheBadCitizenIsCaught() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        ReplayEngine.Result ok = env.replay.verify(t0);
        assertTrue(ok.ok(), ok.summary());
        assertEquals(LogHash.of(env.events(t0)), ok.logHash());
        assertEquals(env.events(t0).size(), ok.eventsVerified());
        // a fresh store instance loads the persisted log and replays it identically
        Env fresh = new Env(tmp);
        ReplayEngine.Result again = fresh.replay.verify(t0);
        assertTrue(again.ok());
        assertEquals(ok.logHash(), again.logHash());

        // the deliberately nondeterministic node
        env.record("bad", 1, Map.of());
        String bad = env.last;
        ReplayEngine.Result r = env.replay.verify(bad);
        assertFalse(r.ok(), "the verifier must catch hidden use of the system clock");
        assertEquals(EventKind.TIMER_SET, r.violation().expected().kind());
        assertEquals("B", r.violation().expected().nodeId());
        assertTrue(r.violation().differences().stream().anyMatch(d -> d.startsWith("payload.due")), r.violation().differences().toString());
        assertTrue(r.violation().getMessage().contains("expected a timer due at"), r.violation().getMessage());
        System.out.println(r.violation().report());
    }

    @Test
    void ad3_timeTravelReconstructsStatesAndInflightMessagesWithoutRunningCode() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        Event viol = s.goToViolation();
        long violPos = viol.seq();
        // one step before the violation: the watchdog firing (b promoted); state shows both nodes as PRIMARY
        Event before = s.back(1);
        assertEquals("b", before.nodeId());
        Map<String, Object> pState = s.stateOf("p"), bState = s.stateOf("b");
        assertEquals("PRIMARY", Canon.map(pState.get("volatile")).get("role"));
        // the cursor is inside the step: the completed boundary excludes the trigger's own state record
        s.goTo((int) violPos);
        assertEquals("PRIMARY", Canon.map(s.stateOf("b").get("volatile")).get("role"));
        assertEquals("PRIMARY", Canon.map(s.stateOf("p").get("volatile")).get("role"));
        System.out.println(s.world());
        // right after p sends a heartbeat, that heartbeat is in flight and p's heartbeat timer is pending
        s.goTo(0);
        Event sent = s.find(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && "Heartbeat".equals(e.payload().get("type")), true).orElseThrow();
        assertTrue(s.inflight().stream().anyMatch(m -> m.messageId().equals(sent.eventId()) && m.to().equals("b") && m.dueAt() == sent.virtualTime() + 10));
        assertTrue(s.timers().stream().anyMatch(t -> t.node().equals("b") && t.name().equals("watchdog")));
        s.goTo((int) violPos);
        // step backward three times, forward three times: reversibility
        String before3 = s.world();
        s.back(3);
        s.step(3);
        assertEquals(before3, s.world());
        // earlier, b was still a backup
        s.goTo(0);
        s.find(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("b"), true);
        assertEquals("BACKUP", Canon.map(s.stateOf("b").get("volatile")).get("role"));
    }

    @Test
    void ad4_branchAtThePartitionAndHealItTwoHundredMillisecondsEarlierMakesTheViolationDisappear() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        CounterfactualEngine cf = new CounterfactualEngine(env.manager);
        CounterfactualEngine.Report rep = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("heal-early", "partition-pb", -200)), 1000L, "heal 200 ms earlier"));
        System.out.println(rep.text());
        assertEquals(CounterfactualEngine.Verdict.FAILURE_DISAPPEARS, rep.verdict());
        assertEquals("T0.1", rep.timelineId());
        // the branch shares the parent's prefix, and the intervention is recorded on env (D-25)
        List<Event> child = env.events("T0.1");
        List<Event> parent = env.events(t0);
        for (int i = 0; i <= at; i++) assertEquals(parent.get(i), child.get(i));
        Event iv = child.get((int) at + 1);
        assertEquals("iv:heal-early", iv.eventId());
        assertEquals(EventKind.INTERVENTION_APPLIED, iv.kind());
        assertEquals("env", iv.nodeId());
        Event healed = child.stream().filter(e -> e.eventId().equals("fault:partition-pb#2")).findFirst().orElseThrow();
        assertEquals(500, healed.virtualTime());
        assertEquals("iv:heal-early", healed.origin());
        assertTrue(healed.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("iv:heal-early")));
        assertEquals(TimelineStatus.TIMED_OUT, env.store.summary("T0.1").status());
        assertNull(env.store.summary("T0.1").firstViolationEventId());
    }

    @Test
    void ad5_comparisonNamesTheInterventionAsFirstCausalDivergenceAndExplainsThePathFromThePartition() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        long at = env.events(t0).stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        CounterfactualEngine cf = new CounterfactualEngine(env.manager);
        CounterfactualEngine.Report rep = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("heal-early", "partition-pb", -200)), 1000L, null));
        DivergenceReport d = new TimelineComparator(env.store).compare(t0, rep.timelineId(), false);
        System.out.println(d.text());
        assertFalse(d.identical());
        assertNotNull(d.primary());
        assertEquals("iv:heal-early", d.primary().eventId(), "law M10: the first causal divergence is the intervention's own event");
        assertEquals('B', d.primary().side());
        assertEquals(at + 1, d.sharedPrefix() == 0 ? -1 : d.sharedPrefix());
        // the path is drawn in T0, where the failure is, starting at the anchor: the partition event that both timelines share
        assertEquals(t0, d.failureTimeline());
        assertEquals("inv:single-primary#1", d.failureEvent());
        assertEquals("fault:partition-pb#1", d.path().get(0).from());
        assertEquals("inv:single-primary#1", d.path().get(d.path().size() - 1).to());
        assertTrue(d.path().stream().anyMatch(h -> h.kind() == EdgeKind.OMISSION), "the path runs through the OMISSION link to the watchdog");
        assertTrue(d.unexplained().isEmpty(), d.unexplained().toString());
        assertTrue(d.flags().stream().noneMatch(f -> f.contains("nondeterminism")), d.flags().toString());
    }
}
