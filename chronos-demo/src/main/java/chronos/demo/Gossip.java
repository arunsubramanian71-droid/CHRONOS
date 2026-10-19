package chronos.demo;

import chronos.sdk.*;

import java.util.*;

/**
 * Synthetic gossip scenario for benchmarks (Section 26): n nodes exchange randomly chosen messages, so the number of
 * events and nodes can be scaled freely. Peers are chosen with the node's own seeded random stream.
 */
public final class Gossip implements Scenario {
    private final int n;
    private final long horizon;

    public Gossip(int n, long horizon) {
        if (n < 2) throw new IllegalArgumentException("gossip needs at least 2 nodes");
        this.n = n;
        this.horizon = horizon;
    }

    @Override
    public String name() {
        return "gossip";
    }

    @Override
    public List<NodeSpec> nodes() {
        List<NodeSpec> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new NodeSpec("g" + i, G::new));
        return l;
    }

    @Override
    public Map<String, String> config() {
        return Map.of("n", Integer.toString(n));
    }

    @Override
    public long defaultHorizon() {
        return horizon;
    }

    @Override
    public long latencyJitter() {
        return 7;
    }

    static final class G implements Node {
        private static String peer(NodeContext ctx) {
            int n = Integer.parseInt(ctx.config("n"));
            String me = ctx.self();
            while (true) {
                String p = "g" + ctx.random("peer", n);
                if (!p.equals(me)) return p;
            }
        }

        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("seen", 0L);
            ctx.setTimer("tick", 10 + ctx.random("timer", 10));
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            ctx.send(peer(ctx), "Gossip", Map.of("hops", 0L));
            ctx.setTimer("tick", 10 + ctx.random("timer", 10));
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            ctx.state().put("seen", ctx.state().getLong("seen", 0) + 1);
            long hops = ((Number) m.body().get("hops")).longValue();
            if (hops < 2) ctx.send(peer(ctx), "Gossip", Map.of("hops", hops + 1));
        }
    }
}
