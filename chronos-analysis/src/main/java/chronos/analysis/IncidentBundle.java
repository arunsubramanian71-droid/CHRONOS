package chronos.analysis;

import chronos.model.*;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * A portable incident bundle: one versioned, checksummed ZIP file holding everything needed to reproduce and investigate a failure on
 * another machine: the timeline and every ancestor it was branched from (header, step-group-checksummed event log, summary), the
 * scenario name, parameters, seed, fault plan and interventions (all in the headers), the observed invariant violation, the causal
 * evidence (the {@code why} chains), the tool and format versions, the code fingerprint and reproduction instructions.
 *
 * Nothing in a bundle is trusted. Import validates the manifest, every file's SHA-256 and size, the file names (no path may leave the
 * staging directory), size and entry-count limits, the headers, the log's own commit-line checksums, the event counts and log hash, and
 * recomputes the causal evidence from the events and compares it with what the bundle claims. A bundle that fails any check is rejected
 * and nothing is written to the destination store. Checkpoints and indexes are not bundled: they are derived and rebuilt on import.
 *
 * Portability boundaries: reproduction re-executes the application, so the importing tool needs the same scenario registered (same name;
 * a different compiled-class fingerprint only produces a warning, as in replay, Section 15). A bundle can always be inspected (show,
 * inspect, why, compare) without the application code, because the log is the source of truth. Format version 1 is read only by
 * tools that know version 1; an unknown version is refused, not guessed at.
 */
public final class IncidentBundle {
    private IncidentBundle() {}

    public static final int FORMAT_VERSION = 1;
    public static final String KIND = "chronos-incident";
    static final int MAX_ENTRIES = 5000;
    static final long MAX_ENTRY_BYTES = 1L << 30, MAX_TOTAL_BYTES = 4L << 30;

    public static final class BundleException extends RuntimeException {
        public BundleException(String m) {
            super(m);
        }
    }

    public record Info(String incident, List<String> lineage, String scenario, Map<String, Object> params, long seed, String status, long events, String logHash,
                       String invariant, String violationEvent, String tool, int formatVersion, List<String> warnings) {}

    public record Reproduction(boolean ok, List<String> problems, List<String> checks, Info info) {}

    // ------------------------------------------------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------------------------------------------------

