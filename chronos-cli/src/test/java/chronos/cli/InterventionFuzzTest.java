package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fuzzer for the whole intervention catalogue: random interventions at random step boundaries of random chaos runs. Whatever is
 * applied, the branch must replay through its lineage, pass the graph self-check, share its parent's prefix, and a branch of a branch
 * must do the same. Interventions that are invalid at the chosen point must be refused with a clear error, never accepted silently
 * or crash the engine.
 */
class InterventionFuzzTest {
    @TempDir
    Path tmp;

    static Intervention random(SeededRandom r, List<Event> parent, long at, List<String> nodes, FaultPlan plan, int n) {
        long vt = parent.get((int) at).virtualTime();
        List<Event> futureSends = parent.stream().filter(e -> e.seq() > at && e.kind() == EventKind.MESSAGE_SENT).toList();
        List<Event> pastSends = parent.stream().filter(e -> e.seq() <= at && e.kind() == EventKind.MESSAGE_SENT).toList();
        String node = nodes.get((int) Math.floorMod(r.nextLong(), (long) nodes.size()));
        String id = "z" + n;
        List<FaultRule> rules = plan.rules();
        return switch ((int) Math.floorMod(r.nextLong(), 14L)) {
            case 0 -> rules.isEmpty() ? Intervention.crashNode(id, node, vt + 5) : Intervention.removeFault(id, rules.get((int) Math.floorMod(r.nextLong(), (long) rules.size())).id());
            case 1 -> rules.isEmpty() ? Intervention.pauseNode(id, node, vt + 3, 40) : Intervention.shiftHeal(id, rules.get((int) Math.floorMod(r.nextLong(), (long) rules.size())).id(), Math.floorMod(r.nextLong(), 200L) - 100);
            case 2 -> futureSends.isEmpty() ? Intervention.crashNode(id, node, vt + 7) : Intervention.dropMessage(id, futureSends.get((int) Math.floorMod(r.nextLong(), (long) futureSends.size())).eventId());
            case 3 -> futureSends.isEmpty() ? Intervention.restartNode(id, node, vt + 9) : Intervention.delayMessage(id, futureSends.get((int) Math.floorMod(r.nextLong(), (long) futureSends.size())).eventId(), 1 + Math.floorMod(r.nextLong(), 150L));
            case 4 -> futureSends.isEmpty() ? Intervention.skewClock(id, node, vt + 2, 30, null) : Intervention.duplicateMessage(id, futureSends.get((int) Math.floorMod(r.nextLong(), (long) futureSends.size())).eventId());
            case 5 -> Intervention.crashNode(id, node, vt + Math.floorMod(r.nextLong(), 120L));
            case 6 -> Intervention.restartNode(id, node, vt + Math.floorMod(r.nextLong(), 120L));
            case 7 -> Intervention.pauseNode(id, node, vt + Math.floorMod(r.nextLong(), 100L), 1 + Math.floorMod(r.nextLong(), 120L));
            case 8 -> Intervention.skewClock(id, node, vt + Math.floorMod(r.nextLong(), 100L), Math.floorMod(r.nextLong(), 400L) - 200, r.nextLong() % 2 == 0 ? null : 50L);
            case 9 -> Intervention.addInput(id, node, vt + Math.floorMod(r.nextLong(), 100L), Map.of("key", "k" + n, "value", (long) n));
            case 10 -> Intervention.removeInput(id, Math.floorMod(r.nextLong(), 6L));
            case 11 -> {
                if (pastSends.size() < 2) yield Intervention.reseedStream(id, node, "x", n);
                String a = pastSends.get((int) Math.floorMod(r.nextLong(), (long) pastSends.size())).eventId(), b = pastSends.get((int) Math.floorMod(r.nextLong(), (long) pastSends.size())).eventId();
                yield a.equals(b) ? Intervention.swapDeliveries(id, a, pastSends.get(0).eventId().equals(a) ? pastSends.get(1).eventId() : pastSends.get(0).eventId()) : Intervention.swapDeliveries(id, a, b);
            }
            case 12 -> Intervention.configPatch(id, "heartbeat", Long.toString(50 + Math.floorMod(r.nextLong(), 200L)));
            default -> Intervention.reseedStream(id, node, "t", Math.floorMod(r.nextLong(), 1000L));
        };
    }

