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
    void readingNeverModifiesAnyFile_NFR04() throws IOException {
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

    private Map<String, String> hashes() throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
                m.put(tmp.relativize(p).toString(), Canon.sha256(Files.readString(p)));
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
}
