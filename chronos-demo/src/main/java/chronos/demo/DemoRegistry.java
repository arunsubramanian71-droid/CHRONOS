package chronos.demo;

import chronos.demo.bad.BadCitizen;
import chronos.sdk.ConfiguredScenario;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;

import java.util.List;
import java.util.Map;

/**
 * Scenario registration (D-22): scenarios are Java classes registered by name. Parameters:
 *   re, re2   partition=on|off (default on: the failing flagship run)
 *   ka2       delay=on|off     ka3  crash=on|off
 *   gossip    nodes=N, horizon=MS
 *   any       omission=literal  use the specification's literal OMISSION scope for partitions (DEV-03); default is the refined scope
 */
public final class DemoRegistry implements ScenarioRegistry {
    @Override
    public List<String> names() {
        return List.of("pingpong", "causal", "re", "re2", "ka2", "ka3", "control", "gossip", "bad", "tutorial");
    }

    @Override
    public Scenario create(String name, Map<String, String> p) {
        Scenario base = build(name, p);
        return "literal".equals(p.get("omission")) ? new ConfiguredScenario(base, Map.of("chronos.omission", "literal")) : base;
    }

    private Scenario build(String name, Map<String, String> p) {
        return switch (name) {
            case "pingpong" -> new PingPong();
            case "causal" -> new CausalDemo();
            case "re" -> ReplicatedStore.re(!"off".equals(p.get("partition")));
            case "re2" -> ReplicatedStore.re2(!"off".equals(p.get("partition")));
            case "ka2" -> new LostUpdate(!"off".equals(p.get("delay")));
            case "ka3" -> new BlockedCommit(!"off".equals(p.get("crash")));
            case "control" -> new Control();
            case "gossip" -> new Gossip(Integer.parseInt(p.getOrDefault("nodes", "5")), Long.parseLong(p.getOrDefault("horizon", "2000")));
            case "bad" -> new BadCitizen();
            case "tutorial" -> new Tutorial();
            default -> throw new IllegalArgumentException("unknown scenario '" + name + "'; known: " + names());
        };
    }
}
