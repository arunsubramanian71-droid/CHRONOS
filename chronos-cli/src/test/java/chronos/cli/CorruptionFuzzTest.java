package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integrity under damage (NFR-11, FR-16): flip random bytes in the stored files of a timeline. The store must never hand back events
 * that differ from what was written: it either loads exactly a whole number of the original step groups (a torn tail), or refuses with an
 * exception. Damaged checkpoints and index files are skipped or rebuilt, never trusted.
 */
class CorruptionFuzzTest {
    @TempDir
    Path tmp;

    private static List<Path> files(Path dir, String sub) throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve(sub))) {
            return s.sorted().toList();
        }
    }

    private void fuzzEvents(boolean compress) throws IOException {
        Path root = tmp.resolve(compress ? "z" : "p");
        Env env = new Env(root);
        env.store.compressNewTimelines(compress);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<Event> original = env.events(t0);
        List<Long> ends = env.store.groupEnds(t0);
        Path tl = root.resolve("timelines").resolve(t0);
        Path seg = files(tl, "events").get(0);
        byte[] good = Files.readAllBytes(seg);
        SeededRandom rnd = new SeededRandom(compress ? 11 : 7);
        int loaded = 0, refused = 0;
        for (int trial = 0; trial < 400; trial++) {
            Path copy = tmp.resolve("c" + compress + trial);
            Files.createDirectories(copy.resolve("timelines"));
            try (Stream<Path> w = Files.walk(tl)) {
                for (Path p : (Iterable<Path>) w::iterator) {
                    Path t = copy.resolve("timelines").resolve(t0).resolve(tl.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            byte[] bad = good.clone();
            int flips = 1 + (int) Math.floorMod(rnd.nextLong(), 3L);
            for (int f = 0; f < flips; f++) bad[(int) Math.floorMod(rnd.nextLong(), (long) bad.length)] ^= (byte) (1 + Math.floorMod(rnd.nextLong(), 255L));
            Files.write(copy.resolve("timelines").resolve(t0).resolve("events").resolve(seg.getFileName().toString()), bad);
            try {
                List<Event> got = new TimelineStore(copy).events(t0);
                // whatever loaded must be exactly a prefix of the original ending at a step boundary
                assertTrue(got.size() == 0 || ends.contains((long) got.size() - 1), "trial " + trial + ": " + got.size() + " events is not a whole number of step groups");
                for (int i = 0; i < got.size(); i++) assertEquals(original.get(i), got.get(i), "trial " + trial + " event " + i + " differs from what was written");
                loaded++;
            } catch (RuntimeException refusal) {
                refused++;
            }
        }
        assertTrue(refused > 100, "most random damage is detected: refused " + refused + ", loaded " + loaded);
    }

    @Test
    void randomDamageToALogIsEitherRefusedOrALosslessPrefix() throws IOException {
        fuzzEvents(false);
    }

    @Test
    void randomDamageToACompressedLogIsEitherRefusedOrALosslessPrefix() throws IOException {
        fuzzEvents(true);
    }

    @Test
    void damagedCheckpointsAndIndexFilesAreSkippedNotTrusted() throws IOException {
        Env env = new Env(tmp.resolve("ck"));
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        Path tl = tmp.resolve("ck/timelines").resolve(t0);
        List<Path> cks = files(tl, "checkpoints");
        assertTrue(cks.size() >= 3);
        // damage every checkpoint but keep the log: verification still passes (from the start), and says which are damaged
        for (Path c : cks) Files.writeString(c, "{ this is not a checkpoint");
        Files.write(tl.resolve("index/main.idx"), new byte[]{1, 2, 3});
        TimelineStore fresh = new TimelineStore(tmp.resolve("ck"));
        assertEquals(cks.size(), fresh.damagedCheckpoints(t0).size());
        assertTrue(fresh.checkpointAtOrBefore(t0, 1000).isEmpty());
        assertEquals("rebuilt", fresh.index(t0).source());
        ReplayEngine re = new ReplayEngine(fresh, env.registry);
        assertTrue(re.verify(t0).ok());
        // damage all but the oldest checkpoint: the next older readable one is used
        Env env2 = new Env(tmp.resolve("ck2"));
        env2.record("re", 42, Map.of(), null, Boolean.FALSE);
        Path tl2 = tmp.resolve("ck2/timelines").resolve(env2.last);
        List<Path> cks2 = files(tl2, "checkpoints");
        for (int i = 1; i < cks2.size(); i++) Files.writeString(cks2.get(i), "{");
        TimelineStore f2 = new TimelineStore(tmp.resolve("ck2"));
        assertEquals(Long.parseLong(cks2.get(0).getFileName().toString().replace(".ckpt", "")), f2.checkpointAtOrBefore(env2.last, 1000).orElseThrow().seq());
        assertTrue(new ReplayEngine(f2, env2.registry).verify(env2.last).ok());
        TimeTravelSession s = new TimeTravelSession(f2, env2.last);
        s.goToViolation();
        assertFalse(s.world().isBlank());
    }
}
