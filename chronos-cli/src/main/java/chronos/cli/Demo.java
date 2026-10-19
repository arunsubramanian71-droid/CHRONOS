package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.CounterfactualEngine.Verdict;
import chronos.model.*;
import chronos.runtime.RunResult;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The flagship demonstration: the acceptance demonstrations AD-1 to AD-6 of Section 4 run live on RE, plus the known-answer
 * scenarios KA-2 to KA-4. Every line is produced by real components, and every claim is checked: a failed check makes the
 * command exit non-zero.
 */
final class Demo {
    private final Cli cli;
    private final Args args;
    private final PrintStream out;
    private int failures;
    private int checks;

    Demo(Cli cli, Args args) {
        this.cli = cli;
        this.args = args;
        this.out = cli.out();
    }

    private void head(String s) {
        out.println();
        out.println("=".repeat(100));
        out.println(s);
        out.println("=".repeat(100));
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) failures++;
        out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what);
    }

    int run() {
        out.println("CHRONOS demonstration on RE: a replicated key-value store (client, primary p, backup b).");
        out.println("p sends a heartbeat every 100 ms; b promotes itself after 300 ms of silence; a 400 ms partition (300-700 ms) cuts p from b.");

        head("AD-1  Run RE with a failing seed: the single-primary invariant breaks, and CHRONOS prints the causal chain from partition to violation");
        RunResult r = cli.record("re", 42, Map.of(), "default", null, Boolean.FALSE, 10, null);
        String t0 = cli.lastTimeline;
        check("the run ended with the single-primary invariant violated", r.violated() && r.firstViolationInvariant().equals("single-primary"));
        TimeTravelSession s = new TimeTravelSession(cli.store(), t0);
        Event viol = s.goToViolation();
        List<List<Integer>> chains = s.whyChains(s.cursor());
        List<Event> evs = cli.store().events(t0);
        check("the first causal chain starts at the partition event and runs through the watchdog (OMISSION) to the violation",
                !chains.isEmpty() && evs.get(chains.get(0).get(0)).eventId().equals("fault:partition-pb#1") && chains.get(0).size() == 3
                        && s.graph().edgeBetween(chains.get(0).get(0), chains.get(0).get(1)) == EdgeKind.OMISSION);

        head("AD-2  Replay the run and show identical event-log hashes; then run a deliberately nondeterministic node and watch CHRONOS flag the first mismatching event");
        ReplayEngine.Result vr = cli.manager().replayEngine().verify(t0);
        out.println("  " + vr.summary());
        check("verification replay regenerated every event of " + t0 + " exactly", vr.ok() && vr.eventsVerified() == evs.size());
        Recording.DeterminismReport det = Recording.determinismCheck(cli.registry(), Recording.Spec.of("re", 42).withCheckpointInterval(0));
        out.println("  two independent runs: hash " + det.hashA() + (det.identical() ? " == " : " != ") + det.hashB());
        check("running RE twice gives byte-identical logs", det.identical());
        cli.record("bad", 1, Map.of(), "default", null, null, 25, null);
        String bad = cli.lastTimeline;
        ReplayEngine.Result br = cli.manager().replayEngine().verify(bad);
        out.print(Cli.indent(br.ok() ? "UNEXPECTED: the verifier passed the bad citizen" : br.violation().report(), "  "));
        check("the bad citizen (reads the system clock) is flagged at its first mismatching event", !br.ok() && br.violation().expected().kind() == EventKind.TIMER_SET);

        head("AD-3  Jump to the event just before the violation, show all node states and in-flight messages, step backward three times");
        out.println("  The violation is recorded in the same atomic step as b's promotion, so the node states of the completed step show BOTH primaries at the violation event:");
        s.goTo((int) viol.seq());
        out.println("  cursor " + s.cursor() + " = " + viol.eventId() + ": " + Exports.describe(viol, s.graph()));
        out.print(Cli.indent(s.world(), "  "));
        boolean bothPrimary = "PRIMARY".equals(Canon.map(s.stateOf("p").get("volatile")).get("role")) && "PRIMARY".equals(Canon.map(s.stateOf("b").get("volatile")).get("role"));
        check("p and b are both PRIMARY at the violation, reconstructed from the log without running any application code", bothPrimary);
        String worldAtViolation = s.world();
        for (int i = 1; i <= 3; i++) {
            Event e = s.back(1);
            out.println("  back " + i + " -> cursor " + s.cursor() + ": " + e.eventId() + " [" + e.nodeId() + " t=" + e.virtualTime() + "] " + Exports.describe(e, s.graph()));
        }
        out.println("  (the cursor is now inside the step that promoted b: states shown are those of the last completed step, where b was still a BACKUP)");
        check("b is still a BACKUP three events earlier", "BACKUP".equals(Canon.map(s.stateOf("b").get("volatile")).get("role")));
        s.step(3);
        check("going forward three events again returns exactly the same world (going back is only a cursor move)", s.world().equals(worldAtViolation));

        head("AD-4  Branch at the partition, heal it 200 ms earlier, run forward: the violation disappears");
        long at = evs.stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
        CounterfactualEngine cf = new CounterfactualEngine(cli.manager());
        CounterfactualEngine.Report rep = cf.run(new CounterfactualEngine.Query(t0, at, List.of(Intervention.shiftHeal("heal-early", "partition-pb", -200)), null, "heal the partition 200 ms earlier"));
        out.print(Cli.indent(rep.text(), "  "));
        String t1 = rep.timelineId();
        check("verdict FAILURE_DISAPPEARS", rep.verdict() == Verdict.FAILURE_DISAPPEARS);
        List<Event> branch = cli.store().events(t1);
        boolean prefix = true;
        for (int i = 0; i <= at; i++) prefix &= evs.get(i).toLine().equals(branch.get(i).toLine());
        check("the branch shares the parent's history up to position " + at + " byte for byte (stored only as a reference, not a copy)", prefix);
        check("the intervention is recorded on env as iv:heal-early at the branch point (D-25)", branch.get((int) at + 1).eventId().equals("iv:heal-early"));
        check("the branch replays and verifies through its lineage", cli.manager().replayEngine().verify(t1).ok());

        head("AD-5  Compare the failing and the healed timelines: CHRONOS names the first causal divergence and the path to the failure");
        DivergenceReport d = new TimelineComparator(cli.store()).compare(t0, t1, false);
        out.print(Cli.indent(d.text(), "  "));
        check("the first causal divergence is the intervention's own event iv:heal-early (law M10)", d.primary() != null && d.primary().eventId().equals("iv:heal-early"));
        check("the explanation path in " + t0 + " starts at the partition event (the anchor) and ends at the violation",
                !d.path().isEmpty() && d.path().get(0).from().equals("fault:partition-pb#1") && d.path().get(d.path().size() - 1).to().equals(viol.eventId()));

        head("AD-6  Generate the static, self-contained HTML report");
        Path file = cli.dataDir().resolve("reports").resolve(t0 + "-vs-" + t1 + ".html");
        cli.generateReport(t0, t1, file);
        try {
            String html = Files.readString(file);
            check("the report exists and is a complete HTML page with an inline SVG space-time diagram", html.startsWith("<!doctype html>") && html.contains("<svg") && html.contains("</html>"));
            check("the report makes no network calls and references no external resource", !HtmlReport.referencesExternalResources(html));
            check("the report shows the first causal divergence, the verdict and the replay status", html.contains("iv:heal-early") && html.contains("FAILURE_DISAPPEARS") && html.contains("VERIFIED"));
        } catch (java.io.IOException e) {
            check("the report can be read back", false);
        }
        out.println("  open it with:  open " + file.toAbsolutePath());

        head("Known-answer scenarios (Section 25)");
        knownAnswers();

        out.println();
        out.println("-".repeat(100));
        out.println("demonstration finished: " + (checks - failures) + " of " + checks + " checks passed" + (failures == 0 ? " - ALL PASSED" : " - " + failures + " FAILED"));
        out.println("timelines: " + cli.dataDir().resolve("timelines").toAbsolutePath() + "   report: " + file.toAbsolutePath());
        return failures == 0 ? 0 : 2;
    }

    private void knownAnswers() {
        CounterfactualEngine cf = new CounterfactualEngine(cli.manager());
        // KA-2 lost update
        cli.record("ka2", 1, Map.of(), "default", null, null, 25, null);
        String ka2 = cli.lastTimeline;
        TimeTravelSession s2 = new TimeTravelSession(cli.store(), ka2);
        s2.goToViolation();
        List<Event> e2 = cli.store().events(ka2);
        check("KA-2 lost update: the root of the chain is the delay fault", e2.get(s2.whyChains(s2.cursor()).get(0).get(0)).eventId().startsWith("fault:delay-first-replicate"));
        long before2 = firstFaultBefore(e2);
        Verdict v2 = cf.run(new CounterfactualEngine.Query(ka2, before2, List.of(Intervention.removeFault("no-delay", "delay-first-replicate")), null, null)).verdict();
        check("KA-2: removing the delay makes the failure disappear (" + v2 + ")", v2 == Verdict.FAILURE_DISAPPEARS);
        // KA-3 blocked commit
        cli.record("ka3", 1, Map.of(), "default", null, null, 25, null);
        String ka3 = cli.lastTimeline;
        TimeTravelSession s3 = new TimeTravelSession(cli.store(), ka3);
        s3.goToViolation();
        List<Event> e3 = cli.store().events(ka3);
        check("KA-3 blocked commit: the root is the coordinator crash", e3.get(s3.whyChains(s3.cursor()).get(0).get(0)).eventId().equals("fault:crash-coord#1"));
        Verdict v3 = cf.run(new CounterfactualEngine.Query(ka3, firstFaultBefore(e3), List.of(Intervention.removeFault("no-crash", "crash-coord")), null, null)).verdict();
        check("KA-3: without the crash no participant stays undecided (" + v3 + ")", v3 == Verdict.FAILURE_DISAPPEARS);
        // KA-4 overdetermined
        cli.record("re2", 42, Map.of(), "default", null, null, 25, null);
        String ka4 = cli.lastTimeline;
        long before4 = firstFaultBefore(cli.store().events(ka4));
        Verdict a1 = cf.run(new CounterfactualEngine.Query(ka4, before4, List.of(Intervention.removeFault("rm1", "partition-p-b1")), null, null)).verdict();
        Verdict a2 = cf.run(new CounterfactualEngine.Query(ka4, before4, List.of(Intervention.removeFault("rm2", "partition-p-b2")), null, null)).verdict();
        check("KA-4 overdetermined: removing either partition alone reports FAILURE_PERSISTS (" + a1 + ", " + a2 + ")", a1 == Verdict.FAILURE_PERSISTS && a2 == Verdict.FAILURE_PERSISTS);
        CounterfactualEngine.CauseSearch cs = cf.findCriticalFaultSets(ka4, 2, 2);
        check("KA-4: the automated search lists both causes together " + cs.minimalCriticalSets(), cs.minimalCriticalSets().size() == 1 && cs.minimalCriticalSets().get(0).size() == 2);
        // KA-0 control
        List<Campaign.Entry> ka0 = Campaign.run(cli.registry(), "control", Map.of(), 1, args.has("full") ? 300 : 60, 4, true, null, null);
        check("KA-0 control: " + ka0.size() + " runs of a correct protocol under random fault plans, no violation, ever", ka0.stream().noneMatch(Campaign.Entry::violated));
    }

    private static long firstFaultBefore(List<Event> es) {
        for (Event e : es) if (e.kind() == EventKind.FAULT_INJECTED) return Math.max(0, e.seq() - 1);
        return 0;
    }
}
