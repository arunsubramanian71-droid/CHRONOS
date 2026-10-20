package chronos.bench;

import chronos.analysis.*;
import chronos.cli.BlindKit;
import chronos.cli.Main;
import chronos.demo.DemoRegistry;
import chronos.demo.Gossip;
import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.Scenario;
import chronos.store.TimelineIndex;
import chronos.store.TimelineStore;
import chronos.store.TimelineWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * The benchmark and stress harness (C18, Sections 16 and 26). Every number it prints was measured by running it: nothing is
 * assumed. Method: fixed seeds and workloads; the synthetic gossip scenario scales events and nodes freely; each whole-system
 * measurement is repeated (default 5, the first discarded as warm-up) and reported as median / p95 / p99; the environment is
 * recorded; results go to a CSV file. Misses against the targets of Section 8 are reported, not hidden.
 *
 * usage: java -jar chronos-bench/target/chronos-bench.jar [all|xl|soak|nfr01|eval|jmh] [--quick] [--csv FILE] [--soak-seeds N] [--no-xl]
 */
public final class BenchMain {
    private static final DemoRegistry REG = new DemoRegistry();
    private static final List<String[]> ROWS = new ArrayList<>();
    private static int reps = 5;
    private static boolean quick;

    private BenchMain() {}

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        String cmd = !a.isEmpty() && !a.get(0).startsWith("--") ? a.remove(0) : "all";
        if (cmd.equals("jmh")) {
            org.openjdk.jmh.Main.main(a.toArray(new String[0]));
            return;
        }
        quick = a.contains("--quick");
        if (quick) reps = 3;
        String csv = opt(a, "--csv", "bench-results.csv");
        int soakSeeds = Integer.parseInt(opt(a, "--soak-seeds", quick ? "300" : "10000"));
        boolean xl = !quick && !a.contains("--no-xl");
        printEnvironment();
        if (cmd.equals("soak")) {
            soak(soakSeeds);
        } else if (cmd.equals("eval")) {
            evaluate(Integer.parseInt(opt(a, "--seeds", "400")));
            evaluatePlanted(Integer.parseInt(opt(a, "--tokens", "500")));
            evaluateExploration();
        } else if (cmd.equals("xl")) {
            millionEvents(10);
            millionEvents(20);
            millionOnDisk(20);
            writeCsv(Path.of(csv));
            return;
        } else if (cmd.equals("nfr01")) {
            nfr01(Integer.parseInt(opt(a, "--seeds", "1000")));
        } else {
            engineAndRecording(quick ? 10_000 : 100_000);
            clocks();
            vectorClockTrigger();
            encoding();
            graphAndSeek(quick ? new int[]{10_000} : new int[]{10_000, 100_000});
            replaySpeed(quick ? new int[]{10_000} : new int[]{10_000, 100_000});
            persistedOpen(quick ? 20_000 : 100_000);
            explorerAndBundles(quick ? 20_000 : 100_000);
            branching(quick ? 20_000 : 100_000);
            counterfactualThreads();
            interventionSearch();
            slowDisk(quick ? 5_000 : 20_000);
            if (xl) {
                millionEvents(10);
                millionEvents(20); // the top of the design envelope (NFR-09)
                millionOnDisk(20);
            }
            demoTime();
            soak(soakSeeds);
            nfr01(quick ? 100 : 1000);
        }
        writeCsv(Path.of(csv));
        System.out.println("\nresults written to " + Path.of(csv).toAbsolutePath());
    }

    private static String opt(List<String> a, String k, String d) {
        int i = a.indexOf(k);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : d;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void printEnvironment() {
        Runtime rt = Runtime.getRuntime();
        String cpu = "unknown";
        try {
            Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !o.isEmpty()) cpu = o;
        } catch (IOException | InterruptedException e) {
            // not macOS: keep "unknown"
        }
        String env = "processor " + cpu + " (" + rt.availableProcessors() + " cores), max heap " + rt.maxMemory() / (1 << 20) + " MiB, " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch") + ", JDK " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name");
        System.out.println("CHRONOS benchmark harness" + (quick ? " (QUICK mode: small sizes, fewer repetitions)" : ""));
        System.out.println("environment: " + env);
        System.out.println("method: fixed seeds; " + reps + " repetitions, the first discarded for warm-up; median / p95 / p99\n");
        row("ENV", "environment", "", "description", env, "", "", "", "");
    }

    private static void row(String id, String question, String variant, String metric, String median, String p95, String p99, String unit, String note) {
        ROWS.add(new String[]{id, question, variant, metric, median, p95, p99, unit, note});
        System.out.printf("%-5s %-46s %-26s %-18s %14s %14s %14s %-9s %s%n", id, trunc(question, 46), trunc(variant, 26), trunc(metric, 18), median, p95, p99, unit, note);
    }

    private static void row(String id, String question, String variant, String metric, Stats s, String unit, String note) {
        row(id, question, variant, metric, fmt(s.median), fmt(s.p95), fmt(s.p99), unit, note);
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }

    private static String fmt(double v) {
        return v >= 1000 ? String.format("%.0f", v) : v >= 10 ? String.format("%.1f", v) : String.format("%.3f", v);
    }

    private static void writeCsv(Path p) throws IOException {
        StringBuilder sb = new StringBuilder("id,question,variant,metric,median,p95,p99,unit,note\n");
        for (String[] r : ROWS) {
            for (int i = 0; i < r.length; i++) sb.append(i == 0 ? "" : ",").append('"').append(r[i].replace("\"", "'")).append('"');
            sb.append('\n');
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    /** Counts events and discards them: the "recorder off" variant. */
    private static final class NullSink implements EventSink {
        long events;

        @Override
        public void append(List<Event> group) {
            events += group.size();
        }
    }

    /** One synchronous write and flush per step group, on the engine's own thread. */
    private static final class SyncDiskSink implements EventSink {
        private final BufferedWriter w;

        SyncDiskSink(Path file) throws IOException {
            w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        @Override
        public void append(List<Event> group) {
            try {
                for (Event e : group) w.write(e.toLine() + "\n");
                w.write("{\"commit\":" + group.get(group.size() - 1).seq() + "}\n");
                w.flush();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        @Override
        public void close() {
            try {
                w.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static Scenario gossip(int nodes, long horizon) {
        return new Gossip(nodes, horizon);
    }

    private static long horizonFor(int nodes, long targetEvents) {
        NullSink probe = new NullSink();
        Scenario sc = gossip(nodes, 1000);
        Engine e = Engine.create(sc, 1, FaultPlan.empty(), new RunOptions(1000, false, 0), new RecordDecisionSource(sc, 1), new Recorder("T0", 0, List.of(probe)));
        e.boot();
        e.runToEnd();
        return (long) Math.ceil(targetEvents / (probe.events / 1000.0)) + 2;
    }

    private record Rec(MemoryLog log, RunResult result, TimelineHeader header, double ms) {}

    private static Rec recordMem(int nodes, long targetEvents, long ckpt) {
        long h = horizonFor(nodes, targetEvents);
        Recording.Spec spec = Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", Integer.toString(nodes), "horizon", Long.toString(h))).withCheckpointInterval(ckpt);
        long t0 = System.nanoTime();
        Recording.MemoryRun r = Recording.recordInMemory(REG, "T0", spec);
        return new Rec(r.log(), r.result(), r.header(), (System.nanoTime() - t0) / 1e6);
    }

    private static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }

    private static long dirBytes(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(x -> x.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B1, B2: engine speed and the cost of recording
    // ------------------------------------------------------------------------------------------------------------

    private static void engineAndRecording(int target) throws IOException {
        for (int nodes : new int[]{3, 5, 10}) {
            long h = horizonFor(nodes, target);
            Stats s = Stats.measure(reps, () -> {
                NullSink sink = new NullSink();
                Scenario sc = gossip(nodes, h);
                long t = System.nanoTime();
                Engine e = Engine.create(sc, 1, FaultPlan.empty(), new RunOptions(h, false, 0), new RecordDecisionSource(sc, 1), new Recorder("T0", 0, List.of(sink)));
                e.boot();
                e.runToEnd();
                return e.stepCount() / ((System.nanoTime() - t) / 1e9);
            });
            row("B1", "How fast does the bare engine step, recording off?", "gossip nodes=" + nodes + " ~" + target + " events", "steps/s", s, "steps/s", "baseline only");
        }
        int nodes = 5;
        long h = horizonFor(nodes, target);
        double[] base = new double[1];
        Stats off = Stats.measure(reps, () -> eventsPerSecond(h, nodes, new NullSink()));
        base[0] = off.median;
        row("B2", "What does recording cost?", "recorder off (null sink)", "events/s", off, "events/s", "target NFR-05: >= 50,000 in memory");
        Stats mem = Stats.measure(reps, () -> eventsPerSecond(h, nodes, new MemoryLog()));
        row("B2", "What does recording cost?", "in memory", "events/s", mem, "events/s", (mem.median >= 50_000 ? "meets" : "MISSES") + " NFR-05 (>= 50,000/s); overhead x" + fmt(base[0] / mem.median));
        Path d = tempDir("chronos-b2-");
        try {
            Stats sync = Stats.measure(reps, () -> {
                try {
                    Path f = d.resolve("sync-" + System.nanoTime() + ".log");
                    SyncDiskSink sink = new SyncDiskSink(f);
                    double v = eventsPerSecond(h, nodes, sink);
                    sink.close();
                    return v;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            row("B2", "What does recording cost?", "synchronous disk", "events/s", sync, "events/s", "overhead x" + fmt(base[0] / sync.median));
            int[] n = {0};
            Stats async = Stats.measure(reps, () -> {
                TimelineStore store = new TimelineStore(d.resolve("store" + (n[0]++)));
                Recording.Spec spec = Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000);
                long t = System.nanoTime();
                RunResult r = Recording.record(store, REG, "T0", spec);
                return r.eventCount() / ((System.nanoTime() - t) / 1e9);
            });
            row("B2", "What does recording cost?", "asynchronous disk (the real store)", "events/s", async, "events/s",
                    (async.median >= 20_000 ? "meets" : "MISSES") + " NFR-05 (>= 20,000/s persisted, incl. checkpoints); overhead x" + fmt(base[0] / async.median));
        } finally {
            deleteTree(d);
        }
    }

    private static double eventsPerSecond(long horizon, int nodes, EventSink sink) {
        Scenario sc = gossip(nodes, horizon);
        long t = System.nanoTime();
        Recorder rec = new Recorder("T0", 0, List.of(sink));
        Engine e = Engine.create(sc, 1, FaultPlan.empty(), new RunOptions(horizon, false, 0), new RecordDecisionSource(sc, 1), rec);
        e.boot();
        e.runToEnd();
        return rec.nextSeq() / ((System.nanoTime() - t) / 1e9);
    }

    // ------------------------------------------------------------------------------------------------------------
    // B3, B4: vector clock cost and encoding
    // ------------------------------------------------------------------------------------------------------------

    private static void clocks() {
        for (int nodes : new int[]{3, 10, 20, 50, 100}) {
            ClockService cs = new ClockService(nodes);
            Stamp in = new ClockService(nodes).stamp(nodes - 1, List.of());
            int ops = 200_000;
            Stats s = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                for (int i = 0; i < ops; i++) cs.stamp(i % nodes, List.of(in));
                return (System.nanoTime() - t) / (double) ops;
            });
            Event e = new Event("n#1", 1, "T0", "n", EventKind.APP_NOTE, 1, 1, cs.current(0).vector(), List.of(), Map.of("text", "x"), "d", null, null);
            row("B3", "How does vector-clock cost grow with the node count?", "nodes=" + nodes, "ns per receive", s, "ns/op", "event line " + e.toLine().length() + " bytes; informs compression");
        }
    }

    /**
     * B3b: the trigger of Section 26 for delta compression of vector clocks: "vector-clock cost dominates at 20 nodes". Dominates is read as more
     * than half, of either the time an event costs the engine or the bytes it takes on disk. The time share divides the cost of one clock
     * stamp (B3's measurement, at the same node count) by the engine's measured time per event on a real gossip run; the byte share is the
     * vector field's share of the encoded event lines of that run.
     */
    private static void vectorClockTrigger() {
        for (int nodes : new int[]{10, 20}) {
            ClockService cs = new ClockService(nodes);
            Stamp in = new ClockService(nodes).stamp(nodes - 1, List.of());
            int ops = 200_000;
            Stats stamp = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                for (int i = 0; i < ops; i++) cs.stamp(i % nodes, List.of(in));
                return (System.nanoTime() - t) / (double) ops;
            });
            long horizon = horizonFor(nodes, quick ? 20_000 : 100_000);
            Stats perEvent = Stats.measure(reps, () -> 1e9 / eventsPerSecond(horizon, nodes, new NullSink()));
            double timeShare = 100.0 * stamp.median / perEvent.median;
            List<Event> events = recordMem(nodes, quick ? 20_000 : 100_000, 0).log().events();
            long vectorBytes = 0, allBytes = 0, withState = 0;
            for (Event e : events) {
                if (e.stateAfter() != null) withState++;
                allBytes += e.toLine().length() + 1;
                vectorBytes += ("\"v\":" + Canon.json(e.vector()) + ",").length();
            }
            double byteShare = 100.0 * vectorBytes / allBytes;
            String variant = "nodes=" + nodes + ", " + events.size() + " events";
            row("B3b", "Does vector-clock cost dominate (Section 26 trigger for delta compression)?", variant, "clock stamp as share of the engine's time per event",
                    fmt(timeShare), fmt(timeShare), fmt(timeShare), "%", fmt(stamp.median) + " ns per stamp against " + fmt(perEvent.median) + " ns per event");
            row("B3b", "Does vector-clock cost dominate (Section 26 trigger for delta compression)?", variant, "vector field as share of the encoded event line",
                    fmt(byteShare), fmt(byteShare), fmt(byteShare), "%", vectorBytes / events.size() + " of " + allBytes / events.size() + " bytes per event, state records included (on "
                    + 100 * withState / events.size() + "% of the events)");
            boolean met = timeShare > 50 || byteShare > 50;
            row("B3b", "Does vector-clock cost dominate (Section 26 trigger for delta compression)?", variant, "trigger (more than 50% of time or bytes)",
                    met ? "MET" : "NOT MET", "", "", "", met ? "implement delta compression of vectors" : "delta compression of vectors is not needed; the conditional requirement is closed");
        }
    }

    private static void encoding() {
        Rec r = recordMem(5, quick ? 20_000 : 100_000, 0);
        List<Event> events = r.log().events();
        long bytes = 0, bytesNoState = 0, withState = 0;
        for (Event e : events) {
            bytes += e.toLine().length() + 1;
            if (e.stateAfter() != null) withState++;
            bytesNoState += (e.stateAfter() == null ? e : e.withState(e.stateDigestAfter(), null)).toLine().length() + 1;
        }
        int sample = Math.min(events.size(), 20_000);
        Stats enc = Stats.measure(reps, () -> {
            long t = System.nanoTime();
            for (int i = 0; i < sample; i++) events.get(i).toLine();
            return (System.nanoTime() - t) / 1e3 / sample;
        });
        row("B4", "How big and how slow is event encoding?", events.size() + " events, " + (100 * withState / events.size()) + "% carry a state record", "microseconds/event", enc, "us/event", "bytes/event " + bytes / events.size());
        row("B12", "How much disk does a timeline use?", "with full state records", "bytes/event", String.valueOf(bytes / events.size()), "", "", "bytes", bytes / events.size() + " MB per million events (NFR-10: measured and reported, initial expectation < 300 bytes without states)");
        row("B12", "How much disk does a timeline use?", "digests only, no state records", "bytes/event", String.valueOf(bytesNoState / events.size()), "", "", "bytes", "");
    }

    // ------------------------------------------------------------------------------------------------------------
    // B5, B6: graph and seek
    // ------------------------------------------------------------------------------------------------------------

    private static void graphAndSeek(int[] sizes) {
        for (int size : sizes) graphAndSeekOne(size);
    }

    private static void graphAndSeekOne(int size) {
        Rec r = recordMem(5, size, 1000);
        List<Event> events = r.log().events();
        Stats build = Stats.measure(reps, () -> {
            long t = System.nanoTime();
            CausalGraph.build(events);
            return (System.nanoTime() - t) / 1e6;
        });
        CausalGraph g = CausalGraph.build(events);
        row("B5", "How do the causal graph build and queries scale?", events.size() + " events", "build", build, "ms", g.edgeCount() + " edges (" + fmt((double) g.edgeCount() / g.size()) + " per event)");
        SeededRandom rnd = new SeededRandom(7);
        Stats q = Stats.measure(reps, () -> {
            long t = System.nanoTime();
            for (int i = 0; i < 2000; i++) g.happensBefore((int) Math.floorMod(rnd.nextLong(), (long) g.size()), (int) Math.floorMod(rnd.nextLong(), (long) g.size()));
            return (System.nanoTime() - t) / 2000.0;
        });
        row("B5", "How do the causal graph build and queries scale?", events.size() + " events", "happens-before", q, "ns/query", "vector clocks, cost ~ node count");
        Stats past = Stats.measure(reps, () -> {
            long t = System.nanoTime();
            for (int i = 0; i < 20; i++) g.causalPast(g.size() - 1 - i);
            return (System.nanoTime() - t) / 1e6 / 20;
        });
        row("B5", "How do the causal graph build and queries scale?", events.size() + " events", "causal past (last)", past, "ms/query", "walks parents backwards");

        MemoryTimelines mem = MemoryTimelines.of(new Recording.MemoryRun(r.log(), r.result(), r.header()));
        long t0 = System.nanoTime();
        TimeTravelSession s = new TimeTravelSession(mem, "T0");
        double open = (System.nanoTime() - t0) / 1e6;
        Stats seek = Stats.measure(reps, () -> {
            double worst = 0;
            for (int i = 0; i < 100; i++) {
                long t = System.nanoTime();
                s.goTo((int) Math.floorMod(rnd.nextLong(), (long) events.size()));
                s.world(); // every node's state, the in-flight messages, the pending timers and the clocks at that moment (G3)
                worst = Math.max(worst, (System.nanoTime() - t) / 1e6);
            }
            return worst;
        });
        double limit = size <= 100_000 ? 1000 : 5000;
        row("B6", "How fast can we seek?", events.size() + " events", "worst of 100 jumps + world", seek, "ms", (seek.p99 < limit ? "meets" : "MISSES") + " NFR-06 (<" + (long) limit + " ms); opening the session (graph + indexes): " + fmt(open) + " ms");
    }

    // ------------------------------------------------------------------------------------------------------------
    // B15, B16: systematic exploration throughput and incident bundles
    // ------------------------------------------------------------------------------------------------------------

    private static void explorerAndBundles(int size) throws IOException {
        SystematicExplorer ex = new SystematicExplorer(REG);
        List<SystematicExplorer.Candidate> cands = ex.generate("re", Map.of("partition", "off"), 42, null, SystematicExplorer.Grid.standard(List.of(30L, 100L, 300L)));
        SystematicExplorer.Config cfg = new SystematicExplorer.Config("re", Map.of("partition", "off"), 42, null, FaultPlan.empty(), cands, 2, 1_000_000, false, true);
        long[] executed = {0};
        Stats s = Stats.measure(reps, () -> {
            long t = System.nanoTime();
            SystematicExplorer.Report r = ex.explore(cfg);
            executed[0] = r.executed();
            return (System.nanoTime() - t) / 1e6;
        });
        row("B15", "How fast is systematic exploration of fault schedules?", "RE, " + cands.size() + " candidates, <= 2 faults", "full exploration", s, "ms",
                executed[0] + " schedules executed: " + fmt(executed[0] / (s.median / 1000)) + " schedules/s, each a complete deterministic run with invariant checks");
        Path d = tempDir("chronos-b16-");
        try {
            TimelineStore store = new TimelineStore(d.resolve("a"));
            long h = horizonFor(5, size);
            Recording.record(store, REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
            int n = store.events("T0").size();
            Path bundle = d.resolve("t0.chronos-incident");
            Stats exp = Stats.measure(reps, () -> {
                try { Files.deleteIfExists(bundle); } catch (IOException e) { throw new UncheckedIOException(e); }
                long t = System.nanoTime();
                IncidentBundle.export(store, "T0", bundle);
                return (System.nanoTime() - t) / 1e6;
            });
            row("B16", "What do portable incident bundles cost?", n + " events", "export", exp, "ms", Files.size(bundle) / 1024 + " KiB (" + fmt((double) Files.size(bundle) / n) + " bytes/event compressed)");
            int[] k = {0};
            Stats imp = Stats.measure(reps, () -> {
                TimelineStore target = new TimelineStore(d.resolve("t" + (k[0]++)));
                long t = System.nanoTime();
                IncidentBundle.importInto(target, bundle, REG);
                return (System.nanoTime() - t) / 1e6;
            });
            row("B16", "What do portable incident bundles cost?", n + " events", "validated import", imp, "ms", "every file checksum, the log's commit checksums, count, log hash and causal evidence recomputed");
            Stats rep = Stats.measure(Math.min(reps, 3), () -> {
                long t = System.nanoTime();
                IncidentBundle.Reproduction r = IncidentBundle.reproduce(bundle, REG);
                if (!r.ok()) throw new IllegalStateException(r.problems().toString());
                return (System.nanoTime() - t) / 1e6;
            });
            row("B16", "What do portable incident bundles cost?", n + " events", "reproduce (import, replay verify, fresh run)", rep, "ms", "");
        } finally {
            deleteTree(d);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B6b: opening a persisted timeline, with the persisted index files and without them
    // ------------------------------------------------------------------------------------------------------------

    private static void persistedOpen(int size) throws IOException {
        Path d = tempDir("chronos-b6b-");
        try {
            TimelineStore store = new TimelineStore(d);
            long h = horizonFor(5, size);
            Recording.record(store, REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
            Path dir = d.resolve("timelines/T0");
            long logBytes = dirBytes(dir.resolve("events")), idxBytes = Files.size(dir.resolve("index/main.idx"));
            int n = store.events("T0").size();
            Stats parse = Stats.measure(reps, () -> {
                TimelineStore fresh = new TimelineStore(d);
                long t = System.nanoTime();
                fresh.events("T0");
                return (System.nanoTime() - t) / 1e6;
            });
            row("B6b", "What does opening a persisted timeline cost?", n + " events", "parse the log from disk", parse, "ms", fmt((double) logBytes / n) + " bytes/event on disk");
            TimelineStore warm = new TimelineStore(d);
            List<Event> events = warm.events("T0");
            Stats load = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                TimelineIndex ix = warm.index("T0");
                double ms = (System.nanoTime() - t) / 1e6;
                if (!ix.source().equals("persisted")) throw new IllegalStateException("index was not loaded from its file");
                return ms;
            });
            row("B6b", "What does opening a persisted timeline cost?", n + " events", "load persisted indexes", load, "ms", idxBytes + " bytes (" + fmt((double) idxBytes / n) + " bytes/event)");
            Stats rebuild = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                new TimelineIndex(events);
                return (System.nanoTime() - t) / 1e6;
            });
            row("B6b", "What does opening a persisted timeline cost?", n + " events", "rebuild indexes from the log", rebuild, "ms", "the fallback when an index file is missing or suspect");
            // opt-in log compression (cut line 6): size and cost against the plain log of the same run
            Path dz = tempDir("chronos-b6c-");
            try {
                TimelineStore zs = new TimelineStore(dz).compressNewTimelines(true);
                Stats recPlain = Stats.measure(reps, () -> {
                    Path dd;
                    try { dd = tempDir("chronos-b6d-"); } catch (IOException e) { throw new UncheckedIOException(e); }
                    try {
                        long t = System.nanoTime();
                        Recording.record(new TimelineStore(dd), REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
                        return (System.nanoTime() - t) / 1e6;
                    } finally { deleteTree(dd); }
                });
                Stats recZip = Stats.measure(reps, () -> {
                    Path dd;
                    try { dd = tempDir("chronos-b6e-"); } catch (IOException e) { throw new UncheckedIOException(e); }
                    try {
                        long t = System.nanoTime();
                        Recording.record(new TimelineStore(dd).compressNewTimelines(true), REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
                        return (System.nanoTime() - t) / 1e6;
                    } finally { deleteTree(dd); }
                });
                Recording.record(zs, REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
                long zipBytes = dirBytes(dz.resolve("timelines/T0/events"));
                row("B12", "How much disk does a timeline use?", "gzip-compressed segments (opt-in --compress)", "bytes/event", String.valueOf(zipBytes / n), "", "", "bytes",
                        "x" + fmt((double) logBytes / zipBytes) + " smaller than the plain log; recording takes x" + fmt(recZip.median / recPlain.median) + " of the plain time (" + fmt(recPlain.median) + " ms plain, " + fmt(recZip.median) + " ms compressed)");
                TimelineStore zfresh = new TimelineStore(dz);
                Stats zparse = Stats.measure(reps, () -> {
                    TimelineStore f = new TimelineStore(dz);
                    long t = System.nanoTime();
                    f.events("T0");
                    return (System.nanoTime() - t) / 1e6;
                });
                row("B6b", "What does opening a persisted timeline cost?", n + " events", "parse the compressed log from disk", zparse, "ms", "x" + fmt(zparse.median / parse.median) + " of the plain parse time");
                if (!zfresh.events("T0").equals(events)) throw new IllegalStateException("compressed timeline differs from the plain one");
            } finally {
                deleteTree(dz);
            }
        } finally {
            deleteTree(d);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B7: replay speed
    // ------------------------------------------------------------------------------------------------------------

    private static void replaySpeed(int[] sizes) {
        for (int size : sizes) {
            Rec r = recordMem(5, size, 1000);
            MemoryTimelines mem = MemoryTimelines.of(new Recording.MemoryRun(r.log(), r.result(), r.header()));
            ReplayEngine re = new ReplayEngine(mem, REG);
            double[] ok = {1};
            Stats replay = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                ReplayEngine.Result res = re.verify("T0");
                if (!res.ok()) ok[0] = 0;
                return (System.nanoTime() - t) / 1e6;
            });
            Stats record = Stats.measure(reps, () -> recordMem(5, size, 1000).ms());
            row("B7", "How fast is verification replay?", r.log().events().size() + " events", "replay time", replay, "ms",
                    "ratio to recording x" + fmt(replay.median / record.median) + (replay.median / record.median <= 2 ? " meets" : " MISSES") + " NFR-07 (<= 2x); verified=" + (ok[0] == 1));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B8, B10: branching and comparison
    // ------------------------------------------------------------------------------------------------------------

    private static void branching(int size) throws IOException {
        Path d = tempDir("chronos-b8-");
        try {
            TimelineStore store = new TimelineStore(d);
            long h = horizonFor(5, size);
            Recording.record(store, REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(1000));
            List<Event> ev = store.events("T0");
            long parentBytes = dirBytes(d.resolve("timelines/T0"));
            TimelineManager mgr = new TimelineManager(store, REG);
            for (double frac : new double[]{0.1, 0.5, 0.9}) {
                long at = mgr.roundToBoundary("T0", (long) (ev.size() * frac));
                long vt = ev.get((int) at).virtualTime();
                double[] bytes = {0};
                Stats s = Stats.measure(reps, () -> {
                    long t = System.nanoTime();
                    TimelineManager.BranchResult b = mgr.branch("T0", at, List.of(), vt + 50, "bench");
                    double ms = (System.nanoTime() - t) / 1e6;
                    bytes[0] = dirBytes(d.resolve("timelines/" + b.timelineId()));
                    store.delete(b.timelineId());
                    return ms;
                });
                row("B8", "What does branching cost, and does sharing the prefix pay off?", "prefix " + at + " of " + ev.size() + " events", "branch creation", s, "ms",
                        "branch stores " + bytes[0] / 1024 + " KiB vs parent " + parentBytes / 1024 + " KiB (a full copy would be >= " + parentBytes * (at + 1) / ev.size() / 1024 + " KiB)");
            }
            long at = mgr.roundToBoundary("T0", 100);
            TimelineManager.BranchResult b = mgr.branch("T0", at, List.of(), null, "bench-compare");
            Stats cmp = Stats.measure(reps, () -> {
                long t = System.nanoTime();
                new TimelineComparator(store).compare("T0", b.timelineId(), false);
                return (System.nanoTime() - t) / 1e6;
            });
            row("B10", "How does comparison scale?", store.events(b.timelineId()).size() + " events, branch at " + at, "compare", cmp, "ms", "linear in events after indexing");
        } finally {
            deleteTree(d);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B9: parallel counterfactuals
    // ------------------------------------------------------------------------------------------------------------

    private static void counterfactualThreads() throws IOException {
        int cores = Runtime.getRuntime().availableProcessors();
        int[] threads = {1, 2, 4, 8};
        double base = 0;
        for (int th : threads) {
            if (th > cores) continue;
            Path d = tempDir("chronos-b9-");
            try {
                TimelineStore store = new TimelineStore(d);
                Recording.record(store, REG, "T0", Recording.Spec.of("re", 42).withStopOnViolation(Boolean.FALSE).withCheckpointInterval(10));
                TimelineManager mgr = new TimelineManager(store, REG);
                long at = store.events("T0").stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq();
                List<CounterfactualEngine.Query> qs = new ArrayList<>();
                int n = quick ? 12 : 48;
                for (int i = 0; i < n; i++) qs.add(new CounterfactualEngine.Query("T0", at, List.of(Intervention.shiftHeal("s" + i, "partition-pb", -(10 + 5 * i))), null, null));
                final double[] t = new double[1];
                Stats s = Stats.measure(Math.min(reps, 3) + 1, () -> {
                    TimelineStore st = new TimelineStore(d);
                    TimelineManager m = new TimelineManager(st, REG);
                    long t0 = System.nanoTime();
                    new CounterfactualEngine(m).runAll(qs, th);
                    t[0] = (System.nanoTime() - t0) / 1e9;
                    for (String c : new ArrayList<>(st.children("T0"))) st.delete(c);
                    return qs.size() / t[0];
                });
                if (th == 1) base = s.median;
                row("B9", "How does counterfactual throughput scale with threads?", th + " thread(s), " + n + " runs", "runs/s", s, "runs/s", "speedup x" + fmt(s.median / base) + " (reports are identical for every thread count, law M12)");
            } finally {
                deleteTree(d);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // XL: a million events
    // ------------------------------------------------------------------------------------------------------------

    private static void millionEvents(int nodes) {
        System.gc();
        long before = usedMemory();
        Rec r = recordMem(nodes, 1_000_000, 1000);
        System.gc();
        long after = usedMemory();
        List<Event> ev = r.log().events();
        row("XL", "Can a million-event timeline be recorded in memory?", ev.size() + " events, " + nodes + " nodes", "record time", fmt(r.ms()), "", "", "ms", fmt(ev.size() / (r.ms() / 1000)) + " events/s");
        row("XL", "Memory profile of a million events", ev.size() + " events, " + nodes + " nodes, held in memory", "heap used", String.valueOf((after - before) / (1 << 20)), "", "", "MiB", (after - before) / ev.size() + " bytes per event (events + checkpoints + indexes of the log)");
        long t0 = System.nanoTime();
        CausalGraph g = CausalGraph.build(ev);
        double gb = (System.nanoTime() - t0) / 1e6;
        row("XL", "Can the causal graph hold a million events?", ev.size() + " events, " + nodes + " nodes", "graph build", fmt(gb), "", "", "ms", g.edgeCount() + " edges");
        MemoryTimelines mem = MemoryTimelines.of(new Recording.MemoryRun(r.log(), r.result(), r.header()));
        long t1 = System.nanoTime();
        TimeTravelSession s = new TimeTravelSession(mem, "T0");
        double open = (System.nanoTime() - t1) / 1e6;
        SeededRandom rnd = new SeededRandom(3);
        double worst = 0;
        for (int i = 0; i < 100; i++) {
            long t = System.nanoTime();
            s.goTo((int) Math.floorMod(rnd.nextLong(), (long) ev.size()));
            s.world();
            worst = Math.max(worst, (System.nanoTime() - t) / 1e6);
        }
        row("XL", "Seek in a million-event timeline", ev.size() + " events, " + nodes + " nodes", "worst of 100 jumps", fmt(worst), "", "", "ms", (worst < 5000 ? "meets" : "MISSES") + " NFR-06 (< 5000 ms); session open " + fmt(open) + " ms");
        long t2 = System.nanoTime();
        ReplayEngine.Result res = new ReplayEngine(mem, REG).verify("T0");
        double rep = (System.nanoTime() - t2) / 1e6;
        row("XL", "Verification replay of a million events", ev.size() + " events, " + nodes + " nodes", "replay time", fmt(rep), "", "", "ms", "verified=" + res.ok() + ", ratio to recording x" + fmt(rep / r.ms()) + (rep / r.ms() <= 2 ? " meets" : " MISSES") + " NFR-07 (<= 2x)");
    }

    /** A million-event timeline written to and read back from disk by the real store (NFR-05 persisted, NFR-06, NFR-07, NFR-10 at scale). */
    private static void millionOnDisk(int nodes) throws IOException {
        Path d = tempDir("chronos-xld-");
        try {
            TimelineStore store = new TimelineStore(d);
            long h = horizonFor(nodes, 1_000_000);
            long t0 = System.nanoTime();
            Recording.record(store, REG, "T0", Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", Integer.toString(nodes), "horizon", Long.toString(h))).withCheckpointInterval(1000));
            double recMs = (System.nanoTime() - t0) / 1e6;
            long n = store.summary("T0").eventCount();
            row("XL", "A million events persisted by the real store", n + " events, " + nodes + " nodes, on disk", "record + persist", fmt(recMs), "", "", "ms",
                    fmt(n / (recMs / 1000)) + " events/s " + (n / (recMs / 1000) >= 20_000 ? "meets" : "MISSES") + " NFR-05 (>= 20,000/s persisted)");
            long logBytes = dirBytes(d.resolve("timelines/T0/events")), all = dirBytes(d.resolve("timelines/T0"));
            row("XL", "A million events persisted by the real store", n + " events, " + nodes + " nodes, on disk", "bytes per event", String.valueOf(logBytes / n), "", "", "bytes",
                    "log " + logBytes / (1 << 20) + " MiB, whole timeline directory (checkpoints, indexes) " + all / (1 << 20) + " MiB");
            TimelineStore fresh = new TimelineStore(d);
            long t1 = System.nanoTime();
            List<Event> ev = fresh.events("T0");
            double parse = (System.nanoTime() - t1) / 1e6;
            long t2 = System.nanoTime();
            TimelineIndex ix = fresh.index("T0");
            double idx = (System.nanoTime() - t2) / 1e6;
            row("XL", "Opening a million persisted events in a fresh store", n + " events, " + nodes + " nodes", "parse the log", fmt(parse), "", "", "ms", "indexes " + ix.source() + " in " + fmt(idx) + " ms");
            long t3 = System.nanoTime();
            TimeTravelSession s = new TimeTravelSession(fresh, "T0");
            double open = (System.nanoTime() - t3) / 1e6;
            SeededRandom rnd = new SeededRandom(5);
            double worst = 0;
            for (int i = 0; i < 100; i++) {
                long t = System.nanoTime();
                s.goTo((int) Math.floorMod(rnd.nextLong(), (long) ev.size()));
                s.world();
                worst = Math.max(worst, (System.nanoTime() - t) / 1e6);
            }
            row("XL", "Seek in a million persisted events", n + " events, " + nodes + " nodes", "worst of 100 jumps", fmt(worst), "", "", "ms", (worst < 5000 ? "meets" : "MISSES") + " NFR-06 (< 5000 ms); session open " + fmt(open) + " ms (the log was already parsed)");
            long t4 = System.nanoTime();
            ReplayEngine.Result res = new ReplayEngine(fresh, REG).verify("T0");
            double rep = (System.nanoTime() - t4) / 1e6;
            row("XL", "Verification replay of a million persisted events", n + " events, " + nodes + " nodes", "replay time", fmt(rep), "", "", "ms", "verified=" + res.ok() + ", ratio to the persisted recording x" + fmt(rep / recMs) + (rep / recMs <= 2 ? " meets" : " MISSES") + " NFR-07 (<= 2x)");
        } finally {
            deleteTree(d);
        }
    }

    private static long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ------------------------------------------------------------------------------------------------------------
    // B11: intervention search
    // ------------------------------------------------------------------------------------------------------------

    private static void interventionSearch() throws IOException {
        Path d = tempDir("chronos-b11-");
        try {
            TimelineStore store = new TimelineStore(d);
            TimelineManager mgr = new TimelineManager(store, REG);
            Recording.record(store, REG, "T0", Recording.Spec.of("re", 42));
            Recording.record(store, REG, "T1", Recording.Spec.of("ka2", 1));
            for (String tl : List.of("T0", "T1")) {
                for (int threads : new int[]{1, 4}) {
                    int[] tried = {0}, crit = {0};
                    Stats s = Stats.measure(reps, () -> {
                        long t = System.nanoTime();
                        InterventionSearch.Result r = new InterventionSearch(mgr).search(tl, 64, threads, false);
                        double ms = (System.nanoTime() - t) / 1e6;
                        tried[0] = r.suspectsTried();
                        crit[0] = r.critical().size();
                        return ms;
                    });
                    row("B11", "How long does intervention search take?", (tl.equals("T0") ? "RE" : "lost update") + ", " + store.events(tl).size() + " events, " + threads + " thread(s)",
                            "search time", s, "ms", tried[0] + " real counterfactual branches tried, " + crit[0] + " critical");
                }
            }
        } finally {
            deleteTree(d);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // B14: the acceptance demonstrations, and the chaos soak
    // ------------------------------------------------------------------------------------------------------------

    private static void demoTime() throws IOException {
        Path d = tempDir("chronos-b14-");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long t = System.nanoTime();
            int code = Main.run(new String[]{"--data", d.toString(), "demo"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()), InputStream.nullInputStream());
            double s = (System.nanoTime() - t) / 1e9;
            row("B14", "How long do the acceptance demonstrations take?", "AD-1 to AD-6 + KA scenarios", "wall clock", fmt(s), "", "", "seconds", (code == 0 ? "all checks passed" : "CHECKS FAILED") + "; target < 600 s");
        } finally {
            deleteTree(d);
        }
    }

    /** Chaos soak (Phase 16): thousands of seeds of the control scenario under random fault plans; the laws are checked on every run. */
    private static void soak(int seeds) {
        long t = System.nanoTime();
        int[] replayed = {0};
        Campaign.Check check = run -> {
            List<String> problems = new ArrayList<>();
            long seed = run.header().seed();
            if (seed % 100 == 0) {
                problems.addAll(CausalGraph.build(run.log().events()).selfCheck(300));
                ReplayEngine.Result r = new ReplayEngine(MemoryTimelines.of(run), REG).verify(run.header().timelineId());
                if (!r.ok()) problems.add("seed " + seed + " replay: " + r.summary());
                synchronized (replayed) {
                    replayed[0]++;
                }
            }
            return problems;
        };
        List<Campaign.Entry> res = Campaign.run(REG, "control", Map.of(), 1, seeds, Runtime.getRuntime().availableProcessors(), true, null, check);
        long violations = res.stream().filter(Campaign.Entry::violated).count();
        long problems = res.stream().mapToLong(e -> e.problems().size()).sum();
        long events = res.stream().mapToLong(Campaign.Entry::events).sum();
        double sec = (System.nanoTime() - t) / 1e9;
        row("SOAK", "Does a correct protocol survive random fault plans?", seeds + " seeds, KA-0 control", "violations", String.valueOf(violations), "", "", "runs", "expected 0; law problems: " + problems + "; " + replayed[0] + " runs also graph-checked and replay-verified");
        row("SOAK", "Chaos soak throughput", seeds + " seeds, " + events + " events", "wall clock", fmt(sec), "", "", "seconds", fmt(seeds / sec) + " runs/s on " + Runtime.getRuntime().availableProcessors() + " threads");
    }

    // ------------------------------------------------------------------------------------------------------------
    // B13: the asynchronous writer on a slow disk
    // ------------------------------------------------------------------------------------------------------------

    private static void slowDisk(int targetEvents) throws IOException {
        long h = horizonFor(5, targetEvents);
        for (long delayMicros : new long[]{0, 100, 500, 2000}) {
            Path d = tempDir("chronos-b13-");
            try {
                TimelineStore store = new TimelineStore(d);
                Recording.Spec spec = Recording.Spec.of("gossip", 1).withParams(Map.of("nodes", "5", "horizon", Long.toString(h))).withCheckpointInterval(0);
                Scenario sc = REG.create("gossip", spec.params());
                TimelineHeader header = Recording.headerFor(sc, spec, "T0");
                TimelineWriter w = store.openWriter(header, 5000, delayMicros);
                long t0 = System.nanoTime();
                Recorder rec = new Recorder("T0", 0, List.of(w));
                Engine e = Engine.create(sc, 1, header.faultPlan(), new RunOptions(header.horizon(), false, 0), new RecordDecisionSource(sc, 1), rec);
                e.boot();
                e.runToEnd();
                double engineSec = (System.nanoTime() - t0) / 1e9; // includes waiting for the queue (back-pressure) and the final drain
                w.close();
                long events = rec.nextSeq();
                row("B13", "How does the asynchronous writer behave under a slow disk?", "simulated " + delayMicros + " us per step group", "events/s", fmt(events / engineSec), "", "", "events/s",
                        "queue high-water " + w.queueHighWater() + " of 256 groups (bounded: the engine waits instead of growing memory); all " + events + " events persisted and readable: "
                                + (new TimelineStore(d).events("T0").size() == events));
            } finally {
                deleteTree(d);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // NFR-01: byte-identical logs, 100% over 1,000 seeded runs of each demo scenario
    // ------------------------------------------------------------------------------------------------------------

    private static void nfr01(int seeds) {
        Map<String, Map<String, String>> demos = new LinkedHashMap<>();
        for (String n : List.of("pingpong", "causal", "re", "re2", "ka2", "ka3", "control")) demos.put(n, Map.of());
        demos.put("gossip", Map.of("nodes", "4", "horizon", "300"));
        int threads = Runtime.getRuntime().availableProcessors();
        for (Map.Entry<String, Map<String, String>> sc : demos.entrySet())
            for (boolean chaos : new boolean[]{false, true}) {
                long t = System.nanoTime();
                List<Campaign.Entry> a = Campaign.run(REG, sc.getKey(), sc.getValue(), 1, seeds, threads, chaos, 600L, null);
                List<Campaign.Entry> b = Campaign.run(REG, sc.getKey(), sc.getValue(), 1, seeds, Math.max(1, threads / 2), chaos, 600L, null);
                int same = 0;
                for (int i = 0; i < seeds; i++) if (a.get(i).logHash().equals(b.get(i).logHash())) same++;
                double sec = (System.nanoTime() - t) / 1e9;
                row("NFR01", "Byte-identical logs over " + seeds + " seeded runs", sc.getKey() + (chaos ? " with chaos plans" : " default plan"), "identical", same + "/" + seeds, "", "", "runs",
                        (same == seeds ? "100% identical" : "MISMATCH") + " (each seed run twice, on different thread counts); " + fmt(sec) + " s");
            }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Evaluation on unplanned failures (Phase 17): a chaos campaign finds failing runs nobody planted; for each, CHRONOS names a root
    // fault from the causal chain and a real counterfactual (remove that fault rule) tests whether it was a but-for cause.
    // ------------------------------------------------------------------------------------------------------------

    private static void evaluate(int seeds) throws IOException {
        for (String sc : List.of("re", "re2")) {
            Map<String, String> params = Map.of("partition", "off");
            List<Campaign.Entry> res = Campaign.run(REG, sc, params, 1, seeds, Runtime.getRuntime().availableProcessors(), true, 900L, null);
            List<Campaign.Entry> failing = res.stream().filter(Campaign.Entry::violated).toList();
            Path d = tempDir("chronos-eval-");
            try {
                TimelineStore store = new TimelineStore(d);
                TimelineManager mgr = new TimelineManager(store, REG);
                CounterfactualEngine cf = new CounterfactualEngine(mgr);
                Map<String, Integer> verdicts = new TreeMap<>();
                Map<String, Integer> rootKinds = new TreeMap<>();
                int noFaultRoot = 0, shown = 0;
                System.out.println("\nEVAL " + sc + ": " + seeds + " chaos seeds, " + failing.size() + " ended in a violation");
                for (Campaign.Entry e : failing) {
                    String id = mgr.allocateRootId();
                    Recording.record(store, REG, id, Recording.Spec.of(sc, e.seed()).withParams(params).withPlan(e.plan()).withHorizon(900L).withCheckpointInterval(25));
                    List<Event> ev = store.events(id);
                    TimeTravelSession s = new TimeTravelSession(store, id);
                    s.goToViolation();
                    List<List<Integer>> chains = s.whyChains(s.cursor());
                    Event root = chains.isEmpty() ? null : ev.get(chains.get(0).get(0));
                    if (root == null || root.kind() != EventKind.FAULT_INJECTED) {
                        noFaultRoot++;
                        continue;
                    }
                    String rule = (String) root.payload().get("rule");
                    String kind = rule.substring(rule.indexOf('-') + 1);
                    rootKinds.merge(kind, 1, Integer::sum);
                    long first = ev.stream().filter(x -> x.kind() == EventKind.FAULT_INJECTED && rule.equals(x.payload().get("rule"))).findFirst().orElseThrow().seq();
                    long at = mgr.roundToBoundary(id, Math.max(0, first - 1));
                    CounterfactualEngine.Report rep = cf.run(new CounterfactualEngine.Query(id, at, List.of(Intervention.removeFault("rm", rule)), null, null));
                    verdicts.merge(rep.verdict().name(), 1, Integer::sum);
                    if (shown++ < 4)
                        System.out.println("  seed " + e.seed() + ": root " + root.eventId() + " (" + Exports.describe(root, null) + ") -> remove it: " + rep.verdict());
                    store.delete(rep.timelineId());
                    store.delete(id);
                }
                int named = failing.size() - noFaultRoot;
                row("EVAL", "Unplanned failures found by a chaos campaign", sc + ", " + seeds + " seeds", "failing runs", String.valueOf(failing.size()), "", "", "runs",
                        "root fault named in " + named + "; root kinds " + rootKinds);
                row("EVAL", "Is the named root fault a but-for cause? (real counterfactual: remove the rule)", sc, "verdicts", verdicts.toString(), "", "", "runs",
                        "FAILURE_PERSISTS means overdetermined or another cause remains; " + noFaultRoot + " failing runs had no fault as the first source");
            } finally {
                deleteTree(d);
            }
        }
    }

    /**
     * Randomised planted causes (the blind kit's cases), diagnosed by CHRONOS from the recording alone, with no access to the plan: does
     * the "why" analysis name exactly the planted fault rules, and does the search over critical fault sets agree? Not blind (the analysers
     * were developed with these scenarios in view); it shows accuracy across randomised parameters, not on bugs nobody planted.
     */
    private static void evaluatePlanted(int tokens) throws IOException {
        BlindKit.Registry reg = new BlindKit.Registry();
        Map<Integer, int[]> byKind = new TreeMap<>(); // kind -> {runs, whyExact, searchExact, butForConfirmed, whyPlusSilenceExact}
        long nanos = 0;
        for (long token = 1; token <= tokens; token++) {
            BlindKit.Case c = BlindKit.caseFor(token);
            Path d = tempDir("chronos-evalp-");
            try {
                TimelineStore store = new TimelineStore(d);
                TimelineManager mgr = new TimelineManager(store, reg);
                FaultPlan plan = FaultPlan.empty();
                for (FaultRule r : c.plan().rules()) plan = plan.with(r);
                Recording.record(store, reg, "T0", Recording.Spec.of("mystery", 42).withParams(Map.of("m", Long.toHexString(token))).withPlan(plan).withStopOnViolation(false).withCheckpointInterval(25));
                long t0 = System.nanoTime();
                List<Event> ev = store.events("T0");
                TimeTravelSession s = new TimeTravelSession(store, "T0");
                s.goToViolation();
                Set<String> why = new TreeSet<>(), whySilence = new TreeSet<>();
                for (TimeTravelSession.Silence si : s.silence(s.cursor())) if (si.faultRule() != null) whySilence.add(si.faultRule());
                for (List<Integer> chain : s.whyChains(s.cursor())) {
                    Event src = ev.get(chain.get(0));
                    if (src.kind() == EventKind.FAULT_INJECTED && src.payload().get("rule") != null) why.add((String) src.payload().get("rule"));
                }
                whySilence.addAll(why);
                CounterfactualEngine.CauseSearch cs = new CounterfactualEngine(mgr).findCriticalFaultSets("T0", 2, 4);
                Set<String> search = cs.minimalCriticalSets().isEmpty() ? Set.of() : cs.minimalCriticalSets().get(0);
                nanos += System.nanoTime() - t0;
                int[] t = byKind.computeIfAbsent((int) (token % BlindKit.CASES), k -> new int[5]);
                t[0]++;
                if (why.equals(c.truth())) t[1]++;
                if (search.equals(c.truth())) t[2]++;
                if (!cs.minimalCriticalSets().isEmpty()) t[3]++;
                if (whySilence.equals(c.truth())) t[4]++;
            } finally {
                deleteTree(d);
            }
        }
        String[] names = {"split brain (partition)", "lost update (delay)", "blocked commit (crash)", "overdetermined (two partitions)", "sender/receiver partition"};
        int[] all = new int[5];
        for (Map.Entry<Integer, int[]> e : byKind.entrySet()) {
            int[] t = e.getValue();
            for (int i = 0; i < 5; i++) all[i] += t[i];
            row("EVAL-PLANTED", "Randomised planted causes diagnosed from the recording alone", names[e.getKey()], "exact match with the planted set", t[1] + "/" + t[0], "", "", "cases",
                    "`why` chain sources: " + t[1] + "/" + t[0] + "; `why` plus possible silence: " + t[4] + "/" + t[0] + "; minimal critical set from `causes`: " + t[2] + "/" + t[0]);
        }
        row("EVAL-PLANTED", "Randomised planted causes diagnosed from the recording alone", "all " + tokens + " tokens", "exact match with the planted set", all[1] + "/" + all[0], "", "", "cases",
                "`why` " + all[1] + "/" + all[0] + ", `why` + silence " + all[4] + "/" + all[0] + ", `causes` " + all[2] + "/" + all[0] + "; mean diagnosis time " + fmt(nanos / 1e6 / tokens) + " ms; NOT blind: see docs/BLIND_PROTOCOL.md");
    }

    /**
     * Systematic exploration against random sampling at the same budget. For each scenario the explorer enumerates every subset of at most
     * two generated candidate faults; every minimal violating set it reports is re-verified independently (it violates, and no proper subset
     * does). The random baseline draws the same number of subsets (with replacement, fixed seed) from the same space and counts how many of
     * the minimal violating sets it happened to hit. Systematic exploration is exhaustive within the bound by construction; the experiment
     * shows what that buys, not that it finds bugs outside the bound.
     */
    private static void evaluateExploration() {
        SystematicExplorer ex = new SystematicExplorer(REG);
        for (String[] sc : new String[][]{{"re", "partition=off"}, {"re2", "partition=off"}, {"ka2", "delay=off"}, {"ka3", "crash=off"}, {"control", ""}}) {
            Map<String, String> params = sc[1].isEmpty() ? Map.of() : Map.of(sc[1].split("=")[0], sc[1].split("=")[1]);
            List<SystematicExplorer.Candidate> cands = ex.generate(sc[0], params, 42, null, SystematicExplorer.Grid.standard(List.of(30L, 100L, 300L)));
            SystematicExplorer.Config cfg = new SystematicExplorer.Config(sc[0], params, 42, null, FaultPlan.empty(), cands, 2, 1_000_000, false, true);
            long t0 = System.nanoTime();
            SystematicExplorer.Report rep = ex.explore(cfg);
            double ms = (System.nanoTime() - t0) / 1e6;
            // independent verification of every reported minimal set: it violates, and no proper subset does
            Map<String, FaultRule> byName = new HashMap<>();
            for (SystematicExplorer.Candidate c : cands) byName.put(c.name(), c.rule());
            int verified = 0;
            for (SystematicExplorer.Finding f : rep.findings()) {
                boolean minimal = true;
                for (int drop = 0; drop < f.faults().size(); drop++) {
                    FaultPlan sub = FaultPlan.empty();
                    for (int i = 0; i < f.faults().size(); i++) if (i != drop) sub = sub.with(byName.get(f.faults().get(i)));
                    if (Recording.recordInMemory(REG, "Xv", Recording.Spec.of(sc[0], 42).withParams(params).withPlan(sub).withStopOnViolation(true).withCheckpointInterval(0)).result().violated()) minimal = false;
                }
                boolean violates = Recording.recordInMemory(REG, "Xv", Recording.Spec.of(sc[0], 42).withParams(params).withPlan(f.plan()).withStopOnViolation(true).withCheckpointInterval(0)).result().violated();
                if (minimal && violates) verified++;
            }
            // random baseline at the same budget over the same space
            SeededRandom rnd = new SeededRandom(2024);
            Set<List<String>> minimalSets = new HashSet<>();
            for (SystematicExplorer.Finding f : rep.findings()) minimalSets.add(f.faults());
            long budget = rep.executed();
            Set<List<String>> hit = new HashSet<>();
            for (long i = 0; i < budget; i++) {
                int size = (int) Math.floorMod(rnd.nextLong(), 3L); // 0..2 faults
                TreeSet<Integer> pick = new TreeSet<>();
                while (pick.size() < Math.min(size, cands.size())) pick.add((int) Math.floorMod(rnd.nextLong(), (long) cands.size()));
                List<String> names = new ArrayList<>();
                FaultPlan plan = FaultPlan.empty();
                for (int c : pick) {
                    names.add(cands.get(c).name());
                    plan = plan.with(cands.get(c).rule());
                }
                if (Recording.recordInMemory(REG, "Xr", Recording.Spec.of(sc[0], 42).withParams(params).withPlan(plan).withStopOnViolation(true).withCheckpointInterval(0)).result().violated()
                        && minimalSets.contains(names)) hit.add(names);
            }
            row("EVAL-EXPLORE", "Systematic exploration of fault schedules against random sampling at the same budget", sc[0] + " " + sc[1] + ", " + cands.size() + " candidates, <= 2 faults",
                    "minimal violating sets found", rep.findings().size() + " (all " + verified + " re-verified minimal)", "", "", "sets",
                    rep.totalSubsets() + " schedules, " + rep.executed() + " executed, " + rep.duplicateTraces() + " duplicate traces, " + rep.skippedAsSuperset() + " pruned, " + rep.termination()
                            + " in " + fmt(ms) + " ms; random sampling of the same " + budget + " executions hit " + hit.size() + " of the " + minimalSets.size() + " minimal sets");
        }
    }
}
