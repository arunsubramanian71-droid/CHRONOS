package chronos.store;

import chronos.model.Event;
import chronos.model.EventKind;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Indexes over a resolved timeline (FR-18): by position (the list index itself), by node, by kind and by virtual time. They are
 * derived data (Section 23, storage rule 2): a timeline's own indexes are written to {@code index/main.idx} when the timeline is
 * finished, and loaded again with a validity check (format, event count, first and last event id, a checksum, and a sample of
 * events). If the file is missing or suspect, the index is rebuilt from the log, so the log is the only thing that must be right.
 *
 * The persisted form covers the timeline's own events with global positions; a branch's resolved index is its parent's index
 * cut at the branch point followed by its own. Lookup by id uses the per-node lane (event {@code n#k} is the k-th event of
 * node n) and verifies the answer against the log, falling back to a map for the environment's ids.
 */
public final class TimelineIndex {
    static final int MAGIC = 0x43485258; // "CHRX"
    static final int VERSION = 1;
    static final int SAMPLE_EVERY = 256;

    private final List<Event> events;
    private final Map<String, int[]> byNode;
    private final Map<EventKind, int[]> byKind;
    private final int[] samplePos;
    private final long[] sampleTime;
    private final String source;
    private Map<String, Integer> byId;

    /** Rebuilds every index from the events. */
    public TimelineIndex(List<Event> events) {
        this(events, build(events, 0, events.size()), "rebuilt");
    }

    private TimelineIndex(List<Event> events, Parts p, String source) {
        this.events = events;
        this.byNode = p.byNode;
        this.byKind = p.byKind;
        this.samplePos = p.samplePos;
        this.sampleTime = p.sampleTime;
        this.source = source;
    }

    /** "persisted" if the indexes were loaded from index files, "rebuilt" if they were derived from the log. */
    public String source() {
        return source;
    }

    public int size() {
        return events.size();
    }

    public Event at(int position) {
        return events.get(position);
    }

    /** Positions of a node's events in order. */
    public int[] positionsOfNode(String node) {
        return byNode.getOrDefault(node, new int[0]);
    }

    public int[] positionsOfKind(EventKind kind) {
        return byKind.getOrDefault(kind, new int[0]);
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(byNode.keySet());
    }

    /** Position of an event id, or -1. */
    public int positionOf(String eventId) {
        int hash = eventId.lastIndexOf('#');
        if (hash > 0) {
            int[] lane = byNode.get(eventId.substring(0, hash));
            if (lane != null) {
                try {
                    long k = Long.parseLong(eventId.substring(hash + 1));
                    if (k >= 1 && k <= lane.length) {
                        int pos = lane[(int) k - 1];
                        if (events.get(pos).eventId().equals(eventId)) return pos;
                    }
                } catch (NumberFormatException ignored) {
                    // not a lane-style id: use the map
                }
            }
        }
        if (byId == null) {
            Map<String, Integer> m = new HashMap<>();
            for (int i = 0; i < events.size(); i++) m.put(events.get(i).eventId(), i);
            byId = m;
        }
        return byId.getOrDefault(eventId, -1);
    }

    /** First position whose virtual time is at least t (virtual time never decreases along the log), or size() if none. */
    public int firstAtOrAfter(long t) {
        int lo = 0, hi = events.size();
        if (sampleTime.length > 0) { // narrow with the time index first
            int a = 0, b = sampleTime.length;
            while (a < b) {
                int mid = (a + b) >>> 1;
                if (sampleTime[mid] < t) a = mid + 1;
                else b = mid;
            }
            if (a > 0) lo = samplePos[a - 1];
            if (a < sampleTime.length) hi = Math.min(hi, samplePos[a]);
        }
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).virtualTime() < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ------------------------------------------------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------------------------------------------------

    /** The index arrays of a run of events (positions are list positions from..to-1 plus the events' own offset). */
    private record Parts(Map<String, int[]> byNode, Map<EventKind, int[]> byKind, int[] samplePos, long[] sampleTime) {}

    private static Parts build(List<Event> events, int from, int to) {
        Builder b = new Builder();
        for (int i = from; i < to; i++) b.add(events.get(i));
        return b.parts();
    }

    /** Collects the index of a stream of events (used by the writer thread while a timeline is written, and for rebuilds). */
    static final class Builder {
        private final Map<String, IntList> nodes = new TreeMap<>();
        private final Map<EventKind, IntList> kinds = new EnumMap<>(EventKind.class);
        private final IntList samplePos = new IntList();
        private final LongList sampleTime = new LongList();
        private int count;
        private long first = -1, last = -1;
        private String firstId, lastId;

        void add(Event e) {
            int pos = (int) e.seq();
            nodes.computeIfAbsent(e.nodeId(), x -> new IntList()).add(pos);
            kinds.computeIfAbsent(e.kind(), x -> new IntList()).add(pos);
            if (count % SAMPLE_EVERY == 0) {
                samplePos.add(pos);
                sampleTime.add(e.virtualTime());
            }
            if (count == 0) {
                first = pos;
                firstId = e.eventId();
            }
            last = pos;
            lastId = e.eventId();
            count++;
        }

        Parts parts() {
            Map<String, int[]> n = new TreeMap<>();
            nodes.forEach((k, v) -> n.put(k, v.toArray()));
            Map<EventKind, int[]> k = new EnumMap<>(EventKind.class);
            kinds.forEach((key, v) -> k.put(key, v.toArray()));
            return new Parts(n, k, samplePos.toArray(), sampleTime.toArray());
        }

        /** The index file: header, node lanes, kind lanes, time samples, CRC-32 of everything before it. */
        byte[] toBytes() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(first);
            out.writeInt(count);
            out.writeUTF(firstId == null ? "" : firstId);
            out.writeUTF(lastId == null ? "" : lastId);
            out.writeInt(nodes.size());
            for (Map.Entry<String, IntList> e : nodes.entrySet()) {
                out.writeUTF(e.getKey());
                writeDeltas(out, e.getValue());
            }
            out.writeInt(kinds.size());
            for (Map.Entry<EventKind, IntList> e : kinds.entrySet()) {
                out.writeUTF(e.getKey().name());
                writeDeltas(out, e.getValue());
            }
            out.writeInt(samplePos.size());
            int prev = 0;
            for (int i = 0; i < samplePos.size(); i++) {
                writeVarint(out, samplePos.get(i) - prev);
                prev = samplePos.get(i);
                out.writeLong(sampleTime.get(i));
            }
            out.flush();
            CRC32 crc = new CRC32();
            crc.update(bos.toByteArray());
            out.writeInt((int) crc.getValue());
            out.flush();
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // persisted form
    // ------------------------------------------------------------------------------------------------------------

    /** One timeline's own index as read from its file, already checked against that timeline's own events. */
    record Own(long first, int count, Parts parts) {}

    /** Reads and validates an index file against the timeline's own events; empty if it is missing, damaged or does not fit. */
    static Optional<Own> readOwn(byte[] file, List<Event> own) {
        try {
            if (file.length < 8) return Optional.empty();
            CRC32 crc = new CRC32();
            crc.update(file, 0, file.length - 4);
            int stored = new DataInputStream(new ByteArrayInputStream(file, file.length - 4, 4)).readInt();
            if (stored != (int) crc.getValue()) return Optional.empty();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file, 0, file.length - 4));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return Optional.empty();
            long first = in.readLong();
            int count = in.readInt();
            String firstId = in.readUTF(), lastId = in.readUTF();
            if (count != own.size()) return Optional.empty();
            if (count > 0 && (first != own.get(0).seq() || !firstId.equals(own.get(0).eventId()) || !lastId.equals(own.get(count - 1).eventId()))) return Optional.empty();
            Map<String, int[]> nodes = new TreeMap<>();
            int nn = in.readInt();
            long total = 0;
            for (int i = 0; i < nn; i++) {
                String name = in.readUTF();
                int[] lane = readDeltas(in);
                nodes.put(name, lane);
                total += lane.length;
            }
            Map<EventKind, int[]> kinds = new EnumMap<>(EventKind.class);
            int nk = in.readInt();
            long total2 = 0;
            for (int i = 0; i < nk; i++) {
                EventKind k = EventKind.valueOf(in.readUTF());
                int[] lane = readDeltas(in);
                kinds.put(k, lane);
                total2 += lane.length;
            }
            if (total != count || total2 != count) return Optional.empty();
            int ns = in.readInt();
            int[] sp = new int[ns];
            long[] st = new long[ns];
            int prev = 0;
            for (int i = 0; i < ns; i++) {
                prev += readVarint(in);
                sp[i] = prev;
                st[i] = in.readLong();
            }
            if (in.available() != 0) return Optional.empty();
            // spot-check the answers against the log: every lane entry must point at an event of that node and kind
            int step = Math.max(1, count / 997);
            long base = count == 0 ? 0 : own.get(0).seq();
            for (Map.Entry<String, int[]> e : nodes.entrySet()) {
                int[] lane = e.getValue();
                for (int i = 0; i < lane.length; i += step) if (!inRange(lane[i], base, count) || !own.get((int) (lane[i] - base)).nodeId().equals(e.getKey())) return Optional.empty();
                if (lane.length > 0 && (!inRange(lane[lane.length - 1], base, count) || !own.get((int) (lane[lane.length - 1] - base)).nodeId().equals(e.getKey()))) return Optional.empty();
            }
            for (Map.Entry<EventKind, int[]> e : kinds.entrySet()) {
                int[] lane = e.getValue();
                for (int i = 0; i < lane.length; i += step) if (!inRange(lane[i], base, count) || own.get((int) (lane[i] - base)).kind() != e.getKey()) return Optional.empty();
                if (lane.length > 0 && (!inRange(lane[lane.length - 1], base, count) || own.get((int) (lane[lane.length - 1] - base)).kind() != e.getKey())) return Optional.empty();
            }
            for (int i = 0; i < ns; i++) if (!inRange(sp[i], base, count) || own.get((int) (sp[i] - base)).virtualTime() != st[i]) return Optional.empty();
            return Optional.of(new Own(first, count, new Parts(nodes, kinds, sp, st)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean inRange(int pos, long base, int count) {
        return pos >= base && pos < base + count;
    }

    /** The resolved index: each segment's persisted parts (cut at the segment's last position), concatenated. */
    static TimelineIndex assemble(List<Event> resolved, List<Own> parts, List<Long> lastPositions) {
        Map<String, List<int[]>> n = new TreeMap<>();
        Map<EventKind, List<int[]>> k = new EnumMap<>(EventKind.class);
        List<int[]> sp = new ArrayList<>();
        List<long[]> st = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            long limit = lastPositions.get(i);
            Parts p = parts.get(i).parts;
            p.byNode.forEach((key, v) -> n.computeIfAbsent(key, x -> new ArrayList<>()).add(cut(v, limit)));
            p.byKind.forEach((key, v) -> k.computeIfAbsent(key, x -> new ArrayList<>()).add(cut(v, limit)));
            int keep = 0;
            while (keep < p.samplePos.length && p.samplePos[keep] <= limit) keep++;
            sp.add(Arrays.copyOf(p.samplePos, keep));
            st.add(Arrays.copyOf(p.sampleTime, keep));
        }
        Map<String, int[]> bn = new TreeMap<>();
        n.forEach((key, v) -> {
            int[] c = concat(v);
            if (c.length > 0) bn.put(key, c);
        });
        Map<EventKind, int[]> bk = new EnumMap<>(EventKind.class);
        k.forEach((key, v) -> {
            int[] c = concat(v);
            if (c.length > 0) bk.put(key, c);
        });
        int[] samplePos = concat(sp);
        int total = 0;
        for (long[] a : st) total += a.length;
        long[] sampleTime = new long[total];
        int at = 0;
        for (long[] a : st) {
            System.arraycopy(a, 0, sampleTime, at, a.length);
            at += a.length;
        }
        return new TimelineIndex(resolved, new Parts(bn, bk, samplePos, sampleTime), "persisted");
    }

    private static int[] cut(int[] positions, long limit) {
        int lo = 0, hi = positions.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (positions[mid] <= limit) lo = mid + 1;
            else hi = mid;
        }
        return lo == positions.length ? positions : Arrays.copyOf(positions, lo);
    }

    private static int[] concat(List<int[]> arrays) {
        int total = 0;
        for (int[] a : arrays) total += a.length;
        int[] out = new int[total];
        int at = 0;
        for (int[] a : arrays) {
            System.arraycopy(a, 0, out, at, a.length);
            at += a.length;
        }
        return out;
    }

    // ----- varints and growable arrays -----

    private static void writeDeltas(DataOutputStream out, IntList v) throws IOException {
        writeVarint(out, v.size());
        int prev = 0;
        for (int i = 0; i < v.size(); i++) {
            writeVarint(out, v.get(i) - prev);
            prev = v.get(i);
        }
    }

    private static int[] readDeltas(DataInputStream in) throws IOException {
        int n = readVarint(in);
        if (n < 0) throw new IOException("negative length");
        int[] out = new int[n];
        int prev = 0;
        for (int i = 0; i < n; i++) {
            prev += readVarint(in);
            out[i] = prev;
        }
        return out;
    }

    private static void writeVarint(DataOutputStream out, int v) throws IOException {
        if (v < 0) throw new IOException("negative varint");
        while (v >= 0x80) {
            out.writeByte((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int shift = 0, v = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IOException("varint too long");
        }
    }

    static final class IntList {
        private int[] a = new int[8];
        private int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int get(int i) { return a[i]; }

        int size() { return n; }

        int[] toArray() { return Arrays.copyOf(a, n); }
    }

    static final class LongList {
        private long[] a = new long[8];
        private int n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        long get(int i) { return a[i]; }

        int size() { return n; }

        long[] toArray() { return Arrays.copyOf(a, n); }
    }
}
