package chronos.runtime;

import chronos.model.*;
import chronos.sdk.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Simulation Engine (C2): owns the world (agenda, scheduler, virtual clock, simulated network, timer service, node
 * lifecycle) and runs the step cycle of Section 9. It is single-threaded (D-02): the only thread that touches a world.
 *
 * The engine never decides anything by itself: latencies, random values and probabilistic faults come from the
 * {@link DecisionSource}; everything else is a pure function of the world, so the same engine serves record, replay and
 * branch modes (D-19).
 *
 * Step cycle (Section 9): take the agenda item with the smallest (due, node index, kind rank, identity); advance virtual
 * time; apply fault transformations; record the trigger and update clocks; run the handler to completion; collect effects
 * as events; digest the node state and update the last-writer map; check invariants through the recording accessor; append
 * all events of the step to the recorder as one atomic group.
 */
public final class Engine {
    /** A causal link under construction: the parent's id and logical stamp plus the kind of edge. */
    private record Link(Ref ref, EdgeKind kind) {}

    private final Scenario scenario;
    private final long seed;
    private RunOptions opts;
    private final List<NodeSlot> slots = new ArrayList<>();
    private final Map<String, NodeSlot> byName = new TreeMap<>();
    private final List<String> nodeOrder = new ArrayList<>();
    private final int envIdx;
    private final ClockService clocks;
    private final Agenda agenda = new Agenda();
    private final FaultInjector faults;
    private DecisionSource decisions;
    private Recorder recorder;

    private final Map<String, Ref> lastWriters = new TreeMap<>();   // "node|volatile.field" -> last-writer trigger event
    private final Map<String, Long> envCounters = new TreeMap<>();  // identity counters for env events (fault:<rule>, inv:<name>)
    private final Set<String> violating = new TreeSet<>();
    private Ref envLast;
    private long time;
    private long stepCount;
    private boolean booted;
    private boolean finished;
    private boolean stopRequested;
    private TimelineStatus stopStatus;
    private TimelineStatus status;
    private String firstViolationId;
    private String firstViolationInvariant;

    // ------------------------------------------------------------------------------------------------------------
    // construction
    // ------------------------------------------------------------------------------------------------------------

    private Engine(Scenario scenario, long seed, FaultInjector faults, RunOptions opts, DecisionSource decisions, Recorder recorder) {
        this.scenario = scenario;
        this.seed = seed;
        this.faults = faults;
        this.opts = opts;
        this.decisions = decisions;
        this.recorder = recorder;
        List<NodeSpec> specs = scenario.nodes();
        if (specs.isEmpty()) throw new IllegalArgumentException("a scenario needs at least one node");
        for (NodeSpec spec : specs) {
            if (byName.containsKey(spec.name())) throw new IllegalArgumentException("duplicate node name: " + spec.name());
            NodeSlot s = new NodeSlot(spec.name(), slots.size(), spec.factory().get());
            slots.add(s);
            byName.put(s.name, s);
            nodeOrder.add(s.name);
        }
        this.envIdx = slots.size();
        this.clocks = new ClockService(slots.size());
    }

    /** A fresh world for a scenario, a seed and a fault plan. Call {@link #boot()} before stepping. */
    public static Engine create(Scenario scenario, long seed, FaultPlan plan, RunOptions opts, DecisionSource decisions, Recorder recorder) {
        validatePlan(scenario, plan);
        Engine e = new Engine(scenario, seed, new FaultInjector(plan), opts, decisions, recorder);
        int i = 0;
        for (ClientInput ci : scenario.workload()) {
            NodeSlot s = e.byName.get(ci.node());
            if (s == null) throw new IllegalArgumentException("workload targets unknown node: " + ci.node());
            if (ci.at() < 0) throw new IllegalArgumentException("workload item at negative time");
            Map<String, Object> data = mapOf("node", ci.node(), "body", ci.body(), "index", (long) i);
            e.agenda.add(new AgendaItem(ci.at(), s.idx, AgendaItem.Kind.CLIENT_INPUT, String.format("ci:%06d", i), data));
            i++;
        }
        for (FaultRule r : plan.rules()) e.scheduleRule(r);
        return e;
    }

