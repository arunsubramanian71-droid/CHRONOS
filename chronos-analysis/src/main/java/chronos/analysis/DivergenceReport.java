package chronos.analysis;

import chronos.model.EdgeKind;

import java.util.List;
import java.util.Map;

/** Result of comparing two timelines (Section 20, "What the report contains"). Every claim cites event ids (NFR-16). */
public record DivergenceReport(String timelineA, String timelineB, boolean strict, long sharedPrefix,
                               Map<TimelineComparator.EventClass, Integer> counts,
                               List<TimelineComparator.LocalDivergence> firstLocal,
                               List<TimelineComparator.Root> roots, TimelineComparator.Root primary,
                               int coneA, int coneB, List<String> affectedNodes, List<String> unexplained,
                               String failureTimeline, String failureEvent, List<Hop> path, String pathNote,
                               String outcomeA, String outcomeB, List<String> flags, List<String> warnings,
                               List<String> coneEventsA, List<String> coneEventsB, List<Displaced> displaced) {

    /**
     * A divergent event that is not an independent root: node-local ids are per-node counters, so when one timeline has an event on a
     * node's lane that the other lacks, every later event of that lane is renumbered. {@code sameContentAs} is the event of the other
     * timeline's lane with the same kind and payload, which shows that this one only moved (reason "renumbered", DEV-36). The other
     * reasons are "absence" (the event it replaces in the other timeline is a delivery whose send does not exist here) and "silence": the node's first different event is an input that did not arrive because an event that is itself in this
     * divergence dropped the message ({@code relatedEvent}); this one is a potential link, in the spirit of an OMISSION edge, not a certain one.
     * "timing" is an event that replaces one which depended on, or handled, something that happened here later or differently, downstream of another root (DEV-47).
     * Either way its consequences stay in the cone.
     */
    public record Displaced(char side, String eventId, String nodeId, String relatedEvent, String reason) {
        public static final String RENUMBERED = "renumbered", SILENCE = "silence", ABSENCE = "absence", TIMING = "timing", POTENTIAL_EDGE = "potential-edge";
    }

    /** One hop of the explanation path: from-event, to-event, the kind of link and a plain-language line. */
    public record Hop(String from, String to, EdgeKind kind, String text) {}

    public boolean identical() {
        return roots.isEmpty() && counts.getOrDefault(TimelineComparator.EventClass.MISSING, 0) == 0
                && counts.getOrDefault(TimelineComparator.EventClass.EXTRA, 0) == 0
                && counts.getOrDefault(TimelineComparator.EventClass.CHANGED, 0) == 0
                && counts.getOrDefault(TimelineComparator.EventClass.REORDERED, 0) == 0;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("COMPARISON ").append(timelineA).append(" (reference A) vs ").append(timelineB).append(" (B), ")
                .append(strict ? "strict" : "structural").append(", shared prefix = ").append(sharedPrefix).append(" events\n");
        for (String w : warnings) sb.append("  WARNING: ").append(w).append('\n');
        if (identical()) {
            sb.append("  no divergence: the timelines are identical after their shared prefix\n");
            sb.append("  A: ").append(outcomeA).append("\n  B: ").append(outcomeB).append('\n');
            return sb.toString();
        }
        sb.append("  classes (after the shared prefix): ").append(counts).append('\n');
        sb.append("  each node's first local divergence:\n");
        for (TimelineComparator.LocalDivergence l : firstLocal)
            sb.append("    ").append(l.node()).append(": A=").append(l.eventA() == null ? "(ended)" : l.eventA()).append(" B=").append(l.eventB() == null ? "(ended)" : l.eventB())
                    .append("  -> ").append(l.kind()).append('\n');
        sb.append("  causal roots (earliest first; taint is computed separately inside each timeline):\n");
        for (TimelineComparator.Root r : roots)
            sb.append("    ").append(r == primary ? "* " : "  ").append(r.eventId()).append(" [").append(r.side() == 'A' ? timelineA : timelineB).append(", ").append(r.nodeId())
                    .append(", t=").append(r.virtualTime()).append(", ").append(r.cls()).append("]\n");
        for (Displaced d : displaced)
            sb.append("    (displaced, not a root: ").append(d.eventId()).append(" [").append(d.side() == 'A' ? timelineA : timelineB).append("] ")
                    .append(d.reason().equals(Displaced.SILENCE)
                            ? "follows the dropped message " + d.relatedEvent() + " to " + d.nodeId() + " (potential link: what the node was waiting for never came)"
                            : d.reason().equals(Displaced.POTENTIAL_EDGE)
                            ? "differs from its counterpart in the other timeline only by the OMISSION edge from " + d.relatedEvent() + " (potential link: which messages the fault dropped to " + d.nodeId() + " differs; the event itself behaved the same)"
                            : d.reason().equals(Displaced.TIMING)
                            ? "replaces an event of the other timeline that depended on or handled " + d.relatedEvent() + ", which happened later or differently here (potential link: the race was decided the other way)"
                            : d.reason().equals(Displaced.ABSENCE)
                            ? "replaces an event of the other timeline that depended on " + d.relatedEvent() + ", which this timeline does not reproduce (potential link: the absence propagated)"
                            : "has the content of " + d.relatedEvent() + " in the other timeline: an event before it on " + d.nodeId() + " shifted the node's numbering")
                    .append(")\n");
        if (primary != null)
            sb.append("  FIRST CAUSAL DIVERGENCE: ").append(primary.eventId()).append(" (").append(primary.cls()).append(" on ").append(primary.nodeId()).append(" at t=")
                    .append(primary.virtualTime()).append(" ms, only in ").append(primary.side() == 'A' ? timelineA : timelineB).append(")\n");
        sb.append("  divergence cone: ").append(coneA).append(" events in A, ").append(coneB).append(" in B; nodes affected: ").append(affectedNodes).append('\n');
        if (!unexplained.isEmpty()) sb.append("  UNEXPLAINED divergent events (in no root's cone, should be impossible): ").append(unexplained).append('\n');
        sb.append("  invariant outcomes: A: ").append(outcomeA).append("; B: ").append(outcomeB).append('\n');
        if (failureEvent != null) {
            sb.append("  explanation path in ").append(failureTimeline).append(" to the failure ").append(failureEvent).append(":\n");
            if (pathNote != null) sb.append("    (").append(pathNote).append(")\n");
            int i = 1;
            for (Hop h : path) sb.append("    ").append(i++).append(". ").append(h.text()).append('\n');
            if (path.isEmpty()) sb.append("    no causal path found\n");
        }
        for (String f : flags) sb.append("  FLAG: ").append(f).append('\n');
        return sb.toString();
    }
}
