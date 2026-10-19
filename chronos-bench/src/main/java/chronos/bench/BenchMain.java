package chronos.bench;

import chronos.analysis.*;
import chronos.cli.Main;
import chronos.demo.DemoRegistry;
import chronos.demo.Gossip;
import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.Scenario;
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
 * usage: java -jar chronos-bench/target/chronos-bench.jar [all|soak|jmh] [--quick] [--csv FILE] [--soak-seeds N] [--no-xl]
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
        } else {
            engineAndRecording(quick ? 10_000 : 100_000);
            clocks();
            encoding();
            graphAndSeek(quick ? new int[]{10_000} : new int[]{10_000, 100_000});
            replaySpeed(quick ? new int[]{10_000} : new int[]{10_000, 100_000});
            branching(quick ? 20_000 : 100_000);
            counterfactualThreads();
            if (xl) millionEvents();
            demoTime();
            soak(soakSeeds);
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

    private static void millionEvents() {
        System.gc();
        long before = usedMemory();
        Rec r = recordMem(10, 1_000_000, 1000);
        System.gc();
        long after = usedMemory();
        List<Event> ev = r.log().events();
        row("XL", "Can a million-event timeline be recorded in memory?", ev.size() + " events, 10 nodes", "record time", fmt(r.ms()), "", "", "ms", fmt(ev.size() / (r.ms() / 1000)) + " events/s");
        row("XL", "Memory profile of a million events", ev.size() + " events held in memory", "heap used", String.valueOf((after - before) / (1 << 20)), "", "", "MiB", (after - before) / ev.size() + " bytes per event (events + checkpoints + indexes of the log)");
        long t0 = System.nanoTime();
        CausalGraph g = CausalGraph.build(ev);
        double gb = (System.nanoTime() - t0) / 1e6;
        row("XL", "Can the causal graph hold a million events?", ev.size() + " events", "graph build", fmt(gb), "", "", "ms", g.edgeCount() + " edges");
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
        row("XL", "Seek in a million-event timeline", ev.size() + " events", "worst of 100 jumps", fmt(worst), "", "", "ms", (worst < 5000 ? "meets" : "MISSES") + " NFR-06 (< 5000 ms); session open " + fmt(open) + " ms");
        long t2 = System.nanoTime();
        ReplayEngine.Result res = new ReplayEngine(mem, REG).verify("T0");
        double rep = (System.nanoTime() - t2) / 1e6;
        row("XL", "Verification replay of a million events", ev.size() + " events", "replay time", fmt(rep), "", "", "ms", "verified=" + res.ok() + ", ratio to recording x" + fmt(rep / r.ms()) + (rep / r.ms() <= 2 ? " meets" : " MISSES") + " NFR-07 (<= 2x)");
    }

    private static long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
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
}
