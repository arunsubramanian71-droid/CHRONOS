package chronos.analysis;

import chronos.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static chronos.model.EventKind.*;
import static org.junit.jupiter.api.Assertions.*;

class CausalGraphTest {
    private static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** The ping-pong graph of Section 13. */
    private CausalGraph pingPong() {
        TraceBuilder t = new TraceBuilder("T", "A", "B");
        Event a1 = t.at(0).node("A", NODE_STARTED, p());
        Event b1 = t.at(0).node("B", NODE_STARTED, p());
        Event a2 = t.at(0).node("A", MESSAGE_SENT, p("to", "B", "type", "ping", "body", p(), "deliverAt", 10L), "STEP_EFFECT:A#1");
        Event b2 = t.at(10).node("B", MESSAGE_DELIVERED, p("messageId", "A#2"), "MESSAGE:A#2");
        Event b3 = t.at(10).node("B", MESSAGE_SENT, p("to", "A", "type", "pong", "body", p(), "deliverAt", 20L), "STEP_EFFECT:B#2");
        t.at(20).node("A", MESSAGE_DELIVERED, p("messageId", "B#3"), "MESSAGE:B#3");
        MemoryTimelines m = t.finish(new MemoryTimelines(), TimelineStatus.COMPLETED);
        return CausalGraph.build(m.events("T"));
    }

    @Test
    void consistentCutsAreClosedUnderCausation() {
        CausalGraph g = pingPong();
        int a1 = g.pos("A#1"), a2 = g.pos("A#2"), b1 = g.pos("B#1"), b2 = g.pos("B#2"), b3 = g.pos("B#3"), a3 = g.pos("A#3");
        // the minimal cut of B's delivery is its causal past plus itself: A's start and send, B's start, the delivery
        BitSet cut = g.minimalCut(List.of(b2));
        assertEquals(List.of(a1, b1, a2, b2), cut.stream().boxed().toList());
        assertTrue(g.isConsistentCut(cut));
        assertEquals(Map.of("A", a2, "B", b2), g.frontier(cut));
        // a frontier that includes B's delivery but stops A before the send is NOT a consistent cut: the message "from the future"
        BitSet bad = g.cutFromFrontier(Map.of("A", a1, "B", b2));
        assertFalse(g.isConsistentCut(bad));
        List<int[]> why = g.inconsistencies(bad, 5);
        assertTrue(why.stream().anyMatch(x -> x[0] == b2 && x[1] == a2), "the delivery is in the cut but its send is not");
        // every prefix of the whole log is consistent, and so is the minimal cut of any set of events
        BitSet all = new BitSet();
        all.set(0, g.size());
        assertTrue(g.isConsistentCut(all));
        for (int i = 0; i < g.size(); i++) assertTrue(g.isConsistentCut(g.minimalCut(List.of(i, g.size() - 1 - i))));
        assertEquals(g.causalPast(a3).length + 1, g.minimalCut(List.of(a3)).cardinality());
        assertTrue(g.minimalCut(List.of(b3)).get(a2));
    }

    @Test
    void edgesAndQueriesOnThePingPongGraph() {
        CausalGraph g = pingPong();
        assertEquals(6, g.size());
        int a1 = g.pos("A#1"), a2 = g.pos("A#2"), b1 = g.pos("B#1"), b2 = g.pos("B#2"), a3 = g.pos("A#3");
        assertTrue(g.happensBefore(a2, b2));
        assertTrue(g.happensBefore(a1, a3));
        assertTrue(g.concurrent(b1, a2), "B's start and A's send are unrelated");
        assertFalse(g.happensBefore(b1, a1));
        assertArrayEquals(new int[]{a1, b1, a2, b2, g.pos("B#3")}, g.causalPast(a3));
        assertArrayEquals(new int[]{b2, g.pos("B#3"), a3}, g.causalFuture(a2));
        // path a2 -> a3 under the Section 20 cost rule: the direct PROGRAM_ORDER edge costs 2, the route through the message
        // costs 1 (MESSAGE) + 2 (B#2 -> B#3 PROGRAM_ORDER) or 1 (STEP_EFFECT) ... = 3 certain hops, so the cheaper direct edge wins
        List<Integer> path = g.path(a2, a3).orElseThrow();
        assertEquals(List.of(a2, a3), path);
        assertEquals(2, g.pathCost(path));
        // but when only certain edges are allowed (the default "why" view) the route goes through the message
        List<Integer> certain = g.path(a2, a3, k -> k != EdgeKind.PROGRAM_ORDER).orElseThrow();
        assertEquals(List.of(a2, b2, g.pos("B#3"), a3), certain);
        assertEquals(3, g.pathCost(certain));
        assertTrue(g.path(a3, a2).isEmpty());
        assertEquals(EdgeKind.MESSAGE, g.edgeBetween(a2, b2));
        assertTrue(g.selfCheck(100).isEmpty());
    }

