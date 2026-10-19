package chronos.analysis;

import chronos.model.*;
import chronos.store.TimelineIndex;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Time-Travel Session (C10, Section 16): a debugging session in which a cursor moves freely over a recorded timeline and
 * every question is answered from the log. No application code runs; going backward is just a cursor move. Positions and
 * step boundaries: node states shown at a position inside a step reflect the last completed step.
 *
 * Phase 10a: goto, step, back, state, world, inflight, timers, why.
 * Phase 10b: next-on, prev-on, find, effects, concurrent-with, watch, diff.
 */
public final class TimeTravelSession {
    private final LogReader log;
    private final String timelineId;
    private final TimelineHeader header;
    private final List<Event> events;
    private final CausalGraph graph;
    private final TimelineIndex index;
    private final List<Long> groupEnds;
    private int cursor;

    public TimeTravelSession(LogReader log, String timelineId) {
        this.log = log;
        this.timelineId = timelineId;
        this.header = log.header(timelineId);
        this.events = log.events(timelineId);
        if (events.isEmpty()) throw new IllegalStateException("timeline " + timelineId + " has no events");
        this.graph = CausalGraph.build(events);
        this.index = new TimelineIndex(events);
        this.groupEnds = log.groupEnds(timelineId);
        this.cursor = events.size() - 1;
    }

    public String timelineId() {
        return timelineId;
    }

    /** Node names in declared order (env excluded). */
    public List<String> timelineHeaderNodes() {
        return header.nodes();
    }

    public CausalGraph graph() {
        return graph;
    }

    public int cursor() {
        return cursor;
    }

    public Event current() {
        return events.get(cursor);
    }

    // ------------------------------------------------------------------------------------------------------------
    // navigation
    // ------------------------------------------------------------------------------------------------------------

    public Event goTo(int position) {
        if (position < 0 || position >= events.size()) throw new IllegalArgumentException("position " + position + " is outside 0.." + (events.size() - 1));
        cursor = position;
        return current();
    }

    public Event goToEvent(String eventId) {
        int p = graph.pos(eventId);
        if (p < 0) throw new IllegalArgumentException("no event " + eventId + " in " + timelineId);
        return goTo(p);
    }

    /** goto violation: the first INVARIANT_VIOLATED event. */
    public Event goToViolation() {
        int[] v = index.positionsOfKind(EventKind.INVARIANT_VIOLATED);
        if (v.length == 0) throw new IllegalStateException("timeline " + timelineId + " has no invariant violation");
        return goTo(v[0]);
    }

    public Event step(int n) {
        return goTo(Math.min(events.size() - 1, cursor + n));
    }

    public Event back(int n) {
        return goTo(Math.max(0, cursor - n));
    }

    public Event nextOn(String node) {
        for (int p : index.positionsOfNode(node)) if (p > cursor) return goTo(p);
        throw new IllegalStateException("no later event on " + node);
    }

    public Event prevOn(String node) {
        int[] ps = index.positionsOfNode(node);
        for (int i = ps.length - 1; i >= 0; i--) if (ps[i] < cursor) return goTo(ps[i]);
        throw new IllegalStateException("no earlier event on " + node);
    }

