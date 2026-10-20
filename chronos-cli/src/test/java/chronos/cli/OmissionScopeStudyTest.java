package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEV-03 (closed): the specification's Phase 12a sentence read literally ("touches" = the node sits on a side of an active partition) versus
 * the refined scope CHRONOS uses by default (the node must also have heard from the other side, the test the specification gives for
 * crash and pause). The switch is the scenario parameter omission=literal. This test records what each choice does.
 */
class OmissionScopeStudyTest {
    @TempDir
    Path tmp;

    private record Outcome(String t0, long omissionEdges, Set<String> omissionOnNodes, DivergenceReport report, Set<String> affected) {}

    private Outcome run(String mode) {
        return run(mode, false);
    }

    private Outcome run(String mode, boolean heal) {
        Env env = new Env(tmp.resolve(mode + heal));
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
        var br = env.manager.branch(t0, at, List.of(heal ? Intervention.shiftHeal("rm", "partition-pb", -200) : Intervention.removeFault("rm", "partition-pb")), null, null);
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
        assertEquals("iv:rm", literal.report().primary().eventId(), "the intervention is first (M10); the removed partition is the other root");
        assertTrue(literal.report().roots().stream().anyMatch(r -> r.eventId().equals("fault:partition-pb#1")));
        assertEquals(refined.report().path().stream().map(DivergenceReport.Hop::from).toList(), literal.report().path().stream().map(DivergenceReport.Hop::from).toList());
        // what changes: the comparison reports p, which the intervention does not really touch, as affected
        assertEquals(Set.of("b", "env"), refined.affected());
        assertEquals(Set.of("b", "env", "p"), literal.affected());
        assertTrue(literal.report().coneA() > refined.report().coneA(), "the divergence cone grows to include p's heartbeats");
        assertTrue(literal.report().coneB() > refined.report().coneB());
        // the heal-earlier counterfactual of AD-4/AD-5 shows the same
        Outcome refinedHeal = run("refined", true), literalHeal = run("literal", true);
        assertEquals(Set.of("b", "env"), refinedHeal.affected());
        assertEquals(Set.of("b", "env", "p"), literalHeal.affected());
        assertEquals(refinedHeal.report().path().stream().map(DivergenceReport.Hop::from).toList(), literalHeal.report().path().stream().map(DivergenceReport.Hop::from).toList());
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

    // ---- the final scope (evidence of a silenced message), DEV-03 -------------------------------------------------------------------------

    private static final FaultPlan EARLY = FaultPlan.of(FaultRule.partition("early", 0, 500L, List.of(List.of("p", "client"), List.of("b"))));

    private Set<String> omissionNodes(List<Event> ev, String rule) {
        Set<String> nodes = new TreeSet<>();
        for (Event e : ev)
            for (Parent par : e.parents()) if (par.kind() == EdgeKind.OMISSION && par.id().startsWith("fault:" + rule)) nodes.add(e.nodeId());
        return nodes;
    }

    @Test
    void aPartitionThatBeginsBeforeAnyContactStillExplainsTheWatchdogItCausedAndOnlyThat() {
        // Found while closing DEV-03: with the heard-from test alone b had never heard from p when the partition began at 0 ms, so its watchdog
        // got no OMISSION edge and `why` could not name the partition. A silenced message (p's heartbeat dropped on its way to b) is the evidence.
        Env env = new Env(tmp.resolve("early"));
        env.record("re", 42, Map.of("partition", "off"), EARLY, Boolean.FALSE);
        String t0 = env.last;
        List<Event> ev = env.events(t0);
        assertEquals(Set.of("b"), omissionNodes(ev, "early"), "b's watchdog, and nothing on p or the client (their timers wait for nothing from b)");
        Event fired = ev.stream().filter(e -> e.nodeId().equals("b") && e.kind() == EventKind.TIMER_FIRED).findFirst().orElseThrow();
        assertTrue(fired.parents().stream().anyMatch(par -> par.kind() == EdgeKind.OMISSION && par.id().equals("fault:early#1")));
        assertTrue(ev.stream().anyMatch(e -> e.kind() == EventKind.MESSAGE_DROPPED && "partition early".equals(e.payload().get("reason"))), "the evidence exists in the log");
        TimeTravelSession s = new TimeTravelSession(env.store, t0);
        s.goToViolation();
        String why = s.why(null);
        assertTrue(why.contains("chain from fault:early#1") && why.contains("--OMISSION-->"), why);
        assertTrue(why.contains("POTENTIAL"), "and it is still labelled potential: " + why);
        // the literal rule of Phase 12a adds edges on nodes whose timers wait for nothing across the cut; the final scope is a subset of it
        Env lit = new Env(tmp.resolve("earlyLit"));
        lit.record("re", 42, Map.of("partition", "off", "omission", "literal"), EARLY, Boolean.FALSE);
        Set<String> literalNodes = omissionNodes(lit.events(lit.last), "early");
        assertTrue(literalNodes.containsAll(Set.of("b")) && literalNodes.size() > 1, "literal: " + literalNodes);
        // the whole thing is deterministic and replays, also from every checkpoint (the evidence is part of the world image)
        assertTrue(env.replay.verify(t0).ok());
        for (long seq : env.store.checkpointSeqs(t0)) assertTrue(env.replay.verifyFrom(t0, seq).ok(), "resume from checkpoint " + seq);
        assertTrue(CausalGraph.build(ev).selfCheck(200).isEmpty());
    }

    @Test
    void theFinalScopeNeverAddsAnEdgeTheLiteralRuleWouldNotAndNeverRemovesOneTheHeardFromTestHad() {
        TestRegistry reg = new TestRegistry();
        int compared = 0, extra = 0;
        for (String sc : List.of("re", "re2", "ka2", "ka3", "gossip", "control")) {
            for (long seed = 1; seed <= 40; seed++) {
                var scenario = reg.create(sc, sc.equals("gossip") ? Map.of("nodes", "4", "horizon", "300") : Map.of());
                FaultPlan plan = ChaosPlanGenerator.generate(scenario, seed, sc.equals("gossip") ? 300 : scenario.defaultHorizon());
                Map<String, String> params = sc.equals("gossip") ? Map.of("nodes", "4", "horizon", "300") : Map.of();
                Set<String> fin = edges(reg, sc, seed, params, plan), lit = edges(reg, sc, seed, merge(params, "omission", "literal"), plan);
                assertTrue(lit.containsAll(fin), sc + " seed " + seed + ": final scope has edges the literal rule lacks: " + fin);
                compared++;
                if (lit.size() > fin.size()) extra++;
            }
        }
        assertEquals(240, compared);
        assertTrue(extra > 0, "the literal rule is genuinely broader on some runs, so the comparison is not vacuous");
    }

    private static Map<String, String> merge(Map<String, String> m, String k, String v) {
        Map<String, String> r = new TreeMap<>(m);
        r.put(k, v);
        return r;
    }

    private Set<String> edges(TestRegistry reg, String sc, long seed, Map<String, String> params, FaultPlan plan) {
        var run = Recording.recordInMemory(reg, "Xs", Recording.Spec.of(sc, seed).withParams(params).withPlan(plan).withCheckpointInterval(0));
        Set<String> out = new TreeSet<>();
        for (Event e : run.log().events())
            for (Parent par : e.parents()) if (par.kind() == EdgeKind.OMISSION) out.add(e.eventId() + "<-" + par.id());
        return out;
    }
}
