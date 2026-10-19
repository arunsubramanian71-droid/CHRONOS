package chronos.analysis;

import chronos.model.*;
import chronos.sdk.*;

import java.util.*;

/**
 * Node-local replay (R5, Stretch): because a node is a function of its input sequence (Section 9), one node can be re-executed
 * alone. Its recorded inputs (start, restart, each delivered message, each timer firing, each client input, each crash) are fed to
 * a fresh instance of the node, with no other node, no agenda and no fault injector, and everything it does is checked against the
 * recording: the messages it sends, the timers it sets and cancels, the numbers it draws, and the state digest after every step.
 *
 * What this checks is the node's own logic; what it cannot check is the world around it (latencies, delivery order and faults are
 * taken from the log as given). Random values are answered from the recorded draws. It is a fast single-node debugging mode:
 * if the node mis-handles an input, the first mismatch names the step, the field of difference and the recorded expectation.
 * Not supported: a node whose state was edited by a synthetic PATCH_STATE intervention (no real input sequence produces it).
 */
public final class NodeLocalReplay {
    public record Mismatch(String stepEventId, long position, String what, String expected, String actual) {}

    public record Result(String timelineId, String node, boolean supported, String note, int steps, int outputsChecked, Mismatch mismatch) {
        public boolean ok() { return supported && mismatch == null; }

        public String text() {
            StringBuilder sb = new StringBuilder("NODE-LOCAL REPLAY of " + node + " on " + timelineId + ": ");
            if (!supported) return sb.append("NOT SUPPORTED (").append(note).append(")\n").toString();
            if (mismatch == null)
                return sb.append("OK, ").append(steps).append(" steps re-executed from recorded inputs alone, ").append(outputsChecked)
                        .append(" outputs and every post-step state digest matched the recording\n").toString();
            sb.append("MISMATCH at step ").append(mismatch.stepEventId()).append(" (position ").append(mismatch.position()).append(") after ")
                    .append(steps).append(" matching steps\n");
            sb.append("  what    : ").append(mismatch.what()).append('\n');
            sb.append("  expected: ").append(mismatch.expected()).append('\n');
            sb.append("  actual  : ").append(mismatch.actual()).append('\n');
            return sb.toString();
        }
    }

    private final LogReader log;
    private final ScenarioRegistry registry;

    public NodeLocalReplay(LogReader log, ScenarioRegistry registry) {
        this.log = log;
        this.registry = registry;
    }

    private static final class Stop extends RuntimeException {
        final Mismatch m;
        Stop(Mismatch m) {
            super(null, null, false, false);
            this.m = m;
        }
    }

