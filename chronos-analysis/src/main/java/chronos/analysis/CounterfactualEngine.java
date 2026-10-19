package chronos.analysis;

import chronos.model.*;
import chronos.store.TimelineStore;

import java.util.*;
import java.util.concurrent.*;

/**
 * Counterfactual Engine (C13, Section 19). A counterfactual run answers "what if?": it branches a recorded timeline at a
 * chosen step, applies one deliberate change, runs forward and reports whether the failure still happens. The future is
 * recomputed by the engine from the branch point, never edited into the old log. This turns a suspicion into evidence; it
 * is evidence about the simulated world under CHRONOS's rules, not proof about reality, and a but-for test can miss
 * overdetermined failures (KA-4).
 *
 * Verdicts: FAILURE_DISAPPEARS, FAILURE_PERSISTS, DIFFERENT_FAILURE, NO_EFFECT, INCONCLUSIVE.
 * Independent branches can run in parallel on a fixed worker pool; each runs in its own isolated world and the results are
 * collected in query order, so a report is identical whatever the thread count (law M12).
 */
public final class CounterfactualEngine {
    public enum Verdict { FAILURE_DISAPPEARS, FAILURE_PERSISTS, DIFFERENT_FAILURE, NO_EFFECT, INCONCLUSIVE }

    /** A counterfactual query: baseline timeline, branch point, interventions, horizon. */
    public record Query(String baseline, long branchSeq, List<Intervention> interventions, Long horizon, String label) {}

