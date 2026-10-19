package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEV-03: the specification's Phase 12a sentence read literally ("touches" = the node sits on a side of an active partition) versus
 * the refined scope CHRONOS uses by default (the node must also have heard from the other side, the test the specification gives for
 * crash and pause). The switch is the scenario parameter omission=literal. This test records what each choice does.
 */
class OmissionScopeStudyTest {
    @TempDir
    Path tmp;

    private record Outcome(String t0, long omissionEdges, Set<String> omissionOnNodes, DivergenceReport report, Set<String> affected) {}

    private Outcome run(String mode) {
        Env env = new Env(tmp.resolve(mode));
        env.record("re", 42, mode.equals("literal") ? Map.of("omission", "literal") : Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<Event> ev = env.events(t0);
        long edges = 0;
        Set<String> nodes = new TreeSet<>();
        for (Event e : ev)
            for (Parent p : e.parents())
                if (p.kind() == EdgeKind.OMISSION) {
                    edges++;
                    nodes.add(e.nodeId());
                }
        long at = env.manager.roundToBoundary(t0, ev.stream().filter(e -> e.eventId().equals("fault:partition-pb#1")).findFirst().orElseThrow().seq() - 1);
        var br = env.manager.branch(t0, at, List.of(Intervention.removeFault("rm", "partition-pb")), null, null);
        DivergenceReport d = new TimelineComparator(env.store).compare(t0, br.timelineId(), false);
        return new Outcome(t0, edges, nodes, d, new TreeSet<>(d.affectedNodes()));
    }

    @Test
    void theLiteralScopeBlamesThePartitionForTheIsolatedNodesOwnHeartbeatsAndWidensTheComparison() {
        Outcome refined = run("refined"), literal = run("literal");
        // what each scope links to the partition
        assertEquals(1, refined.omissionEdges());
        assertEquals(Set.of("b"), refined.omissionOnNodes());
        assertEquals(5, literal.omissionEdges());
        assertEquals(Set.of("b", "p"), literal.omissionOnNodes(), "p's own heartbeat timers wait for nothing from b, yet get OMISSION edges");
        // what does NOT change: the answer to the headline question
        assertEquals(refined.report().primary().eventId(), literal.report().primary().eventId());
        assertEquals("fault:partition-pb#1", literal.report().primary().eventId());
        assertEquals(refined.report().path().stream().map(DivergenceReport.Hop::from).toList(), literal.report().path().stream().map(DivergenceReport.Hop::from).toList());
        // what changes: the comparison blames the partition for changes on p that the intervention does not really cause
        assertEquals(Set.of("b", "env"), refined.affected());
        assertEquals(Set.of("b", "env", "p"), literal.affected());
        assertTrue(literal.report().roots().size() > refined.report().roots().size(), "literal adds a spurious root on p");
        assertTrue(literal.report().coneA() > refined.report().coneA());
    }

    @Test
    void bothScopesKeepEveryStructuralLawAndReplayAcrossManyChaosRuns() {
        TestRegistry reg = new TestRegistry();
        for (String mode : List.of("refined", "literal")) {
            Map<String, String> params = mode.equals("literal") ? Map.of("omission", "literal") : Map.of();
            for (String sc : List.of("re", "re2", "ka2", "ka3", "gossip", "control")) {
                Campaign.Check check = run -> {
                    List<String> problems = new ArrayList<>(CausalGraph.build(run.log().events()).selfCheck(120));
                    ReplayEngine.Result r = new ReplayEngine(MemoryTimelines.of(run), reg).verify(run.header().timelineId());
                    if (!r.ok()) problems.add("replay: " + r.summary());
                    if (mode.equals("literal") != "literal".equals(run.header().params().get("omission"))) problems.add("the mode is not in the header");
                    return problems;
                };
                for (Campaign.Entry e : Campaign.run(reg, sc, params, 1, 40, 4, true, null, check))
                    assertTrue(e.problems().isEmpty(), mode + " " + sc + " seed " + e.seed() + ": " + e.problems());
            }
        }
    }
}
