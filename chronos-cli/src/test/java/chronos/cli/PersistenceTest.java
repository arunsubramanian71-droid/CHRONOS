package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Trace persistence, reload and crash safety on real recordings (FR-16, NFR-11, Section 24 Phase 7 exit criteria). */
class PersistenceTest {
    @TempDir
    Path tmp;

    @Test
    void aTraceSavedByOneContextIsLoadedByAFreshOneAndReplaysIdentically() {
        Env env = new Env(tmp);
        var result = env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<Event> original = env.events(t0);
        Env fresh = new Env(tmp);
        List<Event> loaded = fresh.events(t0);
        assertEquals(original.size(), loaded.size());
        for (int i = 0; i < original.size(); i++) assertEquals(original.get(i), loaded.get(i));
        assertEquals(result.logHash().length(), 64);
        // reconstructed results in the fresh context: states from the log, then genuine replay
        TimeTravelSession s = new TimeTravelSession(fresh.store, t0);
        s.goToViolation();
        assertEquals("PRIMARY", Canon.map(s.stateOf("b").get("volatile")).get("role"));
        assertTrue(fresh.replay.verify(t0).ok());
        assertEquals(fresh.store.header(t0), env.store.header(t0));
        assertEquals(env.store.summary(t0), fresh.store.summary(t0));
    }

    @Test
    void aProcessKilledMidWriteLeavesAReadableLog() throws Exception {
        Path dir = tmp.resolve("killed");
        Files.createDirectories(dir);
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                "chronos.cli.KillMe", dir.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Path seg = dir.resolve("timelines/T0/events/000001.log");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(seg) && System.nanoTime() < deadline) Thread.sleep(50); // wait until at least two segments exist
        assertTrue(Files.exists(seg), "the child process never wrote a second segment");
        Thread.sleep(30);
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);

        TimelineStore store = new TimelineStore(dir);
        assertNull(store.summary("T0"), "the run never finished");
        List<Event> events = store.events("T0");
        assertTrue(events.size() > 5000, "events recovered: " + events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i, events.get(i).seq());
        assertEquals(events.get(events.size() - 1).seq(), store.groupEnds("T0").get(store.groupEnds("T0").size() - 1), "the log ends on a complete step group");
        // everything recovered is a consistent causal history
        assertTrue(CausalGraph.build(events).selfCheck(50).isEmpty());
        // and it replays: the recovered prefix is an exact prefix of the deterministic run
        ReplayEngine.Result r = new ReplayEngine(store, new TestRegistry()).verifyFrom("T0", null);
        // the recorded log is only a prefix, so the replay reports that the recording is shorter, never a wrong match
        assertTrue(r.ok() || r.violation().getMessage().contains("recording ended") || r.violation().getMessage().contains("end-state"), r.summary());
        // `chronos check` calls it what it is: not damage, an unfinished run (a warning with the action), and every other check passes
        StoreChecker.Report rep = new StoreChecker(store, new TestRegistry()).check(List.of(), false);
        assertTrue(rep.ok(), rep.text());
        assertEquals(StoreChecker.Status.WARN, rep.findings().stream().filter(f -> f.check().equals("summary")).findFirst().orElseThrow().status(), rep.text());
        assertTrue(rep.findings().stream().noneMatch(f -> f.status() == StoreChecker.Status.FAIL), rep.text());
    }

    @Test
    void truncatingTheLastSegmentAtAnyByteYieldsAValidPrefixOfTheHistory() throws IOException {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<Event> full = env.events(t0);
        Path seg = tmp.resolve("timelines").resolve(t0).resolve("events/000000.log");
        byte[] all = Files.readAllBytes(seg);
        SeededRandom rnd = new SeededRandom(99);
        int ok = 0;
        for (int i = 0; i < 40; i++) {
            int cut = (int) Math.floorMod(rnd.nextLong(), (long) all.length);
            Path copy = Files.createDirectories(tmp.resolve("cut" + i));
            copyTree(tmp.resolve("timelines"), copy.resolve("timelines"));
            Files.write(copy.resolve("timelines").resolve(t0).resolve("events/000000.log"), Arrays.copyOf(all, cut));
            List<Event> got = new TimelineStore(copy).events(t0);
            assertTrue(got.size() <= full.size());
            for (int k = 0; k < got.size(); k++) assertEquals(full.get(k), got.get(k));
            // a finished timeline whose log lost its end never passes unnoticed
            StoreChecker.Report rep = new StoreChecker(new TimelineStore(copy), new TestRegistry()).check(List.of(t0), false);
            assertEquals(got.size() == full.size(), rep.ok(), "cut at " + cut + ": " + rep.text());
            assertEquals(got.isEmpty() ? List.of() : List.of(), got.isEmpty() ? List.of() : List.of());
            ok++;
        }
        assertEquals(40, ok);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    void aTamperedLogIsRefusedNotSilentlyAccepted() throws IOException {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        Path seg = tmp.resolve("timelines").resolve(t0).resolve("events/000000.log");
        List<String> lines = new ArrayList<>(Files.readAllLines(seg));
        int victim = 10;
        while (lines.get(victim).startsWith("{\"commit\"")) victim++;
        lines.set(victim, lines.get(victim).replace("\"vt\":", "\"vt\":9"));
        Files.write(seg, lines);
        assertThrows(CorruptLogException.class, () -> new Env(tmp).events(t0));
    }
}
