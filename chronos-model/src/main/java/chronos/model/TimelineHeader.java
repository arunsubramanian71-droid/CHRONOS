package chronos.model;

import java.util.*;

/**
 * Everything needed to recreate a run (Section 17, "What a timeline holds"): scenario, parameters, seed, fault plan,
 * node order, the interventions applied at the branch point, format version and code identification.
 * parentId and branchSeq are null/-1 for a root timeline.
 */
public record TimelineHeader(String timelineId, String parentId, long branchSeq, String scenario, Map<String, String> params,
                             long seed, List<String> nodes, FaultPlan faultPlan, List<Intervention> interventions,
                             long horizon, boolean stopOnViolation, int formatVersion, String codeVersion,
                             String codeFingerprint, String label) {

    public TimelineHeader {
        params = Collections.unmodifiableMap(new TreeMap<>(params));
        nodes = List.copyOf(nodes);
        interventions = List.copyOf(interventions);
    }

    public boolean isRoot() {
        return parentId == null;
    }

    /** The reserved parameter that records a branch's continuation policy; absent means Continue (D-15). */
    public static final String CONTINUATION_PARAM = "chronos.continuation";

    /** The same header under another timeline id (used to hold a copy of a log next to the original, for perturbation tests). */
    public TimelineHeader renamed(String newId) {
        return new TimelineHeader(newId, parentId, branchSeq, scenario, params, seed, nodes, faultPlan, interventions, horizon,
                stopOnViolation, formatVersion, codeVersion, codeFingerprint, label);
    }

    public TimelineHeader withParam(String key, String value) {
        Map<String, String> p = new TreeMap<>(params);
        p.put(key, value);
        return new TimelineHeader(timelineId, parentId, branchSeq, scenario, p, seed, nodes, faultPlan, interventions, horizon,
                stopOnViolation, formatVersion, codeVersion, codeFingerprint, label);
    }

    /** True if this timeline was branched with the Pinned continuation policy. */
    public boolean pinned() {
        return "pinned".equals(params.get(CONTINUATION_PARAM));
    }

    public TimelineHeader asBranch(String id, String parent, long branchAt, List<Intervention> ivs, long newHorizon, String newLabel) {
        return new TimelineHeader(id, parent, branchAt, scenario, params, seed, nodes, faultPlan, ivs, newHorizon,
                stopOnViolation, formatVersion, codeVersion, codeFingerprint, newLabel);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("timelineId", timelineId);
        if (parentId != null) m.put("parentId", parentId);
        m.put("branchSeq", branchSeq);
        m.put("scenario", scenario);
        m.put("params", params);
        m.put("seed", seed);
        m.put("nodes", nodes);
        m.put("faultPlan", faultPlan.toList());
        List<Object> ivs = new ArrayList<>();
        for (Intervention i : interventions) ivs.add(i.toMap());
        m.put("interventions", ivs);
        m.put("horizon", horizon);
        m.put("stopOnViolation", stopOnViolation);
        m.put("formatVersion", formatVersion);
        m.put("codeVersion", codeVersion);
        m.put("codeFingerprint", codeFingerprint);
        if (label != null) m.put("label", label);
        return m;
    }

    public static TimelineHeader fromMap(Map<String, Object> m) {
        try {
            return fromMapUnchecked(m);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            throw new IllegalArgumentException("malformed TimelineHeader: " + e.getMessage(), e); // a document of the wrong shape is refused, never a raw runtime error
        }
    }

    private static TimelineHeader fromMapUnchecked(Map<String, Object> m) {
        try {
            int fv = (int) Canon.num(m.get("formatVersion"));
            if (fv != Formats.FORMAT_VERSION)
                throw new UnsupportedFormatException("unsupported log format version " + fv + " (this build reads " + Formats.FORMAT_VERSION + ")");
            Map<String, String> params = new TreeMap<>();
            for (Map.Entry<String, Object> e : Canon.map(m.get("params")).entrySet()) params.put(e.getKey(), (String) e.getValue());
            List<String> nodes = new ArrayList<>();
            for (Object o : Canon.list(m.get("nodes"))) nodes.add((String) o);
            List<Intervention> ivs = new ArrayList<>();
            for (Object o : Canon.list(m.get("interventions"))) ivs.add(Intervention.fromMap(Canon.map(o)));
            return new TimelineHeader((String) m.get("timelineId"), (String) m.get("parentId"), Canon.num(m.get("branchSeq")),
                    (String) m.get("scenario"), params, Canon.num(m.get("seed")), nodes, FaultPlan.fromList(m.get("faultPlan")), ivs,
                    Canon.num(m.get("horizon")), Boolean.TRUE.equals(m.get("stopOnViolation")), fv,
                    (String) m.get("codeVersion"), (String) m.get("codeFingerprint"), (String) m.get("label"));
        } catch (UnsupportedFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptLogException("malformed timeline header: " + e);
        }
    }

    /** Thrown when a stored file has a format version this build does not read. */
    public static final class UnsupportedFormatException extends RuntimeException {
        public UnsupportedFormatException(String msg) {
            super(msg);
        }
    }
}
