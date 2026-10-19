package chronos.store;

import chronos.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * The store's asynchronous writer (D-21): one writer thread per recording takes step groups from a bounded queue and
 * appends them to the segmented JSON-lines log. A single writer means no locks on the file; the bounded queue gives
 * back-pressure to the engine. Only immutable events cross the thread boundary.
 *
 * File format (format version 1): events/NNNNNN.log (or NNNNNN.log.gz when compression is on: the same lines, gzip-compressed with a sync flush per step group) holds one JSON event per line; every step group is closed by a commit
 * line {"commit":lastSeq,"h":sha256-of-the-group's-lines,"n":count}. Lines after the last commit line belong to an
 * unfinished step and are discarded on load (NFR-11).
 */
public final class TimelineWriter implements EventSink {
    private static final Object STOP = new Object();

    private record Finish(TimelineSummary summary, CompletableFuture<Void> done) {}

    private final TimelineStore store;
    private final String timelineId;
    private final Path dir;
    private final int segmentSize;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(256);
    private final Thread thread;
    private volatile Throwable failure;
    private volatile boolean closed;
    private final long simulatedGroupDelayNanos;   // benchmark hook (B13): pretend the disk is slow
    private volatile int queueHighWater;

    private final boolean compress;
    private final TimelineIndex.Builder indexBuilder = new TimelineIndex.Builder();
    private int segmentIndex = -1;
    private int eventsInSegment;
    private OutputStream out;

    TimelineWriter(TimelineStore store, TimelineHeader header, Path dir, int segmentSize, long simulatedGroupDelayMicros, boolean compress) throws IOException {
        this.compress = compress;
        this.simulatedGroupDelayNanos = simulatedGroupDelayMicros * 1000;
        this.store = store;
        this.timelineId = header.timelineId();
        this.dir = dir;
        this.segmentSize = segmentSize;
        Files.createDirectories(dir.resolve("events"));
        Files.createDirectories(dir.resolve("checkpoints"));
        TimelineStore.atomicWrite(dir.resolve("header.json"), Canon.json(header.toMap()) + "\n");
        thread = new Thread(this::loop, "chronos-writer-" + timelineId);
        thread.setDaemon(true);
        thread.start();
    }

    /** The largest number of step groups that were waiting in the bounded queue (its capacity is 256): evidence of back-pressure. */
    public int queueHighWater() {
        return queueHighWater;
    }

    @Override
    public void append(List<Event> group) {
        if (group.isEmpty()) return;
        enqueue(List.copyOf(group));
    }

    @Override
    public void checkpoint(WorldImage image) {
        try {
            String name = String.format("%010d.ckpt", image.seq());
            TimelineStore.atomicWrite(dir.resolve("checkpoints").resolve(name),
                    Canon.json(java.util.Map.of("formatVersion", Formats.FORMAT_VERSION, "image", image.toMap())) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write checkpoint of " + timelineId, e);
        }
    }

    /** Drains the queue, writes summary.json and marks the timeline finished in the catalogue; blocks until done. */
    @Override
    public void finish(TimelineSummary summary) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        enqueue(new Finish(summary, done));
        try {
            done.get();
        } catch (Exception e) {
            throw new IllegalStateException("writer of " + timelineId + " failed: " + (failure != null ? failure : e), failure != null ? failure : e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (thread.isAlive()) queue.put(STOP);
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) throw new IllegalStateException("writer of " + timelineId + " failed: " + failure, failure);
    }

    private void enqueue(Object o) {
        if (failure != null) throw new IllegalStateException("writer of " + timelineId + " failed earlier: " + failure, failure);
        if (closed) throw new IllegalStateException("writer of " + timelineId + " is closed");
        try {
            queue.put(o);
            int size = queue.size();
            if (size > queueHighWater) queueHighWater = size;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while queueing events", e);
        }
    }

    // ----- writer thread -----

    private void loop() {
        try {
            while (true) {
                Object o = queue.take();
                if (o == STOP) break;
                if (o instanceof Finish f) {
                    try {
                        flush();
                        TimelineStore.atomicWrite(dir.resolve("summary.json"), Canon.json(f.summary().toMap()) + "\n");
                        Files.createDirectories(dir.resolve("index"));
                        TimelineStore.atomicWriteBytes(dir.resolve("index").resolve("main.idx"), indexBuilder.toBytes()); // derived: may be lost and rebuilt
                        store.noteFinished(timelineId, f.summary().status());
                        f.done().complete(null);
                    } catch (Throwable t) {
                        f.done().completeExceptionally(t);
                        throw t;
                    }
                } else {
                    @SuppressWarnings("unchecked") List<Event> group = (List<Event>) o;
                    writeGroup(group);
                }
            }
            flush();
            if (out != null) out.close();
        } catch (Throwable t) {
            failure = t;
            queue.clear();
        }
    }

    private void writeGroup(List<Event> group) throws IOException {
        if (out == null || eventsInSegment >= segmentSize) rollSegment();
        StringBuilder sb = new StringBuilder();
        for (Event e : group) {
            sb.append(e.toLine()).append('\n');
            indexBuilder.add(e);
        }
        String lines = sb.toString();
        String commit = Canon.json(java.util.Map.of("commit", group.get(group.size() - 1).seq(), "h", Canon.sha256(lines), "n", group.size())) + "\n";
        out.write((lines + commit).getBytes(StandardCharsets.UTF_8));
        out.flush();
        eventsInSegment += group.size();
        if (simulatedGroupDelayNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(simulatedGroupDelayNanos);
    }

    private void rollSegment() throws IOException {
        if (out != null) out.close();
        segmentIndex++;
        eventsInSegment = 0;
        OutputStream file = Files.newOutputStream(dir.resolve("events").resolve(String.format(compress ? "%06d.log.gz" : "%06d.log", segmentIndex)),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // compressed segments are flushed with a sync marker after every step group, so a crash loses at most the unfinished group
        out = compress ? new java.util.zip.GZIPOutputStream(new BufferedOutputStream(file, 1 << 16), 1 << 16, true) : new BufferedOutputStream(file, 1 << 16);
    }

    private void flush() throws IOException {
        if (out != null) out.flush();
    }
}
