package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A branch made at the last event of a finished timeline (DEV-52). The image written when a run finishes is taken after the run's trailing
 * silent steps, so building the branch's world from it lost the agenda items those steps had consumed (here a pause rule at 319 ms for a node
 * that had crashed at 0): the branch ended at time 0 with another end-state digest, and replaying it through its lineage then failed.
 * Found by the intervention fuzzer at seed 20726 (the 20,001..26,000 range).
 */
class BranchAtFinalEventTest {
    @TempDir
    Path tmp;

    private Env tutorial() {
        TestRegistry reg = new TestRegistry();
        var scenario = reg.create("tutorial", Map.of());
        FaultPlan plan = ChaosPlanGenerator.generate(scenario, 20726, scenario.defaultHorizon());
        Env env = new Env(tmp);
        env.record("tutorial", 20726, Map.of(), plan, Boolean.FALSE);
        return env;
    }

    @Test
    void theFinalImageIsMarkedAndTheBoundaryLookupSkipsIt() {
        Env env = tutorial();
        String t = env.last;
        long last = env.events(t).size() - 1;
        WorldImage fin = env.store.checkpointAtOrBefore(t, last).orElseThrow();
        assertEquals(last, fin.seq());
        assertTrue(fin.data().containsKey("liveTime"), "the end-of-run image records that it was taken after silent steps (time " + fin.data().get("liveTime") + ")");
        WorldImage boundary = env.store.boundaryCheckpointAtOrBefore(t, last).orElseThrow();
        assertTrue(boundary.seq() < last && !boundary.data().containsKey("liveTime"), "the boundary lookup falls back to an older image: " + boundary.seq());
    }

    @Test
    void aBranchAtTheLastEventOfARootAndOfABranchKeepsTheSilentTailAndReplaysThroughItsLineage() {
        Env env = tutorial();
        String t = env.last;
        long last = env.events(t).size() - 1;
        TimelineSummary rootSum = env.store.summary(t);
        assertEquals(319, rootSum.finalVirtualTime(), "the root's run goes on silently to 319 ms after its last event");

        // a neutral intervention (a stream that nothing draws from) at the root's last event: the run must end exactly as the root did
        String b1 = env.manager.branch(t, last, List.of(Intervention.reseedStream("z1", "receiver", "unused", 5)), null, null).timelineId();
        TimelineSummary s1 = env.store.summary(b1);
        assertEquals(rootSum.finalVirtualTime(), s1.finalVirtualTime(), "the pending pause rule still fires");
        assertEquals(rootSum.endStateDigest(), s1.endStateDigest());
        assertTrue(env.replay.verify(b1).ok(), env.replay.verify(b1).summary());

        // and the same at the last event of a branch (the fuzzer's case: a branch of a branch)
        String child = env.manager.branch(t, 4, List.of(Intervention.removeFault("z2", "c3-slow")), null, null).timelineId();
        long childLast = env.events(child).size() - 1;
        for (Intervention iv : List.of(Intervention.reseedStream("z12", "sender", "t", 561), Intervention.configPatch("z13", "k", "v"), Intervention.reseedStream("z14", "receiver", "q", 5))) {
            String grand = env.manager.branch(child, childLast, List.of(iv), null, null).timelineId();
            ReplayEngine.Result r = env.replay.verify(grand);
            assertTrue(r.ok(), iv.type() + ": " + r.summary());
            assertEquals(env.store.summary(child).finalVirtualTime(), env.store.summary(grand).finalVirtualTime(), iv.type());
        }
        // the lineage-wide checks agree
        StoreChecker.Report rep = new StoreChecker(env.store, env.registry).check(List.of(), true);
        assertTrue(rep.ok() && rep.count(StoreChecker.Status.WARN) == 0, rep.text());
    }
}
