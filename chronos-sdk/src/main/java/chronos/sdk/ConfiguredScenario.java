package chronos.sdk;

import chronos.model.FaultPlan;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A scenario with extra configuration entries added to another scenario's own (everything else is delegated). Used for runtime
 * switches that are part of a recording's identity, such as {@code chronos.omission=literal} (DEV-03): they travel in the
 * header's parameters, so record, replay and branch all build the same world.
 */
public final class ConfiguredScenario implements Scenario {
    private final Scenario inner;
    private final Map<String, String> config;
    private final String name;

    public ConfiguredScenario(Scenario inner, Map<String, String> extra) {
        this(inner, extra, inner.name());
    }

    /** As above, under another name (the blind-debugging kit hides which application a case is built on). */
    public ConfiguredScenario(Scenario inner, Map<String, String> extra, String name) {
        this.inner = inner;
        this.name = name;
        Map<String, String> m = new TreeMap<>(inner.config());
        m.putAll(extra);
        this.config = Map.copyOf(m);
    }

    @Override public String name() { return name; }
    @Override public String version() { return inner.version(); }
    @Override public List<NodeSpec> nodes() { return inner.nodes(); }
    @Override public Map<String, String> config() { return config; }
    @Override public List<ClientInput> workload() { return inner.workload(); }
    @Override public FaultPlan defaultFaultPlan() { return inner.defaultFaultPlan(); }
    @Override public List<Invariant> invariants() { return inner.invariants(); }
    @Override public long defaultHorizon() { return inner.defaultHorizon(); }
    @Override public long baseLatency(String from, String to) { return inner.baseLatency(from, to); }
    @Override public long latencyJitter() { return inner.latencyJitter(); }
    @Override public boolean stopOnViolation() { return inner.stopOnViolation(); }
}
