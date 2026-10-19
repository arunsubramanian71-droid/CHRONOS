package chronos.store;

import chronos.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Event Store (C6): persists timelines and implements {@link LogReader} (D-11, D-26).
 *
 * <pre>
 * chronos-data/
 *   catalog.json                    timeline tree: ids, parents, status          (rewritten atomically)
 *   timelines/T0/
 *     header.json                   scenario, seed, nodes, fault plan, format version, code fingerprint
 *     events/000000.log ...         append-only segments, one JSON event per line, step groups closed by a commit line
 *     checkpoints/0000001000.ckpt   complete world images
 *     summary.json                  final status, counts, end-state digest, first violation
 *   timelines/T0.1/                 a branch: stores only the events after its branch point
 * </pre>
 *
 * Two storage rules: the log is append-only and lines after the last commit line are an unfinished step and are discarded
 * on load (NFR-11); indexes are derived and rebuilt from the log (they are never the source of truth).
 * Reading never modifies a file (NFR-04). A branch is resolved by reading its ancestors' events up to the branch point and
 * then its own events (D-12).
 */
public final class TimelineStore implements LogReader {
    public static final int DEFAULT_SEGMENT_SIZE = 5000;

    /** A contiguous range of positions of a resolved timeline that lives in one timeline's own files. */
    public record Segment(String timelineId, long fromSeq, long toSeq) {}

    private record Own(List<Event> events, List<Long> groupEnds, int discardedLines) {}

    private final Path root;
    private final Map<String, Own> ownCache = new ConcurrentHashMap<>();
    private final Map<String, List<Event>> resolvedCache = new ConcurrentHashMap<>();

