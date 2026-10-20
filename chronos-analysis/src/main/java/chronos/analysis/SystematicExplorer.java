package chronos.analysis;

import chronos.model.*;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;

import java.util.*;

/**
 * Bounded systematic exploration of fault schedules. This is NOT random fuzzing: for a declared set of N candidate faults and a bound
 * k it enumerates every subset of at most k candidates, in one fixed order (by size, then lexicographically by candidate index), runs
 * the scenario deterministically under each, and checks the scenario's invariants. Nothing outside that bound is claimed: a run of
 * the explorer that finds no violation says only "no violation in these subsets, with this seed, horizon and these candidates".
 *
 * Soundness of the reductions (all reported, none silent):
 * <ul>
 * <li><b>Trace equivalence.</b> Two cases whose recorded event sequences are identical (same content digests in the same order; rules
 *   that never fired leave no event) are the same execution: the second is counted as a duplicate and not reported again. Equal traces
 *   give equal invariant outcomes because the invariants read only state recorded in the trace.</li>
 * <li><b>Superset pruning</b> (optional, on by default): once a subset violates, every superset is skipped as "contains a known
 *   violating schedule". This is sound for finding <i>minimal</i> violating schedules and is not sound for "does every schedule
 *   violate"; it is reported separately from exploration.</li>
 * <li><b>Limits.</b> {@code maxCases} bounds subsets processed, including duplicate traces and supersets pruned without execution;
 *   reaching it stops the run with termination reason CASE_LIMIT and an explicit statement of how much of the bound was not explored.</li>
 * </ul>
 * Every execution is in memory and deterministic, so a fixed configuration gives a byte-identical report.
 */
public final class SystematicExplorer {
    /** A named candidate fault: the rule's id is its name. */
    public record Candidate(String name, FaultRule rule) {}

    public record Config(String scenario, Map<String, String> params, long seed, Long horizon, FaultPlan basePlan, List<Candidate> candidates,
                         int maxFaults, long maxCases, boolean stopAtFirst, boolean pruneSupersets) {
        public Config {
            if (maxFaults < 0) throw new IllegalArgumentException("maxFaults must be >= 0");
            if (maxCases < 1) throw new IllegalArgumentException("maxCases must be >= 1");
            Set<String> names = new HashSet<>();
            for (Candidate c : candidates) {
                if (!names.add(c.name())) throw new IllegalArgumentException("duplicate candidate name " + c.name());
                if (!c.name().equals(c.rule().id())) throw new IllegalArgumentException("candidate " + c.name() + " must use its name as the rule id");
            }
            if (basePlan != null) for (FaultRule r : basePlan.rules()) if (names.contains(r.id())) throw new IllegalArgumentException("candidate " + r.id() + " clashes with a rule of the base plan");
            params = Map.copyOf(params);
            candidates = List.copyOf(candidates);
        }
    }

    /** One violating schedule that was executed, with everything needed to reproduce it. */
    public record Finding(long caseIndex, List<String> faults, String invariant, String violationEvent, String traceDigest, String logHash, long events,
                          FaultPlan plan, Recording.MemoryRun run) {}

    public enum Termination { EXHAUSTED, CASE_LIMIT, FIRST_VIOLATION }

    public record Report(Config config, long totalSubsets, long executed, long duplicateTraces, long skippedAsSuperset, long notEnumerated,
                         int uniqueTraces, boolean baselineViolates, List<Finding> findings, Termination termination) {
        /** True when every subset of at most maxFaults candidates was either executed, recognised as a duplicate or pruned as a superset. */
        public boolean complete() {
            return termination == Termination.EXHAUSTED;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("SYSTEMATIC EXPLORATION of ").append(config.scenario()).append(' ').append(config.params()).append(" seed=").append(config.seed()).append('\n');
            sb.append("  space: ").append(config.candidates().size()).append(" candidate faults, every subset of at most ").append(config.maxFaults()).append(" = ")
                    .append(totalSubsets).append(" schedules (case limit ").append(config.maxCases()).append(")\n");
            sb.append("  executed ").append(executed).append(", duplicates of an already seen trace ").append(duplicateTraces).append(" (unique traces ").append(uniqueTraces)
                    .append("), skipped as supersets of a violating schedule ").append(skippedAsSuperset).append(", not enumerated ").append(notEnumerated).append('\n');
            sb.append("  termination: ").append(termination).append(complete() ? " (the whole bound was covered)" : termination == Termination.FIRST_VIOLATION
                    ? " (stopped at the first violation; the rest of the bound was not explored)" : " (the case limit was reached: " + notEnumerated + " schedules of the bound were NOT explored)").append('\n');
            if (baselineViolates) sb.append("  NOTE: the schedule with no extra fault already violates an invariant\n");
            if (findings.isEmpty()) {
                sb.append("  RESULT: no invariant violation in the ").append(complete() ? "whole explored bound" : "explored part of the bound")
                        .append(". This says nothing about schedules outside it (other candidates, larger subsets, other seeds or horizons).\n");
            } else {
                sb.append("  RESULT: ").append(findings.size()).append(" distinct violating execution(s)").append(config.pruneSupersets() ? " (minimal: supersets are pruned)" : "").append(":\n");
                for (Finding f : findings)
                    sb.append("    case ").append(f.caseIndex()).append(": faults ").append(f.faults().isEmpty() ? "[none]" : f.faults()).append(" -> ").append(f.invariant()).append(" at ")
                            .append(f.violationEvent()).append(" (").append(f.events()).append(" events, trace ").append(f.traceDigest(), 0, 12).append(")\n");
            }
            return sb.toString();
        }
    }

