package chronos.runtime;

import chronos.model.FaultPlan;
import chronos.sdk.*;

import java.util.*;

/** Hand-written fakes (D-20): small scenarios that let the runtime be tested without the demo module. */
final class Fakes {
    private Fakes() {}

    /** Ping-pong exactly as in Section 10. */
    static Scenario pingPong() {
        return new Scenario() {
            public String name() { return "fake-pingpong"; }
            public List<NodeSpec> nodes() {
                return List.of(new NodeSpec("A", () -> new Node() {
                    public void onStart(NodeContext c) { c.send("B", "ping", Map.of()); }
                }), new NodeSpec("B", () -> new Node() {
                    public void onMessage(NodeContext c, Message m) { c.send(m.from(), "pong", Map.of()); }
                }));
            }
        };
    }

    /** Two nodes sending numbered messages on timers, with jitter and random draws, so the seed matters. */
    static Scenario chatter(long horizon, FaultPlan plan) {
        return new Scenario() {
            public String name() { return "fake-chatter"; }
            public long defaultHorizon() { return horizon; }
            public long latencyJitter() { return 9; }
            public FaultPlan defaultFaultPlan() { return plan; }
            public List<NodeSpec> nodes() {
                return List.of(new NodeSpec("a", Chatter::new), new NodeSpec("b", Chatter::new));
            }
        };
    }

    static final class Chatter implements Node {
        public void onStart(NodeContext c) {
            c.state().put("got", 0L);
            c.state().putDurable("sent", c.state().getDurableLong("sent", 0));
            c.setTimer("t", 20 + c.random("timer", 10));
        }
        public void onRestart(NodeContext c) { c.setTimer("t", 20); }
        public void onTimer(NodeContext c, TimerFiring t) {
            long n = c.state().getDurableLong("sent", 0) + 1;
            c.state().putDurable("sent", n);
            c.send(c.self().equals("a") ? "b" : "a", "m", Map.of("n", n));
            c.setTimer("t", 20 + c.random("timer", 10));
        }
        public void onMessage(NodeContext c, Message m) { c.state().put("got", c.state().getLong("got", 0) + 1); }
    }

    static MemoryLog run(Scenario s, long seed, FaultPlan plan, long checkpointInterval) {
        MemoryLog log = new MemoryLog();
        RunOptions o = RunOptions.of(s).withCheckpointInterval(checkpointInterval);
        Engine e = Engine.create(s, seed, plan, o, new RecordDecisionSource(s, seed), new Recorder("T0", 0, List.of(log)));
        e.boot();
        e.runToEnd();
        return log;
    }
}
