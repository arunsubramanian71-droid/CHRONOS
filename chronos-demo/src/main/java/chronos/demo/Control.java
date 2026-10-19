package chronos.demo;

import chronos.sdk.*;

import java.util.*;

/**
 * KA-0, the control: a correct protocol that must never violate its invariant under any fault plan, so CHRONOS cannot be
 * accused of hallucinating bugs. src numbers its updates (counter in its durable zone) and dst applies an update only if it
 * is the next one in order, which makes it immune to drops, delays, duplicates, reordering, partitions and crashes.
 * Invariant "dst-never-ahead": dst.applied &lt;= src.sent.
 */
public final class Control implements Scenario {
    @Override
    public String name() {
        return "control";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("src", Src::new), new NodeSpec("dst", Dst::new));
    }

    @Override
    public long defaultHorizon() {
        return 2000;
    }

    @Override
    public long latencyJitter() {
        return 5;
    }

    @Override
    public List<Invariant> invariants() {
        return List.of(new Invariant() {
            @Override
            public String name() {
                return "dst-never-ahead";
            }

            @Override
            public Optional<String> check(WorldView v) {
                Object sent = v.read("src", "durable.sent");
                Object applied = v.read("dst", "volatile.applied");
                long s = sent == null ? 0 : ((Number) sent).longValue();
                long a = applied == null ? 0 : ((Number) applied).longValue();
                return a <= s ? Optional.empty() : Optional.of("dst applied " + a + " but src only sent " + s);
            }
        });
    }

    static final class Src implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().putDurable("sent", ctx.state().getDurableLong("sent", 0));
            ctx.setTimer("tick", 50);
        }

        @Override
        public void onRestart(NodeContext ctx) {
            ctx.setTimer("tick", 50);
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            long n = ctx.state().getDurableLong("sent", 0) + 1;
            ctx.state().putDurable("sent", n);
            ctx.send("dst", "Update", Map.of("seq", n));
            ctx.setTimer("tick", 50);
        }
    }

    static final class Dst implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("applied", 0L);
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            long seq = ((Number) m.body().get("seq")).longValue();
            if (seq == ctx.state().getLong("applied", 0) + 1) ctx.state().put("applied", seq);
        }
    }
}
