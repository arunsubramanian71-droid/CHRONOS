package chronos.analysis;

import chronos.model.*;
import chronos.store.TimelineStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * A reproducible counterexample found by the systematic explorer: the scenario and parameters, the seed and horizon, the exploration
 * configuration that led to it, the fault schedule, the violation observed and the digests of the execution, stored as a versioned,
 * checksummed JSON document next to a normal timeline in the store (so every CHRONOS command works on the trace).
 * {@link #verify} re-executes the schedule from the document alone and checks that the same execution and the same violation appear.
 */
public final class Counterexample {
    private Counterexample() {}

    public static final int FORMAT_VERSION = 1;
    public static final String KIND = "chronos-counterexample";

    public record Result(boolean ok, List<String> problems, String traceDigest) {}

    public static Path dir(TimelineStore store) {
        return store.root().resolve("counterexamples");
    }

    /** Records the finding as a timeline and writes its document; returns the document's path. */
    public static Path save(TimelineStore store, chronos.sdk.ScenarioRegistry registry, String timelineId, SystematicExplorer.Config cfg, SystematicExplorer.Finding f) {
        Map<String, Object> ex = new TreeMap<>();
        List<Object> cands = new ArrayList<>();
        for (SystematicExplorer.Candidate c : cfg.candidates()) cands.add(c.rule().toMap());
        ex.put("candidates", cands);
        ex.put("basePlan", cfg.basePlan() == null ? List.of() : cfg.basePlan().toList());
        ex.put("maxFaults", cfg.maxFaults());
        ex.put("maxCases", cfg.maxCases());
        ex.put("stopAtFirst", cfg.stopAtFirst());
        ex.put("pruneSupersets", cfg.pruneSupersets());
        Map<String, Object> origin = new TreeMap<>();
        origin.put("exploration", ex);
        origin.put("caseIndex", f.caseIndex());
        return saveRun(store, registry, timelineId, cfg.scenario(), cfg.params(), cfg.seed(), f.run().header().horizon(), f.plan(), f.faults(),
                f.invariant(), f.violationEvent(), f.traceDigest(), f.events(), origin);
    }

    /**
     * Records a violating run as a timeline and writes the versioned, checksummed document for it. {@code origin} holds how the schedule was found
     * (an exploration configuration, a minimization) and is stored verbatim; {@link #verify} does not need it.
     */
    public static Path saveRun(TimelineStore store, chronos.sdk.ScenarioRegistry registry, String timelineId, String scenario, Map<String, String> params,
                               long seed, long horizon, FaultPlan plan, List<String> faults, String invariant, String violationEvent, String traceDigest,
                               long events, Map<String, Object> origin) {
        Recording.record(store, registry, timelineId, Recording.Spec.of(scenario, seed).withParams(params).withPlan(plan)
                .withHorizon(horizon).withStopOnViolation(true).withCheckpointInterval(25));
        Map<String, Object> m = new TreeMap<>();
        m.put("formatVersion", FORMAT_VERSION);
        m.put("kind", KIND);
        m.put("tool", "chronos 0.2.0");
        m.put("timelineId", timelineId);
        m.put("scenario", scenario);
        m.put("params", new TreeMap<>(params));
        m.put("seed", seed);
        m.put("horizon", horizon);
        m.putAll(origin);
        m.put("faults", faults);
        m.put("plan", plan.toList());
        Map<String, Object> v = new TreeMap<>();
        v.put("invariant", invariant);
        v.put("eventId", violationEvent);
        m.put("violation", v);
        m.put("traceDigest", traceDigest);
        m.put("logHash", LogHash.of(store.events(timelineId))); // of the stored timeline (its events carry its id); the id-neutral trace digest is the cross-check
        m.put("events", events);
        m.put("checksum", Canon.sha256(Canon.json(m)));
        try {
            Files.createDirectories(dir(store));
            Path p = dir(store).resolve(timelineId + ".json");
            Files.writeString(p, Canon.json(m) + "\n", StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Checks a document and re-executes its schedule. Never trusts the document's claims: everything is recomputed and compared. */
    public static Result verify(chronos.sdk.ScenarioRegistry registry, Path file, TimelineStore storeOrNull) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> m;
        try {
            m = Canon.parseMap(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return new Result(false, List.of("cannot read " + file + ": " + e.getMessage()), null);
        }
        try {
            if (!KIND.equals(m.get("kind"))) return new Result(false, List.of("not a CHRONOS counterexample (kind=" + m.get("kind") + ")"), null);
            if (!(m.get("formatVersion") instanceof Number fv) || fv.longValue() != FORMAT_VERSION)
                return new Result(false, List.of("unsupported counterexample format version " + m.get("formatVersion") + " (this tool reads " + FORMAT_VERSION + ")"), null);
            Map<String, Object> body = new TreeMap<>(m);
            Object checksum = body.remove("checksum");
            if (!Canon.sha256(Canon.json(body)).equals(checksum)) return new Result(false, List.of("checksum mismatch: the document was modified or damaged"), null);
            Map<String, String> params = new TreeMap<>();
            for (Map.Entry<String, Object> e : Canon.map(m.get("params")).entrySet()) params.put(e.getKey(), String.valueOf(e.getValue()));
            FaultPlan plan = FaultPlan.fromList(m.get("plan"));
            long seed = Canon.num(m.get("seed")), horizon = Canon.num(m.get("horizon"));
            Recording.MemoryRun run = Recording.recordInMemory(registry, "Xv", Recording.Spec.of((String) m.get("scenario"), seed).withParams(params).withPlan(plan)
                    .withHorizon(horizon).withStopOnViolation(true).withCheckpointInterval(0));
            String digest = SystematicExplorer.traceDigest(run.log().events());
            if (!digest.equals(m.get("traceDigest"))) problems.add("the re-executed trace differs from the recorded one (digest " + digest + " vs " + m.get("traceDigest") + ")");
            Map<String, Object> viol = Canon.map(m.get("violation"));
            if (!run.result().violated()) problems.add("the schedule no longer violates any invariant");
            else {
                if (!Objects.equals(run.result().firstViolationInvariant(), viol.get("invariant"))) problems.add("a different invariant is violated: " + run.result().firstViolationInvariant());
                if (!Objects.equals(run.result().firstViolationEventId(), viol.get("eventId"))) problems.add("the violation is at " + run.result().firstViolationEventId() + ", not " + viol.get("eventId"));
            }
            if (storeOrNull != null && m.get("timelineId") != null && storeOrNull.exists((String) m.get("timelineId"))) {
                String tl = (String) m.get("timelineId");
                if (!LogHash.of(storeOrNull.events(tl)).equals(m.get("logHash"))) problems.add("the stored timeline " + tl + " differs from the recorded log hash");
                ReplayEngine.Result r = new ReplayEngine(storeOrNull, registry).verify(tl);
                if (!r.ok()) problems.add("the stored timeline does not replay: " + r.summary());
            }
            return new Result(problems.isEmpty(), problems, digest);
        } catch (RuntimeException e) {
            return new Result(false, List.of("the document is malformed or inconsistent: " + e.getClass().getSimpleName() + ": " + e.getMessage()), null);
        }
    }
}
