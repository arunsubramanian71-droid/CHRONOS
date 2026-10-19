package chronos.demo.bad;

import chronos.sdk.*;

import java.util.List;
import java.util.Map;

/**
 * The deliberate bad citizen (Section 15): a node that violates the determinism contract by reading the system clock
 * to choose a timer delay. Acceptance demonstration AD-2 uses it to prove that the replay verifier really catches hidden
 * nondeterminism instead of merely passing everything. This is the ONLY place the banned-API scan allows such a call.
 */
public final class BadCitizen implements Scenario {
    @Override
    public String name() {
        return "bad";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("A", A::new), new NodeSpec("B", B::new));
    }

    @Override
    public long defaultHorizon() {
        return 1000;
    }

    static final class A implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.send("B", "ping", Map.of());
        }
    }

    static final class B implements Node {
        @Override
        public void onMessage(NodeContext ctx, Message m) {
            // CHRONOS-BAD-CITIZEN: wall-clock time and an identity hash code, both banned, so two runs agree only by a ~1e-6 coincidence
            long hidden = Math.floorMod(System.nanoTime() * 31 + System.identityHashCode(new Object()), 1_000_003L);
            ctx.setTimer("hidden-delay", 100 + hidden);
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            ctx.note("timer fired");
        }
    }
}