    public Result replay(String timelineId, String node) {
        TimelineHeader header = log.header(timelineId);
        if (header == null) throw new UnknownTimelineException(timelineId);
        Scenario scenario = registry.create(header.scenario(), header.params());
        NodeSpec spec = scenario.nodes().stream().filter(n -> n.name().equals(node)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario " + header.scenario() + " has no node " + node));
        List<Event> events = log.events(timelineId);
        Map<String, Event> byId = new HashMap<>();
        for (Event e : events) byId.put(e.eventId(), e);

        // what happened around the node that it can observe: clock skew and configuration patches, in log order
        for (Event e : events)
            if (e.kind() == EventKind.INTERVENTION_APPLIED && node.equals(e.payload().get("patchedNode")))
                return new Result(timelineId, node, false, "its state was edited by a synthetic PATCH_STATE intervention at " + e.eventId(), 0, 0, null);

        List<Event> lane = new ArrayList<>();
        for (Event e : events) if (e.nodeId().equals(node)) lane.add(e);

        Node impl = spec.factory().get();
        NodeState state = new NodeState();
        Map<String, String> config = new HashMap<>(scenario.config());
        Map<String, String> skewRuleNode = new HashMap<>();
        long[] skew = {0};
        int steps = 0;
        int[] outputs = {0};
        int envCursor = 0;
        Set<String> pendingTimers = new HashSet<>();
        Map<String, Long> randomOrdinals = new HashMap<>();

        int i = 0;
        try {
            while (i < lane.size()) {
                Event trig = lane.get(i);
                if (!trig.kind().isTrigger()) { // paused / resumed carry no handler; a lone effect without a trigger would be a corrupt log
                    if (trig.kind() == EventKind.NODE_PAUSED || trig.kind() == EventKind.NODE_RESUMED) { i++; continue; }
                    throw new Stop(new Mismatch(trig.eventId(), trig.seq(), "an effect event with no trigger before it", "a trigger", trig.kind().name()));
                }
                // bring the observable environment up to date as of this step
                for (; envCursor < events.size() && events.get(envCursor).seq() < trig.seq(); envCursor++) {
                    Event e = events.get(envCursor);
                    if (e.kind() == EventKind.FAULT_INJECTED && "CLOCK_SKEW".equals(e.payload().get("action")) && node.equals(e.payload().get("node"))) {
                        skew[0] = Canon.num(e.payload().get("offset"));
                        skewRuleNode.put((String) e.payload().get("rule"), node);
                    } else if (e.kind() == EventKind.FAULT_CLEARED && "CLOCK_SKEW".equals(e.payload().get("action")) && skewRuleNode.containsKey(e.payload().get("rule"))) {
                        skew[0] = 0;
                    } else if (e.kind() == EventKind.INTERVENTION_APPLIED && Intervention.CONFIG_PATCH.equals(e.payload().get("type"))) {
                        Map<String, Object> p = Canon.map(e.payload().get("params"));
                        config.put((String) p.get("key"), (String) p.get("value"));
                    }
                }
                // collect the effects of this step: the lane events up to the next trigger
                List<Event> effects = new ArrayList<>();
                int j = i + 1;
                while (j < lane.size() && !lane.get(j).kind().isTrigger()) {
                    Event e = lane.get(j);
                    if (e.kind() != EventKind.NODE_PAUSED && e.kind() != EventKind.NODE_RESUMED) effects.add(e);
                    j++;
                }
                Cursor cur = new Cursor(effects);
                Ctx ctx = new Ctx(node, scenario, trig, state, config, skew[0], cur, pendingTimers, randomOrdinals, outputs);
                try {
                    switch (trig.kind()) {
                        case NODE_STARTED -> impl.onStart(ctx);
                        case NODE_RESTARTED -> impl.onRestart(ctx);
                        case CLIENT_INPUT -> impl.onClientInput(ctx, Canon.map(trig.payload().get("body")));
                        case TIMER_FIRED -> {
                            String tid = (String) trig.payload().get("timerId");
                            pendingTimers.remove(tid);
                            impl.onTimer(ctx, new TimerFiring(tid, (String) trig.payload().get("name")));
                        }
                        case MESSAGE_DELIVERED -> {
                            String mid = (String) trig.payload().get("messageId");
                            Event send = byId.get(mid);
                            if (send == null)
                                throw new Stop(new Mismatch(trig.eventId(), trig.seq(), "the delivered message's send event is not in the log", mid, "(missing)"));
                            impl.onMessage(ctx, new Message(mid, send.nodeId(), (String) send.payload().get("type"), Canon.map(send.payload().get("body"))));
                        }
                        case NODE_CRASHED -> {
                            state.wipeVolatile();
                            pendingTimers.clear();
                        }
                        default -> throw new IllegalStateException("unexpected trigger " + trig.kind());
                    }
                } catch (Stop s) {
                    throw s;
                } catch (RuntimeException ex) {
                    Event next = cur.peek();
                    if (next != null && next.kind() == EventKind.NODE_FAILED && ex.getClass().getName().equals(next.payload().get("exception"))) {
                        cur.take();
                        outputs[0]++;
                        steps++;
                        i = j;
                        continue; // the recorded run failed here too; the log ends with it
                    }
                    throw new Stop(new Mismatch(trig.eventId(), trig.seq(), "the node threw an exception the recording does not have",
                            next == null ? "no further events in this step" : describe(next), ex.getClass().getName() + ": " + ex.getMessage()));
                }
                if (cur.peek() != null)
                    throw new Stop(new Mismatch(trig.eventId(), trig.seq(), "the node did not produce a recorded output", describe(cur.peek()), "(nothing: the handler returned)"));
                String digest = Canon.digest(impl.stateCodec().encode(state));
                if (trig.stateDigestAfter() != null && !trig.stateDigestAfter().equals(digest))
                    throw new Stop(new Mismatch(trig.eventId(), trig.seq(), "state digest after the step differs" + stateDiff(trig, impl, state),
                            trig.stateDigestAfter(), digest));
                steps++;
                outputs[0]++; // the state digest
                i = j;
            }
        } catch (Stop s) {
            return new Result(timelineId, node, true, null, steps, outputs[0], s.m);
        }
        return new Result(timelineId, node, true, null, steps, outputs[0], null);
    }

    private static String stateDiff(Event trig, Node impl, NodeState state) {
        if (trig.stateAfter() == null) return " (the recording stored only the digest for this step)";
        Map<String, Object> actual = impl.stateCodec().encode(state);
        List<String> diffs = new ArrayList<>();
        for (String zone : List.of("volatile", "durable")) {
            Map<String, Object> e = Canon.map(trig.stateAfter().get(zone)), a = Canon.map(actual.get(zone));
            Set<String> keys = new TreeSet<>(e.keySet());
            keys.addAll(a.keySet());
            for (String k : keys)
                if (!Canon.json(e.get(k)).equals(Canon.json(a.get(k)))) diffs.add(zone + "." + k + ": expected " + Canon.json(e.get(k)) + ", got " + Canon.json(a.get(k)));
        }
        return diffs.isEmpty() ? "" : " in " + String.join("; ", diffs);
    }

    private static String describe(Event e) {
        return e.eventId() + " " + e.kind() + " " + Canon.json(e.payload());
    }

    private static final class Cursor {
        private final List<Event> effects;
        private int next;

        Cursor(List<Event> effects) { this.effects = effects; }

        Event peek() { return next < effects.size() ? effects.get(next) : null; }

        Event take() { return effects.get(next++); }
    }

    /** The node's whole world in this mode: outputs are checked against the next recorded effect, random values come from the log. */
    private static final class Ctx implements NodeContext {
        private final String self;
        private final Scenario scenario;
        private final Event trigger;
        private final NodeState state;
        private final Map<String, String> config;
        private final long skew;
        private final Cursor cur;
        private final Set<String> pendingTimers;
        private final Map<String, Long> randomOrdinals;
        private final int[] outputs;

        Ctx(String self, Scenario scenario, Event trigger, NodeState state, Map<String, String> config, long skew, Cursor cur,
            Set<String> pendingTimers, Map<String, Long> randomOrdinals, int[] outputs) {
            this.self = self;
            this.scenario = scenario;
            this.trigger = trigger;
            this.state = state;
            this.config = config;
            this.skew = skew;
            this.cur = cur;
            this.pendingTimers = pendingTimers;
            this.randomOrdinals = randomOrdinals;
            this.outputs = outputs;
        }

        private Event expect(EventKind kind, String doing) {
            Event e = cur.peek();
            if (e == null || e.kind() != kind)
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "the node " + doing + " but the recording has a different next effect",
                        e == null ? "(no further effect in this step)" : describe(e), doing));
            return cur.take();
        }

