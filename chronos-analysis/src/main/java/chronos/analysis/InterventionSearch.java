package chronos.analysis;

import chronos.analysis.CounterfactualEngine.Query;
import chronos.analysis.CounterfactualEngine.Report;
import chronos.analysis.CounterfactualEngine.Verdict;
import chronos.model.*;
import chronos.sdk.ClientInput;
import chronos.store.TimelineStore;

import java.util.*;

/**
 * Intervention search (FR-38, Stretch): given a timeline that ends in a violation, find which single changes to its causal past
 * make the failure disappear. The suspects are the changeable events in the causal past of the first violation: message sends
 * (tried as a DROP of that message), scripted client inputs (REMOVE_INPUT) and fault rules that fired (REMOVE_FAULT). Each suspect
 * becomes a real counterfactual branch at the step boundary just before it, run by the {@link CounterfactualEngine} on a worker pool,
 * so every finding is evidence from an actual re-execution, not a guess. Findings that make the failure disappear are the
 * "critical" ones and are ranked by causal distance to the violation (closest first).
 *
 * This is a but-for search over single interventions: it can miss overdetermined failures (KA-4, where only a pair is critical;
 * use {@link CounterfactualEngine#findCriticalFaultSets} for fault rules), and it considers only the suspects named above.
 * When there are more suspects than the cap, those causally closest to the violation are kept (ties by position), so the result is
 * deterministic.
 */
public final class InterventionSearch {
    public enum SuspectKind { MESSAGE, CLIENT_INPUT, FAULT_RULE }

    public record Suspect(SuspectKind kind, String eventId, long seq, int distance, Intervention intervention, long branchPoint) {}

    public record Finding(Suspect suspect, Report report) {
        public boolean critical() { return report.verdict() == Verdict.FAILURE_DISAPPEARS; }
    }

    public record Result(String baseline, String violation, int suspectsFound, int suspectsTried, List<Finding> findings) {
        public List<Finding> critical() { return findings.stream().filter(Finding::critical).toList(); }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("INTERVENTION SEARCH on ").append(baseline).append(": violation ").append(violation).append('\n');
            sb.append("  suspects in the causal past: ").append(suspectsFound).append(", tried (real counterfactual branches): ").append(suspectsTried).append('\n');
            List<Finding> crit = critical();
            if (crit.isEmpty()) sb.append("  no single change to a suspect makes the failure disappear (it may be overdetermined: try `chronos causes`)\n");
            else {
                sb.append("  CRITICAL (changing any one of these makes the failure disappear), closest to the violation first:\n");
                for (Finding f : crit) sb.append("    ").append(describe(f)).append('\n');
                Finding earliest = crit.stream().min(Comparator.comparingLong(f -> f.suspect().seq())).orElseThrow();
                sb.append("  earliest critical event: ").append(earliest.suspect().eventId()).append('\n');
            }
            Map<Verdict, Integer> counts = new TreeMap<>();
            for (Finding f : findings) counts.merge(f.report().verdict(), 1, Integer::sum);
            sb.append("  verdict counts: ").append(counts).append('\n');
            return sb.toString();
        }

