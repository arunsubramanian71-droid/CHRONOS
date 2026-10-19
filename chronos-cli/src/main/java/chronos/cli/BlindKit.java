package chronos.cli;

import chronos.demo.DemoRegistry;
import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.model.SeededRandom;
import chronos.sdk.ConfiguredScenario;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;

import java.util.*;

/**
 * The blind-debugging kit (Section 25, "Blind debugging protocol", R15): one author prepares a case whose planted cause the other
 * does not know, and the other must find the cause using only CHRONOS. The kit builds a case from a token (a number the preparer
 * picks), hides which application it is built on (the scenario is called "mystery"), randomises rule names and times so that a known
 * answer cannot be remembered, times the diagnosis and grades the answer against the planted cause.
 *
 * It is an aid for the human protocol, not a substitute: the token determines the case, so the honour system applies (the debugger
 * must not run {@code blind grade} before answering). A person who has not seen the answer is what makes the evidence blind.
 */
public final class BlindKit {
    private BlindKit() {}

    /** One planted case: the application it is built on, its parameters, the fault plan that causes the failure, and the planted cause. */
    public record Case(String base, Map<String, String> params, FaultPlan plan, Set<String> truth, String description) {}

    public static final int CASES = 5;

    private static String tag(SeededRandom r) {
        return String.format("%04x", Math.floorMod(r.nextLong(), 65536L));
    }

    /** The case for a token: deterministic, so the grader can rebuild what the preparer planted. */
    public static Case caseFor(long token) {
        SeededRandom r = new SeededRandom(token);
        int kind = (int) Math.floorMod(token, (long) CASES);
        r.nextLong();
        switch (kind) {
            case 0 -> {
                String id = "f" + tag(r) + "-cut";
                long at = 200 + Math.floorMod(r.nextLong(), 250L), dur = 350 + Math.floorMod(r.nextLong(), 200L);
                return new Case("re", Map.of("partition", "off"), FaultPlan.of(FaultRule.partition(id, at, dur, List.of(List.of("p"), List.of("b")))), Set.of(id),
                        "a partition of p from b, " + at + " ms for " + dur + " ms, longer than the backup's watchdog");
            }
            case 1 -> {
                String id = "f" + tag(r) + "-slow";
                long by = 120 + Math.floorMod(r.nextLong(), 300L);
                return new Case("ka2", Map.of("delay", "off"), FaultPlan.of(FaultRule.nth(id, FaultRule.FaultAction.DELAY, "p", "b", "Replicate", 1, by)), Set.of(id),
                        "the first Replicate from p to b delayed by " + by + " ms, past its successor, with no sequence numbers");
            }
            case 2 -> {
                String id = "f" + tag(r) + "-down";
                long at = 31 + Math.floorMod(r.nextLong(), 8L);
                return new Case("ka3", Map.of("crash", "off"), FaultPlan.of(FaultRule.crash(id, "coord", at)), Set.of(id), "the coordinator crashes at " + at + " ms, after Prepare has been delivered and before the decision");
            }
            case 3 -> {
                String a = "f" + tag(r) + "-a", b = "f" + tag(r) + "-b";
                long at1 = 200 + Math.floorMod(r.nextLong(), 150L), at2 = 200 + Math.floorMod(r.nextLong(), 150L), dur = 350 + Math.floorMod(r.nextLong(), 150L);
                return new Case("re2", Map.of("partition", "off"), FaultPlan.of(FaultRule.partition(a, at1, dur, List.of(List.of("p"), List.of("b1"))),
                        FaultRule.partition(b, at2, dur, List.of(List.of("p"), List.of("b2")))), Set.of(a, b),
                        "two independent partitions, each sufficient on its own: both are the cause (overdetermined)");
            }
            default -> {
                String id = "f" + tag(r) + "-gap";
                long at = 60 + Math.floorMod(r.nextLong(), 100L), dur = 120 + Math.floorMod(r.nextLong(), 150L);
                return new Case("tutorial", Map.of(), FaultPlan.of(FaultRule.partition(id, at, dur, List.of(List.of("sender"), List.of("receiver")))), Set.of(id),
                        "a partition between sender and receiver, " + at + " ms for " + dur + " ms");
            }
        }
    }

    /** The demo registry plus the scenario "mystery", whose parameter m is the case token. */
    public static final class Registry implements ScenarioRegistry {
        private final DemoRegistry demo = new DemoRegistry();

        @Override
        public List<String> names() {
            return demo.names();
        }

        @Override
        public Scenario create(String name, Map<String, String> params) {
            if (!name.equals("mystery")) return demo.create(name, params);
            long token = Long.parseUnsignedLong(Objects.requireNonNull(params.get("m"), "a mystery case needs its token (parameter m)"), 16);
            Case c = caseFor(token);
            return new ConfiguredScenario(demo.create(c.base(), c.params()), Map.of(), "mystery");
        }
    }

    /** Whether an answer names exactly the planted cause. Accepts rule ids or the fault event ids that cite them ("fault:RULE#1"). */
    public static boolean correct(Case c, Collection<String> answer) {
        Set<String> given = new TreeSet<>();
        for (String a : answer) {
            String s = a.trim();
            if (s.startsWith("fault:")) s = s.substring("fault:".length());
            int hash = s.lastIndexOf('#');
            if (hash > 0) s = s.substring(0, hash);
            given.add(s);
        }
        return given.equals(new TreeSet<>(c.truth()));
    }
}
