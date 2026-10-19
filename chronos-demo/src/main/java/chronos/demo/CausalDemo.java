package chronos.demo;

import chronos.sdk.*;

import java.util.List;
import java.util.Map;

/**
 * Causal-ordering demo: A sends to B, B forwards to C, while C also does unrelated local work. It shows events that are
 * causally ordered next to events that are concurrent although their timestamps suggest an order (Section 11).
 */
public final class CausalDemo implements Scenario {
    @Override
    public String name() {
        return "causal";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("A", A::new), new NodeSpec("B", B::new), new NodeSpec("C", C::new));
    }

    @Override
    public long defaultHorizon() {
        return 1000;
    }

    static final class A implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.note("a1 local event");
            ctx.send("B", "m1", Map.of());
        }
    }

    static final class B implements Node {
        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("m1")) ctx.send("C", "m2", Map.of());
        }
    }

    static final class C implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.note("c1 local event");
            ctx.setTimer("local-work", 5);
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            ctx.note("c local work");
        }
    }
}