    public record Report(Verdict verdict, String baseline, String timelineId, long branchPoint, List<Intervention> interventions,
                         DivergenceReport divergence, String baselineFailure, String branchFailure, int affectedEvents, String explanation,
                         boolean synthetic, boolean pinned) {
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("COUNTERFACTUAL on ").append(baseline).append(" at position ").append(branchPoint).append(" -> new timeline ").append(timelineId).append('\n');
            for (Intervention iv : interventions) sb.append("  intervention ").append(iv.eventId()).append(": ").append(iv.type()).append(' ').append(Canon.json(iv.params())).append('\n');
            sb.append("  baseline failure: ").append(baselineFailure == null ? "none" : baselineFailure).append('\n');
            sb.append("  branch failure  : ").append(branchFailure == null ? "none" : branchFailure).append('\n');
            sb.append("  VERDICT: ").append(verdict).append('\n');
            sb.append("  ").append(explanation).append('\n');
            sb.append("  events in the causal future of the intervention: ").append(affectedEvents).append('\n');
            if (pinned) sb.append("  continuation policy: Pinned (messages the baseline also sent keep their original delivery instants where that is still possible)\n");
            if (synthetic) sb.append("  NOTE: this timeline is synthetic (it contains a state edit that no real execution could produce)\n");
            return sb.toString();
        }
    }

    private final TimelineManager manager;
    private final TimelineStore store;

    public CounterfactualEngine(TimelineManager manager) {
        this.manager = manager;
        this.store = manager.store();
    }

    public Report run(Query q) {
        TimelineManager.BranchResult br = manager.branch(q.baseline(), q.branchSeq(), q.interventions(), q.horizon(), q.label());
        return evaluate(q, br);
    }

    /** Runs independent counterfactuals in parallel on a fixed worker pool; reports come back in query order. */
    public List<Report> runAll(List<Query> queries, int threads) {
        List<String> ids = new ArrayList<>();
        for (Query q : queries) ids.add(manager.allocateChildId(q.baseline())); // ids are allocated before any run starts
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<Report>> fs = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                Query q = queries.get(i);
                String id = ids.get(i);
                fs.add(pool.submit(() -> evaluate(q, manager.branchAs(id, q.baseline(), q.branchSeq(), q.interventions(), q.horizon(), q.label()))));
            }
            List<Report> out = new ArrayList<>();
            for (Future<Report> f : fs) out.add(f.get());
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    /** Re-derives the verdict for a branch that already exists in the store (for reports and the CLI). */
    public Report assess(String baseline, String child) {
        TimelineHeader h = store.header(child);
        if (!baseline.equals(h.parentId())) throw new IllegalArgumentException(child + " is not a direct branch of " + baseline);
        return evaluate(new Query(baseline, h.branchSeq(), h.interventions(), h.horizon(), h.label()), new TimelineManager.BranchResult(child, h.branchSeq(), null, h));
    }

    private Report evaluate(Query q, TimelineManager.BranchResult br) {
        String baseline = q.baseline(), child = br.timelineId();
        TimelineSummary bs = store.summary(baseline), cs = store.summary(child);
        List<Event> be = store.events(baseline), ce = store.events(child);
        String bInv = bs.firstViolationInvariant(), cInv = cs.firstViolationInvariant();
        boolean baseFail = bInv != null, childFail = cInv != null;
        Event bv = baseFail ? byId(be, bs.firstViolationEventId()) : null;
        Event cv = childFail ? byId(ce, cs.firstViolationEventId()) : null;

        Verdict verdict;
        String why;
        if (sameBehavior(be, ce, br.branchSeq()) && bs.status() == cs.status()) {
            verdict = Verdict.NO_EFFECT;
            why = "every node behaved exactly as in " + baseline + ": the intervention changed nothing (for instance the message was going to be dropped anyway). This is itself informative.";
        } else if (!baseFail) {
            if (childFail) {
                verdict = Verdict.DIFFERENT_FAILURE;
                why = "the baseline had no failure, but this branch violated " + cInv + " (" + cv.eventId() + "): the intervention introduced a failure.";
            } else {
                verdict = Verdict.INCONCLUSIVE;
                why = "the baseline has no failure, so there is nothing for the intervention to remove.";
            }
        } else if (childFail) {
            boolean same = bInv.equals(cInv) && fields(bv).equals(fields(cv));
            verdict = same ? Verdict.FAILURE_PERSISTS : Verdict.DIFFERENT_FAILURE;
            why = same ? "the same invariant (" + cInv + ") was violated again at " + cv.eventId() + ": the change was not a but-for cause of the failure (an overdetermined failure can look like this: combine interventions)."
                    : "a different failure occurred: " + cInv + " at " + cv.eventId() + " (baseline: " + bInv + " at " + bv.eventId() + ").";
        } else if (cs.finalVirtualTime() >= bv.virtualTime() || cs.status() == TimelineStatus.COMPLETED) {
            verdict = Verdict.FAILURE_DISAPPEARS;
            why = "the branch ran to virtual time " + cs.finalVirtualTime() + " ms (the baseline failed at " + bv.virtualTime() + " ms) with no violation: in this world the changed event was a but-for cause of " + bInv + ".";
        } else {
            verdict = Verdict.INCONCLUSIVE;
            why = "the horizon ended at " + cs.finalVirtualTime() + " ms, before the baseline failure time " + bv.virtualTime() + " ms.";
        }
        DivergenceReport div = new TimelineComparator(store).compare(baseline, child, false);
        int affected = 0;
        if (!q.interventions().isEmpty()) {
            CausalGraph g = CausalGraph.build(ce);
            int iv = g.pos(q.interventions().get(0).eventId());
            if (iv >= 0) affected = g.causalFuture(iv).length;
        }
        return new Report(verdict, baseline, child, br.branchSeq(), q.interventions(), div, baseFail ? bInv + " at " + bv.eventId() + " (t=" + bv.virtualTime() + ")" : null,
                childFail ? cInv + " at " + cv.eventId() + " (t=" + cv.virtualTime() + ")" : null, affected, why, manager.isSynthetic(child), store.header(child).pinned());
    }

    private static Event byId(List<Event> es, String id) {
        for (Event e : es) if (e.eventId().equals(id)) return e;
        throw new CorruptLogException("event " + id + " named by the summary is missing from the log");
    }

    private static Set<Object> fields(Event violation) {
        return new TreeSet<>(Canon.list(violation.payload().get("fields")).stream().map(Object::toString).toList());
    }

    /** NO_EFFECT is decided by direct event comparison: every node's events after the branch point are the same. */
    private static boolean sameBehavior(List<Event> base, List<Event> child, long bp) {
        List<Event> a = base.stream().filter(e -> e.seq() > bp && !e.isEnv()).toList();
        List<Event> b = child.stream().filter(e -> e.seq() > bp && !e.isEnv()).toList();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Event x = a.get(i), y = b.get(i);
            if (!x.eventId().equals(y.eventId()) || x.kind() != y.kind() || x.virtualTime() != y.virtualTime()
                    || !Canon.json(x.payload()).equals(Canon.json(y.payload())) || !Objects.equals(x.stateDigestAfter(), y.stateDigestAfter())) return false;
        }
        return true;
    }

    /** What combination of fault rules is critical? Removes every single rule, then every pair, ... (Phase 14b, KA-4). */
    public record CauseSearch(List<Set<String>> minimalCriticalSets, Map<Set<String>, Verdict> tried) {}

    public CauseSearch findCriticalFaultSets(String baseline, int maxSetSize, int threads) {
        TimelineHeader h = store.header(baseline);
        List<String> rules = h.faultPlan().rules().stream().map(FaultRule::id).toList();
        List<Event> es = store.events(baseline);
        long firstFault = es.size();
        for (Event e : es) if (e.kind() == EventKind.FAULT_INJECTED) { firstFault = e.seq(); break; }
        long at = manager.roundToBoundary(baseline, Math.max(0, firstFault - 1));
        List<Set<String>> minimal = new ArrayList<>();
        Map<Set<String>, Verdict> tried = new LinkedHashMap<>();
        for (int size = 1; size <= Math.min(maxSetSize, rules.size()); size++) {
            List<Set<String>> combos = new ArrayList<>();
            combine(rules, size, 0, new TreeSet<>(), combos);
            combos.removeIf(c -> minimal.stream().anyMatch(c::containsAll));
            List<Query> qs = new ArrayList<>();
            for (Set<String> c : combos) {
                List<Intervention> ivs = new ArrayList<>();
                int i = 0;
                for (String r : c) ivs.add(Intervention.removeFault("rm" + (++i) + "-" + r.replace('-', '_'), r));
                qs.add(new Query(baseline, at, ivs, h.horizon(), "remove " + c));
            }
            List<Report> reports = runAll(qs, threads);
            for (int i = 0; i < combos.size(); i++) {
                tried.put(combos.get(i), reports.get(i).verdict());
                if (reports.get(i).verdict() == Verdict.FAILURE_DISAPPEARS) minimal.add(combos.get(i));
            }
        }
        return new CauseSearch(minimal, tried);
    }

    private static void combine(List<String> items, int k, int from, TreeSet<String> cur, List<Set<String>> out) {
        if (cur.size() == k) {
            out.add(new TreeSet<>(cur));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            cur.add(items.get(i));
            combine(items, k, i + 1, cur, out);
            cur.remove(items.get(i));
        }
    }
}
