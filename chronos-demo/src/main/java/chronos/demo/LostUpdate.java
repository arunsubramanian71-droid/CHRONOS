package chronos.demo;

import chronos.model.Canon;
import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.sdk.*;

import java.util.*;

/**
 * KA-2, lost update: p replicates each write to b, which applies replicates in arrival order (no sequence numbers).
 * The first replicate is delayed past its successor, so b ends with the older value while p has the newer one.
 * Invariant "replicas-converge": when every replicate has been acknowledged, both copies must be equal.
 */
public final class LostUpdate implements Scenario {
    private final boolean withDelay;

    public LostUpdate(boolean withDelay) {
        this.withDelay = withDelay;
    }

    @Override
    public String name() {
        return "ka2";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("client", Client::new), new NodeSpec("p", P::new), new NodeSpec("b", B::new));
    }

    @Override
    public List<ClientInput> workload() {
        return List.of(new ClientInput(10, "client", Map.of("key", "x", "value", 1L)),
                new ClientInput(20, "client", Map.of("key", "x", "value", 2L)));
    }

    @Override
    public FaultPlan defaultFaultPlan() {
        return withDelay ? FaultPlan.of(FaultRule.nth("delay-first-replicate", FaultRule.FaultAction.DELAY, "p", "b", "Replicate", 1, 250L))
                : FaultPlan.empty();
    }

    @Override
    public List<Invariant> invariants() {
        return List.of(new Invariant() {
            @Override
            public String name() {
                return "replicas-converge";
            }

            @Override
            public Optional<String> check(WorldView v) {
                Object out = v.read("p", "volatile.outstanding");
                if (out == null || ((Number) out).longValue() != 0) return Optional.empty();
                String pd = Canon.json(v.read("p", "volatile.data"));
                String bd = Canon.json(v.read("b", "volatile.data"));
                return pd.equals(bd) ? Optional.empty() : Optional.of("replicas diverged: p=" + pd + " b=" + bd);
            }
        });
    }

    @Override
    public long defaultHorizon() {
        return 1000;
    }

    static final class Client implements Node {
        @Override
        public void onClientInput(NodeContext ctx, Map<String, Object> in) {
            ctx.send("p", "Write", Map.of("key", in.get("key"), "value", in.get("value")));
        }
    }

    static final class P implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("outstanding", 0L);
            ctx.state().getMap("data");
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("Write")) {
                ctx.state().getMap("data").put((String) m.body().get("key"), m.body().get("value"));
                ctx.state().put("outstanding", ctx.state().getLong("outstanding", 0) + 1);
                ctx.send("b", "Replicate", Map.of("key", m.body().get("key"), "value", m.body().get("value")));
            } else if (m.type().equals("Ack")) {
                ctx.state().put("outstanding", ctx.state().getLong("outstanding", 0) - 1);
            }
        }
    }

    static final class B implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().getMap("data");
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("Replicate")) {
                ctx.state().getMap("data").put((String) m.body().get("key"), m.body().get("value"));
                ctx.send("p", "Ack", Map.of());
            }
        }
    }
}
