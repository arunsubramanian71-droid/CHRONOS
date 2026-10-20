package chronos.analysis;

import chronos.model.*;
import chronos.sdk.ScenarioRegistry;

import java.util.*;

/**
 * Failure minimization by delta debugging (Zeller's ddmin) over a recorded run's fault plan.
 *
 * <p>Given a scenario, parameters, seed and fault plan whose execution violates an invariant, it searches for a smaller plan that still
 * violates <i>the same invariant</i>: first a 1-minimal subset of the rules (removing any single remaining rule makes that failure
 * disappear, which is then checked rule by rule and reported), then smaller values for the numeric parameters that make a fault
 * "bigger" (a partition's or pause's {@code duration}, a delay's {@code by}), then the shortest horizon that still reaches the
 * violation. Every candidate is a deterministic re-execution from scratch (the simulator is single-threaded and identity-keyed, so
 * removing a rule never changes what another rule does), cached by its canonical plan, and counted; the result is therefore
 * byte-identical for identical input.
 *
 * <p>What it claims: the returned plan fails, and removing any one of its rules does not (1-minimality, verified). What it does not
 * claim: that no smaller plan exists elsewhere in the space (ddmin is not a minimum-cardinality search), or that a parameter is the
 * smallest that fails when failure is not monotone in it (every accepted value was executed; the search is a binary search). A budget
 * ({@code maxRuns}) stops the search and the report says it was not finished.
 */
public final class FailureMinimizer {
    private final ScenarioRegistry registry;

    public FailureMinimizer(ScenarioRegistry registry) {
        this.registry = registry;
    }

    /** The numeric parameters shrunk after the rule set is minimal: the size of a fault window or of a delay. */
    static final List<String> SHRINKABLE = List.of("duration", "by");

    public record Config(String scenario, Map<String, String> params, long seed, FaultPlan plan, Long horizon, long maxRuns, boolean shrinkParameters) {
        public Config {
            if (maxRuns < 1) throw new IllegalArgumentException("maxRuns must be >= 1");
            params = params == null ? Map.of() : new TreeMap<>(params);
            plan = plan == null ? FaultPlan.empty() : plan;
        }
    }

    /** One rule of the minimal plan and what happens without it. */
    public record Necessity(String ruleId, String withoutIt) {}

    /** A numeric parameter that was made smaller. */
    public record Shrink(String ruleId, String param, long from, long to) {}

    public enum Termination { MINIMAL, BUDGET }

    public record Report(Config config, String invariant, String violationEvent, FaultPlan original, FaultPlan minimal, List<String> removed,
                         List<Necessity> necessity, List<Shrink> shrinks, long originalHorizon, long minimalHorizon, long originalEvents,
                         long minimalEvents, long executions, Termination termination, String traceDigest, Recording.MemoryRun run) {

        public boolean oneMinimal() {
            return termination == Termination.MINIMAL;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("MINIMIZATION of ").append(config.scenario()).append(config.params().isEmpty() ? "" : " " + config.params()).append(" seed=").append(config.seed()).append('\n');
            sb.append("  failure kept: ").append(invariant).append(" violated at ").append(violationEvent).append('\n');
            sb.append("  fault rules: ").append(original.rules().size()).append(" -> ").append(minimal.rules().size())
                    .append("   events up to the violation: ").append(originalEvents).append(" -> ").append(minimalEvents)
                    .append("   horizon: ").append(originalHorizon).append(" -> ").append(minimalHorizon).append(" ms\n");
            sb.append("  executions: ").append(executions).append(" (each a complete deterministic re-run; budget ").append(config.maxRuns()).append(")\n");
            if (!removed.isEmpty()) sb.append("  removed as irrelevant to this failure: ").append(String.join(", ", removed)).append('\n');
            if (minimal.rules().isEmpty()) sb.append("  the failure needs NO injected fault: the scenario violates the invariant by itself with this seed\n");
            for (FaultRule r : minimal.rules()) sb.append("  kept: ").append(r.id()).append("  ").append(Canon.json(r.toMap())).append('\n');
            for (Shrink s : shrinks) sb.append("  shrunk: ").append(s.ruleId()).append('.').append(s.param()).append(' ').append(s.from()).append(" -> ").append(s.to()).append('\n');
            for (Necessity n : necessity) sb.append("  check: without ").append(n.ruleId()).append(": ").append(n.withoutIt()).append('\n');
            if (termination == Termination.MINIMAL)
                sb.append("  RESULT: 1-minimal: removing any single kept rule makes the failure disappear (checked above). This is not a proof that no smaller plan exists.\n");
            else
                sb.append("  RESULT: NOT FINISHED: the run budget was reached; the plan above still fails but may contain removable rules.\n");
            sb.append("  trace ").append(traceDigest, 0, 12).append('\n');
            return sb.toString();
        }
    }

