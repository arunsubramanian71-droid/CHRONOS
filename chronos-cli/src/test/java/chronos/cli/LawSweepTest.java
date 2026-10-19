package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The branching laws M6, M7 and M8 swept over many random chaos runs (faults in force at the branch point), not just hand-picked ones. */
class LawSweepTest {
    @TempDir
    Path tmp;

    private static String strip(Event e) {
        return e.toLine().replaceFirst("\"tl\":\"[^\"]*\",", "");
    }

    private static String behaviour(Event e) {
        return e.eventId() + "|" + e.kind() + "|" + e.virtualTime() + "|" + Canon.json(e.payload()) + "|" + e.stateDigestAfter();
    }

    @Test
    void m6AndM7_aNoOpBranchAtAnyBoundaryOfAnyChaosRunReproducesItsParentUnderEitherPolicy() {
        TestRegistry reg = new TestRegistry();
        List<String> scenarios = List.of("gossip", "re", "re2", "ka2", "ka3", "control", "twopairs", "pingpong");
        int branches = 0;
        for (long seed = 1; seed <= 48; seed++) {
            String sc = scenarios.get((int) (seed % scenarios.size()));
            Map<String, String> params = sc.equals("gossip") ? Map.of("nodes", "4", "horizon", "400") : Map.of();
            var scenario = reg.create(sc, params);
            FaultPlan plan = ChaosPlanGenerator.generate(scenario, seed, sc.equals("gossip") ? 400 : scenario.defaultHorizon());
            Env env = new Env(tmp.resolve("e" + seed));
            env.record(sc, seed, params, plan, Boolean.FALSE);
            String t = env.last;
            List<Event> parent = env.events(t);
            SeededRandom rnd = new SeededRandom(seed);
            for (int k = 0; k < 3; k++) {
                long want = env.manager.bootEnd(t) + Math.floorMod(rnd.nextLong(), (long) Math.max(1, parent.size() - env.manager.bootEnd(t) - 1));
                long at = env.manager.roundToBoundary(t, want);
                for (boolean pinned : new boolean[]{false, true}) {
                    env.manager.withPinnedContinuation(pinned);
                    var br = env.manager.branch(t, at, List.of(), null, null);
                    env.manager.withPinnedContinuation(false);
                    List<Event> child = env.events(br.timelineId());
                    String where = sc + " seed " + seed + " at " + at + (pinned ? " pinned" : "");
                    assertEquals(parent.size(), child.size(), where);
                    for (int i = 0; i < parent.size(); i++)
                        assertEquals(strip(parent.get(i)), strip(child.get(i)), where + " event " + i);
                    assertTrue(new TimelineComparator(env.store).compare(t, br.timelineId(), true).identical(), where);
                    assertEquals(env.store.summary(t).endStateDigest(), env.store.summary(br.timelineId()).endStateDigest(), where);
                    assertTrue(env.replay.verify(br.timelineId()).ok(), where + " replays through its lineage");
                    branches++;
                }
            }
        }
        assertEquals(48 * 3 * 2, branches);
    }

    @Test
    void m8_aPairUntouchedByTheInterventionBehavesIdenticallyUnderRandomLocalFaultPlans() {
        TestRegistry reg = new TestRegistry();
        int checked = 0;
        for (long seed = 1; seed <= 40; seed++) {
            var scenario = reg.create("twopairs", Map.of());
            FaultPlan plan = ChaosPlanGenerator.generate(scenario, seed, 600);
            if (plan.hasNonLocalRules()) continue; // M8 holds for plans with no nonLocal rules (D-13)
            Env env = new Env(tmp.resolve("m8-" + seed));
            env.record("twopairs", seed, Map.of(), plan, Boolean.FALSE);
            String t = env.last;
            List<Event> base = env.events(t);
            long at = env.manager.roundToBoundary(t, base.size() / 4);
            long vt = base.get((int) at).virtualTime();
            Optional<Event> victim = base.stream().filter(e -> e.seq() > at && e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("a1")).findFirst();
            if (victim.isEmpty()) continue;
            List<List<Intervention>> variants = List.of(
                    List.of(Intervention.dropMessage("d", victim.get().eventId())),
                    List.of(Intervention.delayMessage("l", victim.get().eventId(), 90)),
                    List.of(Intervention.duplicateMessage("u", victim.get().eventId())),
                    List.of(Intervention.pauseNode("p", "a2", vt + 10, 60)),
                    List.of(Intervention.skewClock("s", "a1", vt + 5, 70, null)));
            for (List<Intervention> ivs : variants) {
                var br = env.manager.branch(t, at, ivs, null, null);
                List<Event> child = env.events(br.timelineId());
                for (String node : List.of("b1", "b2")) {
                    List<String> a = base.stream().filter(e -> e.nodeId().equals(node) && e.seq() > at).map(LawSweepTest::behaviour).toList();
                    List<String> b = child.stream().filter(e -> e.nodeId().equals(node) && e.seq() > at).map(LawSweepTest::behaviour).toList();
                    assertEquals(a, b, "seed " + seed + " " + ivs.get(0).type() + ": node " + node + " is causally unconnected and must behave identically");
                }
                checked++;
            }
        }
        assertTrue(checked >= 60, "checked " + checked);
    }

