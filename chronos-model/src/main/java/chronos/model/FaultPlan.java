package chronos.model;

import java.util.*;

/** A fault plan: an ordered list of rules (Section 18). Immutable. */
public record FaultPlan(List<FaultRule> rules) {
    public FaultPlan {
        rules = List.copyOf(rules);
        Set<String> seen = new HashSet<>();
        for (FaultRule r : rules)
            if (!seen.add(r.id())) throw new IllegalArgumentException("duplicate fault rule id: " + r.id());
    }

    public static FaultPlan empty() {
        return new FaultPlan(List.of());
    }

    public static FaultPlan of(FaultRule... rules) {
        return new FaultPlan(List.of(rules));
    }

    public FaultPlan with(FaultRule r) {
        List<FaultRule> l = new ArrayList<>(rules);
        l.add(r);
        return new FaultPlan(l);
    }

    public FaultPlan without(String ruleId) {
        List<FaultRule> l = new ArrayList<>();
        for (FaultRule r : rules) if (!r.id().equals(ruleId)) l.add(r);
        return new FaultPlan(l);
    }

    public Optional<FaultRule> find(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** The sentence a run's report must carry when the plan has nonLocal rules (D-13), or null if the locality law M8 applies. */
    public String localityNote() {
        List<String> ids = rules.stream().filter(FaultRule::nonLocal).map(FaultRule::id).toList();
        return ids.isEmpty() ? null : "fault rule(s) " + ids + " are flagged nonLocal: the locality law M8 (an unaffected node behaves identically in a branch) does not apply to this run";
    }

    /** True if some rule is nonLocal, which voids law M8 (locality) for the run; the run's report says so. */
    public boolean hasNonLocalRules() {
        return rules.stream().anyMatch(FaultRule::nonLocal);
    }

    public Object toList() {
        List<Object> l = new ArrayList<>();
        for (FaultRule r : rules) l.add(r.toMap());
        return l;
    }

    public static FaultPlan fromList(Object o) {
        List<FaultRule> l = new ArrayList<>();
        for (Object e : Canon.list(o)) l.add(FaultRule.fromMap(Canon.map(e)));
        return new FaultPlan(l);
    }
}