        private static String describe(Finding f) {
            Suspect s = f.suspect();
            return s.eventId() + " (" + s.kind() + ", " + s.distance() + " causal hops from the violation): " + s.intervention().type() + ' ' + Canon.json(s.intervention().params());
        }
    }

    private final TimelineManager manager;
    private final TimelineStore store;

    public InterventionSearch(TimelineManager manager) {
        this.manager = manager;
        this.store = manager.store();
    }

    /** The suspects for a baseline, closest to its first violation first. */
    public List<Suspect> suspects(String baseline) {
        TimelineSummary sum = store.summary(baseline);
        if (sum == null || sum.firstViolationEventId() == null) throw new IllegalArgumentException(baseline + " has no invariant violation to search for");
        List<Event> events = store.events(baseline);
        CausalGraph g = CausalGraph.build(events);
        int v = g.pos(sum.firstViolationEventId());
        BitSet past = new BitSet();
        for (int p : g.causalPast(v)) past.set(p);
        past.set(v);

        Set<String> deliveredInPast = new HashSet<>();
        for (int i = past.nextSetBit(0); i >= 0; i = past.nextSetBit(i + 1))
            if (g.event(i).kind() == EventKind.MESSAGE_DELIVERED) deliveredInPast.add((String) g.event(i).payload().get("messageId"));

        TimelineHeader header = store.header(baseline);
        Set<String> planRules = new HashSet<>();
        for (FaultRule r : header.faultPlan().rules()) planRules.add(r.id());
        List<ClientInput> workload = manager.scenarioOf(baseline).workload();
        boolean[] used = new boolean[workload.size()];

        List<Suspect> out = new ArrayList<>();
        Set<String> seenRules = new HashSet<>();
        int n = 0;
        for (int i = past.nextSetBit(0); i >= 0; i = past.nextSetBit(i + 1)) {
            Event e = g.event(i);
            int dist = g.path(i, v).map(pth -> pth.size() - 1).orElse(Integer.MAX_VALUE);
            try {
                switch (e.kind()) {
                    case MESSAGE_SENT -> {
                        if (!deliveredInPast.contains(e.eventId())) break;
                        long at = manager.roundToBoundary(baseline, endOfStepContaining(baseline, e.seq()));
                        out.add(new Suspect(SuspectKind.MESSAGE, e.eventId(), e.seq(), dist, Intervention.dropMessage("s" + (++n), e.eventId()), at));
                    }
                    case CLIENT_INPUT -> {
                        int idx = matchInput(workload, used, e);
                        if (idx < 0) break; // an input added by an intervention is not a scripted input that can be removed
                        long at = manager.roundToBoundary(baseline, e.seq() - 1);
                        out.add(new Suspect(SuspectKind.CLIENT_INPUT, e.eventId(), e.seq(), dist, Intervention.removeInput("s" + (++n), idx), at));
                    }
                    case FAULT_INJECTED -> {
                        String rule = (String) e.payload().get("rule");
                        if (rule == null || !planRules.contains(rule) || !seenRules.add(rule)) break;
                        long at = manager.roundToBoundary(baseline, e.seq() - 1);
                        out.add(new Suspect(SuspectKind.FAULT_RULE, e.eventId(), e.seq(), dist, Intervention.removeFault("s" + (++n), rule), at));
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException beforeFirstBoundary) {
                // a suspect in the very first step has no earlier boundary to branch from; it cannot be tested
            }
        }
        out.sort(Comparator.comparingInt(Suspect::distance).thenComparingLong(Suspect::seq));
        return out;
    }

    public Result search(String baseline, int maxCandidates, int threads, boolean keepBranches) {
        List<Suspect> all = suspects(baseline);
        List<Suspect> tried = all.size() <= maxCandidates ? all : all.subList(0, maxCandidates);
        TimelineHeader h = store.header(baseline);
        List<Query> qs = new ArrayList<>();
        for (Suspect s : tried) qs.add(new Query(baseline, s.branchPoint(), List.of(s.intervention()), h.horizon(), "search " + s.intervention().type() + " " + s.eventId()));
        List<Report> reports = new CounterfactualEngine(manager).runAll(qs, threads);
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < tried.size(); i++) findings.add(new Finding(tried.get(i), reports.get(i)));
        if (!keepBranches) for (Report r : reports) store.delete(r.timelineId());
        findings.sort(Comparator.comparing((Finding f) -> !f.critical()).thenComparingInt(f -> f.suspect().distance()).thenComparingLong(f -> f.suspect().seq()));
        return new Result(baseline, store.summary(baseline).firstViolationEventId(), all.size(), tried.size(), findings);
    }

    private long endOfStepContaining(String tl, long seq) {
        for (long end : store.groupEnds(tl)) if (end >= seq) return end;
        throw new IllegalArgumentException("event " + seq + " is after the last step boundary");
    }

    /** The scripted input an input event corresponds to: same node, same time, same body, not matched before. */
    private static int matchInput(List<ClientInput> workload, boolean[] used, Event e) {
        for (int i = 0; i < workload.size(); i++) {
            ClientInput ci = workload.get(i);
            if (!used[i] && ci.node().equals(e.nodeId()) && ci.at() == e.virtualTime()
                    && Canon.json(Canon.normalizeMap(ci.body())).equals(Canon.json(e.payload().get("body")))) {
                used[i] = true;
                return i;
            }
        }
        return -1;
    }
}
