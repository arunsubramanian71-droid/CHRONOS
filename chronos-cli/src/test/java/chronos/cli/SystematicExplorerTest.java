package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.SystematicExplorer.*;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded systematic exploration: completeness within the bound, correct detection, reproducible and replayable counterexamples, reductions, limits. */
class SystematicExplorerTest {
    @TempDir
    Path tmp;

    private final TestRegistry reg = new TestRegistry();

    private static Candidate cand(FaultRule r) {
        return new Candidate(r.id(), r);
    }

    /** RE without its planted partition: a decisive cause (the partition), two harmless faults and one that can never fire. */
    private Config reConfig(int maxFaults, long maxCases, boolean stopFirst, boolean prune) {
        List<Candidate> cs = List.of(
                cand(FaultRule.partition("part-pb", 300, 400L, List.of(List.of("p"), List.of("b")))),
                cand(FaultRule.pause("pause-client", "client", 150, 20)),
                cand(FaultRule.crash("crash-client", "client", 900)),
                cand(FaultRule.crash("late-crash", "b", 5000))); // after the horizon: never fires
        return new Config("re", Map.of("partition", "off"), 42, null, FaultPlan.empty(), cs, maxFaults, maxCases, stopFirst, prune);
    }

    @Test
    void everySubsetWithinTheBoundIsAccountedForExactlyOnce() {
        Report r = new SystematicExplorer(reg).explore(reConfig(2, 1000, false, false));
        assertEquals(1 + 4 + 6, r.totalSubsets(), "C(4,0) + C(4,1) + C(4,2)");
        assertEquals(r.totalSubsets(), r.executed(), "without pruning every schedule is executed");
        assertEquals(0, r.skippedAsSuperset());
        assertEquals(r.totalSubsets(), r.uniqueTraces() + r.duplicateTraces());
        assertTrue(r.complete());
        assertEquals(Termination.EXHAUSTED, r.termination());
        assertEquals(0, r.notEnumerated());
        // the schedule that can never fire changes nothing: every set with it equals the same set without it
        assertTrue(r.duplicateTraces() >= 4, "the never-firing crash gives duplicate traces: " + r.duplicateTraces());
        assertEquals(SystematicExplorer.totalSubsets(4, 4), 16);
        assertEquals(1, SystematicExplorer.totalSubsets(0, 3));
        assertEquals(Long.MAX_VALUE, SystematicExplorer.totalSubsets(5000, 2000), "saturates instead of overflowing");
    }

