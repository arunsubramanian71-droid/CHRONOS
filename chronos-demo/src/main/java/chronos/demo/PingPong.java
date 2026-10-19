package chronos.demo;

import chronos.sdk.*;

import java.util.List;
import java.util.Map;

/**
 * Ping-pong (Section 10): nodes A and B, latency 10 ms. A sends ping to B, B replies pong. The full recording is six
 * events (golden-file tested). It is the tiny example for clocks and events.
 */
public final class PingPong implements Scenario {
    @Override
    public String name() {
        return "pingpong";
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
            ctx.state().put("pongs", 0L);
            ctx.send("B", "ping", Map.of());
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("pong")) ctx.state().put("pongs", ctx.state().getLong("pongs", 0) + 1);
        }
    }

    static final class B implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("pings", 0L);
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("ping")) {
                ctx.state().put("pings", ctx.state().getLong("pings", 0) + 1);
                ctx.send(m.from(), "pong", Map.of());
            }
        }
    }
}