    public Report minimize(Config cfg) {
        Run runner = new Run(cfg);
        return runner.go();
    }

    private final class Run {
        final Config cfg;
        final Map<String, Outcome> cache = new HashMap<>();
        long executions;
        String invariant;
        boolean outOfBudget;

        Run(Config cfg) {
            this.cfg = cfg;
        }

        record Outcome(boolean violated, String invariant, String event, long time, Recording.MemoryRun run) {}

        Outcome exec(FaultPlan plan, Long horizon) {
            String key = Canon.json(plan.toList()) + "|" + horizon;
            Outcome o = cache.get(key);
            if (o != null) return o;
            executions++;
            Recording.MemoryRun r = Recording.recordInMemory(registry, "Xm", Recording.Spec.of(cfg.scenario(), cfg.seed()).withParams(cfg.params()).withPlan(plan)
                    .withHorizon(horizon).withStopOnViolation(true).withCheckpointInterval(0));
            long t = 0;
            if (r.result().violated()) {
                String id = r.result().firstViolationEventId();
                for (Event e : r.log().events()) if (e.eventId().equals(id)) t = e.virtualTime();
            }
            o = new Outcome(r.result().violated(), r.result().firstViolationInvariant(), r.result().firstViolationEventId(), t, r);
            cache.put(key, o);
            return o;
        }

        /** True if the plan still fails with the same invariant (a different failure is a different bug and does not count). */
        boolean fails(FaultPlan plan, Long horizon) {
            if (executions >= cfg.maxRuns() && !cache.containsKey(Canon.json(plan.toList()) + "|" + horizon)) {
                outOfBudget = true;
                return false;
            }
            Outcome o = exec(plan, horizon);
            return o.violated() && Objects.equals(o.invariant(), invariant);
        }

        FaultPlan planOf(List<FaultRule> rules) {
            return new FaultPlan(rules);
        }

