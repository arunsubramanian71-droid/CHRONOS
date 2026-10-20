package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.Scenario;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The laws M1 to M14 of Section 25, each with at least one automated test, on the real demo scenarios. */
class LawsTest {
    @TempDir
    Path tmp;

    private static final List<Map.Entry<String, Map<String, String>>> ALL = List.of(
            Map.entry("pingpong", Map.of()), Map.entry("causal", Map.of()), Map.entry("re", Map.of()), Map.entry("re", Map.of("partition", "off")),
            Map.entry("re2", Map.of()), Map.entry("ka2", Map.of()), Map.entry("ka3", Map.of()), Map.entry("control", Map.of()),
            Map.entry("gossip", Map.of("nodes", "4", "horizon", "400")), Map.entry("twopairs", Map.of()), Map.entry("tutorial", Map.of()));

    private long boundaryOfFault(Env env, String tl, String faultEventId) {
        return env.events(tl).stream().filter(e -> e.eventId().equals(faultEventId)).findFirst().orElseThrow().seq();
    }

    // ---------------------------------------------------------------- M1 replay
    @Test
    void m1_replayingAnyRecordedTimelineReproducesItsEventsExactly() {
        Env env = new Env(tmp);
        int runs = 0;
        for (Map.Entry<String, Map<String, String>> sc : ALL)
            for (long seed : new long[]{1, 7, 42}) {
                env.record(sc.getKey(), seed, sc.getValue());
                ReplayEngine.Result r = env.replay.verify(env.last);
                assertTrue(r.ok(), sc.getKey() + " seed " + seed + ": " + r.summary());
                assertEquals(env.events(env.last).size(), r.eventsVerified());
                runs++;
            }
        assertEquals(ALL.size() * 3, runs);
    }

