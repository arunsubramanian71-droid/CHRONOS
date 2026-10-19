package chronos.store;

import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TimelineStoreTest {
    @TempDir
    Path tmp;

    static TimelineHeader header(String id, String parent, long branchSeq) {
        return new TimelineHeader(id, parent, branchSeq, "fake", Map.of("k", "v"), 42, List.of("a", "b"), FaultPlan.empty(),
                List.of(), 1000, true, Formats.FORMAT_VERSION, "1", "fp", null);
    }

    static Event ev(String tl, long seq, String node, long vt) {
        return new Event(node + "#" + seq, seq, tl, node, EventKind.APP_NOTE, vt, seq + 1, List.of((int) seq, 0, 0), List.of(),
                Map.of("text", "e" + seq), "d" + seq, null, null);
    }

    static TimelineSummary summary(long n) {
        return new TimelineSummary(TimelineStatus.COMPLETED, n, n - 1, 99, "end", null, null, Map.of("r", 1L), "hash");
    }

    /** writes groups of the given sizes starting at seq 'first'; returns the last seq */
    static long write(TimelineStore store, TimelineHeader h, long first, int segSize, int... groupSizes) {
        TimelineWriter w = store.openWriter(h, segSize);
        long seq = first;
        for (int g : groupSizes) {
            List<Event> group = new ArrayList<>();
            for (int i = 0; i < g; i++) group.add(ev(h.timelineId(), seq++, "a", seq));
            w.append(group);
        }
        w.finish(summary(seq));
        w.close();
        return seq - 1;
    }

    @Test
    void saveThenLoadIntoAFreshStoreRoundTrips() {
        TimelineStore store = new TimelineStore(tmp);
        TimelineHeader h = header("T0", null, -1);
        TimelineWriter w = store.openWriter(h, 3);
        long seq = 0;
        for (int g : new int[]{2, 3, 1, 4}) {
            List<Event> group = new ArrayList<>();
            for (int i = 0; i < g; i++) group.add(ev("T0", seq++, "a", seq));
            w.append(group);
        }
        w.checkpoint(new WorldImage("T0", 4, Map.of("time", 5, "nested", List.of(1, 2))));
        w.finish(summary(seq));
        w.close();

        TimelineStore fresh = new TimelineStore(tmp);
        assertEquals(List.of("T0"), fresh.timelineIds());
        assertEquals(h, fresh.header("T0"));
        List<Event> events = fresh.events("T0");
        assertEquals(10, events.size());
        for (int i = 0; i < 10; i++) assertEquals(ev("T0", i, "a", i + 1), events.get(i));
        assertEquals(List.of(1L, 4L, 5L, 9L), fresh.groupEnds("T0"));
        assertEquals(TimelineStatus.COMPLETED, fresh.summary("T0").status());
        assertEquals(4, fresh.checkpointAtOrBefore("T0", 7).orElseThrow().seq());
        assertTrue(fresh.checkpointAtOrBefore("T0", 3).isEmpty());
        // segments roll after a group once the size is reached: 2+3 | 1+4
        try (Stream<Path> s = Files.list(tmp.resolve("timelines/T0/events"))) {
            assertEquals(2, s.count());
        } catch (IOException e) { fail(e); }
        // indexes are derived
        TimelineIndex ix = fresh.index("T0");
        assertEquals(10, ix.positionsOfNode("a").length);
        assertEquals(0, ix.positionsOfKind(EventKind.TIMER_FIRED).length);
        assertEquals(3, ix.firstAtOrAfter(4));
        assertEquals(ix.positionOf("a#6"), 6);
        // the catalogue
        assertTrue(fresh.timelineIds().contains("T0"));
    }

    @Test
    void anUnfinishedStepAtTheTailIsDiscardedNotPropagated_NFR11() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 2);
        Path seg = tmp.resolve("timelines/T0/events/000000.log");
        // simulate a crash in the middle of the next step: two event lines, one of them cut in half, and no commit line
        String torn = ev("T0", 4, "a", 9).toLine() + "\n" + ev("T0", 5, "a", 9).toLine().substring(0, 20);
        Files.writeString(seg, Files.readString(seg) + torn, StandardCharsets.UTF_8);
        TimelineStore fresh = new TimelineStore(tmp);
        assertEquals(4, fresh.events("T0").size());
        assertEquals(List.of(1L, 3L), fresh.groupEnds("T0"));
        assertEquals(2, fresh.discardedTailLines("T0"));
    }

    @Test
    void aWholeUncommittedGroupAtTheTailIsDiscarded() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 3);
        Path seg = tmp.resolve("timelines/T0/events/000000.log");
        Files.writeString(seg, Files.readString(seg) + ev("T0", 3, "a", 9).toLine() + "\n" + ev("T0", 4, "a", 9).toLine() + "\n");
        assertEquals(3, new TimelineStore(tmp).events("T0").size());
    }

    @Test
    void damageInsideCommittedHistoryIsRefusedWithAClearMessage() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 2);
        Path seg = tmp.resolve("timelines/T0/events/000000.log");
        List<String> lines = new ArrayList<>(Files.readAllLines(seg));
        // flip one payload: the line still parses, so only the checksum can tell
        lines.set(1, lines.get(1).replace("e1", "eX"));
        Files.write(seg, lines);
        CorruptLogException ex = assertThrows(CorruptLogException.class, () -> new TimelineStore(tmp).events("T0"));
        assertTrue(ex.getMessage().contains("checksum"), ex.getMessage());
    }

    @Test
    void garbageBeforeACommitIsCorruptionNotATornTail() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 2);
        Path seg = tmp.resolve("timelines/T0/events/000000.log");
        List<String> lines = new ArrayList<>(Files.readAllLines(seg));
        lines.set(1, "{not json");
        Files.write(seg, lines);
        assertThrows(CorruptLogException.class, () -> new TimelineStore(tmp).events("T0"));
    }

    @Test
    void positionGapsAndWrongTimelineAreRefused() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2);
        Path seg = tmp.resolve("timelines/T0/events/000000.log");
        Files.delete(seg);
        // hand-write a segment whose positions skip 1
        String l0 = ev("T0", 0, "a", 1).toLine() + "\n";
        String l2 = ev("T0", 2, "a", 1).toLine() + "\n";
        Files.writeString(seg, l0 + commit(0, l0) + l2 + commit(2, l2));
        assertThrows(CorruptLogException.class, () -> new TimelineStore(tmp).events("T0"));
    }

    private static String commit(long lastSeq, String lines) {
        int n = (int) lines.chars().filter(c -> c == '\n').count();
        return Canon.json(Map.of("commit", lastSeq, "h", Canon.sha256(lines), "n", n)) + "\n";
    }

    @Test
    void aLogWithAnUnknownFormatVersionIsRefused_NFR18() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2);
        Path h = tmp.resolve("timelines/T0/header.json");
        Files.writeString(h, Files.readString(h).replace("\"formatVersion\":1", "\"formatVersion\":7"));
        TimelineHeader.UnsupportedFormatException ex = assertThrows(TimelineHeader.UnsupportedFormatException.class, () -> new TimelineStore(tmp).header("T0"));
        assertTrue(ex.getMessage().contains("7"));
    }

    @Test
    void unknownTimelineIsReported() {
        assertThrows(UnknownTimelineException.class, () -> new TimelineStore(tmp).header("nope"));
        assertThrows(UnknownTimelineException.class, () -> new TimelineStore(tmp).events("nope"));
    }

    @Test
    void aBranchResolvesToItsParentsPrefixPlusItsOwnEventsAndStoresOnlyTheSuffix() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 3, 2, 3); // groups end at 1,4,6,9
        write(store, header("T0.1", "T0", 4), 5, 100, 2, 2);      // branches at 4, own events 5..8
        TimelineStore fresh = new TimelineStore(tmp);
        List<Event> resolved = fresh.events("T0.1");
        assertEquals(9, resolved.size());
        for (int i = 0; i <= 4; i++) assertEquals("T0", resolved.get(i).timelineId(), "the prefix is read from the parent, not copied");
        for (int i = 0; i <= 4; i++) assertEquals(fresh.events("T0").get(i), resolved.get(i), "shared prefix identical");
        for (int i = 5; i <= 8; i++) assertEquals("T0.1", resolved.get(i).timelineId());
        assertEquals(List.of(1L, 4L, 6L, 8L), fresh.groupEnds("T0.1"));
        assertEquals(List.of(new TimelineStore.Segment("T0", 0, 4), new TimelineStore.Segment("T0.1", 5, 8)), fresh.segments("T0.1"));
        // the branch's own directory holds only the suffix: no copy of the parent
        long ownLines = Files.readAllLines(tmp.resolve("timelines/T0.1/events/000000.log")).stream().filter(l -> !l.startsWith("{\"commit\"")).count();
        assertEquals(4, ownLines);
        assertEquals(List.of("T0.1"), fresh.children("T0"));
    }

    @Test
    void aBranchPointThatIsNotAStepBoundaryIsRefused() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 3);       // boundaries 1, 4
        write(store, header("T0.1", "T0", 2), 3, 100, 2);
        assertThrows(CorruptLogException.class, () -> new TimelineStore(tmp).groupEnds("T0.1"));
    }

    @Test
    void onlyLeafTimelinesCanBeDeleted_FR30() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 2, 3);
        write(store, header("T0.1", "T0", 1), 2, 100, 2);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.delete("T0"));
        assertTrue(ex.getMessage().contains("T0.1"));
        store.delete("T0.1");
        assertEquals(List.of("T0"), store.timelineIds());
        store.delete("T0");
        assertTrue(store.timelineIds().isEmpty());
        assertThrows(UnknownTimelineException.class, () -> store.delete("T0"));
    }

    @Test
    void readingNeverModifiesAnyFile_NFR04() throws Exception {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 3, 2, 3, 2);
        write(store, header("T0.1", "T0", 4), 5, 3, 2);
        Map<String, String> before = hashes();
        TimelineStore fresh = new TimelineStore(tmp);
        fresh.events("T0.1");
        fresh.groupEnds("T0.1");
        fresh.checkpointAtOrBefore("T0.1", 100);
        fresh.index("T0.1");
        fresh.segments("T0.1");
        assertEquals(before, hashes());
    }

    private Map<String, String> hashes() throws IOException, java.security.NoSuchAlgorithmException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
                m.put(tmp.relativize(p).toString(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }
        return m;
    }

    @Test
    void theBoundedQueueKeepsOrderUnderManyGroups() {
        TimelineStore store = new TimelineStore(tmp);
        TimelineWriter w = store.openWriter(header("T0", null, -1), 1000);
        long seq = 0;
        for (int g = 0; g < 5000; g++) {
            List<Event> group = List.of(ev("T0", seq++, "a", g), ev("T0", seq++, "b", g));
            w.append(group);
        }
        w.finish(summary(seq));
        w.close();
        List<Event> events = new TimelineStore(tmp).events("T0");
        assertEquals(10000, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i, events.get(i).seq());
    }

    @Test
    void creatingAnExistingTimelineOrABranchOfAnUnknownParentFails() {
        TimelineStore store = new TimelineStore(tmp);
        write(store, header("T0", null, -1), 0, 100, 1);
        assertThrows(IllegalStateException.class, () -> store.openWriter(header("T0", null, -1)));
        assertThrows(UnknownTimelineException.class, () -> store.openWriter(header("T9.1", "T9", 0)));
    }

    @Test
    void aCheckpointAheadOfTheDurableLogIsIgnored() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        TimelineWriter w = store.openWriter(header("T0", null, -1), 100);
        w.append(List.of(ev("T0", 0, "a", 1), ev("T0", 1, "a", 1)));
        w.checkpoint(new WorldImage("T0", 1, Map.of()));
        w.checkpoint(new WorldImage("T0", 50, Map.of())); // written, but the log never got that far
        w.finish(summary(2));
        w.close();
        assertEquals(1, new TimelineStore(tmp).checkpointAtOrBefore("T0", 99).orElseThrow().seq());
    }

    // ------------------------------------------------------------------------------------------ persisted indexes (FR-18)
    /** Like write, but events alternate between nodes a, b and env and between kinds, as a real recording does. */
    static long writeMixed(TimelineStore store, TimelineHeader h, long first, int segSize, int... groupSizes) {
        TimelineWriter w = store.openWriter(h, segSize);
        long seq = first;
        Map<String, Integer> counters = new HashMap<>();
        for (int g : groupSizes) {
            List<Event> group = new ArrayList<>();
            for (int i = 0; i < g; i++) {
                String node = new String[]{"a", "b", "env"}[(int) (seq % 3)];
                EventKind kind = new EventKind[]{EventKind.APP_NOTE, EventKind.TIMER_FIRED, EventKind.MESSAGE_SENT}[(int) (seq % 3)];
                int k = counters.merge(h.timelineId() + node, 1, Integer::sum);
                String id = node.equals("env") ? "fault:r#" + seq : node + "#" + (first + k);
                group.add(new Event(id, seq, h.timelineId(), node, kind, seq / 2, seq + 1, List.of((int) seq, 0, 0), List.of(), Map.of("text", "e" + seq), "d" + seq, null, null));
                seq++;
            }
            w.append(group);
        }
        w.finish(summary(seq));
        w.close();
        return seq - 1;
    }

    private static void assertSameIndex(TimelineIndex expected, TimelineIndex actual, List<Event> events) {
        assertEquals(expected.size(), actual.size());
        for (String n : expected.nodes()) assertArrayEquals(expected.positionsOfNode(n), actual.positionsOfNode(n), n);
        assertEquals(expected.nodes(), actual.nodes());
        for (EventKind k : EventKind.values()) assertArrayEquals(expected.positionsOfKind(k), actual.positionsOfKind(k), k.name());
        for (Event e : events) assertEquals(expected.positionOf(e.eventId()), actual.positionOf(e.eventId()), e.eventId());
        for (long t = -1; t <= events.get(events.size() - 1).virtualTime() + 2; t++) assertEquals(expected.firstAtOrAfter(t), actual.firstAtOrAfter(t), "t=" + t);
        assertEquals(-1, actual.positionOf("nope#1"));
    }

    @Test
    void indexesArePersistedWhenATimelineFinishesAndLoadedAgainIdenticallyIncludingForBranches() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        writeMixed(store, header("T0", null, -1), 0, 100, 2, 3, 2, 4, 3, 2);
        writeMixed(store, header("T0.1", "T0", 4), 5, 120, 3, 2, 4);
        assertTrue(Files.isRegularFile(tmp.resolve("timelines/T0/index/main.idx")));
        assertTrue(Files.isRegularFile(tmp.resolve("timelines/T0.1/index/main.idx")));
        TimelineStore fresh = new TimelineStore(tmp);
        for (String id : List.of("T0", "T0.1")) {
            TimelineIndex persisted = fresh.index(id);
            assertEquals("persisted", persisted.source(), id);
            assertSameIndex(new TimelineIndex(fresh.events(id)), persisted, fresh.events(id));
        }
    }

    @Test
    void persistedIndexesOfLongTimelinesMatchRebuiltOnesAcrossBranchCuts() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        int[] groups = new int[700];
        Arrays.fill(groups, 3);
        long last = writeMixed(store, header("T0", null, -1), 0, 500, groups); // 2,100 events: many time samples and several segments
        assertEquals(2099, last);
        writeMixed(store, header("T0.1", "T0", 1034), 1035, 500, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3);
        TimelineStore fresh = new TimelineStore(tmp);
        for (String id : List.of("T0", "T0.1")) {
            TimelineIndex persisted = fresh.index(id);
            assertEquals("persisted", persisted.source(), id);
            assertSameIndex(new TimelineIndex(fresh.events(id)), persisted, fresh.events(id));
        }
    }

    @Test
    void aMissingOrDamagedIndexIsRebuiltFromTheLogAndRepairedOnRequest() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        writeMixed(store, header("T0", null, -1), 0, 100, 2, 3, 2, 4);
        Path file = tmp.resolve("timelines/T0/index/main.idx");
        byte[] good = Files.readAllBytes(file);
        List<Event> events = new TimelineStore(tmp).events("T0");
        // missing
        Files.delete(file);
        TimelineIndex rebuilt = new TimelineStore(tmp).index("T0");
        assertEquals("rebuilt", rebuilt.source());
        assertSameIndex(new TimelineIndex(events), rebuilt, events);
        // flipped byte: caught by the checksum
        byte[] bad = good.clone();
        bad[bad.length / 2] ^= 0x55;
        Files.write(file, bad);
        assertEquals("rebuilt", new TimelineStore(tmp).index("T0").source());
        // truncated and empty
        Files.write(file, Arrays.copyOf(good, good.length - 7));
        assertEquals("rebuilt", new TimelineStore(tmp).index("T0").source());
        Files.write(file, new byte[0]);
        assertEquals("rebuilt", new TimelineStore(tmp).index("T0").source());
        // a well-formed index that belongs to a different log (right checksum, wrong events) is also refused
        TimelineStore other = new TimelineStore(tmp.resolve("other"));
        writeMixed(other, header("T0", null, -1), 0, 100, 5, 5);
        byte[] foreign = Files.readAllBytes(tmp.resolve("other/timelines/T0/index/main.idx"));
        Files.write(file, foreign);
        assertEquals("rebuilt", new TimelineStore(tmp).index("T0").source());
        // reading never repaired it (NFR-04) ...
        assertArrayEquals(foreign, Files.readAllBytes(file));
        // ... the explicit repair does
        TimelineStore repair = new TimelineStore(tmp);
        repair.rebuildIndex("T0");
        assertArrayEquals(good, Files.readAllBytes(file), "the repaired file equals what the writer produced");
        assertEquals("persisted", new TimelineStore(tmp).index("T0").source());
        // an index written for an unfinished timeline does not exist, and cannot be forced
        assertThrows(IllegalStateException.class, () -> {
            TimelineStore s2 = new TimelineStore(tmp.resolve("unfinished"));
            TimelineWriter w = s2.openWriter(header("U", null, -1));
            w.append(List.of(ev("U", 0, "a", 1)));
            w.close();
            s2.rebuildIndex("U");
        });
    }

    @Test
    void theIndexFileIsSmallComparedWithTheLog() throws IOException {
        TimelineStore store = new TimelineStore(tmp);
        int[] groups = new int[400];
        Arrays.fill(groups, 2);
        writeMixed(store, header("T0", null, -1), 0, 100, groups);
        long log = 0;
        try (Stream<Path> s = Files.list(tmp.resolve("timelines/T0/events"))) {
            for (Path p : (Iterable<Path>) s::iterator) log += Files.size(p);
        }
        long idx = Files.size(tmp.resolve("timelines/T0/index/main.idx"));
        assertTrue(idx * 5 < log, "index " + idx + " bytes vs log " + log + " bytes");
    }

    // ------------------------------------------------------------------------------------------ opt-in log compression
    @Test
    void compressedSegmentsRoundTripAndAreSmaller() throws IOException {
        TimelineStore plain = new TimelineStore(tmp.resolve("plain"));
        TimelineStore zipped = new TimelineStore(tmp.resolve("zipped")).compressNewTimelines(true);
        int[] groups = new int[300];
        Arrays.fill(groups, 3);
        writeMixed(plain, header("T0", null, -1), 0, 200, groups);
        writeMixed(zipped, header("T0", null, -1), 0, 200, groups);
        writeMixed(zipped, header("T0.1", "T0", 899), 900, 200, 3, 3, 3);
        try (Stream<Path> s = Files.list(tmp.resolve("zipped/timelines/T0/events"))) {
            assertTrue(s.allMatch(p -> p.getFileName().toString().endsWith(".log.gz")));
        }
        TimelineStore fresh = new TimelineStore(tmp.resolve("zipped"));
        assertEquals(new TimelineStore(tmp.resolve("plain")).events("T0"), fresh.events("T0"), "the same events whether or not the segments are compressed");
        assertEquals(new TimelineStore(tmp.resolve("plain")).groupEnds("T0"), fresh.groupEnds("T0"));
        assertEquals("persisted", fresh.index("T0").source());
        assertEquals(909, fresh.events("T0.1").size());
        long plainBytes = 0, zipBytes = 0;
        try (Stream<Path> s = Files.list(tmp.resolve("plain/timelines/T0/events"))) { for (Path p : (Iterable<Path>) s::iterator) plainBytes += Files.size(p); }
        try (Stream<Path> s = Files.list(tmp.resolve("zipped/timelines/T0/events"))) { for (Path p : (Iterable<Path>) s::iterator) zipBytes += Files.size(p); }
        assertTrue(zipBytes * 2 < plainBytes, "compressed " + zipBytes + " vs plain " + plainBytes);
        // uncompressed and compressed timelines can live in one store and one lineage
        assertEquals(fresh.events("T0").subList(0, 900), fresh.events("T0.1").subList(0, 900));
    }

    @Test
    void aCompressedSegmentCutAtAnyByteIsATornTailNotCorruption_NFR11() throws IOException {
        TimelineStore zipped = new TimelineStore(tmp.resolve("z")).compressNewTimelines(true);
        writeMixed(zipped, header("T0", null, -1), 0, 1000, 2, 3, 2, 4, 3);
        Path seg = tmp.resolve("z/timelines/T0/events/000000.log.gz");
        byte[] all = Files.readAllBytes(seg);
        List<Long> ends = new TimelineStore(tmp.resolve("z")).groupEnds("T0");
        int previous = 0;
        for (int cut = 0; cut < all.length; cut++) {
            Path copy = tmp.resolve("cut" + cut);
            Files.createDirectories(copy.resolve("timelines"));
            copyTree(tmp.resolve("z/timelines/T0"), copy.resolve("timelines/T0"));
            Files.write(copy.resolve("timelines/T0/events/000000.log.gz"), Arrays.copyOf(all, cut));
            TimelineStore s = new TimelineStore(copy);
            int n = s.events("T0").size(); // never throws: a truncated tail is discarded
            assertTrue(ends.contains((long) n - 1) || n == 0, "cut at " + cut + " left " + n + " events, which is not a whole number of step groups");
            assertTrue(n >= previous, "more bytes never mean fewer events");
            previous = n;
        }
        // only the gzip trailer is missing in the longest cut, and every step group was already complete and flushed: nothing is lost
        assertEquals(14, previous);
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
}