        Report go() {
            Outcome base = exec(cfg.plan(), cfg.horizon());
            if (!base.violated()) throw new IllegalArgumentException("nothing to minimize: this run does not violate any invariant");
            invariant = base.invariant();
            long originalEvents = base.run().log().events().size();
            long originalHorizon = base.run().header().horizon();
            List<FaultRule> current = new ArrayList<>(cfg.plan().rules());

            // 1. ddmin over the rules
            if (!current.isEmpty() && fails(FaultPlan.empty(), cfg.horizon())) current = new ArrayList<>();
            int n = 2;
            while (current.size() >= 2 && !outOfBudget) {
                List<List<FaultRule>> chunks = split(current, n);
                boolean reduced = false;
                for (List<FaultRule> chunk : chunks) {
                    if (fails(planOf(chunk), cfg.horizon())) {
                        current = new ArrayList<>(chunk);
                        n = 2;
                        reduced = true;
                        break;
                    }
                }
                if (!reduced) {
                    for (List<FaultRule> chunk : chunks) {
                        List<FaultRule> complement = new ArrayList<>(current);
                        complement.removeAll(chunk);
                        if (complement.isEmpty()) continue;
                        if (fails(planOf(complement), cfg.horizon())) {
                            current = complement;
                            n = Math.max(n - 1, 2);
                            reduced = true;
                            break;
                        }
                    }
                }
                if (!reduced) {
                    if (n >= current.size()) break;
                    n = Math.min(n * 2, current.size());
                }
            }
            // ddmin ends 1-minimal only when it was allowed to finish; a single leftover rule is checked against the empty plan above
            boolean again = true;
            while (again && !outOfBudget) {
                again = false;
                for (FaultRule r : new ArrayList<>(current)) {
                    List<FaultRule> without = new ArrayList<>(current);
                    without.remove(r);
                    if (fails(planOf(without), cfg.horizon())) {
                        current = without;
                        again = true;
                        break;
                    }
                }
            }

            // 2. smaller parameters
            List<Shrink> shrinks = new ArrayList<>();
            if (cfg.shrinkParameters()) {
                for (int i = 0; i < current.size() && !outOfBudget; i++) {
                    for (String param : SHRINKABLE) {
                        FaultRule r = current.get(i);
                        if (!(r.params().get(param) instanceof Number num) || num.longValue() <= 1) continue;
                        long good = num.longValue(), bad = 0;
                        if (fails(with(current, i, param, 1), cfg.horizon())) good = 1;
                        else {
                            bad = 1;
                            while (good - bad > 1 && !outOfBudget) {
                                long mid = bad + (good - bad) / 2;
                                if (fails(with(current, i, param, mid), cfg.horizon())) good = mid;
                                else bad = mid;
                            }
                        }
                        if (good < num.longValue()) {
                            shrinks.add(new Shrink(r.id(), param, num.longValue(), good));
                            current = replace(current, i, with(current, i, param, good).rules().get(i));
                        }
                    }
                }
            }
            FaultPlan minimal = planOf(current);

            // 3. the shortest horizon that still reaches the violation, and the evidence run
            Outcome fin = exec(minimal, cfg.horizon());
            Long horizon = cfg.horizon();
            long shortest = Math.min(originalHorizon, Math.max(1, fin.time() + 1));
            if (!outOfBudget && fin.violated() && Objects.equals(fin.invariant(), invariant) && shortest < originalHorizon && fails(minimal, shortest)) horizon = shortest;
            Outcome last = exec(minimal, horizon);
            if (!last.violated() || !Objects.equals(last.invariant(), invariant)) { // cannot happen unless the budget cut a check short: fall back to the verified run
                last = fin;
                horizon = cfg.horizon();
            }

            // 4. the evidence for 1-minimality: what happens without each kept rule (these runs may exceed the budget; they are the proof, so they are always executed)
            boolean wasOut = outOfBudget;
            List<Necessity> necessity = new ArrayList<>();
            boolean stillMinimal = !wasOut;
            for (FaultRule r : current) {
                List<FaultRule> without = new ArrayList<>(current);
                without.remove(r);
                Outcome o = exec(planOf(without), horizon);
                String what;
                if (!o.violated()) what = "no invariant is violated";
                else if (!Objects.equals(o.invariant(), invariant)) what = "a different invariant is violated (" + o.invariant() + ")";
                else {
                    what = "STILL FAILS (not minimal)";
                    stillMinimal = false;
                }
                necessity.add(new Necessity(r.id(), what));
            }
            List<String> removed = new ArrayList<>();
            for (FaultRule r : cfg.plan().rules()) if (minimal.find(r.id()).isEmpty()) removed.add(r.id());
            return new Report(cfg, invariant, last.event(), cfg.plan(), minimal, removed, necessity, shrinks, originalHorizon, horizon == null ? originalHorizon : horizon,
                    originalEvents, last.run().log().events().size(), executions, stillMinimal ? Termination.MINIMAL : Termination.BUDGET,
                    SystematicExplorer.traceDigest(last.run().log().events()), last.run());
        }

        FaultPlan with(List<FaultRule> rules, int i, String param, long value) {
            FaultRule r = rules.get(i);
            Map<String, Object> p = new TreeMap<>(r.params());
            p.put(param, value);
            return planOf(replace(rules, i, new FaultRule(r.id(), r.trigger(), r.action(), p, r.nonLocal())));
        }

        List<FaultRule> replace(List<FaultRule> rules, int i, FaultRule nr) {
            List<FaultRule> l = new ArrayList<>(rules);
            l.set(i, nr);
            return l;
        }

        List<List<FaultRule>> split(List<FaultRule> rules, int n) {
            List<List<FaultRule>> chunks = new ArrayList<>();
            int size = rules.size(), start = 0;
            for (int i = 0; i < n; i++) {
                int end = start + (size - start) / (n - i);
                chunks.add(new ArrayList<>(rules.subList(start, end)));
                start = end;
            }
            chunks.removeIf(List::isEmpty);
            return chunks;
        }
    }
}
