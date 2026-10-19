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
        return l;
    }

    @Override
    public Scenario create(String name, Map<String, String> params) {
        if (name.equals("twopairs")) return new TwoPairs();
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
}