    /** Searches forward or backward from the cursor for an event matching the predicate. */
    public Optional<Event> find(Predicate<Event> p, boolean forward) {
        if (forward) {
            for (int i = cursor + 1; i < events.size(); i++) if (p.test(events.get(i))) return Optional.of(goTo(i));
        } else {
            for (int i = cursor - 1; i >= 0; i--) if (p.test(events.get(i))) return Optional.of(goTo(i));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------------------
    // inspection (reading the log only)
    // ------------------------------------------------------------------------------------------------------------

    /** The last position of a completed step at or before the cursor, or -1. */
    public long completedBoundary() {
        long best = -1;
        for (long e : groupEnds) {
            if (e <= cursor) best = e;
            else break;
        }
        return best;
    }

    /** A node's state at the cursor: the latest state record at or before the last completed step. */
    public Map<String, Object> stateOf(String node) {
        return stateOf(node, completedBoundary());
    }

    Map<String, Object> stateOf(String node, long boundary) {
        int[] ps = index.positionsOfNode(node);
        long nodePos = -1;
        Map<String, Object> state = Map.of();
        for (int i = ps.length - 1; i >= 0; i--) {
            if (ps[i] > boundary) continue;
            Event e = events.get(ps[i]);
            if (e.stateAfter() != null) {
                state = e.stateAfter();
                nodePos = ps[i];
                break;
            }
        }
        // a synthetic PATCH_STATE intervention carries the node's new state in its own event (Section 19)
        for (int p : index.positionsOfKind(EventKind.INTERVENTION_APPLIED)) {
            if (p > boundary || p <= nodePos) continue;
            Event iv = events.get(p);
            if (node.equals(iv.payload().get("patchedNode")) && iv.payload().get("stateAfter") != null) {
                state = Canon.map(iv.payload().get("stateAfter"));
                nodePos = p;
            }
        }
        return state;
    }

    /** Logical clocks at the cursor: each slot's last event at or before it. */
    public Map<String, Stamp> clocks() {
        Map<String, Stamp> m = new LinkedHashMap<>();
        List<String> lanes = new ArrayList<>(header.nodes());
        lanes.add("env");
        for (String n : lanes) {
            int[] ps = index.positionsOfNode(n);
            for (int i = ps.length - 1; i >= 0; i--)
                if (ps[i] <= cursor) {
                    m.put(n, events.get(ps[i]).stamp());
                    break;
                }
        }
        return m;
    }

    /** A message that has been sent but not yet delivered or dropped at the cursor. */
    public record InFlight(String messageId, String from, String to, String type, long sentAt, long dueAt, int copies) {}

    /** A timer that is set but has not fired or been cancelled at the cursor. */
    public record PendingTimer(String timerId, String node, String name, long dueAt) {}

    public List<InFlight> inflight() {
        Map<String, Integer> copies = new LinkedHashMap<>();
        Map<String, Event> sends = new HashMap<>();
        Map<String, Long> delay = new HashMap<>();
        for (int i = 0; i <= cursor; i++) {
            Event e = events.get(i);
            switch (e.kind()) {
                case MESSAGE_SENT -> {
                    sends.put(e.eventId(), e);
                    copies.put(e.eventId(), 1);
                }
                case MESSAGE_DUPLICATED -> copies.merge((String) e.payload().get("messageId"), 1, Integer::sum);
                case FAULT_INJECTED -> {
                    if ("DELAY".equals(e.payload().get("action"))) delay.merge((String) e.payload().get("messageId"), Canon.num(e.payload().get("by")), Long::sum);
                }
                case MESSAGE_DELIVERED, MESSAGE_DROPPED -> {
                    String id = (String) e.payload().get("messageId");
                    copies.computeIfPresent(id, (k, v) -> v - 1);
                }
                default -> { }
            }
        }
        List<InFlight> out = new ArrayList<>();
        for (Map.Entry<String, Integer> en : copies.entrySet()) {
            if (en.getValue() <= 0) continue;
            Event s = sends.get(en.getKey());
            out.add(new InFlight(s.eventId(), s.nodeId(), (String) s.payload().get("to"), (String) s.payload().get("type"), s.virtualTime(),
                    Canon.num(s.payload().get("deliverAt")) + delay.getOrDefault(s.eventId(), 0L), en.getValue()));
        }
        return out;
    }

    public List<PendingTimer> timers() {
        Map<String, PendingTimer> pending = new LinkedHashMap<>();
        for (int i = 0; i <= cursor; i++) {
            Event e = events.get(i);
            switch (e.kind()) {
                case TIMER_SET -> pending.put(e.eventId(), new PendingTimer(e.eventId(), e.nodeId(), (String) e.payload().get("name"), Canon.num(e.payload().get("due"))));
                case TIMER_FIRED, TIMER_CANCELLED -> pending.remove((String) e.payload().get("timerId"));
                case NODE_CRASHED -> pending.values().removeIf(t -> t.node().equals(e.nodeId()));
                default -> { }
            }
        }
        return new ArrayList<>(pending.values());
    }

    /** The whole world at the cursor as text: states, in-flight messages, timers, clocks. */
    public String world() {
        StringBuilder sb = new StringBuilder();
        Event c = current();
        sb.append("timeline ").append(timelineId).append(" cursor ").append(cursor).append(": ").append(c.eventId()).append(' ')
                .append(Exports.describe(c, graph)).append("  (virtual time ").append(c.virtualTime()).append(" ms; last completed step ends at ")
                .append(completedBoundary()).append(")\n");
        sb.append("node states:\n");
        for (String n : header.nodes()) sb.append("  ").append(n).append(": ").append(Canon.json(stateOf(n))).append('\n');
        List<InFlight> fl = inflight();
        sb.append("in-flight messages: ").append(fl.isEmpty() ? "none" : "").append('\n');
        for (InFlight m : fl)
            sb.append("  ").append(m.messageId()).append(' ').append(m.type()).append(' ').append(m.from()).append(" -> ").append(m.to()).append(", sent at ")
                    .append(m.sentAt()).append(", due at ").append(m.dueAt()).append(m.copies() > 1 ? " (x" + m.copies() + ")" : "").append('\n');
        List<PendingTimer> tm = timers();
        sb.append("pending timers: ").append(tm.isEmpty() ? "none" : "").append('\n');
        for (PendingTimer t : tm) sb.append("  ").append(t.timerId()).append(' ').append(t.name()).append(" on ").append(t.node()).append(", due at ").append(t.dueAt()).append('\n');
        sb.append("clocks (Lamport, vector over ").append(header.nodes()).append(" + env):\n");
        for (Map.Entry<String, Stamp> e : clocks().entrySet())
            sb.append("  ").append(e.getKey()).append(": L").append(e.getValue().lamport()).append(' ').append(Exports.vec(e.getValue().vector())).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------------
    // causal questions
    // ------------------------------------------------------------------------------------------------------------

    private int resolve(String eventId) {
        if (eventId == null) return cursor;
        int p = graph.pos(eventId);
        if (p < 0) throw new IllegalArgumentException("no event " + eventId + " in " + timelineId);
        return p;
    }

    /**
     * why: the causal past (the suspects) and the shortest chain of certain causes and OMISSION links. With no baseline it
     * follows those links back to their sources: faults, interventions, inputs and starts (Section 16).
     */
    public String why(String eventId) {
        int e = resolve(eventId);
        List<List<Integer>> chains = whyChains(e);
        StringBuilder sb = new StringBuilder();
        Event ev = events.get(e);
        sb.append("why ").append(ev.eventId()).append(": ").append(Exports.describe(ev, graph)).append('\n');
        sb.append("  suspects: ").append(graph.causalPast(e).length).append(" of ").append(events.size()).append(" events are in its causal past\n");
        if (chains.isEmpty()) sb.append("  no certain cause or omission link leads here (it is a source itself)\n");
        for (List<Integer> chain : chains) {
            Event src = events.get(chain.get(0));
            sb.append("  chain from ").append(src.eventId()).append(" (").append(sourceLabel(src)).append("):\n");
            sb.append(renderChain(chain, "    "));
        }
        return sb.toString();
    }

    /** One shortest chain (certain + OMISSION edges only) from each source to the event; faults and interventions first. */
    public List<List<Integer>> whyChains(int e) {
        BitSet seen = new BitSet(events.size());
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(e);
        seen.set(e);
        List<Integer> sources = new ArrayList<>();
        while (!stack.isEmpty()) {
            int v = stack.pop();
            boolean hasParent = false;
            for (int i = 0; i < graph.parentCount(v); i++) {
                if (graph.parentKind(v, i) == EdgeKind.PROGRAM_ORDER) continue;
                hasParent = true;
                int p = graph.parent(v, i);
                if (!seen.get(p)) {
                    seen.set(p);
                    stack.push(p);
                }
            }
            // sources: events nothing certain leads to (inputs, starts), and always every fault or intervention in the past,
            // because a fault that fires at send time has that send as its trigger but is still a cause worth naming
            if (v != e && (!hasParent || isFault(events.get(v)))) sources.add(v);
        }
        sources.sort(Comparator.comparingInt((Integer p) -> sourcePriority(events.get(p))).thenComparingInt(p -> p));
        List<List<Integer>> chains = new ArrayList<>();
        for (int s : sources) graph.path(s, e, k -> k != EdgeKind.PROGRAM_ORDER).ifPresent(chains::add);
        return chains;
    }

    private static boolean isFault(Event e) {
        return e.kind() == EventKind.FAULT_INJECTED || e.kind() == EventKind.FAULT_CLEARED || e.kind() == EventKind.INTERVENTION_APPLIED;
    }

    private static int sourcePriority(Event e) {
        return switch (e.kind()) {
            case FAULT_INJECTED, FAULT_CLEARED, NODE_CRASHED -> 0;
            case INTERVENTION_APPLIED -> 1;
            case CLIENT_INPUT -> 2;
            default -> 3;
        };
    }

    private static String sourceLabel(Event e) {
        return switch (sourcePriority(e)) {
            case 0 -> "a fault";
            case 1 -> "an intervention";
            case 2 -> "an input";
            default -> "a node start";
        };
    }

    String renderChain(List<Integer> chain, String indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            Event e = events.get(chain.get(i));
            sb.append(indent).append(e.eventId()).append(" [").append(e.nodeId()).append(" t=").append(e.virtualTime()).append("] ").append(Exports.describe(e, graph)).append('\n');
            if (i + 1 < chain.size()) {
                EdgeKind k = graph.edgeBetween(chain.get(i), chain.get(i + 1));
                sb.append(indent).append("  --").append(k).append("-->\n");
            }
        }
        return sb.toString();
    }

    /** effects: everything the event could have influenced. */
    public List<Event> effects(String eventId) {
        return Arrays.stream(graph.causalFuture(resolve(eventId))).mapToObj(events::get).collect(Collectors.toList());
    }

    /** concurrent-with: events with no causal relation to it (vector clocks). */
    public List<Event> concurrentWith(String eventId) {
        int e = resolve(eventId);
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) if (graph.concurrent(e, i)) out.add(events.get(i));
        return out;
    }

    /** A change of one state field. */
    public record FieldChange(String eventId, long position, Object before, Object after) {}

    /** watch node.field: every event at which the field's value changed (first and last are the ends of the list). */
    public List<FieldChange> watch(String node, String qualifiedField) {
        List<FieldChange> out = new ArrayList<>();
        Object prev = null;
        boolean first = true;
        String zone = qualifiedField.substring(0, qualifiedField.indexOf('.'));
        String field = qualifiedField.substring(qualifiedField.indexOf('.') + 1);
        for (int p : index.positionsOfNode(node)) {
            Event e = events.get(p);
            if (e.stateAfter() == null) continue;
            Object cur = Canon.map(e.stateAfter().get(zone)).get(field);
            if (first ? cur != null : !Canon.json(cur).equals(Canon.json(prev))) out.add(new FieldChange(e.eventId(), e.seq(), prev, cur));
            prev = cur;
            first = false;
        }
        return out;
    }

    /** diff: compare the world at this position with another timeline at the same (or latest common) event. */
    public String diff(String otherTimelineId) {
        List<Event> other = log.events(otherTimelineId);
        Map<String, Integer> otherPos = new HashMap<>();
        for (Event e : other) otherPos.put(e.eventId(), (int) e.seq());
        int mine = cursor;
        while (mine >= 0 && !otherPos.containsKey(events.get(mine).eventId())) mine--;
        if (mine < 0) return "no common event before the cursor";
        TimeTravelSession o = new TimeTravelSession(log, otherTimelineId);
        o.goTo(otherPos.get(events.get(mine).eventId()));
        StringBuilder sb = new StringBuilder("diff " + timelineId + "@" + events.get(cursor).eventId() + " vs " + otherTimelineId + "@" + o.current().eventId() + "\n");
        boolean any = false;
        for (String n : header.nodes()) {
            String a = Canon.json(stateOf(n)), b = Canon.json(o.stateOf(n));
            if (!a.equals(b)) {
                any = true;
                sb.append("  ").append(n).append(" differs\n    ").append(timelineId).append(": ").append(a).append("\n    ").append(otherTimelineId).append(": ").append(b).append('\n');
            }
        }
        if (!any) sb.append("  all node states are equal\n");
        return sb.toString();
    }
}
