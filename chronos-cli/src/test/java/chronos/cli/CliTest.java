package chronos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import chronos.analysis.*;
import chronos.model.*;
import java.io.*;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The command line, driven through {@link Main#run}: every command must perform a real operation and report honestly. */
class CliTest {
    @TempDir
    Path tmp;

    record R(int code, String out, String err) {}

    private R cliIn(String stdin, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--data";
        full[1] = tmp.resolve("data").toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = Main.run(full, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new R(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private R cli(String... args) {
        return cliIn("", args);
    }

    @Test
    void helpAndUnknownCommands() {
        R h = cli("help");
        assertEquals(0, h.code());
        assertTrue(h.out().contains("counterfactual") && h.out().contains("report") && h.out().contains("verify"));
        R bad = cli("frobnicate");
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("unknown command"));
        assertEquals(1, cli().code());
    }

    @Test
    void runRecordsATimelineAndExplainsTheFailure() {
        R r = cli("run", "re", "--seed", "42", "--no-stop");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("recorded T0"), r.out());
        assertTrue(r.out().contains("INVARIANT VIOLATED: single-primary"));
        assertTrue(r.out().contains("fault:partition-pb#1") && r.out().contains("OMISSION"));
        assertTrue(Files.isDirectory(tmp.resolve("data/timelines/T0/events")));
        assertTrue(cli("ls").out().contains("T0  re seed=42"));
        // a run is recorded under the next id, and a named scenario with params works
        R r2 = cli("run", "re", "--param", "partition=off", "--seed", "7");
        assertTrue(r2.out().contains("recorded T1") && !r2.out().contains("VIOLATED"), r2.out());
        assertEquals(1, cli("run", "nope").code());
        assertEquals(1, cli("run", "re", "--seed", "abc").code());
        assertTrue(cli("scenarios").out().contains("ka3"));
    }

    @Test
    void showInspectAndVerify() throws Exception {
        cli("run", "pingpong");
        R table = cli("show", "T0");
        assertTrue(table.out().contains("vector[A,B,env]") && table.out().contains("[3,3,0]"), table.out());
        assertTrue(cli("show", "T0", "--diagram").out().contains("recv ping <- A"));
        assertTrue(cli("show", "T0", "--dot").out().startsWith("digraph chronos"));
        assertEquals(6, Canon.list(Canon.parseMap(cli("show", "T0", "--json").out()).get("events")).size());

        cli("run", "re", "--no-stop", "--checkpoint-every", "10");
        R ins = cli("inspect", "T1", "--goto", "violation", "--world", "--state", "b", "--why");
        assertEquals(0, ins.code(), ins.err());
        assertTrue(ins.out().contains("cursor") && ins.out().contains("node states") && ins.out().contains("\"role\":\"PRIMARY\"") && ins.out().contains("fault:partition-pb#1"), ins.out());
        assertTrue(cli("inspect", "T1", "--goto", "5", "--back", "2", "--step", "1").out().contains("cursor 4"));
        assertEquals(1, cli("inspect", "T1", "--goto", "9999").code());
        assertEquals(1, cli("inspect", "T9").code());

        R v = cli("verify", "T1");
        assertEquals(0, v.code());
        assertTrue(v.out().contains("REPLAY VERIFIED"));
        R vc = cli("verify", "T1", "--from-checkpoint", "26");
        assertEquals(0, vc.code());
        assertTrue(vc.out().contains("from position 27"), vc.out());
        // a tampered log is refused with exit code 2 and a clear message
        Path seg = tmp.resolve("data/timelines/T1/events/000000.log");
        List<String> lines = Files.readAllLines(seg);
        int victim = 4;
        while (lines.get(victim).startsWith("{\"commit\"")) victim++; // damage an event line, not a commit line
        lines.set(victim, lines.get(victim).replace("\"vt\":", "\"vt\":9"));
        Files.write(seg, lines);
        R tampered = cli("verify", "T1");
        assertEquals(2, tampered.code());
        assertTrue(tampered.err().contains("not usable"), tampered.err());
    }

    @Test
    void verifyFlagsTheBadCitizenWithExitCodeTwo() {
        cli("run", "bad", "--seed", "1");
        R v = cli("verify", "T0");
        assertEquals(2, v.code(), v.out());
        assertTrue(v.out().contains("DETERMINISM VIOLATION") && v.out().contains("mismatch at B#3"));
    }

    @Test
    void branchCompareCausesAndReport() throws Exception {
        cli("run", "re", "--no-stop");
        R b = cli("branch", "T0", "--heal-earlier", "200");
        assertEquals(0, b.code(), b.err());
        assertTrue(b.out().contains("VERDICT: FAILURE_DISAPPEARS") && b.out().contains("FIRST CAUSAL DIVERGENCE: iv:heal-early"), b.out());
        assertTrue(cli("ls").out().contains("T0.1") && cli("ls").out().contains("iv:heal-early"));
        R c = cli("compare", "T0", "T0.1");
        assertTrue(c.out().contains("explanation path") && c.out().contains("fault:partition-pb#1"), c.out());
        assertTrue(cli("compare", "T0", "T0", "--strict").out().contains("no divergence"));
        // other intervention flags
        R d = cli("branch", "T0", "--drop", "p#16", "--quiet");
        assertEquals(0, d.code(), d.err());
        assertTrue(d.out().contains("VERDICT:"));
        R bad = cli("branch", "T0", "--remove-fault", "no-such-rule");
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("no-such-rule"), bad.err());
        assertEquals(1, cli("branch", "T0", "--delay", "garbage").code());
        // the failed intervention left no half-written timeline behind that breaks ls
        assertEquals(0, cli("ls").code());

        Path file = tmp.resolve("out/report.html");
        R rep = cli("report", "T0", "--against", "T0.1", "--out", file.toString());
        assertEquals(0, rep.code(), rep.err());
        String html = Files.readString(file);
        assertTrue(html.startsWith("<!doctype html>") && html.contains("<svg") && html.contains("FAILURE_DISAPPEARS") && html.contains("iv:heal-early") && html.contains("VERIFIED"));
        assertFalse(HtmlReport.referencesExternalResources(html));
        assertTrue(html.contains("java -jar chronos-cli/target/chronos.jar"), "the regeneration command is documented in the report");
        // a report of a run without a violation
        cli("run", "re", "--param", "partition=off");
        String ok = cli("report", "T0.1").out();
        assertTrue(ok.contains("report written"));
    }

    @Test
    void causesListsBothPartitionsOfTheOverdeterminedScenario() {
        cli("run", "re2");
        R c = cli("causes", "T0");
        assertEquals(0, c.code(), c.err());
        assertTrue(c.out().contains("FAILURE_PERSISTS") && c.out().contains("MINIMAL CRITICAL SETS: [[partition-p-b1, partition-p-b2]]"), c.out());
    }

    @Test
    void knowledgeViewShowsWhatTheBackupHadNotHeardAndCutsAreConsistent() {
        cli("run", "re", "--no-stop");
        R k = cli("inspect", "T0", "--goto", "violation", "--knows");
        assertEquals(0, k.code(), k.err());
        assertTrue(k.out().contains("knowledge of ") && k.out().contains("believes:") && k.out().contains("actually:"), k.out());
        R promo = cli("inspect", "T0", "--knows", "b#12");
        assertTrue(promo.out().contains("about p: last heard") && promo.out().contains("of its events are unknown to b"), promo.out());
        R c = cli("inspect", "T0", "--cut", "b#12,client#3");
        assertTrue(c.out().contains("minimal consistent cut containing") && c.out().contains("consistent: true"), c.out());
        R s = cliIn("knows b#12\ncut b#12\nquit\n", "session", "T0");
        assertTrue(s.out().contains("knowledge of b") && s.out().contains("frontier"), s.out());
        assertNotEquals(0, cli("inspect", "T0", "--cut", "nope#1").code());
    }

    @Test
    void localReplayReExecutesEachNodeAloneFromItsRecordedInputs() {
        cli("run", "re", "--no-stop", "--fault", "skew:b:100:500:200");
        R all = cli("local-replay", "T0");
        assertEquals(0, all.code(), all.err());
        assertTrue(all.out().contains("NODE-LOCAL REPLAY of p on T0: OK") && all.out().contains("NODE-LOCAL REPLAY of b on T0: OK") && all.out().contains("outputs and every post-step state digest matched"), all.out());
        R one = cli("local-replay", "T0", "b");
        assertEquals(0, one.code());
        assertFalse(one.out().contains("NODE-LOCAL REPLAY of p"));
        cli("run", "bad", "--seed", "3");
        R bad = cli("local-replay", "T1");
        assertEquals(1, bad.code(), "hidden nondeterminism is a failing exit code");
        assertTrue(bad.out().contains("MISMATCH"), bad.out());
    }

    @Test
    void reindexReportsAndRepairsTheDerivedIndexFiles() throws Exception {
        cli("run", "re", "--no-stop");
        cli("branch", "T0", "--heal-earlier", "200");
        assertTrue(cli("reindex", "--check").out().contains("T0: indexes persisted") && cli("reindex", "--check").out().contains("T0.1: indexes persisted"));
        Files.delete(tmp.resolve("data/timelines/T0/index/main.idx"));
        // inspection still works without the file (the log is the only thing that must be right) and does not recreate it
        assertEquals(0, cli("inspect", "T0", "--goto", "violation", "--why").code());
        assertFalse(Files.exists(tmp.resolve("data/timelines/T0/index/main.idx")));
        R chk = cli("reindex", "--check");
        assertTrue(chk.out().contains("T0: indexes rebuilt") && chk.out().contains("T0.1: indexes rebuilt"), chk.out());
        R fix = cli("reindex", "T0");
        assertTrue(fix.out().contains("index file rewritten"), fix.out());
        assertTrue(cli("reindex", "--check").out().contains("T0: indexes persisted"));
        assertEquals(1, cli("reindex", "nope").code());
    }

    @Test
    void compressedRecordingsAreTheSameTimelineInASmallerFile() throws Exception {
        R plain = cli("run", "gossip", "--param", "nodes=5", "--param", "horizon=3000");
        R zipped = cli("run", "gossip", "--param", "nodes=5", "--param", "horizon=3000", "--compress");
        assertEquals(0, plain.code());
        assertEquals(0, zipped.code());
        assertTrue(Files.exists(tmp.resolve("data/timelines/T1/events/000000.log.gz")) && Files.exists(tmp.resolve("data/timelines/T0/events/000000.log")));
        assertTrue(Files.size(tmp.resolve("data/timelines/T1/events/000000.log.gz")) * 3 < Files.size(tmp.resolve("data/timelines/T0/events/000000.log")));
        assertEquals(0, cli("verify", "T1").code(), "a compressed timeline replays and verifies");
        assertEquals(cli("show", "T0").out().replace("T0", "T"), cli("show", "T1").out().replace("T1", "T"));
        R br = cli("branch", "T1", "--drop", "g0#5", "--compress");
        assertEquals(0, br.code(), br.err() + br.out());
        assertEquals(0, cli("verify", "T1.1").code());
    }

    @Test
    void aNonLocalFaultPlanIsSaidSoInEveryReport() throws Exception {
        Env env = new Env(tmp.resolve("data"));
        FaultPlan plan = FaultPlan.of(FaultRule.probabilistic("any-drop", FaultRule.FaultAction.DROP, "*", "*", "*", 100, null));
        env.record("re", 42, Map.of("partition", "off"), plan, Boolean.FALSE);
        env.record("re", 42, Map.of("partition", "off"), FaultPlan.empty(), Boolean.FALSE);
        String note = "fault rule(s) [any-drop] are flagged nonLocal: the locality law M8";
        assertTrue(cli("show", "T0").out().contains("NOTE: " + note), "show");
        assertFalse(cli("show", "T1").out().contains("nonLocal"), "a local plan carries no such note");
        R cmp = cli("compare", "T0", "T1");
        assertTrue(cmp.out().contains("WARNING: T0: " + note), cmp.out());
        R rep = cli("report", "T0", "--against", "T1", "--out", tmp.resolve("r.html").toString());
        assertEquals(0, rep.code(), rep.err());
        assertTrue(Files.readString(tmp.resolve("r.html")).contains("flagged nonLocal"), "the HTML report says so, in the replay and in the comparison sections");
    }

    @Test
    void injectAddsFaultsToARecordedRunAndReplayIsTheVerifier() {
        cli("run", "re", "--param", "partition=off", "--no-stop");
        R inj = cli("inject", "T0", "--fault", "partition:300:400:p|b");
        assertEquals(0, inj.code(), inj.err() + inj.out());
        assertTrue(inj.out().contains("recorded T1: re") && inj.out().contains("INVARIANT VIOLATED: single-primary"), inj.out());
        R rp = cli("replay", "T1");
        assertEquals(0, rp.code(), rp.err());
        assertEquals(cli("verify", "T1").out(), rp.out(), "replay is the verify command");
        assertEquals(1, cli("inject", "T0").code(), "inject without a fault is a usage error");
        cli("branch", "T1", "--heal-earlier", "200");
        R br = cli("inject", "T1.1", "--fault", "crash:b:100");
        assertEquals(1, br.code());
        assertTrue(br.err().contains("is a branch"), br.err());
    }

    @Test
    void whyNamesTheFaultBehindASilenceNoTimerNoticed() {
        cli("run", "tutorial", "--fault", "partition:100:200:sender|receiver", "--no-stop");
        R w = cli("inspect", "T0", "--goto", "violation", "--why");
        assertTrue(w.out().contains("possible silence") && w.out().contains("by fault:f1-partition#1 rule f1-partition"), w.out());
        TimeTravelSession s = new TimeTravelSession(new chronos.store.TimelineStore(tmp.resolve("data")), "T0");
        s.goToViolation();
        List<TimeTravelSession.Silence> si = s.silence(s.cursor());
        assertFalse(si.isEmpty());
        assertTrue(si.stream().allMatch(x -> x.node().equals("receiver") && "f1-partition".equals(x.faultRule())), si.toString());
        // the chain itself does not contain the fault: that is the point of the hint
        assertTrue(s.whyChains(s.cursor()).stream().noneMatch(ch -> s.graph().event(ch.get(0)).kind() == EventKind.FAULT_INJECTED));
        // a violation that a timer noticed keeps its certain chain, and a run without a violation has no silence section
        cli("run", "re", "--no-stop");
        assertTrue(cli("inspect", "T1", "--goto", "violation", "--why").out().contains("chain from fault:partition-pb#1"));
        cli("run", "tutorial");
        assertFalse(cli("inspect", "T2", "--why").out().contains("possible silence"));
    }

    @Test
    void searchRanksTheSingleChangesThatMakeTheFailureDisappear() {
        cli("run", "re");
        R r = cli("search", "T0", "--max", "40", "--threads", "2");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("INTERVENTION SEARCH on T0") && r.out().contains("CRITICAL") && r.out().contains("REMOVE_FAULT") && r.out().contains("partition-pb"), r.out());
        assertTrue(r.out().contains("earliest critical event: fault:partition-pb#1"), r.out());
        assertEquals("T0", cli("ls").out().lines().map(String::strip).filter(l -> l.startsWith("T0")).findFirst().orElse("").split(" ")[0], "no branches are left behind without --keep");
        assertFalse(cli("ls").out().contains("T0.1"));
        cli("run", "re", "--param", "partition=off");
        R none = cli("search", "T1");
        assertNotEquals(0, none.code());
        assertTrue(none.err().contains("has no invariant violation"), none.err());
    }

    @Test
    void sessionCommandsReadFromStdinAndGoingLiveCreatesABranch() {
        cli("run", "re", "--no-stop");
        String script = String.join("\n", "goto violation", "why", "back 3", "world", "find kind=timer_fired node=b back", "watch b.volatile.role", "inflight", "timers",
                "run-to kind=timer_fired node=b", "run-to time=500 node=p", "run-to text=nothing-like-this", "goto fault:partition-pb#1", "branch --heal-earlier 200 --quiet", "state b", "diff T0", "bogus", "quit", "");
        R r = cliIn(script, "session", "T0");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("> goto violation") && r.out().contains("chain from fault:partition-pb#1"), r.out());
        assertTrue(r.out().contains("VERDICT: FAILURE_DISAPPEARS") && r.out().contains("switched to T0.1"), r.out());
        assertTrue(r.out().contains("b#12") && r.out().contains("\"BACKUP\" -> \"PRIMARY\""), r.out());
        assertTrue(r.out().contains("error: unknown session command 'bogus'"));
        assertTrue(r.out().contains("breakpoint hit at b#12") && r.out().contains("equals the state reconstructed from the log") && !r.out().contains("MISMATCH"), r.out());
        assertTrue(r.out().contains("no event matched; the replay ran to the end"), r.out());
        assertTrue(r.out().contains("breakpoint hit at p#2"), "a breakpoint on virtual time: " + r.out());
        assertTrue(r.out().contains("diff T0.1@") && r.out().contains(" vs T0@"), "after going live the session is on the branch: " + r.out());
    }

    @Test
    void determinismAcrossFreshJvmLaunches() {
        R r = cli("determinism", "re", "--seed", "42", "--jvm", "2");
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("IDENTICAL") && r.out().contains("fresh JVM launch 1") && r.out().contains("fresh JVM launch 2") && r.out().contains("DETERMINISTIC"), r.out());
        assertEquals(3, r.out().lines().filter(l -> l.contains("hash")).count());
        String[] hashes = r.out().lines().filter(l -> l.contains("hash ")).map(l -> l.replaceAll(".*hash ([0-9a-f]{64}).*", "$1")).toArray(String[]::new);
        assertEquals(hashes[0], hashes[1]);
        assertEquals(hashes[0], hashes[2]);
        // a chaos plan too: the hash of a fault-injected run is stable across JVMs
        R g = cli("determinism", "gossip", "--seed", "3", "--param", "nodes=4", "--param", "horizon=300", "--jvm", "1");
        assertEquals(0, g.code(), g.out() + g.err());
    }

    @Test
    void campaignFindsFailingChaosSeedsAndKeepsThemAsTimelines() {
        R c = cli("campaign", "re", "--seeds", "1-40", "--chaos", "--param", "partition=off", "--threads", "3", "--keep", "--horizon", "900");
        assertEquals(0, c.code(), c.err());
        assertTrue(c.out().contains("40 runs"), c.out());
        long bad = c.out().lines().filter(l -> l.startsWith("  seed ")).count();
        assertTrue(bad >= 1, "a campaign over seeds finds a failing RE run: " + c.out());
        assertEquals(bad, c.out().lines().filter(l -> l.startsWith("recorded T")).count());
        // a kept failing run replays and explains itself
        assertEquals(0, cli("verify", "T0").code());
    }

    @Test
    void deleteOnlyLeafTimelines() {
        cli("run", "re", "--no-stop");
        cli("branch", "T0", "--heal-earlier", "200", "--quiet");
        R d = cli("delete", "T0");
        assertEquals(1, d.code());
        assertTrue(d.err().contains("child timelines"));
        assertEquals(0, cli("delete", "T0.1").code());
        assertEquals(0, cli("delete", "T0").code());
        assertEquals(1, cli("delete", "T0").code());
    }

    @Test
    void theFlagshipDemoPassesEveryCheck() {
        R r = cli("demo");
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("ALL PASSED"), r.out());
        for (String ad : List.of("AD-1", "AD-2", "AD-3", "AD-4", "AD-5", "AD-6")) assertTrue(r.out().contains(ad + "  "), ad);
        assertFalse(r.out().contains("[FAIL]"), r.out());
        assertTrue(Files.exists(tmp.resolve("data/reports")));
    }

    @Test
    void theWholeFaultAndInterventionCatalogueIsAvailableOnTheCommandLine() {
        R pause = cli("run", "re", "--param", "partition=off", "--fault", "pause:p:300:400", "--no-stop");
        assertEquals(0, pause.code(), pause.err());
        assertTrue(pause.out().contains("INVARIANT VIOLATED: single-primary") && pause.out().contains("fault:f1-pause#1") && pause.out().contains("OMISSION"), pause.out());
        R part = cli("run", "re", "--plan", "none", "--fault", "partition:300:400:p|b", "--no-stop");
        assertTrue(part.out().contains("INVARIANT VIOLATED") && part.out().contains("fault:f1-partition#1"), part.out());
        R reorder = cli("run", "ka2", "--param", "delay=off", "--fault", "reorder:p:b:Replicate:1");
        assertTrue(reorder.out().contains("replicas-converge") && reorder.out().contains("fault:f1-reorder#1"), reorder.out());
        R after = cli("run", "ka3", "--param", "crash=off", "--fault", "after:coord#3:crash:coord");
        assertTrue(after.out().contains("no-blocked-participant"), after.out());
        R slow = cli("run", "ka2", "--param", "delay=off", "--fault", "slow:p:b:5:0", "--no-stop");
        assertEquals(0, slow.code());
        assertTrue(slow.out().contains("fault coverage: {f1-slow=1}"), slow.out());
        R onState = cli("run", "re", "--param", "partition=off", "--fault", "state:p:volatile.hb=3:crash:p", "--no-stop");
        assertEquals(0, onState.code(), onState.err());
        assertTrue(onState.out().contains("fault coverage: {f1-state=1}"), onState.out());
        R skew = cli("run", "re", "--fault", "skew:b:100:500:200");
        assertEquals(0, skew.code(), skew.err());
        for (String bad : List.of("frobnicate:x", "pause:p:300", "crash:p:abc"))
            assertEquals(1, cli("run", "re", "--fault", bad).code(), bad);
        assertEquals(1, cli("run", "re", "--fault", "crash:ghost:5").code());

        // interventions on a healthy baseline (T? is the 'partition=off' run)
        cli("run", "re", "--param", "partition=off", "--no-stop");
        String healthy = "T" + (cli("ls").out().lines().filter(l -> l.matches("T\\d+ .*")).count() - 1);
        R p = cli("branch", healthy, "--pause", "p:310:400", "--quiet");
        assertEquals(0, p.code(), p.err());
        assertTrue(p.out().contains("VERDICT: DIFFERENT_FAILURE"), p.out());
        R ps = cli("branch", healthy, "--patch-state", "b:volatile.role=PRIMARY", "--at", "30", "--quiet");
        assertTrue(ps.out().contains("synthetic") && ps.out().contains("DIFFERENT_FAILURE"), ps.out());
        R cfg = cli("branch", healthy, "--config", "watchdog=100", "--at", "20", "--quiet");
        assertEquals(0, cfg.code(), cfg.err());
        R badInput = cli("branch", healthy, "--remove-input", "99");
        assertEquals(1, badInput.code());
        assertTrue(badInput.err().contains("no pending client input"), badInput.err());
        assertEquals(1, cli("branch", healthy, "--add-input", "client:300").code());
        assertEquals(1, cli("branch", healthy, "--pause", "p:300").code());
        R add = cli("branch", healthy, "--add-input", "client:700:{\"op\":\"write\",\"key\":\"q\",\"value\":5}", "--quiet");
        assertEquals(0, add.code(), add.err());
    }
}
