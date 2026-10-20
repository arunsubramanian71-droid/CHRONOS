package chronos.analysis;

import chronos.model.*;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Whole-store integrity verification with actionable diagnostics (the engine behind {@code chronos check}).
 *
 * <p>It reads; it never repairs and never writes. For every timeline it checks the header, the catalogue entry, the log (commit lines and
 * checksums, positions, an interrupted tail), unique event ids, the summary's event count and log hash, the clocks against the graph (law M4),
 * the lineage (the parent exists and the branch point is one of its step boundaries), the derived index, the checkpoints, and optionally a full
 * deterministic replay against the recording. Every problem is a {@link Finding} with a status, a plain message and the action that fixes
 * it. A damaged timeline is reported and the check carries on with the next one: one bad file never hides the state of the others.
 * Nothing here trusts a file because it parsed.
 */
public final class StoreChecker {
    public enum Status { OK, WARN, FAIL }

    public record Finding(String timeline, String check, Status status, String message, String action) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new TreeMap<>();
            m.put("timeline", timeline);
            m.put("check", check);
            m.put("status", status.name());
            m.put("message", message);
            if (action != null) m.put("action", action);
            return m;
        }
    }

    public record Report(List<String> timelines, List<Finding> findings, boolean replayed) {
        public long count(Status s) {
            return findings.stream().filter(f -> f.status() == s).count();
        }

        public boolean ok() {
            return count(Status.FAIL) == 0;
        }

        private long replayFailures() {
            return findings.stream().filter(f -> f.check().equals("replay") && f.status() == Status.FAIL).count();
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (String t : timelines) {
                List<Finding> mine = findings.stream().filter(f -> t.equals(f.timeline())).toList();
                long fail = mine.stream().filter(f -> f.status() == Status.FAIL).count(), warn = mine.stream().filter(f -> f.status() == Status.WARN).count();
                sb.append(t).append("  ").append(fail > 0 ? "FAIL" : warn > 0 ? "WARN" : "OK").append("  ").append(mine.stream().filter(f -> f.status() == Status.OK).count()).append(" checks passed\n");
                for (Finding f : mine) {
                    if (f.status() == Status.OK) continue;
                    sb.append("    ").append(f.status()).append(' ').append(f.check()).append(": ").append(f.message()).append('\n');
                    if (f.action() != null) sb.append("        action: ").append(f.action()).append('\n');
                }
            }
            for (Finding f : findings) {
                if (f.timeline() != null) continue;
                sb.append("store  ").append(f.status()).append(' ').append(f.check()).append(": ").append(f.message()).append('\n');
                if (f.action() != null) sb.append("        action: ").append(f.action()).append('\n');
            }
            sb.append(ok() ? (count(Status.WARN) == 0 ? "STORE OK" : "STORE OK WITH WARNINGS") : count(Status.FAIL) == replayFailures() ? "STORE INTACT, REPLAY FAILS" : "STORE DAMAGED").append(": ").append(timelines.size()).append(" timelines, ")
                    .append(count(Status.WARN)).append(" warnings, ").append(count(Status.FAIL)).append(" failures")
                    .append(!replayed ? ", replay not run" : replayFailures() == 0 ? ", every finished timeline replayed deterministically"
                    : ", " + replayFailures() + " timeline(s) do not replay deterministically").append('\n');
            return sb.toString();
        }

        /** Machine-readable form: canonical JSON, byte-identical for identical findings. */
        public String json() {
            Map<String, Object> m = new TreeMap<>();
            m.put("tool", "chronos check");
            m.put("formatVersion", 1);
            m.put("replayed", replayed);
            m.put("ok", ok());
            Map<String, Object> sum = new TreeMap<>();
            sum.put("timelines", timelines.size());
            sum.put("ok", count(Status.OK));
            sum.put("warnings", count(Status.WARN));
            sum.put("failures", count(Status.FAIL));
            m.put("summary", sum);
            m.put("timelines", timelines);
            List<Object> fs = new ArrayList<>();
            for (Finding f : findings) fs.add(f.toMap());
            m.put("findings", fs);
            return Canon.json(m);
        }
    }

    /** SHA-256 of empty input, which the summaries of branches recorded before DEV-49 carry as their log hash. */
    static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final TimelineStore store;
    private final ScenarioRegistry registry;

    public StoreChecker(TimelineStore store, ScenarioRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** Checks the given timelines (all of the store if null or empty), plus the store-level files. */
    public Report check(Collection<String> only, boolean replay) {
        List<Finding> out = new ArrayList<>();
        List<String> onDisk;
        try {
            onDisk = store.timelineIds();
        } catch (RuntimeException e) {
            out.add(new Finding(null, "store", Status.FAIL, "cannot list the timelines directory: " + e.getMessage(), "check that --data points at a CHRONOS data directory"));
            return new Report(List.of(), out, replay);
        }
        List<String> ids = only == null || only.isEmpty() ? onDisk : new ArrayList<>(only);
        Map<String, Object> catalogued = new TreeMap<>();
        try {
            catalogued = Canon.map(store.readCatalog().get("timelines"));
        } catch (Exception e) {
            out.add(new Finding(null, "catalogue", Status.FAIL, "catalog.json is unreadable: " + describe(e),
                    "the catalogue is derived metadata; restore it from a backup, or re-adopt the timelines with `chronos incident import`"));
        }
        if (only == null || only.isEmpty()) {
            for (String c : catalogued.keySet())
                if (!onDisk.contains(c)) out.add(new Finding(null, "catalogue", Status.FAIL, "timeline " + c + " is in the catalogue but its directory is missing", "restore the directory from a backup or an incident bundle"));
        }
        for (String id : ids) {
            if (!onDisk.contains(id)) {
                out.add(new Finding(id, "exists", Status.FAIL, "no such timeline", "see `chronos ls` for the timelines of this store"));
                continue;
            }
            checkOne(id, catalogued, replay, out);
        }
        return new Report(ids, out, replay);
    }

    private void checkOne(String id, Map<String, Object> catalogued, boolean replay, List<Finding> out) {
        TimelineHeader h;
        try {
            h = store.header(id);
            out.add(ok(id, "header", "format version " + h.formatVersion() + ", scenario " + h.scenario() + ", seed " + h.seed()));
        } catch (Exception e) {
            out.add(fail(id, "header", describe(e), "this timeline cannot be read; restore it from a backup or an incident bundle (other timelines are unaffected)"));
            return;
        }
        if (!(catalogued.get(id) instanceof Map<?, ?> entry)) {
            out.add(warn(id, "catalogue", "the timeline is not in the catalogue (a crash between writing the files and updating it, or a copied directory)",
                    "harmless for reading; `chronos incident import` of its bundle re-registers a timeline"));
        } else if (!Objects.equals(entry.get("scenario"), h.scenario()) || !Objects.equals(entry.get("parentId"), h.parentId())) {
            out.add(fail(id, "catalogue", "the catalogue says scenario " + entry.get("scenario") + ", parent " + entry.get("parentId") + " but the header says " + h.scenario() + ", " + h.parentId(),
                    "one of the two files was edited or damaged; trust the header, whose log is checksummed, and rewrite the catalogue"));
        } else {
            out.add(ok(id, "catalogue", "listed"));
        }

        if (h.parentId() != null) {
            if (!store.exists(h.parentId())) {
                out.add(fail(id, "lineage", "parent timeline " + h.parentId() + " is missing; this branch's history before position " + (h.branchSeq() + 1) + " lives there",
                        "restore " + h.parentId() + " from a backup or an incident bundle of this branch (bundles carry the whole lineage)"));
                return;
            }
            try {
                store.groupEnds(id);
                out.add(ok(id, "lineage", "branch of " + h.parentId() + " at position " + h.branchSeq() + ", a step boundary of the parent"));
            } catch (Exception e) {
                out.add(fail(id, "lineage", describe(e), "the parent's log or the branch point is damaged; check " + h.parentId() + " first"));
                return;
            }
        }

        List<Event> events;
        try {
            events = store.events(id);
            int tail = store.discardedTailLines(id);
            out.add(ok(id, "log", events.size() + " events (positions 0.." + (events.size() - 1) + "), every step group's checksum holds"));
            if (tail > 0)
                out.add(warn(id, "tail", tail + " line(s) after the last commit were ignored: an interrupted write, never part of the history",
                        "nothing to repair; the durable history ends at position " + (events.size() - 1) + ". If this was meant to be a finished run, record it again"));
        } catch (Exception e) {
            out.add(fail(id, "log", describe(e), "restore this timeline from a backup or an incident bundle; the message names the damaged record, and other timelines are unaffected"));
            return;
        }

        Set<String> seen = new HashSet<>();
        String dup = null;
        for (Event e : events) if (!seen.add(e.eventId()) && dup == null) dup = e.eventId();
        out.add(dup == null ? ok(id, "ids", "all event ids are unique") : fail(id, "ids", "event id " + dup + " occurs twice", "the log was altered or two recordings were merged; restore from a backup"));

        TimelineSummary sum = null;
        boolean summaryBroken = false;
        try {
            sum = store.summary(id);
        } catch (Exception e) {
            summaryBroken = true;
            out.add(fail(id, "summary", describe(e), "the summary is derived from the log; `chronos verify " + id + "` still works, but restore the file from a backup"));
        }
        if (sum == null && !summaryBroken)
            out.add(warn(id, "summary", "no summary.json: the run did not finish (it was interrupted while recording)", "the recorded prefix is valid and readable; record the scenario again for a finished run"));
        else if (sum != null) {
            // a root's summary hashes its whole log; a branch's hashes only the events it recorded itself (after its branch point)
            List<Event> hashed = h.parentId() == null ? events : events.subList((int) Math.min(events.size(), h.branchSeq() + 1), events.size());
            String actual = LogHash.of(hashed);
            String shown = String.valueOf(sum.logHash());
            if (sum.eventCount() != events.size()) out.add(fail(id, "summary", "the summary says " + sum.eventCount() + " events, the log holds " + events.size(), "the log or the summary was truncated or edited; restore from a backup"));
            else if (h.parentId() != null && EMPTY_HASH.equals(sum.logHash()) && !hashed.isEmpty())
                out.add(warn(id, "summary", "the summary's log hash is the hash of empty input: branches recorded by versions before the fix to Recorder.switchTo carry no hash of their own events (DEV-49)",
                        "harmless; the events are still protected by the step-group checksums, and `chronos verify " + id + "` proves they replay"));
            else if (!actual.equals(sum.logHash())) out.add(fail(id, "summary", "the log hash differs from the summary's (log " + actual.substring(0, 12) + ", summary " + shown.substring(0, Math.min(12, shown.length())) + "): the events were changed after the run finished",
                    "restore from a backup; `chronos verify` shows the first event that no longer replays"));
            else out.add(ok(id, "summary", "event count and log hash match (" + actual.substring(0, 12) + ")"));
        }

        try {
            List<String> problems = CausalGraph.build(events).selfCheck(300);
            out.add(problems.isEmpty() ? ok(id, "clocks", "vector clocks, Lamport clocks and graph reachability agree (law M4)")
                    : fail(id, "clocks", problems.size() + " disagreement(s); first: " + problems.get(0), "the recording is inconsistent; restore from a backup"));
        } catch (Exception e) {
            out.add(fail(id, "clocks", "the causal graph cannot be built: " + describe(e), "restore from a backup"));
        }

        try {
            String src = store.index(id).source();
            out.add(src.equals("persisted") ? ok(id, "index", "persisted indexes load and match the log")
                    : warn(id, "index", "the persisted index is missing or damaged; it is being rebuilt from the log on every read (correct, but slower)", "run `chronos reindex " + id + "` to rewrite it"));
        } catch (Exception e) {
            out.add(fail(id, "index", describe(e), "run `chronos reindex " + id + "`"));
        }

        try {
            List<Long> bad = store.damagedCheckpoints(id);
            out.add(bad.isEmpty() ? ok(id, "checkpoints", store.checkpointSeqs(id).size() + " checkpoints readable")
                    : warn(id, "checkpoints", "damaged checkpoint(s) at position(s) " + bad + " are skipped; replay and time travel start from the next older readable one",
                            "no repair is needed for correctness; delete the damaged checkpoint files to silence this"));
        } catch (Exception e) {
            out.add(fail(id, "checkpoints", describe(e), "an unsupported or unreadable checkpoint format; checkpoints are optional, replay starts from the log"));
        }

        if (replay && sum != null) {
            try {
                ReplayEngine.Result r = new ReplayEngine(store, registry).verify(id);
                out.add(r.ok() ? ok(id, "replay", "re-executed deterministically: " + r.eventsVerified() + " events matched")
                        : fail(id, "replay", r.violation().report().replace('\n', ' '), "the log passed its checksums, so either the application is not deterministic (it reads real time, randomness or hidden state) or the code changed since the recording; `chronos verify " + id + "` names the first event that differs"));
            } catch (Exception e) {
                out.add(fail(id, "replay", describe(e), "the scenario may not be registered in this build; `chronos help` lists the known scenarios"));
            }
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static Finding ok(String id, String check, String msg) {
        return new Finding(id, check, Status.OK, msg, null);
    }

    private static Finding warn(String id, String check, String msg, String action) {
        return new Finding(id, check, Status.WARN, msg, action);
    }

    private static Finding fail(String id, String check, String msg, String action) {
        return new Finding(id, check, Status.FAIL, msg, action);
    }

    /** True if a data directory exists and looks like a store (used by the CLI to give a clear message instead of an empty report). */
    public static boolean looksLikeStore(Path root) {
        return Files.isDirectory(root.resolve("timelines"));
    }
}
