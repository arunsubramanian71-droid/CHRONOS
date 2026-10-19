package chronos.runtime;

import chronos.model.*;
import chronos.sdk.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    private static String row(Event e) {
        String parents = e.parents().stream().map(Parent::id).distinct().collect(Collectors.joining(", "));
        return e.seq() + " " + e.eventId() + " " + e.nodeId() + " " + e.kind() + " " + e.virtualTime() + " " + e.lamport() + " "
                + e.vector() + " " + (parents.isEmpty() ? "none" : parents);
    }

    @Test
    void pingPongRecordingIsExactlyTheTableOfSection10() {
        MemoryLog log = Fakes.run(Fakes.pingPong(), 1, FaultPlan.empty(), 0);
        List<String> rows = log.events().stream().map(EngineTest::row).toList();
        assertEquals(List.of(
                "0 A#1 A NODE_STARTED 0 1 [1, 0, 0] none",
                "1 B#1 B NODE_STARTED 0 1 [0, 1, 0] none",
                "2 A#2 A MESSAGE_SENT 0 2 [2, 0, 0] A#1",
                "3 B#2 B MESSAGE_DELIVERED 10 3 [2, 2, 0] B#1, A#2",
                "4 B#3 B MESSAGE_SENT 10 4 [2, 3, 0] B#2",
                "5 A#3 A MESSAGE_DELIVERED 20 5 [3, 3, 0] A#2, B#3"), rows);
        assertEquals(TimelineStatus.COMPLETED, log.summary().status());
    }

    @Test
    void stepGroupsAreAtomicAndEndOnStepBoundaries() {
        MemoryLog log = Fakes.run(Fakes.pingPong(), 1, FaultPlan.empty(), 0);
        // boot: A#1 | B#1 | A#2 ; then the delivery step (B#2,B#3) ; then the delivery step (A#3)
        assertEquals(List.of(0L, 1L, 2L, 4L, 5L), log.groupEnds());
    }

    @Test
    void sameScenarioSeedAndPlanGiveByteIdenticalLogsAcrossManySeeds() {
        for (long seed = 1; seed <= 25; seed++) {
            Scenario s = Fakes.chatter(400, FaultPlan.empty());
            String h1 = LogHash.of(Fakes.run(s, seed, FaultPlan.empty(), 0).events());
            String h2 = LogHash.of(Fakes.run(s, seed, FaultPlan.empty(), 0).events());
            assertEquals(h1, h2, "seed " + seed);
        }
        // and the seed really matters
        Scenario s = Fakes.chatter(400, FaultPlan.empty());
        assertNotEquals(LogHash.of(Fakes.run(s, 1, FaultPlan.empty(), 0).events()), LogHash.of(Fakes.run(s, 2, FaultPlan.empty(), 0).events()));
    }

    @Test
    void logOrderIsALinearExtensionOfCausality() {
        MemoryLog log = Fakes.run(Fakes.chatter(500, FaultPlan.empty()), 3, FaultPlan.empty(), 0);
        Map<String, Long> seqOf = new HashMap<>();
        for (Event e : log.events()) seqOf.put(e.eventId(), e.seq());
        for (Event e : log.events())
            for (Parent p : e.parents()) assertTrue(seqOf.get(p.id()) < e.seq(), e.eventId() + " has a parent " + p.id() + " that is not earlier");
        // every vector reflects its parents
        for (Event e : log.events())
            for (Parent p : e.parents()) {
                Event pe = log.events().get(seqOf.get(p.id()).intValue());
                assertTrue(VectorClock.compare(pe.vector(), e.vector()) == VectorClock.Order.BEFORE, p.id() + " -> " + e.eventId());
                assertTrue(pe.lamport() < e.lamport());
            }
    }

    @Test
    void tieBreakingUsesTheStableKeyNotInsertionOrder() {
        List<AgendaItem> items = new ArrayList<>();
        Map<String, Object> d = Map.of();
        items.add(new AgendaItem(10, 1, AgendaItem.Kind.TIMER, "b#9", d));
        items.add(new AgendaItem(10, 1, AgendaItem.Kind.DELIVERY, "a#5", d));
        items.add(new AgendaItem(10, 0, AgendaItem.Kind.TIMER, "a#1", d));
        items.add(new AgendaItem(5, 2, AgendaItem.Kind.TIMER, "c#1", d));
        items.add(new AgendaItem(10, 3, AgendaItem.Kind.FAULT, "r#inject", d));
        items.add(new AgendaItem(10, 1, AgendaItem.Kind.DELIVERY, "a#4", d));
        for (int rot = 0; rot < items.size(); rot++) {
            Agenda ag = new Agenda();
            List<AgendaItem> copy = new ArrayList<>(items);
            Collections.rotate(copy, rot);
            copy.forEach(ag::add);
            List<String> order = new ArrayList<>();
            while (!ag.isEmpty()) order.add(ag.poll().identity());
            assertEquals(List.of("c#1", "a#1", "a#4", "a#5", "b#9", "r#inject"), order, "rotation " + rot);
        }
    }

    @Test
    void partitionDropsCrossGroupMessagesAndHealRestoresDelivery() {
        FaultPlan plan = FaultPlan.of(FaultRule.partition("cut", 100, 200L, List.of(List.of("a"), List.of("b"))));
        MemoryLog log = Fakes.run(Fakes.chatter(600, plan), 5, plan, 0);
        List<Event> ev = log.events();
        Event injected = ev.stream().filter(e -> e.kind() == EventKind.FAULT_INJECTED).findFirst().orElseThrow();
        Event cleared = ev.stream().filter(e -> e.kind() == EventKind.FAULT_CLEARED).findFirst().orElseThrow();
        assertEquals("fault:cut#1", injected.eventId());
        assertEquals("fault:cut#2", cleared.eventId());
        assertEquals(100, injected.virtualTime());
        assertEquals(300, cleared.virtualTime());
        List<Event> drops = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DROPPED).toList();
        assertFalse(drops.isEmpty());
        for (Event d : drops) {
            assertTrue(d.eventId().startsWith("drop:"));
            assertTrue(d.virtualTime() >= 100 && d.virtualTime() <= 300, "drop at " + d.virtualTime());
            assertTrue(d.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:cut#1")));
            assertTrue(d.parents().stream().anyMatch(p -> p.kind() == EdgeKind.MESSAGE));
        }
        // after the heal, deliveries resume
        assertTrue(ev.stream().anyMatch(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.virtualTime() > 300));
        // no delivery while the partition is in force (everything sent before 100 may still arrive up to ~110, so look inside)
        assertTrue(ev.stream().noneMatch(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.virtualTime() > 105 && e.virtualTime() < 300));
        assertEquals(Map.of("cut", 1L), log.summary().faultCoverage());
    }

    @Test
    void identicalPlanAndSeedGiveIdenticalFaultyRuns() {
        FaultPlan plan = FaultPlan.of(FaultRule.partition("cut", 100, 200L, List.of(List.of("a"), List.of("b"))),
                FaultRule.probabilistic("noise", FaultRule.FaultAction.DROP, "a", "b", "m", 300, null));
        for (long seed = 1; seed <= 10; seed++) {
            String h1 = LogHash.of(Fakes.run(Fakes.chatter(800, plan), seed, plan, 0).events());
            String h2 = LogHash.of(Fakes.run(Fakes.chatter(800, plan), seed, plan, 0).events());
            assertEquals(h1, h2);
        }
    }

    @Test
    void probabilisticRuleHitsTheSameMessageRegardlessOfOtherTraffic() {
        // D-06/D-13: the fate of message a#k depends only on (seed, message id, rule id)
        FaultPlan plan = FaultPlan.of(FaultRule.probabilistic("noise", FaultRule.FaultAction.DROP, "a", "b", "m", 500, null));
        Set<String> dropped1 = droppedIds(Fakes.run(Fakes.chatter(1000, plan), 9, plan, 0));
        Set<String> dropped2 = droppedIds(Fakes.run(Fakes.chatter(1000, plan), 9, plan, 0));
        assertFalse(dropped1.isEmpty());
        assertEquals(dropped1, dropped2);
        long seed = 9;
        for (String id : dropped1) assertTrue(IdentityHash.bounded(seed, 1000, "prob", "noise", id.substring(5)) < 500);
    }

    private static Set<String> droppedIds(MemoryLog log) {
        return log.events().stream().filter(e -> e.kind() == EventKind.MESSAGE_DROPPED).map(Event::eventId).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void crashWipesVolatileKeepsDurableAndRestartRecovers() {
        FaultPlan plan = FaultPlan.of(FaultRule.crash("kill-a", "a", 90), FaultRule.restart("revive-a", "a", 200));
        MemoryLog log = Fakes.run(Fakes.chatter(400, plan), 4, plan, 0);
        List<Event> ev = log.events();
        Event crashed = ev.stream().filter(e -> e.kind() == EventKind.NODE_CRASHED).findFirst().orElseThrow();
        Event restarted = ev.stream().filter(e -> e.kind() == EventKind.NODE_RESTARTED).findFirst().orElseThrow();
        assertEquals("a", crashed.nodeId());
        assertTrue(crashed.parents().stream().anyMatch(p -> p.kind() == EdgeKind.FAULT && p.id().equals("fault:kill-a#1")));
        @SuppressWarnings("unchecked") Map<String, Object> afterCrash = (Map<String, Object>) crashed.stateAfter().get("volatile");
        assertTrue(afterCrash.isEmpty(), "volatile zone is lost on a crash");
        @SuppressWarnings("unchecked") Map<String, Object> durable = (Map<String, Object>) crashed.stateAfter().get("durable");
        assertTrue(((Number) durable.get("sent")).longValue() >= 1, "durable zone survives a crash: " + durable);
        // while crashed, deliveries to a are dropped with reason target down and a's timers are discarded
        assertTrue(ev.stream().anyMatch(e -> e.kind() == EventKind.MESSAGE_DROPPED && e.payload().get("reason").equals("target down")));
        assertTrue(ev.stream().noneMatch(e -> e.nodeId().equals("a") && e.virtualTime() > crashed.virtualTime() && e.virtualTime() < restarted.virtualTime()
                && e.seq() > crashed.seq() && e.kind() != EventKind.NODE_RESTARTED));
        // after the restart the node counts on from its durable counter
        Event firstSendAfter = ev.stream().filter(e -> e.nodeId().equals("a") && e.kind() == EventKind.MESSAGE_SENT && e.seq() > restarted.seq()).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) firstSendAfter.payload().get("body");
        assertEquals(((Number) durable.get("sent")).longValue() + 1, ((Number) body.get("n")).longValue());
    }

    @Test
    void invalidOperationsAreRecordedAsNodeFailedAndStopTheRun() {
        Scenario s = new Scenario() {
            public String name() { return "bad-send"; }
            public List<NodeSpec> nodes() {
                return List.of(new NodeSpec("a", () -> new Node() {
                    public void onStart(NodeContext c) { c.send("nobody", "x", Map.of()); }
                }));
            }
        };
        MemoryLog log = Fakes.run(s, 1, FaultPlan.empty(), 0);
        Event failed = log.events().stream().filter(e -> e.kind() == EventKind.NODE_FAILED).findFirst().orElseThrow();
        assertTrue(failed.payload().get("message").toString().contains("unknown node"));
        assertEquals(TimelineStatus.ABORTED, log.summary().status());
    }

    @Test
    void nonCanonicalMessageBodyIsRefused() {
        Scenario s = new Scenario() {
            public String name() { return "bad-body"; }
            public List<NodeSpec> nodes() {
                return List.of(new NodeSpec("a", () -> new Node() {
                    public void onStart(NodeContext c) { c.send("a", "x", Map.of("f", 1.5)); }
                }));
            }
        };
        MemoryLog log = Fakes.run(s, 1, FaultPlan.empty(), 0);
        assertTrue(log.events().stream().anyMatch(e -> e.kind() == EventKind.NODE_FAILED));
    }

    @Test
    void unknownNodeInFaultPlanIsRejectedUpFront() {
        FaultPlan plan = FaultPlan.of(FaultRule.crash("k", "ghost", 5));
        assertThrows(IllegalArgumentException.class, () -> Fakes.run(Fakes.chatter(100, plan), 1, plan, 0));
    }

    @Test
    void checkpointRestoreThenRunEqualsTheUninterruptedRun_lawM5() {
        FaultPlan plan = FaultPlan.of(FaultRule.partition("cut", 120, 150L, List.of(List.of("a"), List.of("b"))),
                FaultRule.crash("kill-b", "b", 400), FaultRule.restart("revive-b", "b", 520),
                FaultRule.nth("nth", FaultRule.FaultAction.DELAY, "a", "b", "m", 3, 77L));
        Scenario s = Fakes.chatter(900, plan);
        MemoryLog full = Fakes.run(s, 11, plan, 7);
        assertTrue(full.checkpoints().size() > 5, "periodic checkpoints were taken");
        for (WorldImage img : full.checkpoints()) {
            MemoryLog rest = new MemoryLog();
            Recorder rec = new Recorder("T0", img.seq() + 1, List.of(rest));
            Engine e = Engine.restore(s, img, new RecordDecisionSource(s, 11), rec, RunOptions.of(s).withCheckpointInterval(0));
            e.runToEnd();
            List<String> expected = full.events().stream().filter(x -> x.seq() > img.seq()).map(Event::toLine).toList();
            List<String> actual = rest.events().stream().map(Event::toLine).toList();
            assertEquals(expected, actual, "restoring the checkpoint at seq " + img.seq());
        }
    }

    @Test
    void checkpointImagesSurviveAJsonRoundTrip() {
        Scenario s = Fakes.chatter(300, FaultPlan.empty());
        MemoryLog full = Fakes.run(s, 2, FaultPlan.empty(), 5);
        for (WorldImage img : full.checkpoints()) {
            WorldImage back = WorldImage.fromMap(Canon.parseMap(Canon.json(img.toMap())));
            assertEquals(img.digest(), back.digest());
        }
    }

    @Test
    void invariantViolationRecordsWitnessEdgesToTheLastWriters() {
        Scenario s = new Scenario() {
            public String name() { return "inv"; }
            public List<NodeSpec> nodes() {
                return List.of(new NodeSpec("a", () -> new Node() {
                    public void onStart(NodeContext c) { c.state().put("x", 0L); c.setTimer("t", 50); }
                    public void onTimer(NodeContext c, TimerFiring t) { c.state().put("x", 1L); }
                }), new NodeSpec("b", () -> new Node() {
                    public void onStart(NodeContext c) { c.state().put("y", 1L); }
                }));
            }
            public List<Invariant> invariants() {
                return List.of(new Invariant() {
                    public String name() { return "x-below-y"; }
                    public Optional<String> check(WorldView v) {
                        long x = ((Number) v.read("a", "volatile.x")).longValue();
                        long y = ((Number) v.read("b", "volatile.y")).longValue();
                        return x < y ? Optional.empty() : Optional.of("x >= y");
                    }
                });
            }
        };
        MemoryLog log = Fakes.run(s, 1, FaultPlan.empty(), 0);
        Event viol = log.events().stream().filter(e -> e.kind() == EventKind.INVARIANT_VIOLATED).findFirst().orElseThrow();
        assertEquals("inv:x-below-y#1", viol.eventId());
        assertEquals("env", viol.nodeId());
        Set<String> witnesses = viol.parents().stream().filter(p -> p.kind() == EdgeKind.WITNESS).map(Parent::id).collect(Collectors.toSet());
        // last writer of a.x is the timer firing (a#4 = TIMER_FIRED); last writer of b.y is b's start event
        Event timerFired = log.events().stream().filter(e -> e.kind() == EventKind.TIMER_FIRED).findFirst().orElseThrow();
        assertEquals(Set.of(timerFired.eventId(), "b#1"), witnesses);
        assertTrue(viol.parents().stream().anyMatch(p -> p.kind() == EdgeKind.STEP_EFFECT && p.id().equals(timerFired.eventId())));
        assertEquals(TimelineStatus.VIOLATED, log.summary().status());
        // the violation's vector merged every witness before incrementing env's slot (law M3 for WITNESS edges)
        for (Parent p : viol.parents()) {
            Event pe = log.events().stream().filter(e -> e.eventId().equals(p.id())).findFirst().orElseThrow();
            assertEquals(VectorClock.Order.BEFORE, VectorClock.compare(pe.vector(), viol.vector()));
        }
    }

    @Test
    void everyCopyOfADuplicatedMessageHasItsOwnIdentity_regressionFromTheChaosSoak() {
        // two DUPLICATE rules hit the same message: the soak found that both tried to create the env event dup:<id> (D-24 collision)
        FaultPlan plan = FaultPlan.of(FaultRule.onMessage("dup-a", FaultRule.FaultAction.DUPLICATE, "A#2", null),
                FaultRule.onMessage("dup-b", FaultRule.FaultAction.DUPLICATE, "A#2", null),
                FaultRule.nth("dup-c", FaultRule.FaultAction.DUPLICATE, "B", "A", "pong", 1, null),
                FaultRule.partition("cut", 15, 100L, List.of(List.of("A"), List.of("B"))));
        MemoryLog log = Fakes.run(Fakes.pingPong(), 1, plan, 0);
        List<Event> ev = log.events();
        Set<String> ids = new HashSet<>();
        for (Event e : ev) assertTrue(ids.add(e.eventId()), "duplicate event id " + e.eventId());
        assertEquals(List.of("dup:A#2", "dup:A#2~2"), ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DUPLICATED && e.payload().get("messageId").equals("A#2")).map(Event::eventId).toList());
        // the original and both copies are delivered to B at the same instant, in the stable order of their identities
        long deliveries = ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("B")).count();
        assertEquals(3, deliveries);
        // the copies of pong sent by B at t=10 are dropped by the partition (15..115), each with its own drop identity
        assertTrue(ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DROPPED).map(Event::eventId).distinct().count() == ev.stream().filter(e -> e.kind() == EventKind.MESSAGE_DROPPED).count());
        // the whole log is a valid causal graph (this is what crashed in the soak)
        assertEquals(ev.size(), ev.stream().map(Event::eventId).distinct().count());
        // and it replays through the same engine from the recorded decisions: checkpoints survive too
        assertEquals(TimelineStatus.COMPLETED, log.summary().status());
    }
}