    @Test
    void theViolationIsFoundExactlyWhereItBelongsAndSupersetsAreSkippedAndCounted() {
        Report r = new SystematicExplorer(reg).explore(reConfig(3, 1000, false, true));
        assertEquals(1, r.findings().size(), r.text());
        Finding f = r.findings().get(0);
        assertEquals(List.of("part-pb"), f.faults(), "minimal: just the partition");
        assertEquals("single-primary", f.invariant());
        assertFalse(r.baselineViolates());
        // the supersets of {part-pb} of size 2 and 3 are the sets of one or two of the other three candidates added to it: 3 + 3, all skipped
        assertEquals(6, r.skippedAsSuperset(), r.text());
        assertEquals(1 + 4 + 6 + 4, r.totalSubsets());
        assertEquals(r.totalSubsets(), r.executed() + r.skippedAsSuperset());
        // without pruning, every superset also violates (the cause is decisive), and they are all reported as distinct executions
        Report all = new SystematicExplorer(reg).explore(reConfig(3, 1000, false, false));
        for (Finding x : all.findings()) assertTrue(x.faults().contains("part-pb"), x.faults().toString());
        // distinct executions only: the crash at 900 ms and the crash at 5000 ms never fire before the run stops at the violation, so they add nothing
        assertEquals(2, all.findings().size(), all.text());
        // independent brute force: run every subset directly and compare with the explorer's account
        int n = 4;
        Map<String, Boolean> byDigest = new HashMap<>();
        for (int mask = 0; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) > 3) continue;
            FaultPlan plan = FaultPlan.empty();
            for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) plan = plan.with(reConfig(1, 1, false, false).candidates().get(i).rule());
            var run = Recording.recordInMemory(reg, "B" + mask, Recording.Spec.of("re", 42).withParams(Map.of("partition", "off")).withPlan(plan).withStopOnViolation(true).withCheckpointInterval(0));
            Boolean prev = byDigest.put(SystematicExplorer.traceDigest(run.log().events()), run.result().violated());
            if (prev != null) assertEquals(prev, run.result().violated(), "equal traces must have equal outcomes (the equivalence is sound)");
            assertEquals(((mask & 1) != 0), run.result().violated(), "violates exactly when the partition is in the schedule");
        }
        assertEquals(all.uniqueTraces(), byDigest.size(), "the explorer's unique-trace count equals brute force");
    }

    @Test
    void aBoundWithNoViolationSaysSoWithoutClaimingMoreThanTheBound() {
        List<Candidate> cs = List.of(cand(FaultRule.pause("pause-b", "b", 100, 30)), cand(FaultRule.crash("crash-client", "client", 900)));
        Report r = new SystematicExplorer(reg).explore(new Config("re", Map.of("partition", "off"), 42, null, FaultPlan.empty(), cs, 2, 100, false, true));
        assertTrue(r.findings().isEmpty());
        assertTrue(r.complete());
        assertTrue(r.text().contains("no invariant violation in the whole explored bound"), r.text());
        assertTrue(r.text().contains("says nothing about schedules outside it"), r.text());
        // KA-0, the correct protocol: a generated space, no violation anywhere in it
        SystematicExplorer ex = new SystematicExplorer(reg);
        List<Candidate> gen = ex.generate("control", Map.of(), 7, null, SystematicExplorer.Grid.standard(List.of(30L, 90L)));
        assertFalse(gen.isEmpty());
        Report c = ex.explore(new Config("control", Map.of(), 7, null, FaultPlan.empty(), gen, 2, 100000, false, true));
        assertTrue(c.findings().isEmpty(), c.text());
        assertTrue(c.complete(), c.text());
    }

    @Test
    void limitsTerminateTheRunAndSayHowMuchWasNotExplored() {
        Config cfg = reConfig(3, 5, false, false);
        Report r = new SystematicExplorer(reg).explore(cfg);
        assertEquals(Termination.CASE_LIMIT, r.termination());
        assertFalse(r.complete());
        assertEquals(5, r.executed());
        assertEquals(r.totalSubsets() - 5, r.notEnumerated());
        assertTrue(r.text().contains("NOT explored"), r.text());
        // stop at the first violation
        Report first = new SystematicExplorer(reg).explore(reConfig(3, 1000, true, true));
        assertEquals(Termination.FIRST_VIOLATION, first.termination());
        assertEquals(1, first.findings().size());
        assertTrue(first.text().contains("stopped at the first violation"));
        assertThrows(IllegalArgumentException.class, () -> reConfig(-1, 1, false, false));
        assertThrows(IllegalArgumentException.class, () -> reConfig(1, 0, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Config("re", Map.of(), 1, null, null,
                List.of(cand(FaultRule.crash("same", "p", 1)), cand(FaultRule.crash("same", "b", 1))), 1, 10, false, false), "duplicate candidate names are refused");
    }

    @Test
    void caseLimitAlsoBoundsEnumerationWhenSupersetPruningWouldSkipTheWholeSpace() {
        // The base partition already violates the invariant, so the empty candidate set is a violating subset
        // of every schedule. Without an enumeration limit, pruning still walks all 2^18 combinations.
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 18; i++)
            candidates.add(cand(FaultRule.pause("candidate-" + i, "client", 900L + i, 10)));
        FaultPlan base = FaultPlan.of(FaultRule.partition("base-partition", 300, 400L, List.of(List.of("p"), List.of("b"))));
        Config cfg = new Config("re", Map.of("partition", "off"), 42, null, base, candidates, 18, 2, false, true);

        Report r = new SystematicExplorer(reg).explore(cfg);
        assertEquals(Termination.CASE_LIMIT, r.termination());
        assertEquals(1, r.executed(), "only the empty candidate set should execute");
        assertEquals(1, r.skippedAsSuperset(), "the second schedule is pruned");
        assertEquals(2, r.executed() + r.skippedAsSuperset(), "the limit bounds all processed subsets");
        assertEquals(r.totalSubsets() - 2, r.notEnumerated());
        assertFalse(r.complete());
    }

    @Test
    void aFixedConfigurationGivesAByteIdenticalReportAndGeneratedSpaceInAnyOrderOfCalls() {
        String a = new SystematicExplorer(reg).explore(reConfig(3, 1000, false, true)).text();
        new SystematicExplorer(reg).explore(reConfig(1, 1000, false, true)); // an unrelated run in between
        String b = new SystematicExplorer(reg).explore(reConfig(3, 1000, false, true)).text();
        assertEquals(a, b);
        SystematicExplorer ex = new SystematicExplorer(reg);
        List<Candidate> g1 = ex.generate("re", Map.of("partition", "off"), 42, null, SystematicExplorer.Grid.standard(List.of(100L, 300L)));
        List<Candidate> g2 = ex.generate("re", Map.of("partition", "off"), 42, null, SystematicExplorer.Grid.standard(List.of(100L, 300L)));
        assertEquals(g1.stream().map(Candidate::name).toList(), g2.stream().map(Candidate::name).toList());
        assertTrue(g1.stream().anyMatch(c -> c.name().contains("drop-p-b-Heartbeat-n1")), "links come from the observed fault-free behaviour");
        assertTrue(g1.stream().anyMatch(c -> c.name().contains("part-b-p@300") || c.name().contains("part-p-b@300")));
    }

    @Test
    void aCounterexampleIsReproducibleFromItsDocumentAloneAndItsTraceReplays() throws IOException {
        TimelineStore store = new TimelineStore(tmp.resolve("data"));
        Config cfg = reConfig(2, 1000, false, true);
        Report r = new SystematicExplorer(reg).explore(cfg);
        Finding f = r.findings().get(0);
        Path doc = Counterexample.save(store, reg, "T0", cfg, f);
        // the document is a complete record
        Map<String, Object> m = Canon.parseMap(Files.readString(doc));
        for (String k : List.of("formatVersion", "kind", "tool", "scenario", "params", "seed", "horizon", "exploration", "faults", "plan", "violation", "traceDigest", "logHash", "checksum"))
            assertTrue(m.containsKey(k), k);
        assertEquals(List.of("part-pb"), m.get("faults"));
        // reproduces, in a fresh store and with no store at all
        Counterexample.Result ok = Counterexample.verify(reg, doc, store);
        assertTrue(ok.ok(), ok.problems().toString());
        assertEquals(f.traceDigest(), ok.traceDigest());
        assertTrue(Counterexample.verify(reg, doc, null).ok());
        assertTrue(new ReplayEngine(store, reg).verify("T0").ok(), "the saved trace replays");
        assertTrue(new TimeTravelSession(store, "T0").goToViolation() != null);
        // damage is detected, never trusted
        String text = Files.readString(doc);
        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, text.replace("\"seed\":42", "\"seed\":43"));
        assertFalse(Counterexample.verify(reg, bad, null).ok());
        assertTrue(Counterexample.verify(reg, bad, null).problems().get(0).contains("checksum"));
        Files.writeString(bad, text.replace("\"formatVersion\":1", "\"formatVersion\":9"));
        assertTrue(Counterexample.verify(reg, bad, null).problems().get(0).contains("unsupported"));
        Files.writeString(bad, "{ not json");
        assertFalse(Counterexample.verify(reg, bad, null).ok());
        Files.writeString(bad, "{\"kind\":\"something-else\"}");
        assertTrue(Counterexample.verify(reg, bad, null).problems().get(0).contains("not a CHRONOS counterexample"));
        assertFalse(Counterexample.verify(reg, tmp.resolve("missing.json"), null).ok());
        // a document that is internally consistent (valid checksum) but whose claims are false is caught by re-execution
        Map<String, Object> lie = new TreeMap<>(m);
        lie.remove("checksum");
        lie.put("plan", List.of()); // the schedule no longer contains the partition
        lie.put("checksum", Canon.sha256(Canon.json(lie)));
        Files.writeString(bad, Canon.json(lie) + "\n", StandardCharsets.UTF_8);
        Counterexample.Result liar = Counterexample.verify(reg, bad, null);
        assertFalse(liar.ok());
        assertTrue(liar.problems().stream().anyMatch(p -> p.contains("no longer violates") || p.contains("differs")), liar.problems().toString());
    }

    @Test
    void theCommandLineExploresSavesVerifiesAndReportsLimits() {
        String data = tmp.resolve("cli").toString();
        java.util.function.Function<String[], String[]> run = args -> {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            List<String> full = new ArrayList<>(List.of("--data", data));
            full.addAll(List.of(args));
            int code = Main.run(full.toArray(new String[0]), new java.io.PrintStream(o, true, StandardCharsets.UTF_8), new java.io.PrintStream(o, true, StandardCharsets.UTF_8), java.io.InputStream.nullInputStream());
            return new String[]{Integer.toString(code), o.toString(StandardCharsets.UTF_8)};
        };
        String[] e = run.apply(new String[]{"explore", "re", "--param", "partition=off", "--candidate", "partition:300:400:p|b", "--candidate", "pause:client:150:20", "--max-faults", "2", "--save"});
        assertEquals("0", e[0], e[1]);
        assertTrue(e[1].contains("EXHAUSTED") && e[1].contains("saved counterexample T0"), e[1]);
        String file = e[1].replaceAll("(?s).*chronos counterexample (\\S+).*", "$1");
        String[] v = run.apply(new String[]{"counterexample", file});
        assertEquals("0", v[0], v[1]);
        assertTrue(v[1].contains("REPRODUCED"));
        String[] limited = run.apply(new String[]{"explore", "re", "--param", "partition=off", "--max-faults", "2", "--max-cases", "10", "--times", "100,300"});
        assertTrue(limited[1].contains("CASE_LIMIT") && limited[1].contains("NOT explored"), limited[1]);
        assertEquals("1", run.apply(new String[]{"explore", "re", "--param", "partition=off", "--candidate", "partition:300:400:p|b", "--fail-on-violation"})[0]);
        assertEquals("2", run.apply(new String[]{"counterexample", tmp.resolve("nope.json").toString()})[0]);
    }
}