    @Test
    void pathsPreferCertainEdgesOverProgramOrder() {
        // x -PROGRAM_ORDER-> y -PROGRAM_ORDER-> z  versus  x -MESSAGE-> m -STEP_EFFECT-> z? build a diamond
        TraceBuilder t = new TraceBuilder("T", "a", "b");
        t.at(0).node("a", NODE_STARTED, p());
        t.at(0).node("b", NODE_STARTED, p());
        t.at(1).node("a", APP_NOTE, p("text", "mid"));
        t.at(2).node("a", MESSAGE_SENT, p("to", "b", "type", "m", "body", p(), "deliverAt", 3L));
        t.at(3).node("b", MESSAGE_DELIVERED, p("messageId", "a#3"), "MESSAGE:a#3");
        CausalGraph g = CausalGraph.build(t.finish(new MemoryTimelines(), TimelineStatus.COMPLETED).events("T"));
        List<Integer> path = g.path(g.pos("a#1"), g.pos("b#2")).orElseThrow();
        assertEquals(List.of(g.pos("a#1"), g.pos("a#2"), g.pos("a#3"), g.pos("b#2")), path);
        // a#1 -> a#2 -> a#3 are PROGRAM_ORDER hops (cost 2 each), a#3 -> b#2 is a MESSAGE (cost 1): total 5
        assertEquals(5, g.pathCost(path));
    }

    @Test
    void atEqualCostAPathWithFewerProgramOrderHopsWins() {
        // env events are chained by PROGRAM_ORDER; the violation is also caused (STEP_EFFECT, certain) by a node event that an
        // OMISSION edge ties back to the fault. Direct PROGRAM_ORDER f -> v costs 2, OMISSION + STEP_EFFECT costs 1 + 1 = 2.
        TraceBuilder t = new TraceBuilder("T", "a");
        t.at(0).node("a", NODE_STARTED, p());
        Event f = t.at(5).env("fault:f#1", FAULT_INJECTED, p("rule", "f", "action", "PARTITION"));
        Event w = t.at(9).node("a", TIMER_FIRED, p("timerId", "a#1", "name", "wd"), "OMISSION:" + f.eventId());
        t.at(9).env("inv:i#1", INVARIANT_VIOLATED, p("invariant", "i", "message", "m"), "STEP_EFFECT:" + w.eventId());
        CausalGraph g = CausalGraph.build(t.finish(new MemoryTimelines(), TimelineStatus.COMPLETED).events("T"));
        List<Integer> path = g.path(g.pos("fault:f#1"), g.pos("inv:i#1")).orElseThrow();
        assertEquals(List.of(g.pos("fault:f#1"), g.pos("a#2"), g.pos("inv:i#1")), path, "the because-of route, not the bare env program order");
        assertEquals(2, g.pathCost(path));
    }

    @Test
    void parentsThatDoNotPrecedeTheEventAreRefused() {
        Event bad = new Event("x#1", 0, "T", "x", NODE_STARTED, 0, 1, List.of(1, 0), List.of(new Parent("ghost", EdgeKind.MESSAGE)), Map.of(), null, null, null);
        assertThrows(CorruptLogException.class, () -> CausalGraph.build(List.of(bad)));
        Event ok = new Event("x#1", 0, "T", "x", NODE_STARTED, 0, 1, List.of(1, 0), List.of(), Map.of(), null, null, null);
        Event dup = new Event("x#1", 1, "T", "x", NODE_STARTED, 0, 2, List.of(2, 0), List.of(), Map.of(), null, null, null);
        assertThrows(CorruptLogException.class, () -> CausalGraph.build(List.of(ok, dup)));
        Event gap = new Event("x#2", 5, "T", "x", NODE_STARTED, 0, 2, List.of(2, 0), List.of(), Map.of(), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> CausalGraph.build(List.of(ok, gap)));
    }

    @Test
    void theSelfCheckCatchesCorruptedClocks() {
        TraceBuilder t = new TraceBuilder("T", "a", "b");
        t.at(0).node("a", NODE_STARTED, p());
        t.at(0).node("b", NODE_STARTED, p());
        Event s = t.at(1).node("a", MESSAGE_SENT, p("to", "b", "type", "m", "body", p(), "deliverAt", 2L));
        t.at(2).node("b", MESSAGE_DELIVERED, p("messageId", "a#2"), "MESSAGE:a#2");
        List<Event> events = new ArrayList<>(t.finish(new MemoryTimelines(), TimelineStatus.COMPLETED).events("T"));
        // damage the delivery's vector: it forgets what it learned from the message
        Event d = events.get(3);
        events.set(3, new Event(d.eventId(), d.seq(), d.timelineId(), d.nodeId(), d.kind(), d.virtualTime(), d.lamport(), List.of(0, 2, 0), d.parents(), d.payload(), d.stateDigestAfter(), d.stateAfter(), d.origin()));
        List<String> problems = CausalGraph.build(events).selfCheck(100);
        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).contains("vector clocks"));
    }

    @Test
    void exportsKeepAllVectorSlots() {
        CausalGraph g = pingPong();
        String table = Exports.eventTable(g.events(), List.of("A", "B"));
        assertTrue(table.contains("vector[A,B,env]"));
        assertTrue(table.contains("[3,3,0]"), "env's slot is shown even though env never acts");
        String dot = Exports.dot(g, List.of("A", "B"));
        assertTrue(dot.contains("\"A#2\" -> \"B#2\" [label=\"MESSAGE\"]"));
        assertTrue(dot.contains("style=dashed"));
        String json = Exports.json(g, List.of("A", "B"));
        assertEquals(6, Canon.list(Canon.parseMap(json).get("events")).size());
        assertTrue(Exports.spaceTime(g, List.of("A", "B")).contains("recv ping <- A"));
    }
}
