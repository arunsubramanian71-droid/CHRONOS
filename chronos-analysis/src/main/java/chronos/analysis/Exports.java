package chronos.analysis;

import chronos.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Text, DOT and JSON exports of a timeline (FR-45). The event table lists all N + 1 vector slots (D-07) and is the table
 * of Section 10; the space-time diagram has one lane per node (env last), time running down.
 */
public final class Exports {
    private Exports() {}

    /** The event table of Section 10: seq, event id, node, kind, virtual time, Lamport, vector [nodes..., env], parents. */
    public static String eventTable(List<Event> events, List<String> nodes) {
        List<String> slots = new ArrayList<>(nodes);
        slots.add("env");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-5s %-14s %-8s %-19s %-9s %-8s %-18s %s%n", "seq", "event id", "node", "kind", "vt(ms)", "Lamport",
                "vector[" + String.join(",", slots) + "]", "parents"));
        for (Event e : events) {
            String parents = e.parents().stream().map(Parent::id).distinct().collect(Collectors.joining(", "));
            sb.append(String.format("%-5d %-14s %-8s %-19s %-9d %-8d %-18s %s%n", e.seq(), e.eventId(), e.nodeId(), e.kind(), e.virtualTime(),
                    e.lamport(), vec(e.vector()), parents.isEmpty() ? "none" : parents));
        }
        return sb.toString();
    }

    public static String vec(List<Integer> v) {
        return v.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    /** One-line, plain-language description of an event, using the graph to find a delivery's send event. */
    public static String describe(Event e, CausalGraph g) {
        Map<String, Object> p = e.payload();
        switch (e.kind()) {
            case NODE_STARTED: return "start";
            case NODE_CRASHED: return "CRASH";
            case NODE_RESTARTED: return "restart";
            case NODE_FAILED: return "FAILED " + p.get("message");
            case MESSAGE_SENT: return "send " + p.get("type") + " -> " + p.get("to");
            case MESSAGE_DELIVERED: {
                if (g != null) {
                    Event send = sendOf(e, g);
                    if (send != null) return "recv " + send.payload().get("type") + " <- " + send.nodeId() + (e.payload().containsKey("duplicate") ? " (dup)" : "");
                }
                return "recv " + p.get("messageId");
            }
            case MESSAGE_DROPPED: return "drop " + p.get("messageId") + " (" + p.get("reason") + ")";
            case MESSAGE_DUPLICATED: return "duplicate " + p.get("messageId");
            case TIMER_SET: return "set timer " + p.get("name") + " due " + p.get("due");
            case TIMER_FIRED: return "timer " + p.get("name") + " fires";
            case TIMER_CANCELLED: return "cancel timer " + p.get("timerId");
            case CLIENT_INPUT: return "client input " + Canon.json(p.get("body"));
            case RANDOM_DRAWN: return "random " + p.get("stream") + "=" + p.get("value");
            case FAULT_INJECTED: return "FAULT " + p.get("action") + " (" + p.get("rule") + ")";
            case FAULT_CLEARED: return "fault cleared (" + p.get("rule") + ")";
            case INVARIANT_VIOLATED: return "VIOLATED " + p.get("invariant") + ": " + p.get("message");
            case INTERVENTION_APPLIED: return "INTERVENE " + p.get("type") + " " + Canon.json(p.get("params"));
            case APP_NOTE: return "note: " + p.get("text");
            default: return e.kind().name();
        }
    }

    static Event sendOf(Event delivered, CausalGraph g) {
        int pos = g.pos(delivered.eventId());
        for (int i = 0; i < g.parentCount(pos); i++)
            if (g.parentKind(pos, i) == EdgeKind.MESSAGE) return g.event(g.parent(pos, i));
        return null;
    }

    /** Space-time diagram as text: a lane per node (env last), one row per event, causal links listed on the right. */
    public static String spaceTime(CausalGraph g, List<String> nodes) {
        List<String> lanes = new ArrayList<>(nodes);
        lanes.add("env");
        int w = 30;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%5s %7s |", "seq", "t(ms)"));
        for (String l : lanes) sb.append(' ').append(pad(l, w)).append('|');
        sb.append(" caused by\n");
        for (Event e : g.events()) {
            sb.append(String.format("%5d %7d |", e.seq(), e.virtualTime()));
            for (String l : lanes) sb.append(' ').append(pad(l.equals(e.nodeId()) ? e.eventId() + " " + describe(e, g) : "", w)).append('|');
            int pos = (int) e.seq();
            List<String> causes = new ArrayList<>();
            for (int i = 0; i < g.parentCount(pos); i++) {
                EdgeKind k = g.parentKind(pos, i);
                if (k == EdgeKind.PROGRAM_ORDER || k == EdgeKind.STEP_EFFECT) continue;
                causes.add(k + " " + g.event(g.parent(pos, i)).eventId());
            }
            sb.append(' ').append(String.join("; ", causes)).append('\n');
        }
        return sb.toString();
    }

    private static String pad(String s, int w) {
        if (s.length() > w) return s.substring(0, w - 1) + "~";
        return s + " ".repeat(w - s.length());
    }

    /** Graphviz DOT text: a cluster per node, solid arrows for certain links, dashed for potential ones. */
    public static String dot(CausalGraph g, List<String> nodes) {
        List<String> lanes = new ArrayList<>(nodes);
        lanes.add("env");
        StringBuilder sb = new StringBuilder("digraph chronos {\n  rankdir=LR;\n  node [shape=box, fontsize=10];\n");
        for (String l : lanes) {
            sb.append("  subgraph \"cluster_").append(l).append("\" { label=\"").append(l).append("\";\n");
            for (Event e : g.events())
                if (e.nodeId().equals(l))
                    sb.append("    \"").append(e.eventId()).append("\" [label=\"").append(esc(e.eventId() + "\\n" + e.kind() + "\\nt=" + e.virtualTime()
                            + " L" + e.lamport() + " " + vec(e.vector()))).append("\"];\n");
            sb.append("  }\n");
        }
        for (Event e : g.events()) {
            int pos = (int) e.seq();
            for (int i = 0; i < g.parentCount(pos); i++) {
                EdgeKind k = g.parentKind(pos, i);
                sb.append("  \"").append(g.event(g.parent(pos, i)).eventId()).append("\" -> \"").append(e.eventId()).append("\" [label=\"").append(k)
                        .append("\"").append(k.certain() ? "" : ", style=dashed").append("];\n");
            }
        }
        return sb.append("}\n").toString();
    }

    private static String esc(String s) {
        return s.replace("\"", "'");
    }

    /** JSON export: nodes, then every event with its stamps and parents. */
    public static String json(CausalGraph g, List<String> nodes) {
        Map<String, Object> m = new TreeMap<>();
        m.put("nodes", nodes);
        List<Object> evs = new ArrayList<>();
        for (Event e : g.events()) {
            Map<String, Object> em = new TreeMap<>();
            em.put("id", e.eventId());
            em.put("seq", e.seq());
            em.put("node", e.nodeId());
            em.put("kind", e.kind().name());
            em.put("vt", e.virtualTime());
            em.put("lamport", e.lamport());
            em.put("vector", e.vector());
            List<Object> ps = new ArrayList<>();
            for (Parent p : e.parents()) ps.add(Map.of("id", p.id(), "kind", p.kind().name()));
            em.put("parents", ps);
            evs.add(em);
        }
        m.put("events", evs);
        return Canon.json(m);
    }
}