    /** Rebuilds a runnable world from a checkpoint image (FR-21). */
    public static Engine restore(Scenario scenario, WorldImage image, DecisionSource decisions, Recorder recorder, RunOptions opts) {
        Map<String, Object> d = image.data();
        FaultInjector fi = FaultInjector.restore(Canon.map(d.get("faults")));
        Engine e = new Engine(scenario, Canon.num(d.get("seed")), fi, opts, decisions, recorder);
        e.clocks.restore(Canon.map(d.get("clocks")));
        e.time = Canon.num(d.get("time"));
        e.stepCount = Canon.num(d.get("step"));
        Map<String, Object> env = Canon.map(d.get("env"));
        e.envLast = env.get("last") == null ? null : Ref.fromMap(Canon.map(env.get("last")));
        for (Map.Entry<String, Object> en : Canon.map(env.get("counters")).entrySet()) e.envCounters.put(en.getKey(), Canon.num(en.getValue()));
        List<Object> nodes = Canon.list(d.get("nodes"));
        if (nodes.size() != e.slots.size()) throw new IllegalArgumentException("checkpoint has " + nodes.size() + " nodes, scenario declares " + e.slots.size());
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> nm = Canon.map(nodes.get(i));
            NodeSlot s = e.slots.get(i);
            if (!s.name.equals(nm.get("name"))) throw new IllegalArgumentException("checkpoint node order differs from scenario");
            s.restore(nm);
            s.fieldDigests = e.fieldDigests(s);
        }
        for (Object o : Canon.list(d.get("agenda"))) e.agenda.add(AgendaItem.fromMap(Canon.map(o)));
        for (Map.Entry<String, Object> en : Canon.map(d.get("lastWriters")).entrySet()) e.lastWriters.put(en.getKey(), Ref.fromMap(Canon.map(en.getValue())));
        for (Object o : Canon.list(d.get("violating"))) e.violating.add((String) o);
        e.firstViolationId = (String) d.get("firstViolationId");
        e.firstViolationInvariant = (String) d.get("firstViolationInvariant");
        e.booted = true;
        return e;
    }

    private static void validatePlan(Scenario scenario, FaultPlan plan) {
        Set<String> names = new TreeSet<>();
        for (NodeSpec n : scenario.nodes()) names.add(n.name());
        for (FaultRule r : plan.rules()) {
            if (r.action() == FaultRule.FaultAction.CRASH || r.action() == FaultRule.FaultAction.RESTART)
                if (!names.contains(r.str("node"))) throw new IllegalArgumentException("rule " + r.id() + " names unknown node " + r.str("node"));
            if (r.action() == FaultRule.FaultAction.PARTITION)
                for (Object g : Canon.list(r.params().get("groups")))
                    for (Object n : Canon.list(g))
                        if (!names.contains((String) n)) throw new IllegalArgumentException("rule " + r.id() + " names unknown node " + n);
        }
    }

    private void scheduleRule(FaultRule r) {
        if (r.trigger() != FaultRule.TriggerType.AT_TIME) return;
        agenda.add(new AgendaItem(r.num("at"), envIdx, AgendaItem.Kind.FAULT, r.id() + "#inject", mapOf("rule", r.id(), "op", "INJECT")));
    }

    // ------------------------------------------------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------------------------------------------------

    public Scenario scenario() {
        return scenario;
    }

    public long seed() {
        return seed;
    }

    public long virtualTime() {
        return time;
    }

    public long stepCount() {
        return stepCount;
    }

    public boolean isFinished() {
        return finished;
    }

    /** Position the next recorded event will get. */
    public long nextSeq() {
        return recorder.nextSeq();
    }

    public List<String> nodeNames() {
        return Collections.unmodifiableList(nodeOrder);
    }

    public void setDecisionSource(DecisionSource ds) {
        this.decisions = ds;
    }

    public DecisionSource decisionSource() {
        return decisions;
    }

    public void setRecorder(Recorder r) {
        this.recorder = r;
    }

    public Recorder recorder() {
        return recorder;
    }

    public void setOptions(RunOptions o) {
        this.opts = o;
    }

    /** Canonical state of a node as it is now. */
    public Map<String, Object> stateOf(String node) {
        NodeSlot s = byName.get(node);
        if (s == null) throw new IllegalArgumentException("unknown node: " + node);
        return s.node.stateCodec().encode(s.state);
    }

    public Map<String, Long> faultCoverage() {
        return faults.coverage();
    }

    public RunResult result() {
        return new RunResult(status == null ? TimelineStatus.RECORDING : status, recorder.nextSeq(), time, endStateDigest(),
                firstViolationId, firstViolationInvariant, faults.coverage(), recorder.ownLogHash());
    }

    public String endStateDigest() {
        Map<String, Object> m = new TreeMap<>();
        for (NodeSlot s : slots) m.put(s.name, Canon.digest(s.node.stateCodec().encode(s.state)));
        m.put("time", time);
        return Canon.digest(m);
    }

    // ------------------------------------------------------------------------------------------------------------
    // running
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Starts every node. The NODE_STARTED events of all nodes are recorded first, in declared order, and only then the
     * effects of the start handlers, which reproduces the ping-pong recording of Section 10 exactly.
     */
    public void boot() {
        if (booted) throw new IllegalStateException("world already booted");
        booted = true;
        List<List<Event>> deferred = new ArrayList<>();
        for (NodeSlot s : slots) {
            List<Event> trigGroup = new ArrayList<>();
            List<Event> effects = new ArrayList<>();
            Event trig = emitNode(s, EventKind.NODE_STARTED, new TreeMap<>(), List.of(), null);
            trigGroup.add(trig);
            s.startRef = trig.ref();
            runHandler(s, trig.ref(), effects, ctx -> s.node.onStart(ctx));
            finishTrigger(s, trig, trigGroup, true);
            recorder.commit(trigGroup);
            deferred.add(effects);
            if (stopRequested) break;
        }
        for (List<Event> g : deferred) recorder.commit(g);
        List<Event> inv = new ArrayList<>();
        checkInvariants(inv, slots.get(0).startRef);
        recorder.commit(inv);
        afterCommit();
    }

    /** Runs one step. Returns false once the run has ended (agenda empty, horizon reached or a stop condition fired). */
    public boolean step() {
        if (!booted) throw new IllegalStateException("boot() the world first");
        if (finished) return false;
        if (stopRequested) {
            finish(stopStatus);
            return false;
        }
        AgendaItem item = agenda.peek();
        if (item == null) {
            finish(TimelineStatus.COMPLETED);
            return false;
        }
        if (item.due() > opts.horizon()) {
            finish(TimelineStatus.TIMED_OUT);
            return false;
        }
        agenda.poll();
        time = Math.max(time, item.due());
        stepCount++;
        List<Event> group = new ArrayList<>();
        Ref trigger = switch (item.kind()) {
            case DELIVERY -> doDelivery(item, group);
            case TIMER -> doTimer(item, group);
            case CLIENT_INPUT -> doClientInput(item, group);
            case FAULT -> doFault(item, group);
        };
        checkInvariants(group, trigger);
        recorder.commit(group);
        afterCommit();
        return true;
    }

    /** Runs to the end of the run and returns its result. */
    public RunResult runToEnd() {
        while (!finished) step();
        return result();
    }

    private void afterCommit() {
        if (!finished && opts.checkpointInterval() > 0 && stepCount % opts.checkpointInterval() == 0)
            recorder.checkpoint(captureImage());
    }

    private void finish(TimelineStatus st) {
        status = st;
        finished = true;
        recorder.checkpoint(captureImage());
        TimelineSummary sum = new TimelineSummary(st, recorder.nextSeq(), recorder.nextSeq() - 1, time, endStateDigest(),
                firstViolationId, firstViolationInvariant, faults.coverage(), recorder.ownLogHash());
        recorder.finish(sum);
    }

    // ------------------------------------------------------------------------------------------------------------
    // step kinds
    // ------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Ref doDelivery(AgendaItem item, List<Event> group) {
        Map<String, Object> d = item.data();
        String msgId = (String) d.get("messageId");
        String from = (String) d.get("from");
        String to = (String) d.get("to");
        long copy = d.containsKey("copy") ? Canon.num(d.get("copy")) : (Boolean.TRUE.equals(d.get("dup")) ? 1 : 0);
        boolean dup = copy > 0;
        Ref sendRef = Ref.fromMap(Canon.map(d.get("sendRef")));
        NodeSlot t = byName.get(to);
        String dropId = "drop:" + msgId + (copy == 0 ? "" : copy == 1 ? "~dup" : "~dup" + copy); // every copy of a message has its own identity

        if (t.crashed) {
            List<Link> links = envBase();
            links.add(new Link(sendRef, EdgeKind.MESSAGE));
            if (t.crashRef != null) links.add(new Link(t.crashRef, EdgeKind.FAULT));
            group.add(emitEnv(dropId, EventKind.MESSAGE_DROPPED, mapOf("messageId", msgId, "reason", "target down"), links, null));
            return null;
        }
        ActiveFault cut = faults.partitionCutting(from, to);
        if (cut != null) {
            List<Link> links = envBase();
            links.add(new Link(sendRef, EdgeKind.MESSAGE));
            links.add(new Link(cut.ref, EdgeKind.FAULT));
            group.add(emitEnv(dropId, EventKind.MESSAGE_DROPPED, mapOf("messageId", msgId, "reason", "partition " + cut.ruleId), links, null));
            return null;
        }
        List<Link> links = new ArrayList<>();
        if (t.last != null) links.add(new Link(t.last, EdgeKind.PROGRAM_ORDER));
        links.add(new Link(sendRef, EdgeKind.MESSAGE));
        for (Object o : Canon.list(d.get("faultRefs"))) links.add(new Link(Ref.fromMap(Canon.map(o)), EdgeKind.FAULT));
        // a message sent while a partition cut its link, and delivered only because the partition healed, was altered by the heal
        if (d.containsKey("sentAt"))
            for (ActiveFault healed : faults.healedPartitionsCrossed(from, to, Canon.num(d.get("sentAt")), time)) links.add(new Link(healed.clearRef, EdgeKind.FAULT));
        Event trig = emitNode(t, EventKind.MESSAGE_DELIVERED, dup ? mapOf("messageId", msgId, "duplicate", true) : mapOf("messageId", msgId),
                links, (String) d.get("origin"));
        group.add(trig);
        t.heardFrom.add(from);
        Message msg = new Message(msgId, from, (String) d.get("type"), Canon.map(d.get("body")));
        runHandler(t, trig.ref(), group, ctx -> t.node.onMessage(ctx, msg));
        finishTrigger(t, trig, group, false);
        return trig.ref();
    }

    private Ref doTimer(AgendaItem item, List<Event> group) {
        String timerId = (String) item.data().get("timerId");
        NodeSlot s = byName.get((String) item.data().get("node"));
        NodeSlot.PendingTimer pt = s.timers.remove(timerId);
        if (pt == null) throw new IllegalStateException("timer " + timerId + " is on the agenda but not pending");
        List<Link> links = new ArrayList<>();
        if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
        links.add(new Link(pt.setRef(), EdgeKind.TIMER));
        for (ActiveFault f : faults.omissionSources(s, pt.setAt(), time)) links.add(new Link(f.ref, EdgeKind.OMISSION));
        Event trig = emitNode(s, EventKind.TIMER_FIRED, mapOf("timerId", timerId, "name", pt.name()), links, null);
        group.add(trig);
        TimerFiring tf = new TimerFiring(timerId, pt.name());
        runHandler(s, trig.ref(), group, ctx -> s.node.onTimer(ctx, tf));
        finishTrigger(s, trig, group, false);
        return trig.ref();
    }

    private Ref doClientInput(AgendaItem item, List<Event> group) {
        Map<String, Object> d = item.data();
        NodeSlot s = byName.get((String) d.get("node"));
        if (s.crashed) {
            List<Link> links = envBase();
            if (s.crashRef != null) links.add(new Link(s.crashRef, EdgeKind.FAULT));
            group.add(emitEnv("drop:" + item.identity(), EventKind.MESSAGE_DROPPED, mapOf("messageId", item.identity(), "reason", "target down"), links, null));
            return null;
        }
        List<Link> links = new ArrayList<>();
        if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
        Event trig = emitNode(s, EventKind.CLIENT_INPUT, mapOf("body", Canon.map(d.get("body"))), links, null);
        group.add(trig);
        Map<String, Object> body = Canon.map(d.get("body"));
        runHandler(s, trig.ref(), group, ctx -> s.node.onClientInput(ctx, body));
        finishTrigger(s, trig, group, false);
        return trig.ref();
    }

    private Ref doFault(AgendaItem item, List<Event> group) {
        String ruleId = (String) item.data().get("rule");
        String op = (String) item.data().get("op");
        FaultRule rule = faults.rule(ruleId);
        if (rule == null) throw new IllegalStateException("fault rule " + ruleId + " is scheduled but unknown");
        if (op.equals("CLEAR")) {
            ActiveFault af = faults.activePartition(ruleId);
            if (af != null) group.add(clearPartition(af, faults.origin(ruleId + "/clear")));
            return null;
        }
        String origin = faults.origin(ruleId);
        switch (rule.action()) {
            case PARTITION -> {
                Map<String, Object> payload = mapOf("rule", ruleId, "action", "PARTITION", "groups", rule.params().get("groups"), "at", time);
                if (rule.params().containsKey("duration")) payload.put("duration", rule.params().get("duration"));
                Event fe = faultEvent(rule, null, payload);
                group.add(fe);
                List<List<String>> groups = new ArrayList<>();
                for (Object g : Canon.list(rule.params().get("groups"))) {
                    List<String> l = new ArrayList<>();
                    for (Object n : Canon.list(g)) l.add((String) n);
                    groups.add(l);
                }
                faults.activate(new ActiveFault("partition", ruleId, fe.ref(), time, null, groups, null));
                if (rule.params().containsKey("duration"))
                    agenda.add(new AgendaItem(time + rule.num("duration"), envIdx, AgendaItem.Kind.FAULT, ruleId + "#clear", mapOf("rule", ruleId, "op", "CLEAR")));
                return null;
            }
            case CRASH -> {
                NodeSlot s = byName.get(rule.str("node"));
                if (s.crashed) return null;
                Event fe = faultEvent(rule, null, mapOf("rule", ruleId, "action", "CRASH", "node", s.name, "at", time));
                group.add(fe);
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                Event crash = emitNode(s, EventKind.NODE_CRASHED, mapOf("reason", "fault " + ruleId), links, origin);
                group.add(crash);
                s.crashed = true;
                s.crashRef = crash.ref();
                s.state.wipeVolatile();
                s.timers.clear();
                agenda.removeIf(i -> i.kind() == AgendaItem.Kind.TIMER && s.name.equals(i.data().get("node")));
                faults.activate(new ActiveFault("crash", ruleId, fe.ref(), time, null, List.of(), s.name));
                finishTrigger(s, crash, group, false);
                return crash.ref();
            }
            case RESTART -> {
                NodeSlot s = byName.get(rule.str("node"));
                if (!s.crashed) return null;
                Event fe = faultEvent(rule, null, mapOf("rule", ruleId, "action", "RESTART", "node", s.name, "at", time));
                group.add(fe);
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                Event restart = emitNode(s, EventKind.NODE_RESTARTED, mapOf("reason", "fault " + ruleId), links, origin);
                group.add(restart);
                s.crashed = false;
                ActiveFault crashFault = faults.activeCrash(s.name);
                if (crashFault != null) faults.close(crashFault, time);
                runHandler(s, restart.ref(), group, ctx -> s.node.onRestart(ctx));
                finishTrigger(s, restart, group, false);
                return restart.ref();
            }
            default -> throw new IllegalStateException("rule " + ruleId + " (" + rule.action() + ") cannot be scheduled by time");
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // handler execution, effects and state
    // ------------------------------------------------------------------------------------------------------------

    private void runHandler(NodeSlot s, Ref trigger, List<Event> sink, Consumer<NodeContext> handler) {
        Ctx ctx = new Ctx(s, trigger, sink);
        try {
            handler.accept(ctx);
        } catch (DeterminismViolation dv) {
            throw dv;
        } catch (RuntimeException ex) {
            List<Link> links = new ArrayList<>();
            if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
            links.add(new Link(trigger, EdgeKind.STEP_EFFECT));
            String msg = String.valueOf(ex.getMessage());
            sink.add(emitNode(s, EventKind.NODE_FAILED, mapOf("exception", ex.getClass().getName(), "message", msg), links, null));
            stopRequested = true;
            stopStatus = TimelineStatus.ABORTED;
        } finally {
            ctx.active = false;
        }
    }

    /** Digests the node state after a step, updates the last-writer map and stamps the trigger event with its state. */
    private void finishTrigger(NodeSlot s, Event trig, List<Event> group, boolean forceState) {
        Map<String, Object> enc = s.node.stateCodec().encode(s.state);
        String sd = Canon.digest(enc);
        TreeMap<String, String> now = fieldDigests(s);
        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, String> e : now.entrySet()) if (!e.getValue().equals(s.fieldDigests.get(e.getKey()))) changed.add(e.getKey());
        for (String f : s.fieldDigests.keySet()) if (!now.containsKey(f)) changed.add(f);
        for (String f : changed) lastWriters.put(s.name + "|" + f, trig.ref());
        s.fieldDigests = now;
        int i = indexOf(group, trig.eventId());
        group.set(i, group.get(i).withState(sd, (forceState || !changed.isEmpty()) ? enc : null));
    }

    private static int indexOf(List<Event> group, String id) {
        for (int i = 0; i < group.size(); i++) if (group.get(i).eventId().equals(id)) return i;
        throw new IllegalStateException("trigger " + id + " missing from its step group");
    }

    private TreeMap<String, String> fieldDigests(NodeSlot s) {
        TreeMap<String, String> m = new TreeMap<>();
        for (String f : s.state.fieldNames()) m.put(f, Canon.digest(s.state.read(f)));
        return m;
    }

    private void checkInvariants(List<Event> group, Ref trigger) {
        for (Invariant inv : scenario.invariants()) {
            RecordingView view = new RecordingView(byName, nodeOrder);
            Optional<String> res = inv.check(view);
            if (res.isEmpty()) {
                violating.remove(inv.name());
                continue;
            }
            if (!violating.add(inv.name())) continue; // already recorded; a violation is recorded when it begins
            long n = envCounters.merge("inv:" + inv.name(), 1L, Long::sum);
            List<Link> links = envBase();
            if (trigger != null) links.add(new Link(trigger, EdgeKind.STEP_EFFECT));
            List<Object> witnessIds = new ArrayList<>();
            List<Object> fields = new ArrayList<>();
            Set<String> seen = new TreeSet<>();
            for (String r : view.reads()) {
                fields.add(r.replace('|', '.'));
                String[] nf = r.split("\\|", 2);
                Ref w = lastWriters.get(r);
                if (w == null) w = byName.get(nf[0]).startRef;
                if (w != null && seen.add(w.id())) {
                    links.add(new Link(w, EdgeKind.WITNESS));
                    witnessIds.add(w.id());
                }
            }
            Event ev = emitEnv("inv:" + inv.name() + "#" + n, EventKind.INVARIANT_VIOLATED,
                    mapOf("invariant", inv.name(), "message", res.get(), "witnesses", witnessIds, "fields", fields), links, null);
            group.add(ev);
            if (firstViolationId == null) {
                firstViolationId = ev.eventId();
                firstViolationInvariant = inv.name();
            }
            if (opts.stopOnViolation() && !stopRequested) {
                stopRequested = true;
                stopStatus = TimelineStatus.VIOLATED;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // event construction
    // ------------------------------------------------------------------------------------------------------------

    private Event build(String id, int slotIdx, String nodeName, EventKind kind, Map<String, Object> payload, List<Link> links, String origin) {
        List<Stamp> in = new ArrayList<>();
        List<Parent> ps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Link l : links) {
            if (l == null || l.ref() == null) continue;
            if (!seen.add(l.ref().id() + "|" + l.kind())) continue;
            in.add(l.ref().stamp());
            ps.add(new Parent(l.ref().id(), l.kind()));
        }
        Stamp st = clocks.stamp(slotIdx, in);
        return new Event(id, -1, "", nodeName, kind, time, st.lamport(), st.vector(), ps, payload, null, null, origin);
    }

    private Event emitNode(NodeSlot s, EventKind kind, Map<String, Object> payload, List<Link> links, String origin) {
        String id = s.name + "#" + (++s.counter);
        Event e = build(id, s.idx, s.name, kind, payload, links, origin);
        s.last = e.ref();
        return e;
    }

    private Event emitEnv(String id, EventKind kind, Map<String, Object> payload, List<Link> links, String origin) {
        Event e = build(id, envIdx, Event.ENV, kind, payload, links, origin);
        envLast = e.ref();
        return e;
    }

    private List<Link> envBase() {
        List<Link> l = new ArrayList<>();
        if (envLast != null) l.add(new Link(envLast, EdgeKind.PROGRAM_ORDER));
        return l;
    }

    /** A FAULT_INJECTED event on env for a rule firing, keyed fault:&lt;ruleId&gt;#&lt;n&gt; (D-24). */
    private Event faultEvent(FaultRule rule, Ref cause, Map<String, Object> payload) {
        long n = envCounters.merge("fault:" + rule.id(), 1L, Long::sum);
        String origin = faults.origin(rule.id());
        List<Link> links = envBase();
        if (cause != null) links.add(new Link(cause, EdgeKind.STEP_EFFECT));
        Ref iv = faults.ivRef(origin);
        if (iv != null) links.add(new Link(iv, EdgeKind.FAULT));
        Event e = emitEnv("fault:" + rule.id() + "#" + n, EventKind.FAULT_INJECTED, payload, links, origin);
        faults.fired(rule.id());
        return e;
    }

    private Event clearPartition(ActiveFault af, String origin) {
        long n = envCounters.merge("fault:" + af.ruleId, 1L, Long::sum);
        List<Link> links = envBase();
        links.add(new Link(af.ref, EdgeKind.FAULT));
        Ref iv = faults.ivRef(origin);
        if (iv != null) links.add(new Link(iv, EdgeKind.FAULT));
        Event e = emitEnv("fault:" + af.ruleId + "#" + n, EventKind.FAULT_CLEARED, mapOf("rule", af.ruleId, "action", "PARTITION", "at", time), links, origin);
        af.clearRef = e.ref();
        faults.close(af, time);
        return e;
    }

    private AgendaItem deliveryItem(String identity, long due, int targetIdx, String msgId, String from, String to, String type,
                                    Map<String, Object> body, Ref sendRef, List<Ref> faultRefs, String origin, long copy) {
        Map<String, Object> d = mapOf("messageId", msgId, "from", from, "to", to, "type", type, "body", body, "sendRef", sendRef.toMap(), "sentAt", time);
        List<Object> fr = new ArrayList<>();
        for (Ref r : faultRefs) fr.add(r.toMap());
        d.put("faultRefs", fr);
        if (origin != null) d.put("origin", origin);
        if (copy > 0) {
            d.put("dup", true);
            d.put("copy", copy);
        }
        return new AgendaItem(due, targetIdx, AgendaItem.Kind.DELIVERY, identity, d);
    }

    // ------------------------------------------------------------------------------------------------------------
    // the node context
    // ------------------------------------------------------------------------------------------------------------

    private final class Ctx implements NodeContext {
        final NodeSlot slot;
        final Ref trigger;
        final List<Event> sink;
        boolean active = true;

        Ctx(NodeSlot slot, Ref trigger, List<Event> sink) {
            this.slot = slot;
            this.trigger = trigger;
            this.sink = sink;
        }

        private void check() {
            if (!active) throw new IllegalStateException("node context used outside its handler");
        }

        private List<Link> effectLinks() {
            List<Link> l = new ArrayList<>();
            if (slot.last != null) l.add(new Link(slot.last, EdgeKind.PROGRAM_ORDER));
            l.add(new Link(trigger, EdgeKind.STEP_EFFECT));
            return l;
        }

        @Override
        public String self() {
            return slot.name;
        }

        @Override
        public List<String> nodes() {
            return Collections.unmodifiableList(nodeOrder);
        }

        @Override
        public long now() {
            return time;
        }

        @Override
        public NodeState state() {
            check();
            return slot.state;
        }

        @Override
        public String config(String key) {
            return scenario.config().get(key);
        }

        @Override
        public String send(String to, String type, Map<String, Object> body) {
            check();
            NodeSlot target = byName.get(to);
            if (target == null) throw new IllegalArgumentException("send to unknown node: " + to);
            if (type == null || type.isBlank()) throw new IllegalArgumentException("a message needs a type");
            Map<String, Object> nbody = Canon.normalizeMap(body == null ? Map.of() : body);
            String id = slot.name + "#" + (slot.counter + 1);
            long latency = decisions.latency(id, slot.name, to, type, time);
            if (latency < 0) throw new IllegalStateException("negative latency for message " + id);
            long deliverAt = time + latency;
            List<FaultRule> hits = faults.matchSend(id, slot.name, to, type, decisions);
            Event send = emitNode(slot, EventKind.MESSAGE_SENT, mapOf("to", to, "type", type, "body", nbody, "deliverAt", deliverAt), effectLinks(), null);
            sink.add(send);
            Ref sref = send.ref();

            FaultRule dropRule = null;
            for (FaultRule r : hits) if (r.action() == FaultRule.FaultAction.DROP) { dropRule = r; break; }
            if (dropRule != null) {
                Event fe = faultEvent(dropRule, sref, mapOf("rule", dropRule.id(), "action", "DROP", "messageId", id));
                sink.add(fe);
                List<Link> links = envBase();
                links.add(new Link(sref, EdgeKind.MESSAGE));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                sink.add(emitEnv("drop:" + id, EventKind.MESSAGE_DROPPED, mapOf("messageId", id, "reason", "fault " + dropRule.id()), links, faults.origin(dropRule.id())));
                return id;
            }
            long due = deliverAt;
            List<Ref> faultRefs = new ArrayList<>();
            String origin = null;
            List<Ref> dupRefs = new ArrayList<>();
            List<Long> dupCopies = new ArrayList<>();
            for (FaultRule r : hits) {
                if (r.action() == FaultRule.FaultAction.DELAY) {
                    Event fe = faultEvent(r, sref, mapOf("rule", r.id(), "action", "DELAY", "messageId", id, "by", r.num("by")));
                    sink.add(fe);
                    due += r.num("by");
                    faultRefs.add(fe.ref());
                    if (origin == null) origin = faults.origin(r.id());
                } else if (r.action() == FaultRule.FaultAction.DUPLICATE) {
                    Event fe = faultEvent(r, sref, mapOf("rule", r.id(), "action", "DUPLICATE", "messageId", id));
                    sink.add(fe);
                    List<Link> links = envBase();
                    links.add(new Link(sref, EdgeKind.MESSAGE));
                    links.add(new Link(fe.ref(), EdgeKind.FAULT));
                    long copy = envCounters.merge("dup:" + id, 1L, Long::sum); // each copy of a message has its own env identity (D-24)
                    Event de = emitEnv("dup:" + id + (copy == 1 ? "" : "~" + copy), EventKind.MESSAGE_DUPLICATED, mapOf("messageId", id, "copy", copy), links, faults.origin(r.id()));
                    sink.add(de);
                    dupRefs.add(de.ref());
                    dupCopies.add(copy);
                    if (origin == null) origin = faults.origin(r.id());
                }
            }
            agenda.add(deliveryItem(id, due, target.idx, id, slot.name, to, type, nbody, sref, faultRefs, origin, 0));
            for (int k = 0; k < dupCopies.size(); k++) {
                long c = dupCopies.get(k);
                agenda.add(deliveryItem(id + "+dup" + (c == 1 ? "" : c), due, target.idx, id, slot.name, to, type, nbody, sref, List.of(dupRefs.get(k)), origin, c));
            }
            return id;
        }

        @Override
        public String setTimer(String name, long delayMs) {
            check();
            if (delayMs < 0) throw new IllegalArgumentException("negative timer delay");
            long due = time + delayMs;
            Event ev = emitNode(slot, EventKind.TIMER_SET, mapOf("name", name == null ? "" : name, "due", due), effectLinks(), null);
            sink.add(ev);
            String id = ev.eventId();
            slot.timers.put(id, new NodeSlot.PendingTimer(id, name == null ? "" : name, due, time, ev.ref()));
            agenda.add(new AgendaItem(due, slot.idx, AgendaItem.Kind.TIMER, id, mapOf("timerId", id, "node", slot.name)));
            return id;
        }

        @Override
        public boolean cancelTimer(String timerId) {
            check();
            NodeSlot.PendingTimer pt = slot.timers.remove(timerId);
            if (pt == null) return false;
            agenda.removeIf(i -> i.kind() == AgendaItem.Kind.TIMER && i.identity().equals(timerId));
            sink.add(emitNode(slot, EventKind.TIMER_CANCELLED, mapOf("timerId", timerId), effectLinks(), null));
            return true;
        }

        @Override
        public long random(String stream) {
            check();
            long st = slot.randomStates.computeIfAbsent(stream, k -> IdentityHash.of(seed, "stream", slot.name, k));
            long computed = SeededRandom.valueAt(st);
            long ordinal = slot.randomOrdinals.merge(stream, 1L, Long::sum) - 1;
            long value = decisions.random(slot.name, stream, ordinal, computed);
            slot.randomStates.put(stream, SeededRandom.advance(st));
            sink.add(emitNode(slot, EventKind.RANDOM_DRAWN, mapOf("stream", stream, "ordinal", ordinal, "value", value), effectLinks(), null));
            return value;
        }

        @Override
        public void note(String text) {
            check();
            sink.add(emitNode(slot, EventKind.APP_NOTE, mapOf("text", text == null ? "" : text), effectLinks(), null));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // interventions (Section 19)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Applies interventions at the current step boundary (a branch point). Each one is recorded as an INTERVENTION_APPLIED
     * event on env with id iv:&lt;id&gt; (D-25). Events the intervention changes directly carry origin iv:&lt;id&gt; and a FAULT
     * edge from the intervention event. A branch with no interventions records nothing, so law M7 holds.
     */
    public void applyInterventions(List<Intervention> ivs) {
        if (!booted) throw new IllegalStateException("boot() the world first");
        if (ivs.isEmpty()) return;
        List<Event> group = new ArrayList<>();
        for (Intervention iv : ivs) {
            Event ev = emitEnv(iv.eventId(), EventKind.INTERVENTION_APPLIED,
                    mapOf("id", iv.id(), "type", iv.type(), "params", iv.params(), "at", time), envBase(), null);
            group.add(ev);
            faults.registerIntervention(iv.eventId(), ev.ref());
            applyOne(iv, ev.ref(), group);
        }
        recorder.commit(group);
    }

    private void applyOne(Intervention iv, Ref ivRef, List<Event> group) {
        String origin = iv.eventId();
        Map<String, Object> p = iv.params();
        switch (iv.type()) {
            case Intervention.REMOVE_FAULT -> {
                String ruleId = (String) p.get("ruleId");
                FaultRule r = faults.rule(ruleId);
                if (r == null) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown fault rule " + ruleId);
                if (r.action() == FaultRule.FaultAction.CRASH && faults.activeCrash(r.str("node")) != null)
                    throw new IllegalArgumentException("intervention " + iv.id() + ": the crash of " + r.str("node") + " already happened and cannot be removed");
                agenda.removeIf(i -> i.kind() == AgendaItem.Kind.FAULT && ruleId.equals(i.data().get("rule")));
                ActiveFault af = faults.activePartition(ruleId);
                if (af != null) group.add(clearPartition(af, origin));
                faults.removeRule(ruleId);
            }
            case Intervention.SHIFT_HEAL -> {
                String ruleId = (String) p.get("ruleId");
                long by = Canon.num(p.get("by"));
                FaultRule r = faults.rule(ruleId);
                if (r == null || r.action() != FaultRule.FaultAction.PARTITION)
                    throw new IllegalArgumentException("intervention " + iv.id() + ": " + ruleId + " is not a partition rule");
                ActiveFault af = faults.activePartition(ruleId);
                if (af != null) {
                    AgendaItem clr = agenda.find(i -> i.kind() == AgendaItem.Kind.FAULT && ruleId.equals(i.data().get("rule")) && "CLEAR".equals(i.data().get("op")));
                    if (clr == null) throw new IllegalArgumentException("intervention " + iv.id() + ": the partition never heals, nothing to shift");
                    agenda.removeIf(i -> i.identity().equals(clr.identity()));
                    agenda.add(clr.withDue(Math.max(time, clr.due() + by)));
                    faults.setOrigin(ruleId + "/clear", origin);
                } else {
                    AgendaItem inj = agenda.find(i -> i.kind() == AgendaItem.Kind.FAULT && ruleId.equals(i.data().get("rule")) && "INJECT".equals(i.data().get("op")));
                    if (inj == null || !r.params().containsKey("duration"))
                        throw new IllegalArgumentException("intervention " + iv.id() + ": partition " + ruleId + " has already healed or never heals");
                    long nd = r.num("duration") + by;
                    if (nd <= 0) throw new IllegalArgumentException("intervention " + iv.id() + ": the shifted partition would have no duration");
                    Map<String, Object> np = new TreeMap<>(r.params());
                    np.put("duration", nd);
                    faults.replaceRule(new FaultRule(r.id(), r.trigger(), r.action(), np, r.nonLocal()));
                    faults.setOrigin(ruleId, origin);
                    faults.setOrigin(ruleId + "/clear", origin);
                }
            }
            case Intervention.DROP_MESSAGE -> {
                String msgId = (String) p.get("messageId");
                AgendaItem it = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(msgId));
                if (it != null) {
                    agenda.removeIf(i -> i.identity().equals(msgId) && i.kind() == AgendaItem.Kind.DELIVERY);
                    List<Link> links = envBase();
                    links.add(new Link(Ref.fromMap(Canon.map(it.data().get("sendRef"))), EdgeKind.MESSAGE));
                    links.add(new Link(ivRef, EdgeKind.FAULT));
                    group.add(emitEnv("drop:" + msgId, EventKind.MESSAGE_DROPPED, mapOf("messageId", msgId, "reason", "intervention " + iv.id()), links, origin));
                } else {
                    faults.addRule(FaultRule.onMessage("iv_" + iv.id(), FaultRule.FaultAction.DROP, msgId, null), origin);
                }
            }
            case Intervention.DELAY_MESSAGE -> {
                String msgId = (String) p.get("messageId");
                long by = Canon.num(p.get("by"));
                AgendaItem it = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(msgId));
                if (it != null) {
                    agenda.removeIf(i -> i.identity().equals(msgId) && i.kind() == AgendaItem.Kind.DELIVERY);
                    Map<String, Object> nd = new TreeMap<>(it.data());
                    List<Object> fr = new ArrayList<>(Canon.list(nd.get("faultRefs")));
                    fr.add(ivRef.toMap());
                    nd.put("faultRefs", fr);
                    nd.put("origin", origin);
                    agenda.add(it.withDue(it.due() + by).withData(nd));
                } else {
                    faults.addRule(FaultRule.onMessage("iv_" + iv.id(), FaultRule.FaultAction.DELAY, msgId, by), origin);
                }
            }
            case Intervention.DUPLICATE_MESSAGE -> {
                String msgId = (String) p.get("messageId");
                AgendaItem it = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(msgId));
                if (it != null) {
                    Ref sref = Ref.fromMap(Canon.map(it.data().get("sendRef")));
                    List<Link> links = envBase();
                    links.add(new Link(sref, EdgeKind.MESSAGE));
                    links.add(new Link(ivRef, EdgeKind.FAULT));
                    long copy = envCounters.merge("dup:" + msgId, 1L, Long::sum);
                    Event de = emitEnv("dup:" + msgId + (copy == 1 ? "" : "~" + copy), EventKind.MESSAGE_DUPLICATED, mapOf("messageId", msgId, "copy", copy), links, origin);
                    group.add(de);
                    Map<String, Object> nd = new TreeMap<>(it.data());
                    nd.put("dup", true);
                    nd.put("copy", copy);
                    nd.put("faultRefs", new ArrayList<>(List.of(de.ref().toMap())));
                    nd.put("origin", origin);
                    agenda.add(new AgendaItem(it.due(), it.nodeIdx(), AgendaItem.Kind.DELIVERY, msgId + "+dup" + (copy == 1 ? "" : copy), nd));
                } else {
                    faults.addRule(FaultRule.onMessage("iv_" + iv.id(), FaultRule.FaultAction.DUPLICATE, msgId, null), origin);
                }
            }
            case Intervention.CRASH_NODE -> {
                String node = (String) p.get("node");
                long at = Canon.num(p.get("at"));
                if (!byName.containsKey(node)) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + node);
                if (at < time) throw new IllegalArgumentException("intervention " + iv.id() + ": cannot crash a node in the past");
                FaultRule r = FaultRule.crash("iv_" + iv.id(), node, at);
                faults.addRule(r, origin);
                scheduleRule(r);
            }
            default -> throw new IllegalArgumentException("unknown intervention type: " + iv.type());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // checkpoints (Section 14)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A complete, runnable image of the world at a step boundary: node states, pending messages and timers (the agenda),
     * every node's clocks, per-node event counters, random stream positions, virtual time, active partitions and the
     * progress of fault rules (including per-sender message ordinals), the last-writer map and the env clock values.
     */
    public WorldImage captureImage() {
        Map<String, Object> d = new TreeMap<>();
        d.put("scenario", scenario.name());
        d.put("seed", seed);
        d.put("time", time);
        d.put("step", stepCount);
        d.put("clocks", clocks.snapshot());
        Map<String, Object> env = new TreeMap<>();
        env.put("last", envLast == null ? null : envLast.toMap());
        env.put("counters", envCounters);
        d.put("env", env);
        List<Object> nodes = new ArrayList<>();
        for (NodeSlot s : slots) nodes.add(s.toMap());
        d.put("nodes", nodes);
        List<Object> ag = new ArrayList<>();
        for (AgendaItem i : agenda.sorted()) ag.add(i.toMap());
        d.put("agenda", ag);
        d.put("faults", faults.toMap());
        Map<String, Object> lw = new TreeMap<>();
        for (Map.Entry<String, Ref> e : lastWriters.entrySet()) lw.put(e.getKey(), e.getValue().toMap());
        d.put("lastWriters", lw);
        d.put("violating", new ArrayList<>(violating));
        d.put("firstViolationId", firstViolationId);
        d.put("firstViolationInvariant", firstViolationInvariant);
        return new WorldImage(recorder.timelineId(), recorder.nextSeq() - 1, d);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Small helper: a canonical-friendly mutable map from alternating keys and values. */
    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
