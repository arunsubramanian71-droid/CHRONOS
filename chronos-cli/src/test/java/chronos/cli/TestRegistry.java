package chronos.cli;

import chronos.demo.DemoRegistry;
import chronos.model.FaultPlan;
import chronos.sdk.*;

import java.util.*;

/** The demo registry plus scenarios that only tests need. */
final class TestRegistry implements ScenarioRegistry {
    private final DemoRegistry demo = new DemoRegistry();

    @Override
    public List<String> names() {
        List<String> l = new ArrayList<>(demo.names());
        l.add("twopairs");
        l.add("clocky");
        l.add("chain");
        return l;
    }

    @Override
    public Scenario create(String name, Map<String, String> params) {
        if (name.equals("twopairs")) return new TwoPairs();
        if (name.equals("clocky")) return new Clocky();
        if (name.equals("chain")) return new Chain();
        return demo.create(name, params);
    }

    /** Two independent pairs of nodes (a1-a2 and b1-b2) that never talk to each other: a locality (law M8) testbed. */
    static final class TwoPairs implements Scenario {
        public String name() { return "twopairs"; }
        public long defaultHorizon() { return 600; }
        public boolean stopOnViolation() { return false; }
        public List<NodeSpec> nodes() {
            return List.of(new NodeSpec("a1", () -> new Pinger("a2")), new NodeSpec("a2", () -> new Pinger("a1")),
                    new NodeSpec("b1", () -> new Pinger("b2")), new NodeSpec("b2", () -> new Pinger("b1")));
        }
        public long latencyJitter() { return 6; }
    }

    /** a sends to b at start; b forwards to c; c keeps count. Constant latency 10: x arrives at 10, y at 20. The Pinned-policy testbed. */
    static final class Chain implements Scenario {
        public String name() { return "chain"; }
        public long defaultHorizon() { return 200; }
        public boolean stopOnViolation() { return false; }
        public List<NodeSpec> nodes() { return List.of(new NodeSpec("a", Source::new), new NodeSpec("b", Relay::new), new NodeSpec("c", Sink::new)); }
    }

    static final class Source implements Node {
        public void onStart(NodeContext c) { c.send("b", "x", Map.of()); }
    }

    static final class Relay implements Node {
        public void onMessage(NodeContext c, Message m) { c.send("c", "y", Map.of("at", c.now())); }
    }

    static final class Sink implements Node {
        public void onMessage(NodeContext c, Message m) { c.state().put("received", c.state().getLong("received", 0) + 1); }
    }

    static final class Pinger implements Node {
        private final String peer;
        Pinger(String peer) { this.peer = peer; }
        public void onStart(NodeContext c) { c.state().put("n", 0L); c.setTimer("t", 25 + c.random("t", 10)); }
        public void onTimer(NodeContext c, TimerFiring t) {
            c.send(peer, "ping", Map.of("n", c.state().getLong("n", 0)));
            c.setTimer("t", 25 + c.random("t", 10));
        }
        public void onMessage(NodeContext c, Message m) { c.state().put("n", c.state().getLong("n", 0) + 1); }
    }

    /** A node that records the time it READS on every timer firing, to show what a clock-skew fault does and does not change. */
    static final class Clocky implements Scenario {
        public String name() { return "clocky"; }
        public long defaultHorizon() { return 400; }
        public List<NodeSpec> nodes() { return List.of(new NodeSpec("a", ClockReader::new), new NodeSpec("b", ClockReader::new)); }
    }

    static final class ClockReader implements Node {
        public void onStart(NodeContext c) { c.state().put("lastRead", c.now()); c.setTimer("t", 50); }
        public void onTimer(NodeContext c, TimerFiring t) {
            c.state().put("lastRead", c.now());
            c.send(c.self().equals("a") ? "b" : "a", "ping", Map.of());
            c.setTimer("t", 50);
        }
    }
}