    @Test
    void m1_replayFromEveryCheckpointEqualsReplayFromTheStart_R2() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String tl = env.last;
        List<Long> cps = env.store.checkpointSeqs(tl);
        assertTrue(cps.size() >= 4, "checkpoints: " + cps);
        assertTrue(cps.contains(env.store.summary(tl).lastSeq()), "a checkpoint is written at the end of the run");
        ReplayEngine.Result full = env.replay.verify(tl);
        assertTrue(full.ok());
        for (long c : cps) {
            ReplayEngine.Result r = env.replay.verifyFrom(tl, c);
            assertTrue(r.ok(), "from checkpoint " + c + ": " + r.summary());
            assertEquals(env.events(tl).size() - (c + 1), r.eventsVerified());
            assertEquals(full.logHash(), r.logHash());
        }
    }

    @Test
    void m1_resumedReplayFromEveryCheckpointOfARunThatStoppedOnAViolationEndsWhereTheRunEnded() {
        // regression: a checkpoint taken after the run stopped did not remember the stop, so the replay went on past the recording
        Env env = new Env(tmp);
        for (String sc : List.of("re", "ka2", "ka3", "re2")) {
            env.record(sc, 42, Map.of());
            String tl = env.last;
            assertNotNull(env.store.summary(tl).firstViolationEventId(), sc);
            for (long c : env.store.checkpointSeqs(tl)) {
                ReplayEngine.Result r = env.replay.verifyFrom(tl, c);
                assertTrue(r.ok(), sc + " from checkpoint " + c + ": " + r.summary());
            }
            // branching from the very end of the stopped run still continues (going live is allowed there)
            long end = env.store.summary(tl).lastSeq();
            var br = env.manager.branch(tl, end, List.of(), 1500L, null);
            assertTrue(env.store.summary(br.timelineId()).lastSeq() >= end);
        }
    }

    @Test
    void m1_aMutatedRecordingIsCaughtAtExactlyThatEvent() throws IOException {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String tl = env.last;
        // log mutation test (Section 15): alter one recorded event (and keep the log self-consistent) -> the verifier must fail there
        List<Event> events = new ArrayList<>(env.events(tl));
        int victim = 20;
        Event orig = events.get(victim);
        Map<String, Object> pl = new TreeMap<>(orig.payload());
        pl.put("deliverAt", Canon.num(pl.getOrDefault("deliverAt", 0L)) + 1);
        pl.put("tamper", 1L);
        events.set(victim, new Event(orig.eventId(), orig.seq(), orig.timelineId(), orig.nodeId(), orig.kind(), orig.virtualTime(), orig.lamport(),
                orig.vector(), orig.parents(), pl, orig.stateDigestAfter(), orig.stateAfter(), orig.origin()));
        MemoryTimelines mem = new MemoryTimelines();
        MemoryLog log = new MemoryLog();
        TimelineHeader h = env.store.header(tl);
        // rebuild a memory log with the mutated event, same boundaries
        long start = 0;
        for (long end : env.store.groupEnds(tl)) {
            log.append(events.subList((int) start, (int) end + 1));
            start = end + 1;
        }
        log.finish(env.store.summary(tl));
        mem.add(h, log);
        ReplayEngine.Result r = new ReplayEngine(mem, env.registry).verify(tl);
        assertFalse(r.ok());
        assertEquals(orig.eventId(), r.violation().actual().eventId());
        assertEquals(victim, r.violation().actual().seq());
        assertEquals(events.get(victim - 1).eventId(), r.violation().lastMatchingEventId());
    }

    // ---------------------------------------------------------------- M2 reconstruction
    @Test
    void m2_stateRebuiltFromTheLogEqualsTheStateSeenLiveAtEveryStep() {
        for (Map.Entry<String, Map<String, String>> sc : List.of(Map.entry("re", Map.<String, String>of()), Map.entry("ka3", Map.<String, String>of()),
                Map.entry("control", Map.<String, String>of()), Map.entry("gossip", Map.of("nodes", "4", "horizon", "300")))) {
            Scenario scenario = new TestRegistry().create(sc.getKey(), sc.getValue());
            long seed = 5;
            MemoryLog log = new MemoryLog();
            Engine e = Engine.create(scenario, seed, scenario.defaultFaultPlan(), RunOptions.of(scenario).withCheckpointInterval(0), new RecordDecisionSource(scenario, seed),
                    new Recorder("T0", 0, List.of(log)));
            Map<Long, Map<String, String>> live = new TreeMap<>();
            e.boot();
            live.put(e.nextSeq() - 1, liveStates(e));
            while (e.step()) live.put(e.nextSeq() - 1, liveStates(e));
            Recording.MemoryRun run = new Recording.MemoryRun(log, e.result(), Recording.headerFor(scenario, Recording.Spec.of(sc.getKey(), seed).withParams(sc.getValue()), "T0"));
            TimeTravelSession s = new TimeTravelSession(MemoryTimelines.of(run), "T0");
            int checked = 0;
            for (long boundary : log.groupEnds()) {
                Map<String, String> expected = live.get(boundary);
                if (expected == null) continue; // boundaries inside the boot sequence are not observable from outside the engine
                s.goTo((int) boundary);
                for (String n : s.timelineHeaderNodes())
                    assertEquals(expected.get(n), Canon.json(s.stateOf(n)), sc.getKey() + ": state of " + n + " at step ending " + boundary);
                checked++;
            }
            assertTrue(checked > 3, sc.getKey());
        }
    }

    private static Map<String, String> liveStates(Engine e) {
        Map<String, String> m = new TreeMap<>();
        for (String n : e.nodeNames()) m.put(n, Canon.json(e.stateOf(n)));
        return m;
    }

    @Test
    void m2_inflightAndTimersFromTheNearestCheckpointEqualTheFullFoldAtEveryPosition() throws Exception {
        // reconstruction from a checkpoint (Section 14) must agree with folding the whole log, at every cursor, on every demo
        Env env = new Env(tmp);
        java.lang.reflect.Method inflight = TimeTravelSession.class.getDeclaredMethod("inflight", boolean.class);
        java.lang.reflect.Method timers = TimeTravelSession.class.getDeclaredMethod("timers", boolean.class);
        inflight.setAccessible(true);
        timers.setAccessible(true);
        int checked = 0;
        for (Map.Entry<String, Map<String, String>> sc : ALL) {
            env.record(sc.getKey(), 5, sc.getValue(), null, Boolean.FALSE);
            if (sc.getKey().equals("re2")) continue;
            TimeTravelSession s = new TimeTravelSession(env.store, env.last);
            int n = env.events(env.last).size();
            for (int pos = 0; pos < n; pos += Math.max(1, n / 60)) {
                s.goTo(pos);
                assertEquals(inflight.invoke(s, false).toString(), inflight.invoke(s, true).toString(), sc.getKey() + " in-flight at " + pos);
                assertEquals(timers.invoke(s, false).toString(), timers.invoke(s, true).toString(), sc.getKey() + " timers at " + pos);
                checked++;
            }
        }
        // and under faults that change the agenda: duplicates, delays, pauses, crashes
        FaultPlan plan = FaultPlan.of(FaultRule.nth("d", FaultRule.FaultAction.DUPLICATE, "p", "b", "Heartbeat", 2, null), FaultRule.nth("l", FaultRule.FaultAction.DELAY, "p", "b", "Heartbeat", 3, 130L),
                FaultRule.crash("c", "b", 250), FaultRule.restart("r", "b", 420), FaultRule.pause("g", "p", 100, 60));
        env.record("re", 42, Map.of("partition", "off"), plan, Boolean.FALSE);
        TimeTravelSession s = new TimeTravelSession(env.store, env.last);
        for (int pos = 0; pos < s.graph().size(); pos++) {
            s.goTo(pos);
            assertEquals(inflight.invoke(s, false).toString(), inflight.invoke(s, true).toString(), "faulty in-flight at " + pos);
            assertEquals(timers.invoke(s, false).toString(), timers.invoke(s, true).toString(), "faulty timers at " + pos);
            checked++;
        }
        assertTrue(checked > 300);
    }

    // ---------------------------------------------------------------- M3, M4 clocks and graph
    @Test
    void m3_m4_vectorOrderEqualsGraphReachabilityAndEveryEdgePointsBackwards() {
        Env env = new Env(tmp);
        for (Map.Entry<String, Map<String, String>> sc : ALL) {
            env.record(sc.getKey(), 3, sc.getValue(), null, Boolean.FALSE);
            CausalGraph g = CausalGraph.build(env.events(env.last));
            List<String> problems = g.selfCheck(400);
            assertTrue(problems.isEmpty(), sc.getKey() + ": " + problems.stream().limit(5).toList());
        }
    }

    @Test
    void m3_faultWitnessAndOmissionEdgesCarryClocksLikeMessages() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        List<Event> ev = env.events(env.last);
        Map<String, Event> byId = ev.stream().collect(Collectors.toMap(Event::eventId, e -> e));
        int checked = 0;
        for (Event e : ev)
            for (Parent p : e.parents())
                if (p.kind() == EdgeKind.FAULT || p.kind() == EdgeKind.WITNESS || p.kind() == EdgeKind.OMISSION) {
                    Event src = byId.get(p.id());
                    assertEquals(VectorClock.Order.BEFORE, VectorClock.compare(src.vector(), e.vector()), p.kind() + " " + src.eventId() + " -> " + e.eventId());
                    assertTrue(src.lamport() < e.lamport());
                    checked++;
                }
        assertTrue(checked >= 5, "checked " + checked);
        // env's own vector slot is the last one and counts env events
        List<Event> envEvents = ev.stream().filter(Event::isEnv).toList();
        assertTrue(envEvents.get(envEvents.size() - 1).vector().get(3) >= envEvents.size());
        assertEquals(4, envEvents.get(0).vector().size(), "N + 1 slots: client, p, b, env");
    }

    // ---------------------------------------------------------------- M5 snapshot
    @Test
    void m5_everyStoredCheckpointOfEveryDemoRestoresToTheSameFuture() {
        Env env = new Env(tmp);
        for (Map.Entry<String, Map<String, String>> sc : ALL) {
            env.record(sc.getKey(), 9, sc.getValue(), null, Boolean.FALSE);
            for (long c : env.store.checkpointSeqs(env.last)) {
                ReplayEngine.Result r = env.replay.verifyFrom(env.last, c);
                assertTrue(r.ok(), sc.getKey() + " from checkpoint " + c + ": " + r.summary());
            }
        }
    }

    // ---------------------------------------------------------------- M6, M7 prefix and no-op
    @Test
    void m6_aBranchPrefixEqualsItsParentsAndBranchOfBranchIsConsistent() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = boundaryOfFault(env, t0, "fault:partition-pb#1");
        CounterfactualEngine cf = new CounterfactualEngine(env.manager);
        var r1 = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("e1", "partition-pb", -100)), null, null));
        List<Event> p = env.events(t0), c = env.events(r1.timelineId());
        for (int i = 0; i <= at; i++) assertEquals(p.get(i).toLine(), c.get(i).toLine());
        // branch of the branch, later, with a different change: its prefix is the branch's, which is the parent's
        long at2 = env.manager.roundToBoundary(r1.timelineId(), c.size() / 2);
        assertTrue(at2 > at);
        var r2 = cf.run(new CounterfactualEngine.Query(r1.timelineId(), at2, List.of(Intervention.dropMessage("d1", firstHeartbeatAfter(c, at2))), null, null));
        assertEquals(r1.timelineId() + ".1", r2.timelineId());
        List<Event> g = env.events(r2.timelineId());
        for (int i = 0; i <= at2; i++) assertEquals(c.get(i).toLine(), g.get(i).toLine());
        for (int i = 0; i <= at; i++) assertEquals(p.get(i).toLine(), g.get(i).toLine());
        // both branches replay and verify from scratch through their lineage (interventions at every branch point)
        assertTrue(env.replay.verify(r1.timelineId()).ok());
        ReplayEngine.Result rr = env.replay.verify(r2.timelineId());
        assertTrue(rr.ok(), rr.summary());
        // a branch stores only its suffix
        assertEquals(c.size() - (at + 1), countLogEvents(r1.timelineId()));
    }

    private static String firstHeartbeatAfter(List<Event> evs, long pos) {
        return evs.stream().filter(e -> e.seq() > pos && e.kind() == EventKind.MESSAGE_SENT && "Heartbeat".equals(e.payload().get("type"))).findFirst().orElseThrow().eventId();
    }

    private long countLogEvents(String tl) {
        try (Stream<Path> s = Files.list(tmp.resolve("timelines").resolve(tl).resolve("events"))) {
            long n = 0;
            for (Path p : (Iterable<Path>) s::iterator)
                n += Files.readAllLines(p).stream().filter(l -> !l.startsWith("{\"commit\"")).count();
            return n;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void m7_aBranchWithNoInterventionsEqualsItsParentAndComparisonReportsNoDivergence() {
        Env env = new Env(tmp);
        for (String sc : List.of("re", "ka2", "control")) {
            env.record(sc, 4, Map.of(), null, Boolean.FALSE);
            String t = env.last;
            for (double frac : new double[]{0.2, 0.5, 0.9}) {
                long at = env.manager.roundToBoundary(t, (long) (env.events(t).size() * frac));
                var br = env.manager.branch(t, at, List.of(), null, "no-op");
                List<Event> p = env.events(t), c = env.events(br.timelineId());
                assertEquals(p.size(), c.size(), sc + " at " + at);
                for (int i = 0; i < p.size(); i++) assertEquals(p.get(i).toLine().replaceFirst("\"tl\":\"[^\"]*\",", ""), c.get(i).toLine().replaceFirst("\"tl\":\"[^\"]*\",", ""), sc + " event " + i);
                assertTrue(new TimelineComparator(env.store).compare(t, br.timelineId(), true).identical());
                assertEquals(env.store.summary(t).endStateDigest(), env.store.summary(br.timelineId()).endStateDigest());
            }
        }
    }

    @Test
    void m6_aBranchMadeAtItsParentsOwnBranchPointDoesNotInheritTheParentsInterventions() {
        // found by the intervention fuzzer (seed 5219): the lineage replay applied the parent's interventions to a grandchild whose prefix does not contain them
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, 40);
        long vt = env.events(t0).get((int) at).virtualTime();
        String child = env.manager.branch(t0, at, List.of(Intervention.crashNode("a1", "b", vt + 30)), null, null).timelineId();
        String grand = env.manager.branch(child, at, List.of(Intervention.pauseNode("a2", "p", vt + 10, 50)), null, null).timelineId();
        assertEquals(env.events(t0).subList(0, (int) at + 1).stream().map(Event::eventId).toList(), env.events(grand).subList(0, (int) at + 1).stream().map(Event::eventId).toList());
        assertTrue(env.events(grand).stream().noneMatch(e -> e.eventId().equals("iv:a1")), "the grandchild's history does not contain its parent's intervention");
        assertTrue(env.replay.verify(grand).ok());
        assertTrue(env.replay.verify(child).ok());
    }

    @Test
    void m5_everyCheckpointEqualsTheWorldReachedByReplayingFromTheStartEvenAfterSilentSteps() {
        // found by the intervention fuzzer (seed 6716): a silent step (a restart rule for a node that is not down) consumed its agenda item,
        // a checkpoint after it claimed the previous boundary without that item, and a grandchild branched from it ended at the wrong time
        Env env = new Env(tmp);
        env.checkpointInterval = 1;
        env.manager.withCheckpointInterval(1);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, 40);
        long vt = env.events(t0).get((int) at).virtualTime();
        String child = env.manager.branch(t0, at, List.of(Intervention.restartNode("r", "p", vt + 1)), null, null).timelineId();
        LogReader noCheckpoints = new LogReader() {
            public List<String> timelineIds() { return env.store.timelineIds(); }
            public TimelineHeader header(String id) { return env.store.header(id); }
            public TimelineSummary summary(String id) { return env.store.summary(id); }
            public List<Event> events(String id) { return env.store.events(id); }
            public List<Long> groupEnds(String id) { return env.store.groupEnds(id); }
            public Optional<WorldImage> checkpointAtOrBefore(String id, long seq) { return Optional.empty(); }
            public List<Long> checkpointSeqs(String id) { return List.of(); }
        };
        ReplayEngine scratch = new ReplayEngine(noCheckpoints, env.registry);
        int compared = 0;
        for (long seq : env.store.checkpointSeqs(child)) {
            WorldImage image = env.store.checkpointAtOrBefore(child, seq).orElseThrow();
            if (image.seq() != seq || !env.store.groupEnds(child).contains(seq)) continue;
            Map<String, Object> fromScratch = scratch.buildAt(child, seq).engine().captureImage().data();
            for (String key : List.of("agenda", "time", "step", "nodes", "faults"))
                assertEquals(Canon.json(fromScratch.get(key)), Canon.json(image.data().get(key)), "checkpoint " + seq + ": " + key + " must equal the world replayed from the start");
            compared++;
        }
        assertTrue(compared >= 5, "checkpoints compared: " + compared);
        long second = env.manager.roundToBoundary(child, at + 1);
        String grand = env.manager.branch(child, second, List.of(Intervention.skewClock("k", "b", vt + 10, 5, null)), null, null).timelineId();
        assertTrue(env.replay.verify(grand).ok());
    }

    // ---------------------------------------------------------------- M8 locality
    @Test
    void m8_nodesWithNoCausalConnectionToAnInterventionBehaveIdenticallyInTheBranch() {
        Env env = new Env(tmp);
        env.record("twopairs", 11, Map.of());
        String t = env.last;
        List<Event> base = env.events(t);
        long at = env.manager.roundToBoundary(t, base.size() / 4);
        String victimMsg = base.stream().filter(e -> e.seq() > at && e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("a1")).findFirst().orElseThrow().eventId();
        List<List<Intervention>> variants = List.of(
                List.of(Intervention.dropMessage("d", victimMsg)),
                List.of(Intervention.delayMessage("l", victimMsg, 90)),
                List.of(Intervention.duplicateMessage("u", victimMsg)),
                List.of(Intervention.crashNode("c", "a2", base.get((int) at).virtualTime() + 40)));
        for (List<Intervention> ivs : variants) {
            var br = env.manager.branch(t, at, ivs, null, null);
            List<Event> child = env.events(br.timelineId());
            for (String node : List.of("b1", "b2")) {
                List<String> a = base.stream().filter(e -> e.nodeId().equals(node) && e.seq() > at).map(LawsTest::behaviour).toList();
                List<String> b = child.stream().filter(e -> e.nodeId().equals(node) && e.seq() > at).map(LawsTest::behaviour).toList();
                assertEquals(a, b, ivs.get(0).type() + ": node " + node + " is causally unconnected to the intervention and must behave identically");
            }
            // and the pair that was touched really did change
            List<String> a1 = base.stream().filter(e -> e.nodeId().startsWith("a") && e.seq() > at).map(LawsTest::behaviour).toList();
            List<String> a2 = child.stream().filter(e -> e.nodeId().startsWith("a") && e.seq() > at).map(LawsTest::behaviour).toList();
            assertNotEquals(a1, a2, ivs.get(0).type());
        }
    }

    private static String behaviour(Event e) {
        return e.eventId() + "|" + e.kind() + "|" + e.virtualTime() + "|" + Canon.json(e.payload()) + "|" + e.stateDigestAfter();
    }

    // ---------------------------------------------------------------- M9 identity, M10 root
    @Test
    void m9_comparingATimelineWithItselfReportsNoDivergence() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        DivergenceReport d = new TimelineComparator(env.store).compare(env.last, env.last, true);
        assertTrue(d.identical());
        assertNull(d.primary());
        assertTrue(d.text().contains("no divergence"));
    }

    @Test
    void m10_theFirstCausalDivergenceIsTheInterventionsOwnEventAndRootsInTheBranchLieInItsFuture() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<Event> base = env.events(t0);
        long at = boundaryOfFault(env, t0, "fault:partition-pb#1");
        String hb = base.stream().filter(e -> e.seq() > at && e.kind() == EventKind.MESSAGE_SENT && "Heartbeat".equals(e.payload().get("type"))).findFirst().orElseThrow().eventId();
        List<Intervention> ivs1 = List.of(Intervention.shiftHeal("iv1", "partition-pb", -200));
        List<Intervention> ivs2 = List.of(Intervention.dropMessage("iv2", hb));
        List<Intervention> ivs3 = List.of(Intervention.delayMessage("iv3", hb, 120));
        // (lengthening a partition is deliberately not in this list: the extra drops it causes have no causal link from the
        //  intervention, a documented limitation; the comparator lists them as additional roots)
        for (List<Intervention> ivs : List.of(ivs1, ivs2, ivs3)) {
            var br = env.manager.branch(t0, at, ivs, null, null);
            DivergenceReport d = new TimelineComparator(env.store).compare(t0, br.timelineId(), false);
            if (d.identical()) continue; // an intervention may legitimately change nothing
            assertNotNull(d.primary(), ivs.get(0).type());
            assertEquals(ivs.get(0).eventId(), d.primary().eventId(), "M10 for " + ivs.get(0).type() + "\n" + d.text());
            CausalGraph g = CausalGraph.build(env.events(br.timelineId()));
            BitSet future = g.causalFutureOf(List.of(g.pos(ivs.get(0).eventId())));
            for (TimelineComparator.Root r : d.roots())
                if (r.side() == 'B') assertTrue(future.get(r.pos()), "root " + r.eventId() + " of the branch is not in the causal future of " + ivs.get(0).eventId() + " (" + ivs.get(0).type() + ")\n" + d.text());
            assertTrue(d.unexplained().isEmpty(), d.unexplained().toString());
        }
    }

    // ---------------------------------------------------------------- M11 immutability
    @Test
    void m11_filesOfACompletedTimelineNeverChange() throws Exception {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        Map<String, String> before = hashes(tmp.resolve("timelines").resolve(t0));
        long at = boundaryOfFault(env, t0, "fault:partition-pb#1");
        env.manager.branch(t0, at, List.of(Intervention.shiftHeal("x", "partition-pb", -200)), null, null);
        env.manager.branch(t0, at, List.of(), null, null);
        env.replay.verify(t0);
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        s.goToViolation();
        s.world();
        s.why(null);
        new TimelineComparator(env.store).compare(t0, "T0.1", true);
        assertTrue(before.containsKey("index/main.idx"), "the derived index is part of the timeline's files");
        assertEquals("persisted", env.store.index(t0).source(), "sessions use the persisted indexes");
        assertEquals("persisted", env.store.index("T0.1").source());
        assertEquals(before, hashes(tmp.resolve("timelines").resolve(t0)));
        // the catalogue lists the tree
        assertEquals(List.of("T0.1", "T0.2"), env.store.children(t0));
    }

    private Map<String, String> hashes(Path dir) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) m.put(dir.relativize(p).toString().replace('\\', '/'), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }
        return m;
    }

    // ---------------------------------------------------------------- M12 thread independence
    @Test
    void m12_counterfactualAndCampaignReportsDoNotDependOnTheNumberOfThreads() throws IOException {
        List<String> texts = new ArrayList<>();
        for (int threads : new int[]{1, 4}) {
            Path d = Files.createDirectories(tmp.resolve("t" + threads));
            Env env = new Env(d);
            env.record("re", 42, Map.of(), null, Boolean.FALSE);
            String t0 = env.last;
            long at = boundaryOfFault(env, t0, "fault:partition-pb#1");
            List<CounterfactualEngine.Query> qs = new ArrayList<>();
            for (long by : new long[]{-250, -200, -150, -100, -50, 0, 50, 100})
                qs.add(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("h" + (by < 0 ? "m" + (-by) : "p" + by), "partition-pb", by)), null, "shift " + by));
            List<CounterfactualEngine.Report> reps = new CounterfactualEngine(env.manager).runAll(qs, threads);
            StringBuilder sb = new StringBuilder();
            for (var r : reps) sb.append(r.text()).append(r.divergence().text()).append(LogHash.of(env.events(r.timelineId()))).append('\n');
            texts.add(sb.toString());
            List<Campaign.Entry> camp = Campaign.run(env.registry, "re", Map.of("partition", "off"), 1, 40, threads, true, 800L, null);
            texts.set(texts.size() - 1, texts.get(texts.size() - 1) + camp.stream().map(e -> e.seed() + ":" + e.status() + ":" + e.violation() + ":" + e.logHash()).collect(Collectors.joining(",")));
        }
        assertEquals(texts.get(0), texts.get(1));
    }

    // ---------------------------------------------------------------- M13 env identity
    @Test
    void m13_addingEnvEventsInABranchDoesNotRenumberOtherEnvEvents() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = boundaryOfFault(env, t0, "fault:partition-pb#1");
        String hb = env.events(t0).stream().filter(e -> e.seq() > at && e.kind() == EventKind.MESSAGE_SENT && "Heartbeat".equals(e.payload().get("type"))).findFirst().orElseThrow().eventId();
        var br = env.manager.branch(t0, at, List.of(Intervention.dropMessage("x", hb)), null, null);
        Map<String, Event> a = env.events(t0).stream().filter(Event::isEnv).collect(Collectors.toMap(Event::eventId, e -> e));
        List<Event> bEnv = env.events(br.timelineId()).stream().filter(Event::isEnv).toList();
        Set<String> added = bEnv.stream().map(Event::eventId).filter(id -> !a.containsKey(id)).collect(Collectors.toSet());
        assertTrue(added.contains("iv:x"));
        for (Event e : bEnv)
            if (a.containsKey(e.eventId())) assertEquals(a.get(e.eventId()).kind(), e.kind(), "id " + e.eventId() + " keeps its meaning");
        // every env id is an identity key, never a running counter of env events
        for (Event e : bEnv) assertTrue(e.eventId().matches("(drop:.+|dup:.+|fault:[^#]+#\\d+|iv:.+|inv:[^#]+#\\d+)"), e.eventId());
        // the shared env events (the drops of heartbeats that were in flight) are untouched
        for (Event e : bEnv)
            if (a.containsKey(e.eventId()) && !e.eventId().equals("drop:" + hb)) assertEquals(a.get(e.eventId()).contentDigest(false), e.contentDigest(false), e.eventId());
        // the one message the intervention dropped keeps its id; only its content (cause, origin) changed
        Event dropped = bEnv.stream().filter(e -> e.eventId().equals("drop:" + hb)).findFirst().orElseThrow();
        assertEquals("iv:x", dropped.origin());
    }

    // ---------------------------------------------------------------- M14 witnesses
    @Test
    void m14_theWitnessesOfAViolationAreExactlyTheLastWritersOfTheFieldsItsInvariantRead() {
        Env env = new Env(tmp);
        for (String[] sc : new String[][]{{"re", "single-primary"}, {"ka2", "replicas-converge"}, {"ka3", "no-blocked-participant"}, {"re2", "single-primary"}}) {
            env.record(sc[0], 42, Map.of());
            String tl = env.last;
            TimeTravelSession s = new TimeTravelSession(env.store, tl);
            Event v = s.goToViolation();
            assertEquals(sc[1], v.payload().get("invariant"));
            Set<String> expected = new TreeSet<>();
            for (Object f : Canon.list(v.payload().get("fields"))) {
                String q = (String) f;
                String node = q.substring(0, q.indexOf('.'));
                String field = q.substring(q.indexOf('.') + 1);
                List<TimeTravelSession.FieldChange> ch = s.watch(node, field).stream().filter(c -> c.position() < v.seq()).toList();
                expected.add(ch.isEmpty() ? node + "#1" : ch.get(ch.size() - 1).eventId());
            }
            Set<String> witnesses = v.parents().stream().filter(p -> p.kind() == EdgeKind.WITNESS).map(Parent::id).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(expected, witnesses, sc[0]);
            assertEquals(new TreeSet<>(Canon.list(v.payload().get("witnesses")).stream().map(Object::toString).toList()), witnesses);
        }
    }
}