        @Override public String self() { return self; }

        @Override public List<String> nodes() { return scenario.nodes().stream().map(NodeSpec::name).toList(); }

        @Override public long now() { return trigger.virtualTime() + skew; }

        @Override public NodeState state() { return state; }

        @Override
        public String send(String to, String type, Map<String, Object> body) {
            Map<String, Object> nbody = Canon.normalizeMap(body == null ? Map.of() : body);
            String doing = "sent " + type + " to " + to + " " + Canon.json(nbody);
            Event e = expect(EventKind.MESSAGE_SENT, doing);
            if (!to.equals(e.payload().get("to")) || !type.equals(e.payload().get("type")) || !Canon.json(nbody).equals(Canon.json(e.payload().get("body"))))
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "a sent message differs", describe(e), doing));
            outputs[0]++;
            return e.eventId();
        }

        @Override
        public String setTimer(String name, long delayMs) {
            String n = name == null ? "" : name;
            String doing = "set timer " + n + " after " + delayMs + " ms";
            Event e = expect(EventKind.TIMER_SET, doing);
            if (!n.equals(e.payload().get("name")) || trigger.virtualTime() + delayMs != Canon.num(e.payload().get("due")))
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "a timer differs", describe(e), doing + " (due " + (trigger.virtualTime() + delayMs) + ")"));
            pendingTimers.add(e.eventId());
            outputs[0]++;
            return e.eventId();
        }

        @Override
        public boolean cancelTimer(String timerId) {
            if (!pendingTimers.remove(timerId)) return false; // the engine records nothing for a cancel of a timer that is not pending
            Event e = expect(EventKind.TIMER_CANCELLED, "cancelled timer " + timerId);
            if (!timerId.equals(e.payload().get("timerId")))
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "a cancelled timer differs", describe(e), "cancelled timer " + timerId));
            outputs[0]++;
            return true;
        }

        @Override
        public long random(String stream) {
            Event e = expect(EventKind.RANDOM_DRAWN, "drew from stream " + stream);
            long ordinal = randomOrdinals.merge(stream, 1L, Long::sum) - 1;
            if (!stream.equals(e.payload().get("stream")) || ordinal != Canon.num(e.payload().get("ordinal")))
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "a random draw differs", describe(e), "draw " + ordinal + " from " + stream));
            outputs[0]++;
            return Canon.num(e.payload().get("value"));
        }

        @Override
        public String config(String key) { return config.get(key); }

        @Override
        public void note(String text) {
            String t = text == null ? "" : text;
            Event e = expect(EventKind.APP_NOTE, "wrote note '" + t + "'");
            if (!t.equals(e.payload().get("text")))
                throw new Stop(new Mismatch(trigger.eventId(), trigger.seq(), "a note differs", describe(e), t));
            outputs[0]++;
        }
    }
}