    private final ScenarioRegistry registry;

    public SystematicExplorer(ScenarioRegistry registry) {
        this.registry = registry;
    }

    /** n choose k, saturating at Long.MAX_VALUE. */
    static long choose(int n, int k) {
        if (k < 0 || k > n) return 0;
        long r = 1;
        for (int i = 1; i <= k; i++) {
            long next = r / i * (n - k + i) + (r % i * (n - k + i)) / i;
            if (next < r) return Long.MAX_VALUE;
            r = next;
        }
        return r;
    }

    /** The number of schedules the bound contains: sum over sizes 0..k of C(N, size). */
    public static long totalSubsets(int candidates, int maxFaults) {
        long t = 0;
        for (int s = 0; s <= Math.min(maxFaults, candidates); s++) {
            long c = choose(candidates, s);
            if (c == Long.MAX_VALUE || t > Long.MAX_VALUE - c) return Long.MAX_VALUE;
            t += c;
        }
        return t;
    }

    public Report explore(Config cfg) {
        Scenario sc = registry.create(cfg.scenario(), cfg.params());
        FaultPlan base = cfg.basePlan() != null ? cfg.basePlan() : FaultPlan.empty();
        long horizon = cfg.horizon() != null ? cfg.horizon() : sc.defaultHorizon();
        int n = cfg.candidates().size();
        long total = totalSubsets(n, cfg.maxFaults());
        Map<String, Long> seen = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        List<Set<Integer>> violating = new ArrayList<>();
        long executed = 0, duplicates = 0, skipped = 0, index = 0;
        boolean baselineViolates = false;
        Termination termination = Termination.EXHAUSTED;
        outer:
        for (int size = 0; size <= Math.min(cfg.maxFaults(), n); size++) {
            int[] comb = new int[size];
            for (int i = 0; i < size; i++) comb[i] = i;
            while (true) {
                Set<Integer> chosen = new TreeSet<>();
                for (int c : comb) chosen.add(c);
                long thisCase = index++;
                // Bound enumeration work, not just executions. Otherwise a violating empty schedule can cause every
                // non-empty subset to be pruned while the explorer still walks an exponentially large search space.
                if (thisCase >= cfg.maxCases()) {
                    termination = Termination.CASE_LIMIT;
                    index--;
                    break outer;
                }
                if (cfg.pruneSupersets() && violating.stream().anyMatch(chosen::containsAll)) {
                    skipped++;
                } else {
                    FaultPlan plan = base;
                    List<String> names = new ArrayList<>();
                    for (int c : chosen) {
                        plan = plan.with(cfg.candidates().get(c).rule());
                        names.add(cfg.candidates().get(c).name());
                    }
                    Recording.MemoryRun run = Recording.recordInMemory(registry, "X" + thisCase, Recording.Spec.of(cfg.scenario(), cfg.seed()).withParams(cfg.params())
                            .withPlan(plan).withHorizon(horizon).withStopOnViolation(true).withCheckpointInterval(0));
                    executed++;
                    String digest = traceDigest(run.log().events());
                    if (seen.containsKey(digest)) {
                        duplicates++;
                    } else {
                        seen.put(digest, thisCase);
                        if (run.result().violated()) {
                            if (size == 0) baselineViolates = true;
                            findings.add(new Finding(thisCase, names, run.result().firstViolationInvariant(), run.result().firstViolationEventId(), digest,
                                    run.result().logHash(), run.result().eventCount(), plan, run));
                            violating.add(chosen);
                            if (cfg.stopAtFirst()) {
                                termination = Termination.FIRST_VIOLATION;
                                break outer;
                            }
                        }
                    }
                }
                // next combination in lexicographic order
                int i = size - 1;
                while (i >= 0 && comb[i] == n - size + i) i--;
                if (i < 0) break;
                comb[i]++;
                for (int j = i + 1; j < size; j++) comb[j] = comb[j - 1] + 1;
            }
        }
        long accounted = executed + skipped;
        long notEnumerated = termination == Termination.EXHAUSTED ? 0 : Math.max(0, total - accounted);
        return new Report(cfg, total, executed, duplicates, skipped, notEnumerated, seen.size(), baselineViolates, findings, termination);
    }

