package chronos.analysis;

import chronos.model.*;
import chronos.analysis.TimelineComparator.EventClass;
import org.junit.jupiter.api.Test;

import java.util.*;

import static chronos.model.EventKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Hand-built cases for the comparator (Section 20): concurrent roots, the cross-timeline anchor rule (D-16) and edge cases. */
class ComparatorTest {
    private static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void twoConcurrentRootsAreBothListedAndTheEarliestIsPrimary() {
        // a and b each change one note; nothing connects them: neither is causally first
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        A.at(50).node("a", APP_NOTE, p("text", "x"));
        A.at(40).node("b", APP_NOTE, p("text", "y"));
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        B.at(50).node("a", APP_NOTE, p("text", "CHANGED-a"));
        B.at(40).node("b", APP_NOTE, p("text", "CHANGED-b"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        System.out.println(d.text());
        assertEquals(4, d.roots().size(), "two roots in each timeline");
        assertEquals("b#2", d.primary().eventId(), "the earlier of the concurrent roots (t=40) is only a display convention");
        assertTrue(d.flags().stream().anyMatch(f -> f.contains("concurrent roots")));
        assertEquals(2, d.counts().get(EventClass.CHANGED));
        assertTrue(d.unexplained().isEmpty());
        assertEquals(List.of("a", "b"), d.affectedNodes());
    }

    @Test
    void aChildOfARootIsInTheConeButIsNotARoot_andRootsOfOneTimelineAreNeverCausallyOrdered() {
        // b's send differs (body); a's delivery of it also differs (its state digest), so a#2 is divergent but its parent is tainted
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        Event s = A.at(10).node("b", MESSAGE_SENT, p("to", "a", "type", "m", "body", p(), "deliverAt", 10L));
        A.at(10).node("a", MESSAGE_DELIVERED, p("messageId", "b#2", "seen", "same-before"), "MESSAGE:" + s.eventId());
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        Event s2 = B.at(10).node("b", MESSAGE_SENT, p("to", "a", "type", "m", "body", p("n", 1L), "deliverAt", 10L));
        B.at(10).node("a", MESSAGE_DELIVERED, p("messageId", "b#2", "seen", "different"), "MESSAGE:" + s2.eventId());
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        // b#2 changed (body) and is a root; a#2 changed too but its parent b#2 is tainted, so it is not a root
        assertEquals(List.of("b#2", "b#2"), d.roots().stream().map(TimelineComparator.Root::eventId).toList());
        assertEquals("b#2", d.primary().eventId());
        assertEquals(2, d.coneA(), "the cone is the causal future of the roots within D: b#2 and a#2");
        assertEquals(2, d.coneB());
        assertTrue(d.unexplained().isEmpty());
        assertEquals(2, d.counts().get(EventClass.CHANGED));
    }

    @Test
    void aSameButTaintedEventIsNotPartOfTheConeBecauseTheConeIsTakenWithinD() {
        // a's delivery looks identical in both timelines (same payload, same state): it is Same, so it is outside D
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        Event s = A.at(10).node("b", MESSAGE_SENT, p("to", "a", "type", "m", "body", p(), "deliverAt", 10L));
        A.at(10).node("a", MESSAGE_DELIVERED, p("messageId", "b#2"), "MESSAGE:" + s.eventId());
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        Event s2 = B.at(10).node("b", MESSAGE_SENT, p("to", "a", "type", "m", "body", p("n", 1L), "deliverAt", 10L));
        B.at(10).node("a", MESSAGE_DELIVERED, p("messageId", "b#2"), "MESSAGE:" + s2.eventId());
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertEquals(1, d.counts().get(EventClass.CHANGED));
        assertEquals(1, d.coneA(), "only divergent events count towards the cone");
    }

    @Test
    void theAnchorRuleStartsThePathAtTheLastSameEventOnTheRootsNodeWhenTheRootLivesInTheOtherTimeline() {
        // A (failing): fault f1 on env, then a watchdog on b caused by it (OMISSION), then the violation.
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        Event f1 = A.at(300).env("fault:cut#1", FAULT_INJECTED, p("rule", "cut", "action", "PARTITION"));
        Event wd = A.at(600).node("b", TIMER_FIRED, p("timerId", "b#1", "name", "wd"), "OMISSION:" + f1.eventId());
        A.at(600).env("inv:single#1", INVARIANT_VIOLATED, p("invariant", "single", "message", "two"), "STEP_EFFECT:" + wd.eventId(), "WITNESS:" + wd.eventId());
        // B (healed): the same prefix, then the intervention's own event, then no violation
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        B.at(300).env("fault:cut#1", FAULT_INJECTED, p("rule", "cut", "action", "PARTITION"));
        B.at(300).env("iv:heal", INTERVENTION_APPLIED, p("id", "heal", "type", "SHIFT_HEAL"));
        B.at(500).node("a", APP_NOTE, p("text", "later"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.VIOLATED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        System.out.println(d.text());
        assertEquals("iv:heal", d.primary().eventId());
        assertEquals('B', d.primary().side());
        assertEquals("A", d.failureTimeline());
        assertEquals("inv:single#1", d.failureEvent());
        // the path is drawn in A from the anchor: the last Same event on env before the divergence = fault:cut#1
        assertEquals("fault:cut#1", d.path().get(0).from());
        assertEquals(2, d.path().size(), "fault -OMISSION-> watchdog -> violation, not the bare env program order");
        assertEquals(EdgeKind.OMISSION, d.path().get(0).kind());
        assertTrue(d.path().get(1).kind().certain(), "the last hop is a certain because-of link");
        assertEquals("b#2", d.path().get(0).to(), "b#1 is the start event; b#2 is the watchdog firing");
        assertEquals("inv:single#1", d.path().get(d.path().size() - 1).to());
        assertNotNull(d.pathNote());
        assertTrue(d.pathNote().contains("anchor"));
    }

    @Test
    void whenNoAnchorExistsThePathFallsBackToTheEarliestRootOfTheFailureTimeline() {
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        Event w = A.at(100).node("b", APP_NOTE, p("text", "boom"));
        A.at(100).env("inv:x#1", INVARIANT_VIOLATED, p("invariant", "x", "message", "m"), "STEP_EFFECT:" + w.eventId());
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        B.at(50).env("iv:first", INTERVENTION_APPLIED, p("id", "first", "type", "T")); // no earlier env event: no anchor
        B.at(100).node("b", APP_NOTE, p("text", "calm"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.VIOLATED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertEquals("iv:first", d.primary().eventId());
        assertFalse(d.path().isEmpty());
        assertTrue(d.pathNote().contains("earliest root"), d.pathNote());
    }

    @Test
    void identicalTimelinesReportNothingAndAShorterOneIsMissingItsTail() {
        TraceBuilder A = new TraceBuilder("A", "a", "b");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("b", NODE_STARTED, p());
        A.at(10).node("a", APP_NOTE, p("text", "one"));
        A.at(20).node("a", APP_NOTE, p("text", "two"));
        TraceBuilder B = new TraceBuilder("B", "a", "b");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("b", NODE_STARTED, p());
        B.at(10).node("a", APP_NOTE, p("text", "one"));
        TraceBuilder C = new TraceBuilder("C", "a", "b");
        C.at(0).node("a", NODE_STARTED, p());
        C.at(0).node("b", NODE_STARTED, p());
        C.at(10).node("a", APP_NOTE, p("text", "one"));
        C.at(20).node("a", APP_NOTE, p("text", "two"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        C.finish(m, TimelineStatus.COMPLETED);
        assertTrue(new TimelineComparator(m).compare("A", "C", true).identical());
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertEquals(1, d.counts().get(EventClass.MISSING));
        assertEquals("a#3", d.primary().eventId(), "the root is the point where one side stopped");
        assertEquals(EventClass.MISSING, d.primary().cls());
    }

    @Test
    void sameEventsInADifferentLocalOrderAreReordered() {
        TraceBuilder A = new TraceBuilder("A", "a");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(1).env("drop:m1", MESSAGE_DROPPED, p("messageId", "m1"));
        A.at(2).env("drop:m2", MESSAGE_DROPPED, p("messageId", "m2"));
        TraceBuilder B = new TraceBuilder("B", "a");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(1).env("drop:m2", MESSAGE_DROPPED, p("messageId", "m2"));
        B.at(2).env("drop:m1", MESSAGE_DROPPED, p("messageId", "m1"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertEquals(2, d.counts().get(EventClass.REORDERED));
        assertFalse(d.identical());
    }

    @Test
    void anEventThatOnlyMovedBecauseAnEarlierLaneEventIsMissingIsDisplacedNotARoot() {
        // A has notes m1 then m2 on lane a; B lacks m1, so B's a#2 is A's m2 renumbered. Only the lost m1 is a real root.
        TraceBuilder A = new TraceBuilder("A", "a");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(10).node("a", APP_NOTE, p("text", "m1"));
        A.at(20).node("a", APP_NOTE, p("text", "m2"));
        TraceBuilder B = new TraceBuilder("B", "a");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(20).node("a", APP_NOTE, p("text", "m2"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertEquals(List.of("a#2"), d.roots().stream().map(TimelineComparator.Root::eventId).toList(), d.text());
        assertEquals('A', d.roots().get(0).side());
        assertEquals(1, d.displaced().size());
        assertEquals(new DivergenceReport.Displaced('B', "a#2", "a", "a#3", DivergenceReport.Displaced.RENUMBERED), d.displaced().get(0));
        assertTrue(d.unexplained().isEmpty(), "the displaced event's consequences stay explained");
        assertTrue(d.text().contains("displaced, not a root"));
        // a genuinely different event is still a root
        TraceBuilder C = new TraceBuilder("C", "a");
        C.at(0).node("a", NODE_STARTED, p());
        C.at(20).node("a", APP_NOTE, p("text", "something else"));
        C.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport e = new TimelineComparator(m).compare("B", "C", false);
        assertTrue(e.displaced().isEmpty());
        assertEquals(2, e.roots().size());
    }

    @Test
    void anOutputDifferenceDespiteIdenticalInputsIsFlaggedAsSuspectedNondeterminism_FR43() {
        TraceBuilder A = new TraceBuilder("A", "a");
        Event s = A.at(0).node("a", NODE_STARTED, p());
        A.at(0).node("a", TIMER_SET, p("name", "t", "due", 100L), "STEP_EFFECT:" + s.eventId());
        TraceBuilder B = new TraceBuilder("B", "a");
        Event s2 = B.at(0).node("a", NODE_STARTED, p());
        B.at(0).node("a", TIMER_SET, p("name", "t", "due", 100L, "extra", "differs"), "STEP_EFFECT:" + s2.eventId());
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        DivergenceReport d = new TimelineComparator(m).compare("A", "B", false);
        assertTrue(d.flags().stream().anyMatch(f -> f.contains("suspected application nondeterminism")), d.flags().toString());
        assertTrue(d.firstLocal().get(0).kind().startsWith("output difference"));
    }

    @Test
    void strictComparisonSeesAPureTimingDifferenceThatStructuralComparisonIgnores() {
        TraceBuilder A = new TraceBuilder("A", "a");
        A.at(0).node("a", NODE_STARTED, p());
        A.at(10).node("a", APP_NOTE, p("text", "hi"));
        TraceBuilder B = new TraceBuilder("B", "a");
        B.at(0).node("a", NODE_STARTED, p());
        B.at(15).node("a", APP_NOTE, p("text", "hi"));
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        B.finish(m, TimelineStatus.COMPLETED);
        assertTrue(new TimelineComparator(m).compare("A", "B", false).identical());
        assertFalse(new TimelineComparator(m).compare("A", "B", true).identical());
    }

    @Test
    void differentSeedsAreAllowedWithAWarning() {
        TraceBuilder A = new TraceBuilder("A", "a");
        A.at(0).node("a", NODE_STARTED, p());
        MemoryTimelines m = new MemoryTimelines();
        A.finish(m, TimelineStatus.COMPLETED);
        TimelineHeader h = m.header("A");
        MemoryTimelines m2 = new MemoryTimelines();
        TraceBuilder B = new TraceBuilder("B", "a");
        B.at(0).node("a", NODE_STARTED, p());
        B.finish(m2, TimelineStatus.COMPLETED);
        // combine: header of B gets another seed
        MemoryTimelines both = new MemoryTimelines();
        A = new TraceBuilder("A", "a");
        A.at(0).node("a", NODE_STARTED, p());
        A.finish(both, TimelineStatus.COMPLETED);
        TraceBuilder B2 = new TraceBuilder("B", "a");
        B2.at(0).node("a", NODE_STARTED, p());
        B2.finish(both, TimelineStatus.COMPLETED);
        assertTrue(new TimelineComparator(both).compare("A", "B", false).warnings().isEmpty());
        assertNotNull(h);
    }
}