    public TimelineStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root.resolve("timelines"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------------------------------------------------

    public TimelineWriter openWriter(TimelineHeader header) {
        return openWriter(header, DEFAULT_SEGMENT_SIZE);
    }

    public TimelineWriter openWriter(TimelineHeader header, int segmentSize) {
        return openWriter(header, segmentSize, 0);
    }

    /** As above, with a simulated per-step-group delay in microseconds: a hook to benchmark the writer's back-pressure on a slow disk (B13). */
    public TimelineWriter openWriter(TimelineHeader header, int segmentSize, long simulatedGroupDelayMicros) {
        Path dir = dirOf(header.timelineId());
        if (Files.exists(dir)) throw new IllegalStateException("timeline " + header.timelineId() + " already exists");
        if (header.parentId() != null && !Files.isDirectory(dirOf(header.parentId()))) throw new UnknownTimelineException(header.parentId());
        try {
            TimelineWriter w = new TimelineWriter(this, header, dir, segmentSize, simulatedGroupDelayMicros);
            updateCatalog(header, TimelineStatus.RECORDING);
            return w;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open timeline " + header.timelineId(), e);
        }
    }

    void noteFinished(String timelineId, TimelineStatus status) throws IOException {
        updateCatalog(header(timelineId), status);
    }

    private synchronized void updateCatalog(TimelineHeader h, TimelineStatus status) {
        try {
            Map<String, Object> cat = readCatalog();
            Map<String, Object> tls = Canon.map(cat.get("timelines"));
            Map<String, Object> e = new TreeMap<>();
            if (h.parentId() != null) e.put("parentId", h.parentId());
            e.put("branchSeq", h.branchSeq());
            e.put("status", status.name());
            e.put("scenario", h.scenario());
            if (h.label() != null) e.put("label", h.label());
            tls.put(h.timelineId(), e);
            atomicWrite(root.resolve("catalog.json"), Canon.json(cat) + "\n");
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot update the catalogue", ex);
        }
    }

    /** The persisted catalogue: {"formatVersion":1,"timelines":{id:{parentId,branchSeq,status,scenario,label}}}. */
    public synchronized Map<String, Object> readCatalog() throws IOException {
        Path p = root.resolve("catalog.json");
        if (!Files.exists(p)) {
            Map<String, Object> m = new TreeMap<>();
            m.put("formatVersion", Formats.FORMAT_VERSION);
            m.put("timelines", new TreeMap<String, Object>());
            return m;
        }
        Map<String, Object> m = Canon.parseMap(Files.readString(p, StandardCharsets.UTF_8));
        if (Canon.num(m.get("formatVersion")) != Formats.FORMAT_VERSION)
            throw new TimelineHeader.UnsupportedFormatException("unsupported catalogue format version " + m.get("formatVersion"));
        return m;
    }

    /** Child timelines of a timeline, from the catalogue. */
    public List<String> children(String id) {
        try {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Object> e : Canon.map(readCatalog().get("timelines")).entrySet())
                if (id.equals(Canon.map(e.getValue()).get("parentId"))) out.add(e.getKey());
            out.sort(NATURAL);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes a leaf timeline (FR-30). A timeline with children cannot be deleted: its descendants share its prefix (D-12). */
    public synchronized void delete(String id) {
        if (!Files.isDirectory(dirOf(id))) throw new UnknownTimelineException(id);
        List<String> kids = children(id);
        if (!kids.isEmpty()) throw new IllegalStateException("cannot delete timeline " + id + ": it has child timelines " + kids);
        try {
            try (Stream<Path> walk = Files.walk(dirOf(id))) {
                for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            }
            Map<String, Object> cat = readCatalog();
            Canon.map(cat.get("timelines")).remove(id);
            atomicWrite(root.resolve("catalog.json"), Canon.json(cat) + "\n");
            ownCache.remove(id);
            resolvedCache.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + id, e);
        }
    }

    static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path dirOf(String id) {
        if (id.contains("/") || id.contains("\\") || id.contains("..")) throw new IllegalArgumentException("bad timeline id: " + id);
        return root.resolve("timelines").resolve(id);
    }

    // ------------------------------------------------------------------------------------------------------------
    // LogReader
    // ------------------------------------------------------------------------------------------------------------

    public static final Comparator<String> NATURAL = (a, b) -> {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = compareToken(x[i], y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    };

    private static int compareToken(String a, String b) {
        String pa = a.replaceAll("\\d+$", ""), pb = b.replaceAll("\\d+$", "");
        int c = pa.compareTo(pb);
        if (c != 0) return c;
        String na = a.substring(pa.length()), nb = b.substring(pb.length());
        if (na.isEmpty() || nb.isEmpty()) return na.compareTo(nb);
        return Long.compare(Long.parseLong(na), Long.parseLong(nb));
    }

    @Override
    public List<String> timelineIds() {
        try (Stream<Path> s = Files.list(root.resolve("timelines"))) {
            List<String> ids = new ArrayList<>(s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList());
            ids.sort(NATURAL);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String id) {
        return Files.isDirectory(dirOf(id));
    }

    @Override
    public TimelineHeader header(String id) {
        Path p = dirOf(id).resolve("header.json");
        if (!Files.isRegularFile(p)) throw new UnknownTimelineException(id);
        try {
            return TimelineHeader.fromMap(Canon.parseMap(Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new CorruptLogException("header of " + id + " is corrupt: " + e.getMessage());
        }
    }

    @Override
    public TimelineSummary summary(String id) {
        Path p = dirOf(id).resolve("summary.json");
        if (!Files.isRegularFile(p)) return null;
        try {
            return TimelineSummary.fromMap(Canon.parseMap(Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new CorruptLogException("summary of " + id + " is corrupt: " + e.getMessage());
        }
    }

    /** Segment resolution (D-11): which timeline's files hold which positions of the resolved history. */
    public List<Segment> segments(String id) {
        TimelineHeader h = header(id);
        List<Segment> out = new ArrayList<>();
        if (h.parentId() != null) {
            for (Segment s : segments(h.parentId())) {
                if (s.fromSeq() > h.branchSeq()) break;
                out.add(new Segment(s.timelineId(), s.fromSeq(), Math.min(s.toSeq(), h.branchSeq())));
            }
        }
        Own own = own(id);
        long first = h.parentId() == null ? 0 : h.branchSeq() + 1;
        long last = first + own.events().size() - 1;
        if (!own.events().isEmpty()) out.add(new Segment(id, first, last));
        return out;
    }

    @Override
    public List<Event> events(String id) {
        List<Event> cached = resolvedCache.get(id);
        if (cached != null) return cached;
        TimelineHeader h = header(id);
        List<Event> out;
        Own own = own(id);
        if (h.parentId() == null) {
            out = Collections.unmodifiableList(own.events());
        } else {
            List<Event> parent = events(h.parentId());
            if (parent.size() < h.branchSeq() + 1)
                throw new CorruptLogException("timeline " + id + " branches at " + h.branchSeq() + " but parent " + h.parentId() + " has only " + parent.size() + " events");
            List<Event> merged = new ArrayList<>(parent.subList(0, (int) h.branchSeq() + 1));
            merged.addAll(own.events());
            out = Collections.unmodifiableList(merged);
        }
        if (summary(id) != null) resolvedCache.put(id, out);
        return out;
    }

    @Override
    public List<Long> groupEnds(String id) {
        TimelineHeader h = header(id);
        List<Long> out = new ArrayList<>();
        if (h.parentId() != null) {
            boolean found = false;
            for (long e : groupEnds(h.parentId())) {
                if (e > h.branchSeq()) break;
                out.add(e);
                if (e == h.branchSeq()) found = true;
            }
            if (!found) throw new CorruptLogException("branch point " + h.branchSeq() + " of " + id + " is not a step boundary of " + h.parentId());
        }
        out.addAll(own(id).groupEnds());
        return out;
    }

    /** Number of lines after the last commit line that were discarded as an unfinished step when the log was loaded. */
    public int discardedTailLines(String id) {
        return own(id).discardedLines();
    }

    public TimelineIndex index(String id) {
        return new TimelineIndex(events(id));
    }

    @Override
    public List<Long> checkpointSeqs(String id) {
        TimelineHeader h = header(id);
        TreeSet<Long> seqs = new TreeSet<>(ownCheckpointSeqs(id));
        if (h.parentId() != null) for (long s : checkpointSeqs(h.parentId())) if (s <= h.branchSeq()) seqs.add(s);
        return new ArrayList<>(seqs);
    }

    @Override
    public Optional<WorldImage> checkpointAtOrBefore(String id, long seq) {
        TimelineHeader h = header(id);
        long lastOwn = lastOwnSeq(id, h);
        long best = -1;
        for (long s : ownCheckpointSeqs(id)) if (s <= seq && s <= lastOwn && s > best) best = s;
        if (best >= 0) return Optional.of(readCheckpoint(id, best));
        if (h.parentId() != null) return checkpointAtOrBefore(h.parentId(), Math.min(seq, h.branchSeq()));
        return Optional.empty();
    }

    private long lastOwnSeq(String id, TimelineHeader h) {
        Own o = own(id);
        long first = h.parentId() == null ? 0 : h.branchSeq() + 1;
        return first + o.events().size() - 1;
    }

    private List<Long> ownCheckpointSeqs(String id) {
        Path dir = dirOf(id).resolve("checkpoints");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            List<Long> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".ckpt")) out.add(Long.parseLong(n.substring(0, n.length() - 5)));
            }
            Collections.sort(out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private WorldImage readCheckpoint(String id, long seq) {
        Path p = dirOf(id).resolve("checkpoints").resolve(String.format("%010d.ckpt", seq));
        try {
            Map<String, Object> m = Canon.parseMap(Files.readString(p, StandardCharsets.UTF_8));
            if (Canon.num(m.get("formatVersion")) != Formats.FORMAT_VERSION)
                throw new TimelineHeader.UnsupportedFormatException("unsupported checkpoint format version " + m.get("formatVersion") + " in " + p);
            return WorldImage.fromMap(Canon.map(m.get("image")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new CorruptLogException("checkpoint " + p + " is corrupt: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // reading one timeline's own files, with validation and torn-tail recovery
    // ------------------------------------------------------------------------------------------------------------

    private Own own(String id) {
        Own c = ownCache.get(id);
        if (c != null) return c;
        Own o = readOwn(id);
        if (summary(id) != null) ownCache.put(id, o);
        return o;
    }

    private Own readOwn(String id) {
        TimelineHeader h = header(id);
        Path evDir = dirOf(id).resolve("events");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(evDir)) {
            try (Stream<Path> s = Files.list(evDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<Event> events = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        long expected = h.parentId() == null ? 0 : h.branchSeq() + 1;
        List<Event> pending = new ArrayList<>();
        StringBuilder pendingText = new StringBuilder();
        String suspect = null; // first unparseable line after the last commit
        int discarded = 0;
        for (int fi = 0; fi < files.size(); fi++) {
            Path f = files.get(fi);
            boolean lastFile = fi == files.size() - 1;
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                int ln = 0;
                while ((line = r.readLine()) != null) {
                    ln++;
                    String where = f.getFileName() + ":" + ln;
                    if (line.startsWith("{\"commit\":")) {
                        Map<String, Object> c;
                        try {
                            c = Canon.parseMap(line);
                        } catch (IllegalArgumentException e) {
                            if (suspect == null) suspect = where;
                            pending.clear();
                            pendingText.setLength(0);
                            discarded++;
                            continue;
                        }
                        if (suspect != null)
                            throw new CorruptLogException("corrupt record at " + id + "/" + suspect + " (followed by a commit line, so it is not a torn tail)");
                        long lastSeq = Canon.num(c.get("commit"));
                        if (pending.isEmpty() || Canon.num(c.get("n")) != pending.size() || pending.get(pending.size() - 1).seq() != lastSeq)
                            throw new CorruptLogException("commit line at " + id + "/" + where + " does not match the step group before it");
                        if (!Canon.sha256(pendingText.toString()).equals(c.get("h")))
                            throw new CorruptLogException("step group closed at " + id + "/" + where + " fails its checksum (the log was modified or damaged)");
                        events.addAll(pending);
                        ends.add(lastSeq);
                        pending.clear();
                        pendingText.setLength(0);
                    } else {
                        Event e;
                        try {
                            e = Event.fromLine(line);
                        } catch (RuntimeException ex) {
                            if (suspect == null) suspect = where;
                            discarded++;
                            continue;
                        }
                        if (suspect != null) { discarded++; continue; }
                        long want = expected + events.size() + pending.size();
                        if (e.seq() != want)
                            throw new CorruptLogException("event " + e.eventId() + " at " + id + "/" + where + " has position " + e.seq() + ", expected " + want);
                        if (!e.timelineId().equals(id))
                            throw new CorruptLogException("event " + e.eventId() + " at " + id + "/" + where + " claims timeline " + e.timelineId());
                        pending.add(e);
                        pendingText.append(line).append('\n');
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            if (!lastFile && (!pending.isEmpty() || suspect != null))
                throw new CorruptLogException("segment " + f.getFileName() + " of " + id + " ends inside a step group");
        }
        discarded += pending.size();
        return new Own(Collections.unmodifiableList(events), Collections.unmodifiableList(ends), discarded);
    }
}
