package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.runtime.MemoryLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** R5: a node re-executed alone from its recorded inputs produces exactly the recorded outputs and states. */
class NodeLocalReplayTest {
    @TempDir
    Path tmp;

    @Test
    void everyNodeOfEveryDeterministicDemoReplaysAloneUnderDefaultAndChaosPlans() {
        TestRegistry reg = new TestRegistry();
        int[] nodeRuns = {0};
        Campaign.Check check = run -> {
            List<String> problems = new ArrayList<>();
            MemoryTimelines mem = MemoryTimelines.of(run);
            NodeLocalReplay nlr = new NodeLocalReplay(mem, reg);
            for (String n : run.header().nodes()) {
                NodeLocalReplay.Result r = nlr.replay(run.header().timelineId(), n);
                if (!r.ok()) problems.add(r.text());
                synchronized (nodeRuns) { nodeRuns[0]++; }
            }
            return problems;
        };
        for (String sc : List.of("pingpong", "causal", "re", "re2", "ka2", "ka3", "control", "gossip", "twopairs", "clocky")) {
            for (boolean chaos : new boolean[]{false, true}) {
                List<Campaign.Entry> res = Campaign.run(reg, sc, Map.of(), 1, 25, 4, chaos, null, check);
                for (Campaign.Entry e : res) assertTrue(e.problems().isEmpty(), sc + " chaos=" + chaos + " seed " + e.seed() + ": " + e.problems());
            }
        }
        assertTrue(nodeRuns[0] > 1000, "many node replays ran: " + nodeRuns[0]);
    }

    @Test
    void aTamperedRecordedOutputIsReportedAtThatStepWithTheExpectation() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String tl = env.last;
        List<Event> events = new ArrayList<>(env.events(tl));
        int victim = -1;
        for (Event e : events) if (e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("p") && e.seq() > 20) { victim = (int) e.seq(); break; }
        Event orig = events.get(victim);
        Map<String, Object> pl = new TreeMap<>(orig.payload());
        pl.put("type", "Tampered");
        events.set(victim, new Event(orig.eventId(), orig.seq(), orig.timelineId(), orig.nodeId(), orig.kind(), orig.virtualTime(), orig.lamport(),
                orig.vector(), orig.parents(), pl, orig.stateDigestAfter(), orig.stateAfter(), orig.origin()));
        MemoryTimelines mem = rebuild(env, tl, events);
        NodeLocalReplay nlr = new NodeLocalReplay(mem, env.registry);
        NodeLocalReplay.Result r = nlr.replay(tl, "p");
        assertFalse(r.ok());
        assertTrue(r.mismatch().expected().contains("Tampered"), r.text());
        assertTrue(r.mismatch().what().contains("sent message"), r.text());
        assertTrue(r.steps() > 3, "the steps before the tampered one matched");
        // other nodes are unaffected by the tamper in p's output (they are fed the recorded inputs, not p's re-run)... except the receiver of that message
        assertTrue(nlr.replay(tl, "client").ok());
    }

    @Test
    void aTamperedStateDigestNamesTheFieldThatDiffers() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String tl = env.last;
        List<Event> events = new ArrayList<>(env.events(tl));
        int victim = -1;
        for (Event e : events) if (e.nodeId().equals("b") && e.stateAfter() != null && e.kind() == EventKind.TIMER_FIRED) { victim = (int) e.seq(); break; }
        Event orig = events.get(victim);
        Map<String, Object> st = new TreeMap<>(orig.stateAfter());
        Map<String, Object> vol = new TreeMap<>(Canon.map(st.get("volatile")));
        vol.put("role", "TAMPERED");
        st.put("volatile", vol);
        events.set(victim, new Event(orig.eventId(), orig.seq(), orig.timelineId(), orig.nodeId(), orig.kind(), orig.virtualTime(), orig.lamport(),
                orig.vector(), orig.parents(), orig.payload(), Canon.digest(st), st, orig.origin()));
        NodeLocalReplay.Result r = new NodeLocalReplay(rebuild(env, tl, events), env.registry).replay(tl, "b");
        assertFalse(r.ok());
        assertTrue(r.mismatch().what().contains("volatile.role"), r.text());
    }

    @Test
    void theBadCitizenCannotBeReplayedAloneEither() {
        Env env = new Env(tmp);
        env.record("bad", 7, Map.of());
        String tl = env.last;
        boolean anyMismatch = false;
        for (String n : env.store.header(tl).nodes()) anyMismatch |= !new NodeLocalReplay(env.store, env.registry).replay(tl, n).ok();
        assertTrue(anyMismatch, "hidden nondeterminism is caught by node-local replay too");
    }

    @Test
    void syntheticStateEditsAreRefusedAndUnknownNodesRejected() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, 40);
        var br = env.manager.branch(t0, at, List.of(Intervention.patchState("ps", "b", "volatile.role", "PRIMARY")), null, null);
        NodeLocalReplay.Result r = new NodeLocalReplay(env.store, env.registry).replay(br.timelineId(), "b");
        assertFalse(r.supported());
        assertTrue(r.text().contains("NOT SUPPORTED"));
        // the node it did not edit still replays from the same recording
        assertTrue(new NodeLocalReplay(env.store, env.registry).replay(br.timelineId(), "p").ok());
        assertThrows(IllegalArgumentException.class, () -> new NodeLocalReplay(env.store, env.registry).replay(t0, "ghost"));
        // a branch with honest interventions replays node-locally too (inputs are whatever the branch recorded)
        var heal = env.manager.branch(t0, env.manager.roundToBoundary(t0, 70), List.of(Intervention.shiftHeal("h", "partition-pb", -200)), null, null);
        for (String n : env.store.header(heal.timelineId()).nodes()) assertTrue(new NodeLocalReplay(env.store, env.registry).replay(heal.timelineId(), n).ok(), n);
    }

    private static MemoryTimelines rebuild(Env env, String tl, List<Event> events) {
        MemoryTimelines mem = new MemoryTimelines();
        MemoryLog log = new MemoryLog();
        long start = 0;
        for (long end : env.store.groupEnds(tl)) {
            log.append(events.subList((int) start, (int) end + 1));
            start = end + 1;
        }
        log.finish(env.store.summary(tl));
        mem.add(env.store.header(tl), log);
        return mem;
    }
}