    @Test
    void m2_stateInflightAndTimersRebuiltFromTheLogEqualTheLiveEngineAtEveryStepOfChaosRuns() {
        TestRegistry reg = new TestRegistry();
        List<String> scenarios = List.of("gossip", "re", "re2", "ka2", "ka3", "control", "twopairs", "pingpong");
        long checked = 0;
        for (long seed = 1; seed <= 40; seed++) {
            String sc = scenarios.get((int) (seed % scenarios.size()));
            Map<String, String> params = sc.equals("gossip") ? Map.of("nodes", "4", "horizon", "400") : Map.of();
            var scenario = reg.create(sc, params);
            FaultPlan plan = ChaosPlanGenerator.generate(scenario, seed, sc.equals("gossip") ? 400 : scenario.defaultHorizon());
            chronos.runtime.MemoryLog log = new chronos.runtime.MemoryLog();
            chronos.runtime.Engine e = chronos.runtime.Engine.create(scenario, seed, plan, chronos.runtime.RunOptions.of(scenario).withStopOnViolation(false).withCheckpointInterval(10),
                    new chronos.runtime.RecordDecisionSource(scenario, seed), new chronos.runtime.Recorder("T0", 0, List.of(log)));
            Map<Long, Object[]> live = new TreeMap<>();
            e.boot();
            Runnable snap = () -> {
                Map<String, String> states = new TreeMap<>();
                for (String n : e.nodeNames()) states.put(n, Canon.json(e.stateOf(n)));
                live.put(e.nextSeq() - 1, new Object[]{states, e.liveInflight(), e.liveTimers()});
            };
            snap.run();
            while (e.step()) snap.run();
            Recording.MemoryRun run = new Recording.MemoryRun(log, e.result(), Recording.headerFor(scenario, Recording.Spec.of(sc, seed).withParams(params).withPlan(plan), "T0"));
            TimeTravelSession s = new TimeTravelSession(MemoryTimelines.of(run), "T0");
            for (long boundary : log.groupEnds()) {
                Object[] truth = live.get(boundary);
                if (truth == null) continue; // inside the boot sequence
                s.goTo((int) boundary);
                @SuppressWarnings("unchecked") Map<String, String> states = (Map<String, String>) truth[0];
                for (String n : s.timelineHeaderNodes()) assertEquals(states.get(n), Canon.json(s.stateOf(n)), sc + " seed " + seed + ": state of " + n + " at " + boundary);
                Map<String, Integer> inflight = new TreeMap<>();
                for (TimeTravelSession.InFlight m : s.inflight()) inflight.put(m.messageId(), m.copies());
                assertEquals(truth[1], inflight, sc + " seed " + seed + ": messages in flight at " + boundary);
                List<String> timers = new ArrayList<>();
                for (TimeTravelSession.PendingTimer t : s.timers()) timers.add(t.timerId() + "|" + t.node() + "|" + t.name() + "|" + t.dueAt());
                Collections.sort(timers);
                assertEquals(truth[2], timers, sc + " seed " + seed + ": pending timers at " + boundary);
                checked++;
            }
        }
        assertTrue(checked > 1500, "checked " + checked);
    }
}