    @Test
    void randomInterventionsAtRandomBoundariesAlwaysYieldReplayableBranchesOrAClearRefusal() {
        TestRegistry reg = new TestRegistry();
        List<String> scenarios = List.of("gossip", "re", "re2", "ka2", "ka3", "twopairs", "tutorial");
        int applied = 0, refused = 0, nested = 0;
        long seeds = Long.parseLong(System.getenv().getOrDefault("CHRONOS_FUZZ_SEEDS", "70")); // raise it for a long run: CHRONOS_FUZZ_SEEDS=1000 mvn ...
        long from = Long.parseLong(System.getenv().getOrDefault("CHRONOS_FUZZ_FROM", "1"));
        for (long seed = from; seed <= seeds; seed++) {
            String sc = scenarios.get((int) (seed % scenarios.size()));
            Map<String, String> params = sc.equals("gossip") ? Map.of("nodes", "4", "horizon", "400") : Map.of();
            var scenario = reg.create(sc, params);
            FaultPlan plan = ChaosPlanGenerator.generate(scenario, seed, sc.equals("gossip") ? 400 : scenario.defaultHorizon());
            Env env = new Env(tmp.resolve("f" + seed));
            env.record(sc, seed, params, plan, Boolean.FALSE);
            String t = env.last;
            List<Event> parent = env.events(t);
            SeededRandom rnd = new SeededRandom(seed * 31);
            List<String> nodes = env.store.header(t).nodes();
            for (int k = 0; k < 4; k++) {
                long boot = env.manager.bootEnd(t);
                long want = boot + Math.floorMod(rnd.nextLong(), (long) Math.max(1, parent.size() - boot - 1));
                long at = env.manager.roundToBoundary(t, want);
                Intervention iv = random(rnd, parent, at, nodes, plan, k);
                String where = sc + " seed " + seed + " at " + at + " " + iv.type() + " " + Canon.json(iv.params());
                String child;
                try {
                    child = env.manager.branch(t, at, List.of(iv), null, null).timelineId();
                } catch (IllegalArgumentException refusal) {
                    assertNotNull(refusal.getMessage(), where);
                    assertFalse(refusal.getMessage().isBlank(), where);
                    refused++;
                    continue;
                }
                applied++;
                List<Event> c = env.events(child);
                for (int i = 0; i <= at; i++) assertEquals(parent.get(i).toLine().replaceFirst("\"tl\":\"[^\"]*\",", ""), c.get(i).toLine().replaceFirst("\"tl\":\"[^\"]*\",", ""), where + " prefix " + i);
                assertTrue(c.stream().anyMatch(e -> e.kind() == EventKind.INTERVENTION_APPLIED), where);
                List<String> graph;
                try {
                    graph = CausalGraph.build(c).selfCheck(150);
                } catch (RuntimeException broken) {
                    throw new AssertionError(where + ": the branch log is not a valid graph: " + broken.getMessage(), broken);
                }
                assertTrue(graph.isEmpty(), where + ": " + graph.stream().limit(3).toList());
                // an application that fails on an input it never expected (an added input with an arbitrary body) is the application's doing, recorded as NODE_FAILED
                if (!iv.type().equals(Intervention.ADD_INPUT)) assertTrue(c.stream().noneMatch(e -> e.kind() == EventKind.NODE_FAILED), where + " crashed a node's application: " + c.stream().filter(e -> e.kind() == EventKind.NODE_FAILED).map(e -> e.payload()).toList());
                ReplayEngine.Result r = env.replay.verify(child);
                assertTrue(r.ok(), where + ": " + r.summary());
                // a branch of the branch, with a second random intervention
                if (k % 2 == 0) {
                    long at2 = env.manager.roundToBoundary(child, at + Math.max(1, (c.size() - at) / 2));
                    Intervention iv2 = random(rnd, c, at2, nodes, plan, 10 + k);
                    try {
                        String grand = env.manager.branch(child, at2, List.of(iv2), null, null).timelineId();
                        ReplayEngine.Result gr = env.replay.verify(grand);
                        assertTrue(gr.ok(), where + " then " + iv2.type() + " " + Canon.json(iv2.params()) + ": the branch of the branch must replay through two lineages: " + gr.summary() + (gr.violation() == null ? "" : "\n" + gr.violation().report()));
                        assertTrue(CausalGraph.build(env.events(grand)).selfCheck(100).isEmpty(), where + " then " + iv2.type());
                        nested++;
                    } catch (IllegalArgumentException refusal) {
                        assertFalse(refusal.getMessage().isBlank());
                    }
                }
            }
            // everything this seed wrote (a chaos root, its branches and their branches) is a consistent store: no failure, and no warning on a freshly written one
            StoreChecker.Report rep = new StoreChecker(env.store, reg).check(List.of(), false);
            assertTrue(rep.ok() && rep.count(StoreChecker.Status.WARN) == 0, sc + " seed " + seed + ": " + rep.text());
        }
        if (from == 1) {
            assertTrue(applied >= seeds * 2, "applied " + applied + ", refused " + refused);
            assertTrue(nested >= seeds / 3, "nested " + nested);
        }
    }
}
