package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.StoreChecker.Status;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code chronos check}: a healthy store passes every check, and each kind of damage is reported with the right status, the right
 * check name and an action, without an exception, while the undamaged timelines keep reporting OK.
 */
class StoreCheckerTest {
    @TempDir
    Path tmp;

    private final TestRegistry reg = new TestRegistry();

    /** A store with a failing root T0, its branch T0.1 and a second root T1 (gossip). */
    private Path newStore(String name) throws IOException {
        Path root = tmp.resolve(name);
        Env env = new Env(root);
        env.store.compressNewTimelines(false);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, env.events(t0).size() - 1);
        env.manager.branch(t0, at, List.of(Intervention.removeFault("i1", "partition-pb")), null, null);
        env.record("gossip", 3, Map.of("nodes", "3", "horizon", "200"));
        return root;
    }

    private Path copy(Path from, String name) throws IOException {
        Path to = tmp.resolve(name);
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        return to;
    }

    private StoreChecker.Report check(Path root, String... only) {
        return new StoreChecker(new TimelineStore(root), reg).check(List.of(only), true);
    }

    private static StoreChecker.Finding find(StoreChecker.Report r, String timeline, String check) {
        return r.findings().stream().filter(f -> Objects.equals(timeline, f.timeline()) && f.check().equals(check)).findFirst().orElseThrow(() -> new AssertionError("no " + check + " finding for " + timeline + " in " + r.text()));
    }

    private static Path log(Path root, String tl) throws IOException {
        try (Stream<Path> s = Files.list(root.resolve("timelines").resolve(tl).resolve("events"))) {
            return s.sorted().toList().get(0);
        }
    }

    @Test
    void aHealthyStorePassesEveryCheckIncludingReplayAndABranchCarriesAHashOfItsOwnEvents() throws IOException {
        Path root = newStore("ok");
        StoreChecker.Report r = check(root);
        assertTrue(r.ok() && r.count(Status.WARN) == 0, r.text());
        assertEquals(List.of("T0", "T0.1", "T1"), r.timelines());
        for (String t : r.timelines()) for (String c : List.of("header", "catalogue", "log", "ids", "summary", "clocks", "index", "checkpoints", "replay"))
            assertEquals(Status.OK, find(r, t, c).status(), t + " " + c);
        assertTrue(r.text().startsWith("T0  OK") && r.text().contains("STORE OK: 3 timelines, 0 warnings, 0 failures, every finished timeline replayed"), r.text());
        // DEV-49: the branch's summary hash is the hash of the events it recorded, not the hash of empty input
        TimelineStore s = new TimelineStore(root);
        String own = LogHash.of(s.events("T0.1").subList((int) s.header("T0.1").branchSeq() + 1, s.events("T0.1").size()));
        assertEquals(own, s.summary("T0.1").logHash());
        assertNotEquals(StoreCheckerEmptyHash.VALUE, s.summary("T0.1").logHash());
    }

    /** The SHA-256 of empty input. */
    private static final class StoreCheckerEmptyHash {
        static final String VALUE = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }

    @Test
    void theJsonFormIsCanonicalDeterministicAndConsistentWithTheText() throws IOException {
        Path root = newStore("json");
        String a = check(root).json(), b = check(root).json();
        assertEquals(a, b);
        Map<String, Object> m = Canon.parseMap(a);
        assertEquals(Boolean.TRUE, m.get("ok"));
        Map<String, Object> sum = Canon.map(m.get("summary"));
        assertEquals(3L, Canon.num(sum.get("timelines")));
        assertEquals(0L, Canon.num(sum.get("failures")));
        assertEquals(Canon.list(m.get("findings")).size(), Canon.num(sum.get("ok")) + Canon.num(sum.get("warnings")) + Canon.num(sum.get("failures")));
        assertEquals(Canon.json(m), a, "already canonical");
    }

    @Test
    void aFlippedByteInALogFailsThatTimelineOnlyAndNamesTheDamagedRecord() throws IOException {
        Path root = copy(newStore("src1"), "flip");
        Path f = log(root, "T1");
        byte[] b = Files.readAllBytes(f);
        b[b.length / 3] ^= 0x20;
        Files.write(f, b);
        StoreChecker.Report r = check(root);
        assertFalse(r.ok());
        assertEquals(Status.FAIL, find(r, "T1", "log").status());
        assertNotNull(find(r, "T1", "log").action());
        assertEquals(Status.OK, find(r, "T0", "log").status(), "the other timelines are still reported");
        assertEquals(Status.OK, find(r, "T0.1", "replay").status());
        assertTrue(r.text().contains("STORE DAMAGED"), r.text());
    }

    @Test
    void anInterruptedWriteIsAWarningNotAFailureAndTheDurableHistoryIsKept() throws IOException {
        Path root = copy(newStore("src2"), "torn");
        Files.writeString(log(root, "T0"), "{\"id\":\"p#99\",\"kind\":\"TIMER_SE", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        StoreChecker.Report r = check(root, "T0");
        assertTrue(r.ok(), r.text());
        StoreChecker.Finding tail = find(r, "T0", "tail");
        assertEquals(Status.WARN, tail.status());
        assertTrue(tail.message().contains("interrupted write") && tail.action().contains("nothing to repair"), tail.toString());
        assertEquals(Status.OK, find(r, "T0", "replay").status());
    }

    @Test
    void aMissingIndexAndADamagedCheckpointAreWarningsWithTheRepairNamed() throws IOException {
        Path root = copy(newStore("src3"), "idx");
        Files.delete(root.resolve("timelines/T1/index/main.idx"));
        Path ckpt;
        try (Stream<Path> s = Files.list(root.resolve("timelines/T0/checkpoints"))) {
            ckpt = s.sorted().toList().get(1);
        }
        Files.writeString(ckpt, "garbage");
        StoreChecker.Report r = check(root);
        assertTrue(r.ok(), r.text());
        assertEquals(Status.WARN, find(r, "T1", "index").status());
        assertTrue(find(r, "T1", "index").action().contains("chronos reindex T1"));
        assertEquals(Status.WARN, find(r, "T0", "checkpoints").status());
        // the documented repair works
        new TimelineStore(root).rebuildIndex("T1");
        assertEquals(Status.OK, find(check(root, "T1"), "T1", "index").status());
    }

    @Test
    void summaryProblemsAreDistinguishedUnfinishedTruncatedEditedAndTheKnownLegacyBranchHash() throws IOException {
        Path base = newStore("src4");
        Path unfinished = copy(base, "nosum");
        Files.delete(unfinished.resolve("timelines/T1/summary.json"));
        StoreChecker.Report r = check(unfinished, "T1");
        assertTrue(r.ok(), r.text());
        assertEquals(Status.WARN, find(r, "T1", "summary").status());
        assertNull(r.findings().stream().filter(f -> f.check().equals("replay")).findFirst().orElse(null), "an unfinished run is not replayed to its end");

        Path edited = copy(base, "editedsum");
        Path sp = edited.resolve("timelines/T1/summary.json");
        Files.writeString(sp, Files.readString(sp).replaceAll("\"eventCount\":\\d+", "\"eventCount\":5"));
        assertEquals(Status.FAIL, find(check(edited, "T1"), "T1", "summary").status());

        Path legacy = copy(base, "legacy");
        Path lp = legacy.resolve("timelines/T0.1/summary.json");
        Files.writeString(lp, Files.readString(lp).replaceAll("\"logHash\":\"[0-9a-f]+\"", "\"logHash\":\"" + StoreCheckerEmptyHash.VALUE + "\""));
        StoreChecker.Report lr = check(legacy, "T0.1");
        assertTrue(lr.ok(), lr.text());
        assertEquals(Status.WARN, find(lr, "T0.1", "summary").status());
        // the same value on a root, where it cannot be explained, is a failure
        Path rootBad = copy(base, "rootbad");
        Path rp = rootBad.resolve("timelines/T0/summary.json");
        Files.writeString(rp, Files.readString(rp).replaceAll("\"logHash\":\"[0-9a-f]+\"", "\"logHash\":\"" + StoreCheckerEmptyHash.VALUE + "\""));
        assertEquals(Status.FAIL, find(check(rootBad, "T0"), "T0", "summary").status());
    }

    @Test
    void aTamperedEventWithAValidCommitChecksumIsCaughtByTheSummaryHashAndByReplay() throws IOException {
        Path root = copy(newStore("src5"), "tamper");
        Path f = log(root, "T1");
        List<String> out = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        boolean edited = false;
        for (String line : Files.readAllLines(f)) {
            if (line.startsWith("{\"commit\":")) {
                out.add(line.replaceAll("\"h\":\"[0-9a-f]+\"", "\"h\":\"" + Canon.sha256(group.toString()) + "\""));
                group.setLength(0);
                continue;
            }
            if (!edited && line.contains("\"kind\":\"TIMER_SET\"")) {
                Matcher m = Pattern.compile("\"due\":(\\d+)").matcher(line);
                assertTrue(m.find());
                line = line.substring(0, m.start(1)) + (Long.parseLong(m.group(1)) + 7) + line.substring(m.end(1));
                edited = true;
            }
            out.add(line);
            group.append(line).append('\n');
        }
        assertTrue(edited);
        Files.write(f, out);
        StoreChecker.Report r = check(root, "T1");
        assertEquals(Status.OK, find(r, "T1", "log").status(), "the checksums were recomputed, so the log itself looks fine");
        assertEquals(Status.FAIL, find(r, "T1", "summary").status());
        assertEquals(Status.FAIL, find(r, "T1", "replay").status());
        assertFalse(r.ok());
        assertTrue(r.text().contains("1 timeline(s) do not replay deterministically") && !r.text().contains("every finished timeline replayed"), r.text());
        assertTrue(r.text().contains("STORE DAMAGED"), "a tampered log is damage, not just a replay failure");
        assertTrue(find(r, "T1", "replay").action().contains("not deterministic"), "the action names the real causes, not 'restore the log'");
    }

    @Test
    void headerCatalogueAndLineageDamageAreReportedWithoutAnExceptionAndWithoutHidingOtherTimelines() throws IOException {
        Path base = newStore("src6");
        Path h = copy(base, "hdr");
        Files.writeString(h.resolve("timelines/T1/header.json"), "{ not json");
        StoreChecker.Report hr = check(h);
        assertEquals(Status.FAIL, find(hr, "T1", "header").status());
        assertEquals(Status.OK, find(hr, "T0", "replay").status());

        Path c = copy(base, "cat");
        Files.writeString(c.resolve("catalog.json"), "garbage");
        StoreChecker.Report cr = check(c);
        assertTrue(cr.findings().stream().anyMatch(f -> f.timeline() == null && f.check().equals("catalogue") && f.status() == Status.FAIL), cr.text());
        assertEquals(Status.OK, find(cr, "T0", "log").status());

        Path p = copy(base, "orphan");
        deleteTree(p.resolve("timelines/T0"));
        StoreChecker.Report pr = check(p);
        assertEquals(Status.FAIL, find(pr, "T0.1", "lineage").status());
        assertTrue(find(pr, "T0.1", "lineage").action().contains("restore T0"), find(pr, "T0.1", "lineage").toString());
        assertTrue(pr.findings().stream().anyMatch(f -> f.timeline() == null && f.message().contains("T0 is in the catalogue")), pr.text());

        StoreChecker.Report none = check(base, "T77");
        assertEquals(Status.FAIL, find(none, "T77", "exists").status());
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> w = Files.walk(p)) {
            for (Path x : (Iterable<Path>) w.sorted(Comparator.reverseOrder())::iterator) Files.delete(x);
        }
    }

    // ---- command line ------------------------------------------------------------------------------------------------------------------

    private record R(int code, String out, String err) {}

    private R cli(Path data, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--data";
        full[1] = data.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = Main.run(full, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        return new R(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommandReportsExitCodesForOkWarningStrictAndDamage() throws IOException {
        Path base = newStore("src7");
        R ok = cli(base, "check");
        assertEquals(0, ok.code(), ok.out());
        assertTrue(ok.out().contains("STORE OK: 3 timelines"), ok.out());
        assertEquals(0, cli(base, "check", "T0", "T1", "--no-replay").code());
        assertTrue(cli(base, "check", "T0", "--no-replay").out().contains("replay not run"));
        R js = cli(base, "check", "--json");
        assertEquals(0, js.code());
        assertEquals(Boolean.TRUE, Canon.parseMap(js.out()).get("ok"));

        Path warn = copy(base, "w");
        Files.delete(warn.resolve("timelines/T1/index/main.idx"));
        assertEquals(0, cli(warn, "check").code(), "warnings alone do not fail the check");
        R strict = cli(warn, "check", "--strict");
        assertEquals(2, strict.code());
        assertTrue(strict.out().contains("action: run `chronos reindex T1`"), strict.out());
        assertEquals(0, cli(warn, "reindex", "T1").code());
        assertEquals(0, cli(warn, "check", "--strict").code());

        Path bad = copy(base, "b");
        byte[] b = Files.readAllBytes(log(bad, "T1"));
        b[b.length / 2] ^= 0x10;
        Files.write(log(bad, "T1"), b);
        R damaged = cli(bad, "check");
        assertEquals(2, damaged.code());
        assertTrue(damaged.out().contains("STORE DAMAGED") && damaged.out().contains("FAIL log"), damaged.out());
        assertEquals(Boolean.FALSE, Canon.parseMap(cli(bad, "check", "--json").out()).get("ok"));
    }
}
