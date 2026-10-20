package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Failure minimization (delta debugging over a failing run's fault plan): the result must fail the same way, be 1-minimal, and be reproducible. */
class FailureMinimizerTest {
    @TempDir
    Path tmp;

    private final TestRegistry reg = new TestRegistry();

    private FaultPlan chaos(String scenario, long seed) {
        var sc = reg.create(scenario, Map.of());
        return ChaosPlanGenerator.generate(sc, seed, sc.defaultHorizon());
    }

    private Recording.MemoryRun run(String scenario, long seed, FaultPlan plan, Long horizon) {
        return Recording.recordInMemory(reg, "Xt", Recording.Spec.of(scenario, seed).withPlan(plan).withHorizon(horizon).withStopOnViolation(true).withCheckpointInterval(0));
    }

    @Test
    void theMinimalPlanFailsTheSameWayAndEverySingleRemovalMakesItPass() {
        int minimized = 0, reduced = 0, shrunk = 0;
        for (String sc : List.of("re", "ka2", "ka3", "tutorial")) {
            for (long seed = 1; seed <= 40; seed++) {
                FaultPlan plan = chaos(sc, seed);
                Recording.MemoryRun base = run(sc, seed, plan, null);
                if (!base.result().violated()) continue;
                String label = sc + " seed " + seed;
                FailureMinimizer.Report rep = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config(sc, Map.of(), seed, plan, null, 2000, true));
                minimized++;
                assertTrue(rep.oneMinimal(), label);
                assertEquals(base.result().firstViolationInvariant(), rep.invariant(), label);
                // recomputed here, independently of the minimizer's own bookkeeping
                Recording.MemoryRun min = run(sc, seed, rep.minimal(), rep.minimalHorizon());
                assertTrue(min.result().violated(), label + ": the minimal plan must still fail");
                assertEquals(rep.invariant(), min.result().firstViolationInvariant(), label + ": and fail the same way");
                assertEquals(rep.violationEvent(), min.result().firstViolationEventId(), label);
                List<String> original = plan.rules().stream().map(FaultRule::id).toList();
                List<String> kept = rep.minimal().rules().stream().map(FaultRule::id).toList();
                int at = -1;
                for (String k : kept) { // a subsequence of the original, in the original order
                    int i = original.indexOf(k);
                    assertTrue(i > at, label + ": " + kept + " is not an ordered subset of " + original);
                    at = i;
                }
                for (FaultRule r : rep.minimal().rules()) {
                    FaultPlan without = rep.minimal().without(r.id());
                    Recording.MemoryRun w = run(sc, seed, without, rep.minimalHorizon());
                    assertFalse(w.result().violated() && rep.invariant().equals(w.result().firstViolationInvariant()), label + ": 1-minimality, removing " + r.id());
                }
                for (FailureMinimizer.Shrink s : rep.shrinks()) {
                    assertTrue(s.to() < s.from(), label);
                    shrunk++;
                }
                assertTrue(rep.minimalHorizon() <= rep.originalHorizon(), label);
                assertTrue(rep.executions() <= 2000);
                if (kept.size() < original.size()) reduced++;
            }
        }
        assertTrue(minimized >= 15, "enough failing chaos runs to make the property meaningful: " + minimized);
        assertTrue(reduced >= 10, "most of them contain irrelevant faults: " + reduced);
        assertTrue(shrunk >= 1, "some parameter is made smaller: " + shrunk);
    }

    @Test
    void theFlagshipChaosFailureShrinksToItsPartition() {
        FaultPlan plan = chaos("re", 5);
        assertTrue(plan.rules().size() >= 4, plan.rules().toString());
        FailureMinimizer.Report rep = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config("re", Map.of(), 5, plan, null, 2000, true));
        assertEquals(List.of("c1-partition"), rep.minimal().rules().stream().map(FaultRule::id).toList());
        assertEquals("single-primary", rep.invariant());
        assertEquals(List.of(new FailureMinimizer.Necessity("c1-partition", "no invariant is violated")), rep.necessity());
        assertTrue(rep.removed().containsAll(List.of("c2-skew", "c3-crash", "c3-restart", "c4-delay")));
        assertTrue(rep.minimalHorizon() < rep.originalHorizon());
        String text = rep.text();
        assertTrue(text.contains("RESULT: 1-minimal") && text.contains("5 -> 1"), text);
        assertTrue(text.contains("not a proof that no smaller plan exists"), "the claim is bounded honestly");
    }

    @Test
    void theSameInputGivesByteIdenticalReports() {
        FaultPlan plan = chaos("ka3", 13);
        String a = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config("ka3", Map.of(), 13, plan, null, 2000, true)).text();
        String b = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config("ka3", Map.of(), 13, plan, null, 2000, true)).text();
        assertEquals(a, b);
    }

    @Test
    void aBudgetThatRunsOutIsReportedAsUnfinishedAndTheReturnedPlanStillFails() {
        FaultPlan plan = chaos("re", 5);
        FailureMinimizer.Report rep = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config("re", Map.of(), 5, plan, null, 3, true));
        assertFalse(rep.oneMinimal());
        assertTrue(rep.text().contains("NOT FINISHED") && !rep.text().contains("RESULT: 1-minimal"), rep.text());
        Recording.MemoryRun r = run("re", 5, rep.minimal(), rep.minimalHorizon());
        assertTrue(r.result().violated() && rep.invariant().equals(r.result().firstViolationInvariant()), "an unfinished result must still be a failing plan");
        assertThrows(IllegalArgumentException.class, () -> new FailureMinimizer.Config("re", Map.of(), 5, plan, null, 0, true));
    }

    @Test
    void aRunThatDoesNotFailIsRefusedAndAFailureThatNeedsNoFaultSaysSo() {
        var e = assertThrows(IllegalArgumentException.class, () -> new FailureMinimizer(reg).minimize(
                new FailureMinimizer.Config("re", Map.of("partition", "off"), 42, FaultPlan.empty(), null, 100, true)));
        assertTrue(e.getMessage().contains("does not violate"), e.getMessage());
        FaultPlan noise = FaultPlan.of(FaultRule.pause("noise-pause", "b", 5, 10), FaultRule.crash("noise-crash", "a", 150));
        FailureMinimizer.Report rep = new FailureMinimizer(reg).minimize(new FailureMinimizer.Config("doomed", Map.of(), 1, noise, null, 100, true));
        assertTrue(rep.minimal().rules().isEmpty(), rep.text());
        assertEquals(List.of("noise-pause", "noise-crash"), rep.removed());
        assertTrue(rep.text().contains("needs NO injected fault") && rep.oneMinimal(), rep.text());
    }

    // ---- command line ------------------------------------------------------------------------------------------------------------------

    private record R(int code, String out, String err) {}

    private R cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--data";
        full[1] = tmp.resolve("data").toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = Main.run(full, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        return new R(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommandMinimizesSavesAReproducibleCounterexampleAndExplainsIt() {
        assertEquals(0, cli("run", "re", "--seed", "5", "--plan", "chaos").code());
        R m = cli("minimize", "T0", "--save");
        assertEquals(0, m.code(), m.err() + m.out());
        assertTrue(m.out().contains("fault rules: 5 -> 1") && m.out().contains("kept: c1-partition") && m.out().contains("RESULT: 1-minimal"), m.out());
        assertTrue(m.out().contains("saved minimal failure as T1"), m.out());
        // the saved document re-executes to the same trace and violation, and the stored timeline answers `why` with the partition
        Path doc = tmp.resolve("data/counterexamples/T1.json");
        R c = cli("counterexample", doc.toString());
        assertEquals(0, c.code(), c.out());
        assertTrue(c.out().contains("REPRODUCED"), c.out());
        R why = cli("inspect", "T1", "--goto", "violation", "--why");
        assertTrue(why.out().contains("fault:c1-partition#1") && why.out().contains("OMISSION"), why.out());
        // the saved minimal failure is already minimal: minimizing it again changes nothing
        R again = cli("minimize", "T1");
        assertEquals(0, again.code(), again.out() + again.err());
        assertTrue(again.out().contains("fault rules: 1 -> 1") && !again.out().contains("shrunk:") && !again.out().contains("removed as irrelevant"), again.out());
        // it travels as an incident bundle
        assertEquals(0, cli("incident", "export", "T1", "--out", tmp.resolve("t1.chronos-incident").toString()).code());
        R rep = cli("incident", "reproduce", tmp.resolve("t1.chronos-incident").toString());
        assertTrue(rep.out().contains("INCIDENT REPRODUCED"), rep.out());
    }

    @Test
    void theCommandRefusesBranchesAndRunsThatDoNotFail() {
        assertEquals(0, cli("run", "re", "--param", "partition=off", "--seed", "7").code());
        R ok = cli("minimize", "T0");
        assertEquals(1, ok.code());
        assertTrue(ok.err().contains("does not violate"), ok.err());
        assertEquals(0, cli("run", "re", "--seed", "42", "--no-stop").code());
        assertEquals(0, cli("branch", "T1", "--at", "violation", "--remove-fault", "partition-pb").code());
        R br = cli("minimize", "T1.1");
        assertEquals(1, br.code());
        assertTrue(br.err().contains("is a branch"), br.err());
        assertEquals(1, cli("minimize").code());
        assertEquals(1, cli("minimize", "T99").code());
        assertEquals(1, cli("minimize", "T1", "--max-runs", "0").code());
    }
}
