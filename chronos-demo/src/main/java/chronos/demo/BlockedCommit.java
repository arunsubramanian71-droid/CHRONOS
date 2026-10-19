package chronos.demo;

import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.sdk.*;

import java.util.*;

/**
 * KA-3, blocked commit: a two-phase commit whose coordinator crashes after the participants have prepared. The
 * participants cannot decide on their own, so after their decision timeout they are blocked. Without the crash, no
 * participant stays undecided. Invariant "no-blocked-participant" reads each participant's volatile.blocked.
 */
public final class BlockedCommit implements Scenario {
    private final boolean crash;

    public BlockedCommit(boolean crash) {
        this.crash = crash;
    }

    @Override
    public String name() {
        return "ka3";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("client", Client::new), new NodeSpec("coord", Coord::new),
                new NodeSpec("part1", Part::new), new NodeSpec("part2", Part::new));
    }

    @Override
    public Map<String, String> config() {
        return Map.of("decisionTimeout", "200");
    }

    @Override
    public List<ClientInput> workload() {
        return List.of(new ClientInput(10, "client", Map.of("op", "begin")));
    }

    @Override
    public FaultPlan defaultFaultPlan() {
        return crash ? FaultPlan.of(FaultRule.crash("crash-coord", "coord", 35)) : FaultPlan.empty();
    }

    @Override
    public List<Invariant> invariants() {
        return List.of(new Invariant() {
            @Override
            public String name() {
                return "no-blocked-participant";
            }

            @Override
            public Optional<String> check(WorldView v) {
                List<String> blocked = new ArrayList<>();
                for (String p : List.of("part1", "part2")) if (Boolean.TRUE.equals(v.read(p, "volatile.blocked"))) blocked.add(p);
                return blocked.isEmpty() ? Optional.empty() : Optional.of("participants blocked in PREPARED: " + blocked);
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
            ctx.send("coord", "Begin", Map.of());
        }
    }

    static final class Coord implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("txn", "IDLE");
            ctx.state().put("votes", 0L);
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            switch (m.type()) {
                case "Begin" -> {
                    ctx.state().put("txn", "PREPARING");
                    ctx.send("part1", "Prepare", Map.of());
                    ctx.send("part2", "Prepare", Map.of());
                }
                case "Vote" -> {
                    long v = ctx.state().getLong("votes", 0) + 1;
                    ctx.state().put("votes", v);
                    if (v == 2) {
                        ctx.state().put("txn", "COMMITTED");
                        ctx.state().putDurable("decision", "COMMIT");
                        ctx.send("part1", "Commit", Map.of());
                        ctx.send("part2", "Commit", Map.of());
                    }
                }
                default -> { }
            }
        }
    }

    static final class Part implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("state", "INIT");
            ctx.state().put("blocked", false);
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            switch (m.type()) {
                case "Prepare" -> {
                    ctx.state().put("state", "PREPARED");
                    ctx.state().put("timer", ctx.setTimer("decision-timeout", Long.parseLong(ctx.config("decisionTimeout"))));
                    ctx.send("coord", "Vote", Map.of("vote", "yes"));
                }
                case "Commit" -> {
                    ctx.cancelTimer(ctx.state().getString("timer", ""));
                    ctx.state().put("state", "COMMITTED");
                }
                default -> { }
            }
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            if ("PREPARED".equals(ctx.state().getString("state", ""))) {
                ctx.state().put("blocked", true);
                ctx.note("decision timeout in PREPARED: cannot decide alone, blocked");
            }
        }
    }
}
