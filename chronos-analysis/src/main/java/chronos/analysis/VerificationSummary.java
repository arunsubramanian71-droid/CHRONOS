package chronos.analysis;

import chronos.model.*;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;

import java.util.*;

/**
 * The machine-readable companion of an HTML report: one canonical JSON document that says what was run (the experiment manifest: scenario,
 * parameters, seed, horizon, fault plan, interventions of the whole lineage, code version and fingerprint), what was recorded, what was verified
 * (a full deterministic replay, the integrity checks of {@link StoreChecker} on the timeline and every ancestor) and, for a comparison, what the
 * divergence analysis found (first causal divergence, roots, displaced events with their reasons, anything unexplained, outcomes) together with the
 * commands that reproduce it. Everything is recomputed from the store when the document is built; nothing is copied from the report. The same
 * store and arguments give the same bytes.
 */
public final class VerificationSummary {
    private VerificationSummary() {}

    public static final String KIND = "chronos-verification-summary";

    public static Map<String, Object> build(TimelineStore store, ReplayEngine replay, ScenarioRegistry registry, String timeline, String against,
                                            CounterfactualEngine.Report counterfactual, String dataDirForCommands) {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", KIND);
        m.put("formatVersion", 1);
        m.put("tool", "chronos 0.2.0");
        m.put("timeline", timeline);
        if (against != null) m.put("against", against);

        // the experiment manifest
        List<TimelineHeader> lineage = new ArrayList<>();
        for (TimelineHeader h = store.header(timeline); h != null; h = h.parentId() == null ? null : store.header(h.parentId())) lineage.add(0, h);
        TimelineHeader root = lineage.get(0), h = lineage.get(lineage.size() - 1);
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("scenario", root.scenario());
        manifest.put("params", new TreeMap<>(root.params()));
        manifest.put("seed", root.seed());
        manifest.put("nodes", root.nodes());
        manifest.put("horizon", h.horizon());
        manifest.put("faultPlan", root.faultPlan().toList());
        manifest.put("codeVersion", root.codeVersion());
        manifest.put("codeFingerprint", root.codeFingerprint());
        manifest.put("formatVersion", root.formatVersion());
        boolean synthetic = false;
        for (TimelineHeader x : lineage) for (Intervention iv : x.interventions()) synthetic |= iv.synthetic();
        List<Object> lin = lineageEntries(lineage);
        manifest.put("lineage", lin);
        manifest.put("synthetic", synthetic);
        m.put("manifest", manifest);

        // what was recorded
        TimelineSummary sum = store.summary(timeline);
        Map<String, Object> rec = new TreeMap<>();
        rec.put("events", store.events(timeline).size());
        if (sum != null) {
            rec.put("status", sum.status().name());
            rec.put("finalVirtualTime", sum.finalVirtualTime());
            rec.put("endStateDigest", sum.endStateDigest());
            rec.put("logHash", LogHash.of(store.events(timeline)));
            if (sum.firstViolationEventId() != null) {
                Map<String, Object> v = new TreeMap<>();
                v.put("invariant", sum.firstViolationInvariant());
                v.put("event", sum.firstViolationEventId());
                rec.put("firstViolation", v);
            }
            rec.put("faultCoverage", sum.faultCoverage());
        } else {
            rec.put("status", "UNFINISHED");
        }
        m.put("recording", rec);

        // what was verified
        Map<String, Object> ver = new TreeMap<>();
        if (sum != null) {
            ReplayEngine.Result r = replay.verify(timeline);
            Map<String, Object> rm = new TreeMap<>();
            rm.put("ok", r.ok());
            rm.put("eventsVerified", r.eventsVerified());
            if (r.ok()) rm.put("logHash", r.logHash());
            else rm.put("mismatch", r.violation().report());
            rm.put("warnings", r.warnings());
            ver.put("replay", rm);
        }
        List<String> chain = new ArrayList<>();
        for (TimelineHeader x : lineage) chain.add(x.timelineId());
        StoreChecker.Report sc = new StoreChecker(store, registry).check(chain, false);
        Map<String, Object> cm = new TreeMap<>();
        cm.put("ok", sc.ok());
        cm.put("warnings", sc.count(StoreChecker.Status.WARN));
        cm.put("failures", sc.count(StoreChecker.Status.FAIL));
        cm.put("passed", sc.count(StoreChecker.Status.OK));
        List<Object> notOk = new ArrayList<>();
        for (StoreChecker.Finding f : sc.findings()) if (f.status() != StoreChecker.Status.OK) notOk.add(f.toMap());
        cm.put("problems", notOk);
        ver.put("integrity", cm);
        ver.put("verified", (sum == null || Boolean.TRUE.equals(Canon.map(ver.get("replay")).get("ok"))) && sc.ok());
        m.put("verification", ver);

        // what the comparison found
        if (against != null) {
            DivergenceReport d = new TimelineComparator(store).compare(timeline, against, false);
            Map<String, Object> c = new TreeMap<>();
            List<TimelineHeader> otherLineage = new ArrayList<>();
            for (TimelineHeader x = store.header(against); x != null; x = x.parentId() == null ? null : store.header(x.parentId())) otherLineage.add(0, x);
            c.put("againstLineage", lineageEntries(otherLineage));
            c.put("sharedPrefix", d.sharedPrefix());
            Map<String, Object> counts = new TreeMap<>();
            for (Map.Entry<TimelineComparator.EventClass, Integer> e : d.counts().entrySet()) counts.put(e.getKey().name(), e.getValue());
            c.put("counts", counts);
            c.put("identical", d.identical());
            if (d.primary() != null) c.put("firstCausalDivergence", root(d.primary()));
            List<Object> roots = new ArrayList<>();
            for (TimelineComparator.Root r : d.roots()) roots.add(root(r));
            c.put("roots", roots);
            List<Object> disp = new ArrayList<>();
            for (DivergenceReport.Displaced x : d.displaced()) {
                Map<String, Object> dm = new TreeMap<>();
                dm.put("event", x.eventId());
                dm.put("side", String.valueOf(x.side()));
                dm.put("reason", x.reason());
                dm.put("related", x.relatedEvent());
                dm.put("certainty", x.reason().equals(DivergenceReport.Displaced.RENUMBERED) ? "certain" : "potential");
                disp.add(dm);
            }
            c.put("displaced", disp);
            c.put("unexplained", d.unexplained());
            c.put("coneEventsA", d.coneA());
            c.put("coneEventsB", d.coneB());
            c.put("affectedNodes", d.affectedNodes());
            c.put("outcomeA", d.outcomeA());
            c.put("outcomeB", d.outcomeB());
            c.put("flags", d.flags());
            m.put("comparison", c);
            if (counterfactual != null) {
                Map<String, Object> cf = new TreeMap<>();
                cf.put("verdict", counterfactual.verdict().name());
                cf.put("explanation", counterfactual.explanation());
                cf.put("synthetic", counterfactual.synthetic());
                m.put("counterfactual", cf);
            }
        }

        // how to reproduce it
        List<String> cmds = new ArrayList<>();
        String base = "java -jar chronos-cli/target/chronos.jar --data " + dataDirForCommands;
        cmds.add(base + " verify " + timeline);
        cmds.add(base + " check " + timeline);
        if (against != null) cmds.add(base + " compare " + timeline + " " + against);
        cmds.add(base + " incident export " + timeline);
        m.put("reproduce", cmds);
        return m;
    }

    private static List<Object> lineageEntries(List<TimelineHeader> lineage) {
        List<Object> lin = new ArrayList<>();
        for (TimelineHeader x : lineage) {
            Map<String, Object> e = new TreeMap<>();
            e.put("timeline", x.timelineId());
            if (x.parentId() != null) {
                e.put("parent", x.parentId());
                e.put("branchSeq", x.branchSeq());
            }
            List<Object> ivs = new ArrayList<>();
            for (Intervention iv : x.interventions()) {
                Map<String, Object> im = new TreeMap<>();
                im.put("event", iv.eventId());
                im.put("type", iv.type());
                im.put("params", iv.params());
                im.put("synthetic", iv.synthetic());
                ivs.add(im);
            }
            e.put("interventions", ivs);
            if (x.label() != null) e.put("label", x.label());
            lin.add(e);
        }
        return lin;
    }

    private static Map<String, Object> root(TimelineComparator.Root r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("event", r.eventId());
        m.put("node", r.nodeId());
        m.put("side", String.valueOf(r.side()));
        m.put("virtualTime", r.virtualTime());
        m.put("class", r.cls().name());
        return m;
    }

    public static String json(Map<String, Object> m) {
        return Canon.json(m) + "\n";
    }
}