    public static Path export(TimelineStore store, String timelineId, Path out) {
        TimelineSummary sum = store.summary(timelineId);
        if (sum == null) throw new BundleException("timeline " + timelineId + " is not finished; only completed timelines can be exported");
        List<String> lineage = new ArrayList<>();
        for (TimelineHeader h = store.header(timelineId); h != null; h = h.parentId() == null ? null : store.header(h.parentId())) lineage.add(0, h.timelineId());
        TreeMap<String, byte[]> files = new TreeMap<>();
        try {
            for (String id : lineage) {
                Path dir = store.timelineDir(id);
                for (String fixed : List.of("header.json", "summary.json")) files.put("files/" + id + "/" + fixed, Files.readAllBytes(dir.resolve(fixed)));
                try (Stream<Path> s = Files.list(dir.resolve("events"))) {
                    for (Path p : (Iterable<Path>) s.sorted()::iterator) files.put("files/" + id + "/events/" + p.getFileName(), Files.readAllBytes(p));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TimelineHeader root = store.header(lineage.get(0)), leaf = store.header(timelineId);
        Map<String, Object> evidence = new TreeMap<>();
        TimeTravelSession session = new TimeTravelSession(store, timelineId);
        if (sum.firstViolationEventId() != null) {
            Map<String, Object> v = new TreeMap<>();
            v.put("invariant", sum.firstViolationInvariant());
            v.put("eventId", sum.firstViolationEventId());
            evidence.put("violation", v);
            evidence.put("why", session.why(sum.firstViolationEventId()));
        }
        evidence.put("faultPlan", leaf.faultPlan().toList());
        List<Object> ivs = new ArrayList<>();
        for (String id : lineage) for (Intervention iv : store.header(id).interventions()) ivs.add(iv.toMap());
        evidence.put("interventions", ivs);
        evidence.put("causalPastSizeOfViolation", sum.firstViolationEventId() == null ? 0L : (long) session.graph().causalPast(session.graph().pos(sum.firstViolationEventId())).length);
        byte[] evidenceBytes = (Canon.json(evidence) + "\n").getBytes(StandardCharsets.UTF_8);
        files.put("evidence.json", evidenceBytes);

        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("kind", KIND);
        manifest.put("tool", "chronos 0.2.0");
        manifest.put("incident", timelineId);
        manifest.put("lineage", lineage);
        manifest.put("scenario", root.scenario());
        manifest.put("params", new TreeMap<>(root.params()));
        manifest.put("seed", root.seed());
        manifest.put("codeVersion", root.codeVersion());
        manifest.put("codeFingerprint", root.codeFingerprint() == null ? "" : root.codeFingerprint());
        manifest.put("status", sum.status().name());
        manifest.put("events", (long) store.events(timelineId).size());
        manifest.put("logHash", LogHash.of(store.events(timelineId)));
        Map<String, Object> fm = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) fm.put(e.getKey(), Map.of("sha256", sha256(e.getValue()), "size", (long) e.getValue().length));
        manifest.put("files", fm);
        manifest.put("reproduction", List.of(
                "chronos incident import <this file>        (into an empty or existing data directory; add --data DIR)",
                "chronos incident reproduce <this file>     (re-executes the application and checks the same trace and violation)",
                "chronos inspect " + timelineId + " --goto violation --why   (after the import)"));
        manifest.put("checksum", Canon.sha256(Canon.json(manifest)));
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
                z.setLevel(6);
                writeEntry(z, "manifest.json", (Canon.json(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> e : files.entrySet()) writeEntry(z, e.getKey(), e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static void writeEntry(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0); // fixed timestamp: the same incident gives a byte-identical bundle
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    // ------------------------------------------------------------------------------------------------------------
    // validation and import
    // ------------------------------------------------------------------------------------------------------------

    /** The validated contents of a bundle, extracted into a staging directory. */
    private record Staged(Path dir, Map<String, Object> manifest, Map<String, Object> evidence) {}

    private static Staged stage(Path bundle, Path staging) {
        if (!Files.isRegularFile(bundle)) throw new BundleException("no such bundle: " + bundle);
        Map<String, Object> manifest = null;
        Map<String, long[]> seen = new HashMap<>();
        Map<String, String> digests = new HashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(bundle)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new BundleException("too many entries in the bundle (limit " + MAX_ENTRIES + ")");
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\") || name.contains(":") || name.isEmpty()) throw new BundleException("unsafe entry name: " + name);
                if (!(name.equals("manifest.json") || name.equals("evidence.json") || name.startsWith("files/"))) throw new BundleException("unexpected entry: " + name);
                if (seen.containsKey(name)) throw new BundleException("duplicate entry: " + name);
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging)) throw new BundleException("entry escapes the staging directory: " + name);
                Files.createDirectories(target.getParent());
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        size += n;
                        total += n;
                        if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw new BundleException("the bundle is larger than the import limits");
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                seen.put(name, new long[]{size});
                digests.put(name, HexFormat.of().formatHex(md.digest()));
            }
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException ex) {
            if (ex instanceof BundleException be) throw be;
            throw new BundleException("the file is not a readable bundle: " + ex.getMessage());
        }
        try {
            if (!seen.containsKey("manifest.json")) throw new BundleException("the bundle has no manifest");
            manifest = Canon.parseMap(Files.readString(staging.resolve("manifest.json"), StandardCharsets.UTF_8));
            if (!KIND.equals(manifest.get("kind"))) throw new BundleException("not a CHRONOS incident bundle (kind=" + manifest.get("kind") + ")");
            if (!(manifest.get("formatVersion") instanceof Number fv) || fv.longValue() != FORMAT_VERSION)
                throw new BundleException("unsupported bundle format version " + manifest.get("formatVersion") + " (this tool reads version " + FORMAT_VERSION + ")");
            Map<String, Object> body = new TreeMap<>(manifest);
            Object checksum = body.remove("checksum");
            if (!Canon.sha256(Canon.json(body)).equals(checksum)) throw new BundleException("the manifest checksum does not match: the bundle was modified or damaged");
            Map<String, Object> declared = Canon.map(manifest.get("files"));
            for (String name : seen.keySet()) {
                if (name.equals("manifest.json")) continue;
                if (!(declared.get(name) instanceof Map<?, ?>)) throw new BundleException("file " + name + " is not listed in the manifest");
                Map<String, Object> d = Canon.map(declared.get(name));
                if (!digests.get(name).equals(d.get("sha256")) || seen.get(name)[0] != Canon.num(d.get("size"))) throw new BundleException("file " + name + " does not match its checksum: the bundle was modified or damaged");
            }
            for (String name : declared.keySet()) if (!seen.containsKey(name)) throw new BundleException("file " + name + " is listed in the manifest but missing");
            List<Object> lineage = Canon.list(manifest.get("lineage"));
            if (lineage.isEmpty() || !lineage.get(lineage.size() - 1).equals(manifest.get("incident"))) throw new BundleException("the lineage does not end at the incident timeline");
            for (Object idObj : lineage) {
                String id = String.valueOf(idObj);
                if (!id.matches("[A-Za-z0-9._-]+") || id.contains("..")) throw new BundleException("unsafe timeline id: " + id);
                for (String req : List.of("header.json", "summary.json"))
                    if (!seen.containsKey("files/" + id + "/" + req)) throw new BundleException("timeline " + id + " has no " + req);
                for (String name : seen.keySet())
                    if (name.startsWith("files/" + id + "/events/") && !name.matches("files/[^/]+/events/\\d{6}\\.log(\\.gz)?")) throw new BundleException("unexpected event file name: " + name);
            }
            for (String name : seen.keySet()) {
                if (!name.startsWith("files/")) continue;
                String id = name.split("/")[1];
                if (!lineage.contains(id)) throw new BundleException("file " + name + " belongs to a timeline that is not in the lineage");
                String rest = name.substring(("files/" + id + "/").length());
                if (!(rest.equals("header.json") || rest.equals("summary.json") || rest.startsWith("events/"))) throw new BundleException("unexpected file: " + name);
            }
            Map<String, Object> evidence = seen.containsKey("evidence.json") ? Canon.parseMap(Files.readString(staging.resolve("evidence.json"), StandardCharsets.UTF_8)) : Map.of();
            return new Staged(staging, manifest, evidence);
        } catch (IOException | ClassCastException | IllegalArgumentException e) {
            throw new BundleException("the bundle is malformed: " + e.getMessage());
        }
    }

    /** Builds a staging store from a staged bundle and checks the log, the counts and the causal evidence. Returns warnings. */
    private static List<String> check(Staged st, TimelineStore staged, ScenarioRegistry registry) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> m = st.manifest();
        String incident = (String) m.get("incident");
        try {
            Path timelines = staged.root().resolve("timelines");
            Files.createDirectories(timelines);
            for (Object idObj : Canon.list(m.get("lineage"))) {
                String id = (String) idObj;
                Path from = st.dir().resolve("files").resolve(id), to = timelines.resolve(id);
                Files.createDirectories(to.resolve("events"));
                Files.copy(from.resolve("header.json"), to.resolve("header.json"));
                Files.copy(from.resolve("summary.json"), to.resolve("summary.json"));
                if (Files.isDirectory(from.resolve("events"))) try (Stream<Path> s = Files.list(from.resolve("events"))) {
                    for (Path p : (Iterable<Path>) s::iterator) Files.copy(p, to.resolve("events").resolve(p.getFileName()));
                }
                Files.createDirectories(to.resolve("checkpoints"));
            }
            for (Object idObj : Canon.list(m.get("lineage"))) {
                String id = (String) idObj;
                TimelineHeader h = staged.header(id); // parses and validates the header (format version included)
                if (!h.timelineId().equals(id)) throw new BundleException("header of " + id + " claims to be " + h.timelineId());
                staged.adopt(id); // validates the log: commit lines, checksums, positions, parent prefix
            }
        } catch (IOException e) {
            throw new BundleException("cannot stage the bundle: " + e.getMessage());
        } catch (CorruptLogException | UnknownTimelineException | TimelineHeader.UnsupportedFormatException | IllegalStateException e) {
            throw new BundleException("the timeline data is not valid: " + e.getMessage());
        }
        List<Event> events = staged.events(incident);
        if (events.size() != Canon.num(m.get("events"))) throw new BundleException("the event count does not match the manifest (" + events.size() + " vs " + m.get("events") + ")");
        if (!LogHash.of(events).equals(m.get("logHash"))) throw new BundleException("the log hash does not match the manifest: the events are not the ones that were exported");
        TimelineSummary sum = staged.summary(incident);
        if (!sum.status().name().equals(m.get("status"))) throw new BundleException("the status does not match the manifest");
        // causal evidence: recompute it from the events and compare with what the bundle claims
        Map<String, Object> ev = st.evidence();
        if (ev.containsKey("violation")) {
            Map<String, Object> v = Canon.map(ev.get("violation"));
            if (!Objects.equals(v.get("eventId"), sum.firstViolationEventId()) || !Objects.equals(v.get("invariant"), sum.firstViolationInvariant()))
                throw new BundleException("the evidence names a violation that the log does not contain");
            String why = new TimeTravelSession(staged, incident).why(sum.firstViolationEventId());
            if (!why.equals(ev.get("why"))) throw new BundleException("the causal evidence in the bundle does not match what the log implies");
        }
        TimelineHeader root = staged.header((String) Canon.list(m.get("lineage")).get(0));
        if (!registry.names().contains(root.scenario()) && !root.scenario().equals("mystery"))
            warnings.add("this tool has no scenario named '" + root.scenario() + "': the bundle can be inspected but not re-executed");
        else {
            try {
                chronos.sdk.Scenario sc = registry.create(root.scenario(), root.params());
                if (root.codeFingerprint() != null && !chronos.sdk.CodeFingerprint.of(sc).equals(root.codeFingerprint()))
                    warnings.add("the application's compiled-class fingerprint differs from the recording: reproduction runs against different code");
            } catch (RuntimeException e) {
                warnings.add("the scenario cannot be created here: " + e.getMessage());
            }
        }
        return warnings;
    }

    private static Info info(Staged st, TimelineStore staged, List<String> warnings) {
        Map<String, Object> m = st.manifest();
        TimelineSummary sum = staged.summary((String) m.get("incident"));
        List<String> lineage = new ArrayList<>();
        for (Object o : Canon.list(m.get("lineage"))) lineage.add((String) o);
        return new Info((String) m.get("incident"), lineage, (String) m.get("scenario"), Canon.map(m.get("params")), Canon.num(m.get("seed")), sum.status().name(),
                Canon.num(m.get("events")), (String) m.get("logHash"), sum.firstViolationInvariant(), sum.firstViolationEventId(), (String) m.get("tool"),
                (int) Canon.num(m.get("formatVersion")), warnings);
    }

    private static Path newStaging(Path parent) throws IOException {
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, ".incoming-");
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort: a leftover staging directory is harmless
        }
    }

    /** Validates a bundle completely without writing anywhere permanent. */
    public static Info inspect(Path bundle, ScenarioRegistry registry) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("chronos-incident-");
            Staged st = stage(bundle, tmp.resolve("raw"));
            TimelineStore staged = new TimelineStore(tmp.resolve("store"));
            List<String> warnings = check(st, staged, registry);
            return info(st, staged, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    /** Validates a bundle and, only if every check passes, adds its timelines to the store. A timeline that already exists must be identical. */
    public static Info importInto(TimelineStore target, Path bundle, ScenarioRegistry registry) {
        Path tmp = null;
        try {
            tmp = newStaging(target.root());
            Staged st = stage(bundle, tmp.resolve("raw"));
            TimelineStore staged = new TimelineStore(tmp.resolve("store"));
            List<String> warnings = check(st, staged, registry);
            List<String> lineage = new ArrayList<>();
            for (Object o : Canon.list(st.manifest().get("lineage"))) lineage.add((String) o);
            for (String id : lineage) {
                if (!target.exists(id)) continue;
                boolean same = target.summary(id) != null && LogHash.of(target.events(id)).equals(LogHash.of(staged.events(id)))
                        && Canon.json(target.header(id).toMap()).equals(Canon.json(staged.header(id).toMap()));
                if (!same) throw new BundleException("timeline " + id + " already exists in this store and is different: import into an empty data directory (--data DIR)");
            }
            for (String id : lineage) {
                if (target.exists(id)) continue;
                Path to = target.timelineDir(id);
                Files.createDirectories(to.getParent());
                Files.move(staged.timelineDir(id), to);
                target.adopt(id);
            }
            return info(st, target, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    /** Imports into a scratch store and re-executes: the replay verifier, and for a root timeline a fresh run from the recorded scenario, seed and plan. */
    public static Reproduction reproduce(Path bundle, ScenarioRegistry registry) {
        Path tmp = null;
        List<String> problems = new ArrayList<>(), checks = new ArrayList<>();
        try {
            tmp = Files.createTempDirectory("chronos-incident-");
            Staged st = stage(bundle, tmp.resolve("raw"));
            TimelineStore store = new TimelineStore(tmp.resolve("store"));
            List<String> warnings = check(st, store, registry);
            Info info = info(st, store, warnings);
            checks.add("bundle valid: manifest, " + Canon.map(st.manifest().get("files")).size() + " file checksums, log checksums, event count and log hash, causal evidence recomputed");
            if (warnings.stream().anyMatch(w -> w.contains("no scenario named"))) {
                problems.add(warnings.stream().filter(w -> w.contains("no scenario named")).findFirst().get());
                return new Reproduction(false, problems, checks, info);
            }
            ReplayEngine.Result r = new ReplayEngine(store, registry).verify(info.incident());
            if (r.ok()) checks.add("replay verification: " + r.eventsVerified() + " events regenerated exactly through " + info.lineage().size() + " timeline(s) of lineage");
            else problems.add("replay does not reproduce the recording: " + r.summary());
            TimelineHeader root = store.header(info.lineage().get(0));
            if (info.lineage().size() == 1) {
                TimelineHeader h = store.header(info.incident());
                Recording.MemoryRun run = Recording.recordInMemory(registry, "Xr", Recording.Spec.of(h.scenario(), h.seed()).withParams(h.params()).withPlan(h.faultPlan())
                        .withHorizon(h.horizon()).withStopOnViolation(h.stopOnViolation()).withCheckpointInterval(0));
                String fresh = SystematicExplorer.traceDigest(run.log().events()), stored = SystematicExplorer.traceDigest(store.events(info.incident()));
                if (fresh.equals(stored)) checks.add("fresh execution from scenario '" + root.scenario() + "', seed " + root.seed() + " and the recorded fault plan gives the identical trace");
                else problems.add("a fresh execution gives a different trace than the recorded one");
                if (info.invariant() != null && !Objects.equals(run.result().firstViolationInvariant(), info.invariant()))
                    problems.add("the fresh execution does not violate " + info.invariant() + " (it gives " + run.result().firstViolationInvariant() + ")");
                else if (info.invariant() != null) checks.add("the same violation recurs: " + info.invariant() + " at " + run.result().firstViolationEventId());
            }
            return new Reproduction(problems.isEmpty(), problems, checks, info);
        } catch (BundleException e) {
            return new Reproduction(false, List.of(e.getMessage()), checks, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
