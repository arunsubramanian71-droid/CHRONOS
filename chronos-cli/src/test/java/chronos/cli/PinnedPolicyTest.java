package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** D-15 (Stretch): the Pinned continuation policy keeps the parent's original delivery instants where that still makes sense. */
class PinnedPolicyTest {
    @TempDir
    Path tmp;

    private long deliveryAtC(Env env, String tl) {
        return env.events(tl).stream().filter(e -> e.kind() == EventKind.MESSAGE_DELIVERED && e.nodeId().equals("c")).findFirst().orElseThrow().virtualTime();
    }

    private String branch(Env env, boolean pinned, long delayBy) {
        env.manager.withPinnedContinuation(pinned);
        try {
            long at = env.manager.roundToBoundary("T0", env.events("T0").stream().filter(e -> e.kind() == EventKind.MESSAGE_SENT && e.nodeId().equals("a")).findFirst().orElseThrow().seq());
            return env.manager.branch("T0", at, List.of(Intervention.delayMessage("d", "a#2", delayBy)), null, null).timelineId();
        } finally {
            env.manager.withPinnedContinuation(false);
        }
    }

    @Test
    void pinnedKeepsTheOriginalDeliveryInstantWhenItIsStillInTheFutureAndFallsBackToContinueWhenNot() {
        Env env = new Env(tmp);
        env.record("chain", 1, Map.of());
        assertEquals(20, deliveryAtC(env, "T0"), "baseline: x arrives at b at 10, y reaches c at 20");
        // x delayed by 5: b forwards at 15. Continue: 15 + 10 = 25. Pinned: the original instant 20 has not passed
        String cont = branch(env, false, 5), pin = branch(env, true, 5);
        assertEquals(25, deliveryAtC(env, cont));
        assertEquals(20, deliveryAtC(env, pin));
        // x delayed by 15: b forwards at 25, after the original instant: Pinned falls back to Continue
        String cont2 = branch(env, false, 15), pin2 = branch(env, true, 15);
        assertEquals(35, deliveryAtC(env, cont2));
        assertEquals(35, deliveryAtC(env, pin2));
        // the policy is recorded, only on the pinned branches
        assertTrue(env.store.header(pin).pinned() && env.store.header(pin2).pinned());
        assertFalse(env.store.header(cont).pinned());
        assertEquals("pinned", env.store.header(pin).params().get("chronos.continuation"));
        // every branch replays and verifies through its lineage: replay reads the recorded delivery times
        for (String id : List.of(cont, pin, cont2, pin2)) assertTrue(env.replay.verify(id).ok(), id);
        // the report says which policy was used
        assertTrue(new CounterfactualEngine(env.manager).assess("T0", pin).text().contains("continuation policy: Pinned"));
        assertFalse(new CounterfactualEngine(env.manager).assess("T0", cont).text().contains("Pinned"));
    }

    @Test
    void withNoInterventionPinnedAndContinueAgreeEverywhereAndLocalityHolds() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        long at = env.manager.roundToBoundary(t0, 40);
        String cont = env.manager.branch(t0, at, List.of(), null, null).timelineId();
        env.manager.withPinnedContinuation(true);
        String pin = env.manager.branch(t0, at, List.of(), null, null).timelineId();
        env.manager.withPinnedContinuation(false);
        // a no-op branch under either policy reproduces the parent exactly (law M7)
        List<Event> p = env.events(t0);
        for (String id : List.of(cont, pin)) {
            List<Event> b = env.events(id);
            assertEquals(p.size(), b.size());
            for (int i = 0; i <= at; i++) assertEquals(p.get(i), b.get(i));
            for (int i = (int) at + 1; i < p.size(); i++) assertEquals(p.get(i).withPosition(i, id), b.get(i), "event " + i);
        }
        // locality (law M8) under Pinned: a pair untouched by the intervention behaves identically
        Env two = new Env(tmp.resolve("two"));
        two.record("twopairs", 3, Map.of());
        String base = two.last;
        long cut = two.manager.roundToBoundary(base, 30);
        two.manager.withPinnedContinuation(true);
        String br = two.manager.branch(base, cut, List.of(Intervention.crashNode("c", "a1", two.events(base).get((int) cut).virtualTime() + 1)), null, null).timelineId();
        two.manager.withPinnedContinuation(false);
        List<Event> x = two.events(base), y = two.events(br);
        for (String node : List.of("b1", "b2")) {
            List<Event> lx = x.stream().filter(e -> e.nodeId().equals(node)).toList(), ly = y.stream().filter(e -> e.nodeId().equals(node)).toList();
            assertEquals(lx.size(), ly.size(), node);
            for (int i = 0; i < lx.size(); i++) assertEquals(lx.get(i).eventId() + lx.get(i).virtualTime() + lx.get(i).stateDigestAfter(), ly.get(i).eventId() + ly.get(i).virtualTime() + ly.get(i).stateDigestAfter(), node + " #" + i);
        }
    }

    @Test
    void theCliFlagMarksTheBranchAndNeedsNothingElseToReplay() {
        Env env = new Env(tmp.resolve("data"));
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int code = Main.run(new String[]{"--data", tmp.resolve("data").toString(), "branch", "T0", "--heal-earlier", "200", "--pinned"}, new java.io.PrintStream(o, true), new java.io.PrintStream(o, true), java.io.InputStream.nullInputStream());
        assertEquals(0, code, o.toString());
        assertTrue(o.toString().contains("continuation policy: Pinned"), o.toString());
        assertEquals(0, Main.run(new String[]{"--data", tmp.resolve("data").toString(), "verify", "T0.1"}, new java.io.PrintStream(o, true), new java.io.PrintStream(o, true), java.io.InputStream.nullInputStream()));
    }
}
