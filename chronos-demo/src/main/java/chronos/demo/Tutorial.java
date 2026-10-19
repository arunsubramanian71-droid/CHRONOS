package chronos.demo;

import chronos.sdk.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tutorial application of docs/USER_GUIDE.md: a sender ticks every 50 ms and numbers each tick; a receiver counts the ticks it
 * gets. The invariant says the receiver never falls more than one tick behind. With no faults it holds; a partition (or a dropped
 * message) breaks it, which gives the user guide something small to record, explain, branch and compare.
 */
public final class Tutorial implements Scenario {
    private static final int TICKS = 8;

    @Override
    public String name() {
        return "tutorial";
    }

    @Override
    public List<NodeSpec> nodes() {
        return List.of(new NodeSpec("sender", Sender::new), new NodeSpec("receiver", Receiver::new));
    }

    @Override
    public long defaultHorizon() {
        return 1000;
    }

    @Override
    public List<Invariant> invariants() {
        return List.of(new KeepsUp());
    }

    static final class Sender implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("sent", 0L);
            ctx.setTimer("tick", 50);
        }

        @Override
        public void onTimer(NodeContext ctx, TimerFiring t) {
            long n = ctx.state().getLong("sent", 0) + 1;
            ctx.state().put("sent", n);
            ctx.send("receiver", "tick", Map.of("n", n));
            if (n < TICKS) ctx.setTimer("tick", 50);
        }
    }

    static final class Receiver implements Node {
        @Override
        public void onStart(NodeContext ctx) {
            ctx.state().put("received", 0L);
        }

        @Override
        public void onMessage(NodeContext ctx, Message m) {
            ctx.state().put("received", ctx.state().getLong("received", 0) + 1);
        }
    }

    /** The receiver never lags the sender by more than one tick. */
    static final class KeepsUp implements Invariant {
        @Override
        public String name() {
            return "keeps-up";
        }

        @Override
        public Optional<String> check(WorldView view) {
            long sent = view.read("sender", "volatile.sent") == null ? 0 : ((Number) view.read("sender", "volatile.sent")).longValue();
            long received = view.read("receiver", "volatile.received") == null ? 0 : ((Number) view.read("receiver", "volatile.received")).longValue();
            return sent - received > 1 ? Optional.of("the receiver is " + (sent - received) + " ticks behind (sent " + sent + ", received " + received + ")") : Optional.empty();
        }
    }
}