    /** Digest of an execution: the content digests of its events in order (identical for identical executions, whatever the timeline id). */
    public static String traceDigest(List<Event> events) {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) sb.append(e.contentDigest(true)).append('\n');
        return Canon.sha256(sb.toString());
    }

    // ------------------------------------------------------------------------------------------------------------
    // candidate generation from a baseline run
    // ------------------------------------------------------------------------------------------------------------

    /** What a generated space may contain. */
    public record Grid(List<Long> times, long duration, int nthMessages, Set<String> kinds) {
        public static Grid standard(List<Long> times) {
            return new Grid(times, 200, 2, Set.of("crash", "partition", "drop", "pause"));
        }
    }

    /**
     * Builds a candidate space from the scenario's fault-free behaviour: a crash and a pause of every node at each grid time, a
     * partition of every pair of nodes that exchanged messages at each grid time, and a drop of the first {@code nthMessages} messages of
     * every (sender, receiver, type) that occurs. Deterministic: ordered by kind, then node/link names, then time.
     */
    public List<Candidate> generate(String scenario, Map<String, String> params, long seed, Long horizon, Grid grid) {
        Scenario sc = registry.create(scenario, params);
        Recording.MemoryRun baseline = Recording.recordInMemory(registry, "Xbase", Recording.Spec.of(scenario, seed).withParams(params).withPlan(FaultPlan.empty())
                .withHorizon(horizon != null ? horizon : sc.defaultHorizon()).withStopOnViolation(false).withCheckpointInterval(0));
        TreeMap<String, Integer> links = new TreeMap<>();
        TreeSet<String> pairs = new TreeSet<>();
        for (Event e : baseline.log().events()) {
            if (e.kind() != EventKind.MESSAGE_SENT) continue;
            String to = (String) e.payload().get("to"), type = (String) e.payload().get("type");
            links.merge(e.nodeId() + ">" + to + ">" + type, 1, Integer::sum);
            pairs.add(e.nodeId().compareTo(to) < 0 ? e.nodeId() + "|" + to : to + "|" + e.nodeId());
        }
        List<Candidate> out = new ArrayList<>();
        int i = 0;
        List<String> nodes = sc.nodes().stream().map(nd -> nd.name()).sorted().toList();
        if (grid.kinds().contains("crash"))
            for (String nd : nodes) for (long t : grid.times()) out.add(cand(++i, "crash-" + nd + "@" + t, id -> FaultRule.crash(id, nd, t)));
        if (grid.kinds().contains("pause"))
            for (String nd : nodes) for (long t : grid.times()) out.add(cand(++i, "pause-" + nd + "@" + t, id -> FaultRule.pause(id, nd, t, grid.duration())));
        if (grid.kinds().contains("partition"))
            for (String pr : pairs) {
                String[] ab = pr.split("\\|");
                for (long t : grid.times()) out.add(cand(++i, "part-" + ab[0] + "-" + ab[1] + "@" + t, id -> FaultRule.partition(id, t, grid.duration(), List.of(List.of(ab[0]), List.of(ab[1])))));
            }
        if (grid.kinds().contains("drop"))
            for (Map.Entry<String, Integer> l : links.entrySet()) {
                String[] p = l.getKey().split(">");
                for (int k = 1; k <= Math.min(grid.nthMessages(), l.getValue()); k++) {
                    int kk = k;
                    out.add(cand(++i, "drop-" + p[0] + "-" + p[1] + "-" + p[2] + "-n" + k, id -> FaultRule.nth(id, FaultRule.FaultAction.DROP, p[0], p[1], p[2], kk, null)));
                }
            }
        return out;
    }

    private static Candidate cand(int i, String label, java.util.function.Function<String, FaultRule> make) {
        String name = "e" + i + "-" + label;
        return new Candidate(name, make.apply(name));
    }
}
