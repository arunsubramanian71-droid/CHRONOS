package chronos.analysis;

import chronos.model.*;
import chronos.sdk.NodeSpec;
import chronos.sdk.Scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Chaos generator (Section 18): a fault plan generated from a seed. It uses CHRONOS's own seeded generator, so the same seed
 * always yields the same plan. Rules are keyed by time or identity (D-13): partitions and crash/restart pairs by time,
 * probabilistic drop/delay/duplicate rules by a named sender and identity-keyed hashing.
 */
public final class ChaosPlanGenerator {
    private ChaosPlanGenerator() {}

    public static FaultPlan generate(Scenario sc, long seed, long horizon) {
        SeededRandom r = new SeededRandom(IdentityHash.of(seed, "chaos"));
        List<String> nodes = sc.nodes().stream().map(NodeSpec::name).toList();
        List<FaultRule> rules = new ArrayList<>();
        int n = 1 + pick(r, 4);
        for (int i = 1; i <= n; i++) {
            switch (pick(r, 10)) {
                case 0 -> {
                    if (nodes.size() < 2) continue;
                    List<String> shuffled = new ArrayList<>(nodes);
                    for (int k = shuffled.size() - 1; k > 0; k--) java.util.Collections.swap(shuffled, k, pick(r, k + 1));
                    int cut = 1 + pick(r, shuffled.size() - 1);
                    long at = pick(r, Math.max(1, (int) (horizon * 7 / 10)));
                    long dur = 50 + pick(r, 350);
                    rules.add(FaultRule.partition("c" + i + "-partition", at, dur, List.of(shuffled.subList(0, cut), shuffled.subList(cut, shuffled.size()))));
                }
                case 1 -> {
                    String node = nodes.get(pick(r, nodes.size()));
                    long at = 20 + pick(r, Math.max(1, (int) (horizon / 2)));
                    rules.add(FaultRule.crash("c" + i + "-crash", node, at));
                    rules.add(FaultRule.restart("c" + i + "-restart", node, at + 30 + pick(r, 300)));
                }
                case 2 -> rules.add(FaultRule.probabilistic("c" + i + "-drop", FaultRule.FaultAction.DROP, nodes.get(pick(r, nodes.size())), "*", "*", 30 + pick(r, 220), null));
                case 3 -> rules.add(FaultRule.probabilistic("c" + i + "-delay", FaultRule.FaultAction.DELAY, nodes.get(pick(r, nodes.size())), "*", "*", 30 + pick(r, 220), (long) (10 + pick(r, 190))));
                case 4 -> {
                    String node = nodes.get(pick(r, nodes.size()));
                    rules.add(FaultRule.pause("c" + i + "-pause", node, 20 + pick(r, Math.max(1, (int) (horizon / 2))), 30 + pick(r, 300)));
                }
                case 5 -> {
                    String node = nodes.get(pick(r, nodes.size()));
                    rules.add(FaultRule.clockSkew("c" + i + "-skew", node, pick(r, Math.max(1, (int) (horizon / 2))), pick(r, 4000) - 2000L, pick(r, 2) == 0 ? null : (long) (50 + pick(r, 300))));
                }
                case 6 -> {
                    if (nodes.size() < 2) continue;
                    String a = nodes.get(pick(r, nodes.size()));
                    String b = nodes.get(pick(r, nodes.size()));
                    if (a.equals(b)) continue;
                    rules.add(FaultRule.slowLink("c" + i + "-slow", a, b, 2 + pick(r, 5), pick(r, Math.max(1, (int) (horizon / 2))), pick(r, 2) == 0 ? null : (long) (50 + pick(r, 300))));
                }
                case 7 -> rules.add(FaultRule.reorder("c" + i + "-reorder", nodes.get(pick(r, nodes.size())), nodes.get(pick(r, nodes.size())), "*", 1 + pick(r, 6)));
                case 8 -> {
                    String node = nodes.get(pick(r, nodes.size()));
                    String trigger = nodes.get(pick(r, nodes.size())) + "#" + (2 + pick(r, 8));
                    rules.add(pick(r, 2) == 0 ? FaultRule.after("c" + i + "-crash-after", trigger, FaultRule.FaultAction.CRASH, java.util.Map.of("node", node))
                            : FaultRule.after("c" + i + "-pause-after", trigger, FaultRule.FaultAction.PAUSE, java.util.Map.of("node", node, "duration", (long) (30 + pick(r, 200)))));
                }
                default -> rules.add(FaultRule.probabilistic("c" + i + "-dup", FaultRule.FaultAction.DUPLICATE, nodes.get(pick(r, nodes.size())), "*", "*", 30 + pick(r, 170), null));
            }
        }
        return new FaultPlan(rules);
    }

    private static int pick(SeededRandom r, int bound) {
        return (int) Math.floorMod(r.nextLong(), (long) bound);
    }
}
