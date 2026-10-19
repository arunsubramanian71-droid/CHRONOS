package chronos.demo;

import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.sdk.*;

import java.util.*;

/**
 * RE, the flagship example (Section 1): a tiny replicated key-value store with a client, a primary p and one or two
 * backups. p sends a heartbeat every 100 ms; a backup that hears none for 300 ms promotes itself. A partition that cuts p
 * from a backup for 400 ms therefore produces a second primary (split brain), which breaks the single-primary invariant.
 *
 * With one backup this is KA-1. With two backups and two independent partitions it is KA-4 (overdetermined: removing
 * either partition alone leaves the failure in place).
 */
public final class ReplicatedStore implements Scenario {
    private final List<String> backups;
    private final boolean partition;
    private final String name;

    public ReplicatedStore(String name, List<String> backups, boolean partition) {
        this.name = name;
        this.backups = List.copyOf(backups);
        this.partition = partition;
    }

    public static ReplicatedStore re(boolean partition) {
        return new ReplicatedStore("re", List.of("b"), partition);
    }

    public static ReplicatedStore re2(boolean partition) {
        return new ReplicatedStore("re2", List.of("b1", "b2"), partition);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<NodeSpec> nodes() {
        List<NodeSpec> l = new ArrayList<>();
        l.add(new NodeSpec("client", Client::new));
        l.add(new NodeSpec("p", Primary::new));
        for (String b : backups) l.add(new NodeSpec(b, Backup::new));
        return l;
    }

    @Override
    public Map<String, String> config() {
        return Map.of("heartbeat", "100", "watchdog", "300", "backups", String.join(",", backups));
    }

    @Override
    public List<ClientInput> workload() {
        return List.of(
                new ClientInput(50, "client", Map.of("op", "write", "key", "x", "value", 1L)),
                new ClientInput(150, "client", Map.of("op", "write", "key", "y", "value", 2L)),
                new ClientInput(250, "client", Map.of("op", "write", "key", "z", "value", 3L)),
                new ClientInput(520, "client", Map.of("op", "switch", "to", backups.get(0))),
                new ClientInput(600, "client", Map.of("op", "write", "key", "w", "value", 7L)));
    }

    @Override
    public FaultPlan defaultFaultPlan() {
        if (!partition) return FaultPlan.empty();
        List<FaultRule> rules = new ArrayList<>();
        if (backups.size() == 1) {
            rules.add(FaultRule.partition("partition-pb", 300, 400L, List.of(List.of("p"), List.of("b"))));
        } else {
            rules.add(FaultRule.partition("partition-p-b1", 300, 400L, List.of(List.of("p"), List.of("b1"))));
            rules.add(FaultRule.partition("partition-p-b2", 300, 400L, List.of(List.of("p"), List.of("b2"))));
        }
        return new FaultPlan(rules);
    }

    @Override
    public List<Invariant> invariants() {
        List<String> roleNodes = new ArrayList<>();
        roleNodes.add("p");
        roleNodes.addAll(backups);
        return List.of(new SinglePrimary(roleNodes));
    }

    @Override
    public long defaultHorizon() {
        return 1000;
    }

    static final class Client implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("target", "p");
            ctx.state().put("sent", 0L);
        }

        @Override
        public void onClientInput(NodeContext ctx, Map<String, Object> in) {
            String op = (String) in.get("op");
            if (op.equals("switch")) {
                ctx.state().put("target", in.get("to"));
            } else {
                ctx.state().put("sent", ctx.state().getLong("sent", 0) + 1);
                ctx.send(ctx.state().getString("target", "p"), "Write", Map.of("key", in.get("key"), "value", in.get("value")));
            }
        }
    }

    static final class Primary implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("role", "PRIMARY");
            ctx.state().put("hb", 0L);
            ctx.state().getMap("data");
            ctx.setTimer("heartbeat", Long.parseLong(ctx.config("heartbeat")));
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            long n = ctx.state().getLong("hb", 0) + 1;
            ctx.state().put("hb", n);
            for (String b : ctx.config("backups").split(",")) ctx.send(b, "Heartbeat", Map.of("n", n));
            ctx.setTimer("heartbeat", Long.parseLong(ctx.config("heartbeat")));
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            if (m.type().equals("Write") && "PRIMARY".equals(ctx.state().getString("role", ""))) {
                ctx.state().getMap("data").put((String) m.body().get("key"), m.body().get("value"));
                for (String b : ctx.config("backups").split(","))
                    ctx.send(b, "Replicate", Map.of("key", m.body().get("key"), "value", m.body().get("value")));
            }
        }
    }

    static final class Backup implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("role", "BACKUP");
            ctx.state().getMap("data");
            ctx.state().put("watchdog", ctx.setTimer("watchdog", Long.parseLong(ctx.config("watchdog"))));
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            switch (m.type()) {
                case "Heartbeat" -> {
                    if ("BACKUP".equals(ctx.state().getString("role", ""))) {
                        ctx.cancelTimer(ctx.state().getString("watchdog", ""));
                        ctx.state().put("watchdog", ctx.setTimer("watchdog", Long.parseLong(ctx.config("watchdog"))));
                    }
                }
                case "Replicate", "Write" -> {
                    boolean primary = "PRIMARY".equals(ctx.state().getString("role", ""));
                    if (m.type().equals("Replicate") || primary)
                        ctx.state().getMap("data").put((String) m.body().get("key"), m.body().get("value"));
                }
                default -> { }
            }
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            ctx.state().put("role", "PRIMARY");
            ctx.note("no heartbeat for " + ctx.config("watchdog") + " ms: promoting myself to primary");
        }
    }
}
