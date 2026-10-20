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
    private final Map<String, String> configOverrides = new TreeMap<>();   // CONFIG_PATCH interventions
    private final Map<String, Ref> configRefs = new TreeMap<>();            // key -> the intervention that patched it: a handler that reads it is influenced by that intervention
    private final Map<String, Ref> reseedRefs = new TreeMap<>();            // "node|stream" -> the intervention that reseeded it
    private Ref envLast;
    private long time;
    private long stepCount;
    // The virtual time and step count of the last event built. A step that records nothing (a fault that finds nothing to do, a held item)
    // advances `time` and `stepCount` but leaves no trace in the log, so a world is identified by these two values, not by the live ones:
    // restoring a checkpoint and replaying the prefix from the start must give the same world at a boundary.
    private long lastEventTime, lastEventStep;
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
        faults.literalPartitionScope("literal".equals(scenario.config().get("chronos.omission"))); // DEV-03 study switch
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
        e.time = e.lastEventTime = Canon.num(d.get("time"));
        e.stepCount = e.lastEventStep = Canon.num(d.get("step"));
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
        if (d.containsKey("config")) for (Map.Entry<String, Object> en : Canon.map(d.get("config")).entrySet()) e.configOverrides.put(en.getKey(), (String) en.getValue());
        if (d.containsKey("configRefs")) for (Map.Entry<String, Object> en : Canon.map(d.get("configRefs")).entrySet()) e.configRefs.put(en.getKey(), Ref.fromMap(Canon.map(en.getValue())));
        if (d.containsKey("reseedRefs")) for (Map.Entry<String, Object> en : Canon.map(d.get("reseedRefs")).entrySet()) e.reseedRefs.put(en.getKey(), Ref.fromMap(Canon.map(en.getValue())));
        e.booted = true;
        return e;
    }

    private static void validatePlan(Scenario scenario, FaultPlan plan) {
        Set<String> names = new TreeSet<>();
        for (NodeSpec n : scenario.nodes()) names.add(n.name());
        for (FaultRule r : plan.rules()) {
            if (r.params().containsKey("watchNode") && !names.contains(r.str("watchNode"))) throw new IllegalArgumentException("rule " + r.id() + " watches unknown node " + r.str("watchNode"));
            if (r.params().containsKey("node") && !names.contains(r.str("node"))) throw new IllegalArgumentException("rule " + r.id() + " names unknown node " + r.str("node"));
            if (r.action() == FaultRule.FaultAction.SLOW_LINK)
                for (String k : List.of("sender", "receiver"))
                    if (!"*".equals(r.str(k)) && !names.contains(r.str(k))) throw new IllegalArgumentException("rule " + r.id() + " names unknown node " + r.str(k));
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

    /**
     * A verification replay that resumes from a checkpoint must stop where the original run stopped (an invariant violation or a
     * node failure that ended it). Branching deliberately does not call this: going live from the end of a stopped timeline continues.
     */
    public void resumeStopState(WorldImage image) {
        // a full verification must end exactly where the original run ended, including trailing steps that recorded nothing: take the
        // live clock and step count back from the image (branching deliberately does not: it uses the image's normalized values)
        if (image.data().containsKey("liveTime")) {
            time = Canon.num(image.data().get("liveTime"));
            stepCount = Canon.num(image.data().get("liveStep"));
        }
        Object st = image.data().get("stopStatus");
        if (st != null) {
            stopRequested = true;
            stopStatus = TimelineStatus.valueOf((String) st);
        }
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

    /** The messages in flight right now, as the engine's agenda holds them: message id -> number of copies pending (for comparing with log reconstruction, FR-20). */
    public Map<String, Integer> liveInflight() {
        Map<String, Integer> m = new TreeMap<>();
        for (AgendaItem i : agenda.sorted()) if (i.kind() == AgendaItem.Kind.DELIVERY) m.merge((String) i.data().get("messageId"), 1, Integer::sum);
        return m;
    }

    /** The timers pending right now: "timerId|node|name|due". */
    public List<String> liveTimers() {
        List<String> out = new ArrayList<>();
        for (NodeSlot s : slots) for (NodeSlot.PendingTimer t : s.timers.values()) out.add(t.id() + "|" + s.name + "|" + t.name() + "|" + t.due());
        Collections.sort(out);
        return out;
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
            scanAfterEvents(trigGroup);
            scanAfterEvents(effects);
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
        NodeSlot held = pausedTarget(item);
        if (held != null) { // a frozen node does not run: its item is held until it resumes (no event, no time passes)
            agenda.add(item.withDue(Math.max(item.due(), held.resumeAt)));
            return true;
        }
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
        scanAfterEvents(group);
        recorder.commit(group);
        // A step that recorded nothing leaves the log where it was, so a checkpoint taken after it would claim the previous boundary
        // while its agenda lacks the item that step consumed (a silent restart rule, say, that still decides when the run ends).
        if (!group.isEmpty()) afterCommit();
        return true;
    }

    /** The paused node an item would run on, or null (fault actions are never held). */
    private NodeSlot pausedTarget(AgendaItem item) {
        String n = switch (item.kind()) {
            case DELIVERY -> (String) item.data().get("to");
            case TIMER, CLIENT_INPUT -> (String) item.data().get("node");
            case FAULT -> null;
        };
        NodeSlot s = n == null ? null : byName.get(n);
        return s != null && s.paused ? s : null;
    }

    /** AFTER_EVENT triggers: a rule fires immediately after the event it names has been recorded. */
    private void scanAfterEvents(List<Event> group) {
        for (Event e : group) {
            faults.ghostTrigger(e.eventId(), time);
            for (FaultRule r : faults.afterEvent(e.eventId())) {
                faults.markTriggered(r.id());
                agenda.add(new AgendaItem(time, envIdx, AgendaItem.Kind.FAULT, r.id() + "#inject", mapOf("rule", r.id(), "op", "INJECT", "cause", e.ref().toMap())));
            }
        }
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
            ActiveFault crashFault = faults.activeCrash(t.name);
            Ref removedRestart = crashFault == null ? null : faults.restartRemovedRef(t.name, crashFault.start, time);
            if (removedRestart != null) links.add(new Link(removedRestart, EdgeKind.FAULT)); // dropped only because an intervention removed the restart
            group.add(emitEnv(dropId, EventKind.MESSAGE_DROPPED, mapOf("messageId", msgId, "reason", "target down"), links, null));
            return null;
        }
        ActiveFault cut = faults.partitionCutting(from, to);
        if (cut != null) {
            List<Link> links = envBase();
            links.add(new Link(sendRef, EdgeKind.MESSAGE));
            links.add(new Link(cut.ref, EdgeKind.FAULT));
            String dropOrigin = null;
            if (cut.extender != null && cut.extendedFrom != null && time >= cut.extendedFrom) { // dropped only because an intervention lengthened the partition
                links.add(new Link(cut.extender, EdgeKind.FAULT));
                dropOrigin = faults.origin(cut.ruleId + "/clear");
            }
            group.add(emitEnv(dropId, EventKind.MESSAGE_DROPPED, mapOf("messageId", msgId, "reason", "partition " + cut.ruleId), links, dropOrigin));
            cut.lastDropTo.put(to, time); // a message to this node was silenced by the partition: evidence for an OMISSION edge on its timers (DEV-03)
            return null;
        }
        List<Link> links = new ArrayList<>();
        if (t.last != null) links.add(new Link(t.last, EdgeKind.PROGRAM_ORDER));
        links.add(new Link(sendRef, EdgeKind.MESSAGE));
        for (Ref r : faults.ghostPartitionRefs(from, to, time)) links.add(new Link(r, EdgeKind.FAULT)); // delivered only because an intervention removed the partition
        for (Ref r : faults.shortenedPartitionRefs(from, to, time)) links.add(new Link(r, EdgeKind.FAULT)); // delivered only because an intervention healed the partition earlier
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
        for (Ref r : faults.ghostOmissionRefs(s, pt.setAt(), time)) links.add(new Link(r, EdgeKind.FAULT)); // the OMISSION edge of a fault an intervention removed
        for (Ref r : faults.windowAlterationRefs(s, pt.setAt(), time)) if (links.stream().noneMatch(l -> l.ref().equals(r) && l.kind() == EdgeKind.FAULT)) links.add(new Link(r, EdgeKind.FAULT)); // a fault window an intervention moved
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
        if (d.get("cause") != null) links.add(new Link(Ref.fromMap(Canon.map(d.get("cause"))), EdgeKind.FAULT)); // an input an intervention added
        Event trig = emitNode(s, EventKind.CLIENT_INPUT, mapOf("body", Canon.map(d.get("body"))), links, (String) d.get("origin"));
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
        Ref cause = item.data().get("cause") == null ? null : Ref.fromMap(Canon.map(item.data().get("cause")));
        if (op.equals("CLEAR")) return doClear(rule, group);
        String origin = faults.origin(ruleId);
        switch (rule.action()) {
            case PARTITION -> {
                Map<String, Object> payload = mapOf("rule", ruleId, "action", "PARTITION", "groups", rule.params().get("groups"), "at", time);
                if (rule.params().containsKey("duration")) payload.put("duration", rule.params().get("duration"));
                Event fe = faultEvent(rule, cause, payload);
                group.add(fe);
                List<List<String>> groups = new ArrayList<>();
                for (Object g : Canon.list(rule.params().get("groups"))) {
                    List<String> l = new ArrayList<>();
                    for (Object n : Canon.list(g)) l.add((String) n);
                    groups.add(l);
                }
                ActiveFault f = new ActiveFault("partition", ruleId, fe.ref(), time, null, groups, null);
                long by = faults.shift(ruleId);
                if (by > 0 && origin != null && faults.ivRef(origin) != null && rule.params().containsKey("duration")) {
                    f.extendedFrom = time + rule.num("duration") - by; // the heal an intervention moved later
                    f.extender = faults.ivRef(origin);
                }
                if (by != 0 && origin != null && faults.ivRef(origin) != null && rule.params().containsKey("duration")) {
                    f.alterer = faults.ivRef(origin);
                    f.origEnd = time + rule.num("duration") - by;
                }
                if (by < 0 && origin != null && faults.ivRef(origin) != null && rule.params().containsKey("duration")) {
                    f.shortenedFrom = time + rule.num("duration") - by; // the heal an intervention moved earlier: the original run kept dropping until then
                    f.shortener = faults.ivRef(origin);
                }
                faults.activate(f);
                scheduleClear(rule, envIdx);
                return null;
            }
            case CRASH -> {
                NodeSlot s = byName.get(rule.str("node"));
                if (s.crashed) return null;
                Event fe = faultEvent(rule, cause, mapOf("rule", ruleId, "action", "CRASH", "node", s.name, "at", time));
                group.add(fe);
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                Event crash = emitNode(s, EventKind.NODE_CRASHED, mapOf("reason", "fault " + ruleId), links, origin);
                group.add(crash);
                s.crashed = true;
                s.crashRef = crash.ref();
                if (s.paused) { // a crash ends a pause
                    s.paused = false;
                    ActiveFault pf = faults.activePause(s.name);
                    if (pf != null) faults.close(pf, time);
                }
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
                Event fe = faultEvent(rule, cause, mapOf("rule", ruleId, "action", "RESTART", "node", s.name, "at", time));
                group.add(fe);
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                Event restart = emitNode(s, EventKind.NODE_RESTARTED, mapOf("reason", "fault " + ruleId), links, origin);
                group.add(restart);
                s.crashed = false;
                ActiveFault crashFault = faults.activeCrash(s.name);
                if (crashFault != null && origin != null && faults.ivRef(origin) != null) { // a restart an intervention added ends the crash earlier than the original run did
                    crashFault.alterer = faults.ivRef(origin);
                    crashFault.origEnd = faults.nextRestartAfter(s.name, time, ruleId);
                }
                if (crashFault != null) faults.close(crashFault, time);
                runHandler(s, restart.ref(), group, ctx -> s.node.onRestart(ctx));
                finishTrigger(s, restart, group, false);
                return restart.ref();
            }
            case PAUSE -> {
                NodeSlot s = byName.get(rule.str("node"));
                if (s.crashed || s.paused) return null;
                long dur = rule.num("duration");
                Event fe = faultEvent(rule, cause, mapOf("rule", ruleId, "action", "PAUSE", "node", s.name, "at", time, "duration", dur));
                group.add(fe);
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(fe.ref(), EdgeKind.FAULT));
                Event paused = emitNode(s, EventKind.NODE_PAUSED, mapOf("duration", dur), links, origin);
                group.add(paused);
                s.paused = true;
                s.resumeAt = time + dur;
                faults.activate(new ActiveFault("pause", ruleId, fe.ref(), time, null, List.of(), s.name));
                scheduleClear(rule, s.idx); // ordered with the node's own items so that the resume precedes the items it held
                finishTrigger(s, paused, group, false);
                return paused.ref();
            }
            case CLOCK_SKEW -> {
                NodeSlot s = byName.get(rule.str("node"));
                long offset = rule.num("offset");
                Event fe = faultEvent(rule, cause, mapOf("rule", ruleId, "action", "CLOCK_SKEW", "node", s.name, "offset", offset, "at", time));
                group.add(fe);
                s.skew = offset; // only the time the node READS changes; Lamport and vector values count causal steps, not time
                faults.activate(new ActiveFault("skew", ruleId, fe.ref(), time, null, List.of(), s.name));
                scheduleClear(rule, envIdx);
                return null;
            }
            case SLOW_LINK -> {
                Event fe = faultEvent(rule, cause, mapOf("rule", ruleId, "action", "SLOW_LINK", "sender", rule.str("sender"), "receiver", rule.str("receiver"), "factor", rule.num("factor"), "at", time));
                group.add(fe);
                ActiveFault f = new ActiveFault("slowlink", ruleId, fe.ref(), time, null, List.of(), null);
                f.extra.put("sender", rule.str("sender"));
                f.extra.put("receiver", rule.str("receiver"));
                f.extra.put("factor", rule.num("factor"));
                faults.activate(f);
                scheduleClear(rule, envIdx);
                return null;
            }
            default -> throw new IllegalStateException("rule " + ruleId + " (" + rule.action() + ") cannot be scheduled by time or event");
        }
    }

    private void scheduleClear(FaultRule rule, int nodeIdx) {
        if (rule.params().containsKey("duration"))
            agenda.add(new AgendaItem(time + rule.num("duration"), nodeIdx, AgendaItem.Kind.FAULT, rule.id() + "#clear", mapOf("rule", rule.id(), "op", "CLEAR")));
    }

    /** The end of a timed fault: heal a partition, resume a paused node, restore a clock, restore a link. */
    private Ref doClear(FaultRule rule, List<Event> group) {
        ActiveFault af = faults.activeByRule(rule.id());
        if (af == null) return null;
        String origin = faults.origin(rule.id() + "/clear");
        switch (af.kind) {
            case "partition" -> {
                group.add(clearFault(af, origin, "PARTITION"));
                return null;
            }
            case "skew" -> {
                byName.get(af.node).skew = 0;
                group.add(clearFault(af, origin, "CLOCK_SKEW"));
                return null;
            }
            case "slowlink" -> {
                group.add(clearFault(af, origin, "SLOW_LINK"));
                return null;
            }
            case "pause" -> {
                NodeSlot s = byName.get(af.node);
                Event ce = clearFault(af, origin, "PAUSE");
                group.add(ce);
                if (!s.paused) return null;
                s.paused = false;
                List<Link> links = new ArrayList<>();
                if (s.last != null) links.add(new Link(s.last, EdgeKind.PROGRAM_ORDER));
                links.add(new Link(ce.ref(), EdgeKind.FAULT));
                Event resumed = emitNode(s, EventKind.NODE_RESUMED, mapOf("reason", "fault " + rule.id()), links, origin);
                group.add(resumed);
                finishTrigger(s, resumed, group, false);
                return resumed.ref();
            }
            default -> {
                return null;
            }
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
        for (FaultRule r : faults.onState(s.name)) { // ON_STATE triggers: the first time the watched field equals the watched value
            Object v = s.state.read(r.str("watchField"));
            if (v != null && Canon.json(v).equals(Canon.json(r.params().get("watchValue")))) {
                faults.markTriggered(r.id());
                agenda.add(new AgendaItem(time, envIdx, AgendaItem.Kind.FAULT, r.id() + "#inject", mapOf("rule", r.id(), "op", "INJECT", "cause", trig.ref().toMap())));
            }
        }
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
        lastEventTime = time;
        lastEventStep = stepCount;
        return new Event(id, -1, "", nodeName, kind, time, st.lamport(), st.vector(), ps, payload, null, null, origin);
    }

    private Event emitNode(NodeSlot s, EventKind kind, Map<String, Object> payload, List<Link> links, String origin) {
        String id = s.name + "#" + (++s.counter);
        Ref mark = faults.takeLaneMark(s.name);
        if (mark != null) { // an intervention removed an event of this node, so this one takes its place in the lane: it is the intervention's consequence
            links = new ArrayList<>(links);
            links.add(new Link(mark, EdgeKind.FAULT));
        }
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

    private Event clearFault(ActiveFault af, String origin, String action) {
        long n = envCounters.merge("fault:" + af.ruleId, 1L, Long::sum);
        List<Link> links = envBase();
        links.add(new Link(af.ref, EdgeKind.FAULT));
        Ref iv = faults.ivRef(origin);
        if (iv != null) links.add(new Link(iv, EdgeKind.FAULT));
        Event e = emitEnv("fault:" + af.ruleId + "#" + n, EventKind.FAULT_CLEARED, mapOf("rule", af.ruleId, "action", action, "at", time), links, origin);
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

        private final List<Ref> influences = new ArrayList<>(); // interventions whose patched configuration this handler has read

        private List<Link> effectLinks() {
            List<Link> l = new ArrayList<>();
            if (slot.last != null) l.add(new Link(slot.last, EdgeKind.PROGRAM_ORDER));
            l.add(new Link(trigger, EdgeKind.STEP_EFFECT));
            for (Ref r : influences) l.add(new Link(r, EdgeKind.FAULT)); // what the handler does after reading a patched value is that intervention's doing
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
            return time + slot.skew;
        }

        @Override
        public NodeState state() {
            check();
            return slot.state;
        }

        @Override
        public String config(String key) {
            String o = configOverrides.get(key);
            if (o != null && configRefs.containsKey(key) && !influences.contains(configRefs.get(key))) influences.add(configRefs.get(key));
            return o != null ? o : scenario.config().get(key);
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
            List<FaultRule> hits = faults.matchSend(id, slot.name, to, type, decisions, seed, time);
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
            List<Ref> faultRefs = new ArrayList<>(faults.takeGhostRefs()); // an intervention removed a rule that would have changed this message
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
            for (ActiveFault sl : faults.slowLinks(slot.name, to)) { // a slow link makes every delivery on it take factor times as long
                due += latency * (Canon.num(sl.extra.get("factor")) - 1);
                faultRefs.add(sl.ref);
                if (origin == null) origin = faults.origin(sl.ruleId);
            }
            List<String[]> swaps = faults.takeReorders(slot.name, to, type);
            for (FaultRule r : hits) if (r.action() == FaultRule.FaultAction.REORDER) faults.armReorder(r.id(), id); // the n-th message waits for its successor
            for (String[] ro : swaps) {
                AgendaItem heldMsg = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(ro[1]));
                if (heldMsg == null || heldMsg.due() > due) continue; // already delivered, dropped or already after this one
                FaultRule r = faults.rule(ro[0]);
                Event fe = faultEvent(r, sref, mapOf("rule", r.id(), "action", "REORDER", "messageId", ro[1], "swappedWith", id));
                sink.add(fe);
                Map<String, Object> nd = new TreeMap<>(heldMsg.data());
                List<Object> fr = new ArrayList<>(Canon.list(nd.get("faultRefs")));
                fr.add(fe.ref().toMap());
                nd.put("faultRefs", fr);
                if (faults.origin(r.id()) != null) nd.put("origin", faults.origin(r.id()));
                agenda.removeIf(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(ro[1]));
                agenda.add(heldMsg.withDue(due + 1).withData(nd));
            }
            Ref originRef = origin == null ? null : faults.ivRef(origin);
            if (originRef != null && !faultRefs.contains(originRef)) faultRefs.add(originRef); // an event marked with an intervention's origin is that intervention's doing: link it
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
            List<Link> drawLinks = effectLinks();
            Ref reseeded = reseedRefs.get(slot.name + "|" + stream);
            if (reseeded != null && !influences.contains(reseeded)) drawLinks.add(new Link(reseeded, EdgeKind.FAULT)); // a draw from a reseeded stream is the intervention's doing
            sink.add(emitNode(slot, EventKind.RANDOM_DRAWN, mapOf("stream", stream, "ordinal", ordinal, "value", value), drawLinks, null));
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
            Map<String, Object> payload = mapOf("id", iv.id(), "type", iv.type(), "params", iv.params(), "at", time);
            NodeSlot patched = null;
            if (iv.type().equals(Intervention.PATCH_STATE)) { // a synthetic edit: the new state is carried by the intervention event itself
                patched = byName.get((String) iv.params().get("node"));
                if (patched == null) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + iv.params().get("node"));
                String field = (String) iv.params().get("field");
                if (field.startsWith("volatile.")) patched.state.put(field.substring(9), iv.params().get("value"));
                else if (field.startsWith("durable.")) patched.state.putDurable(field.substring(8), iv.params().get("value"));
                else throw new IllegalArgumentException("intervention " + iv.id() + ": the field must be zone-qualified (volatile.x or durable.x)");
                payload.put("patchedNode", patched.name);
                payload.put("stateAfter", patched.node.stateCodec().encode(patched.state));
            }
            Event ev = emitEnv(iv.eventId(), EventKind.INTERVENTION_APPLIED, payload, envBase(), null);
            group.add(ev);
            faults.registerIntervention(iv.eventId(), ev.ref());
            if (patched != null) {
                lastWriters.put(patched.name + "|" + iv.params().get("field"), ev.ref());
                patched.fieldDigests = fieldDigests(patched);
            }
            applyOne(iv, ev.ref(), group);
        }
        scanAfterEvents(group);
        recorder.commit(group);
    }

    private static AgendaItem withIntervention(AgendaItem it, Ref ivRef, String origin) {
        Map<String, Object> nd = new TreeMap<>(it.data());
        List<Object> fr = new ArrayList<>(Canon.list(nd.get("faultRefs")));
        fr.add(ivRef.toMap());
        nd.put("faultRefs", fr);
        nd.put("origin", origin);
        return it.withData(nd);
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
                AgendaItem pending = agenda.find(i -> i.kind() == AgendaItem.Kind.FAULT && ruleId.equals(i.data().get("rule")) && "INJECT".equals(i.data().get("op")));
                if (pending != null) faults.ghostStart(ruleId, pending.due()); // a rule triggered before the branch point but not yet fired: when it would have fired
                agenda.removeIf(i -> i.kind() == AgendaItem.Kind.FAULT && ruleId.equals(i.data().get("rule")));
                ActiveFault removing = faults.activeByRule(ruleId);
                if (removing != null) { // an active partition heals, a paused node resumes, a skewed clock is restored, now
                    removing.alterer = ivRef;
                    removing.origEnd = r.params().containsKey("duration") ? removing.start + r.num("duration") : Long.MAX_VALUE;
                    faults.setOrigin(ruleId + "/clear", origin);
                    doClear(r, group);
                }
                faults.ghost(r, iv.eventId());
                if (r.params().containsKey("node") && (r.action() == FaultRule.FaultAction.CRASH || r.action() == FaultRule.FaultAction.PAUSE
                        || r.action() == FaultRule.FaultAction.CLOCK_SKEW || r.action() == FaultRule.FaultAction.RESTART)) faults.markLane(r.str("node"), ivRef);
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
                    af.alterer = ivRef;
                    af.origEnd = clr.due();
                    faults.setOrigin(ruleId + "/clear", origin);
                    if (by > 0) { // the drops of the extra time are the intervention's doing
                        af.extendedFrom = clr.due();
                        af.extender = ivRef;
                    } else if (by < 0) {
                        af.shortenedFrom = clr.due();
                        af.shortener = ivRef;
                    }
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
                    faults.shiftedBy(ruleId, by);
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
            case Intervention.RESTART_NODE -> {
                String node = (String) p.get("node");
                long at = Canon.num(p.get("at"));
                if (!byName.containsKey(node)) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + node);
                if (at < time) throw new IllegalArgumentException("intervention " + iv.id() + ": cannot restart a node in the past");
                FaultRule r = FaultRule.restart("iv_" + iv.id(), node, at);
                faults.addRule(r, origin);
                scheduleRule(r);
            }
            case Intervention.PAUSE_NODE -> {
                String node = (String) p.get("node");
                long at = Canon.num(p.get("at")), dur = Canon.num(p.get("duration"));
                if (!byName.containsKey(node)) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + node);
                if (at < time || dur <= 0) throw new IllegalArgumentException("intervention " + iv.id() + ": a pause needs a start in the future and a positive duration");
                FaultRule r = FaultRule.pause("iv_" + iv.id(), node, at, dur);
                faults.addRule(r, origin);
                scheduleRule(r);
            }
            case Intervention.SKEW_CLOCK -> {
                String node = (String) p.get("node");
                long at = Canon.num(p.get("at"));
                if (!byName.containsKey(node)) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + node);
                if (at < time) throw new IllegalArgumentException("intervention " + iv.id() + ": cannot skew a clock in the past");
                FaultRule r = FaultRule.clockSkew("iv_" + iv.id(), node, at, Canon.num(p.get("offset")), p.containsKey("duration") ? Canon.num(p.get("duration")) : null);
                faults.addRule(r, origin);
                scheduleRule(r);
            }
            case Intervention.ADD_INPUT -> {
                String node = (String) p.get("node");
                long at = Canon.num(p.get("at"));
                NodeSlot s = byName.get(node);
                if (s == null) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + node);
                if (at < time) throw new IllegalArgumentException("intervention " + iv.id() + ": cannot add an input in the past");
                Map<String, Object> data = mapOf("node", node, "body", Canon.map(p.get("body")), "index", -1L, "origin", origin, "cause", ivRef.toMap());
                agenda.add(new AgendaItem(at, s.idx, AgendaItem.Kind.CLIENT_INPUT, "ci:iv_" + iv.id(), data));
            }
            case Intervention.REMOVE_INPUT -> {
                String ident = String.format("ci:%06d", Canon.num(p.get("index")));
                AgendaItem gone = agenda.find(i -> i.kind() == AgendaItem.Kind.CLIENT_INPUT && i.identity().equals(ident));
                if (gone != null) faults.markLane((String) gone.data().get("node"), ivRef);
                if (!agenda.removeIf(i -> i.kind() == AgendaItem.Kind.CLIENT_INPUT && i.identity().equals(ident)))
                    throw new IllegalArgumentException("intervention " + iv.id() + ": there is no pending client input " + ident + " (it already happened or never existed)");
            }
            case Intervention.SWAP_DELIVERIES -> {
                String a = (String) p.get("a"), b = (String) p.get("b");
                if (a.equals(b)) throw new IllegalArgumentException("intervention " + iv.id() + ": a message cannot be swapped with itself");
                AgendaItem ia = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(a));
                AgendaItem ib = agenda.find(i -> i.kind() == AgendaItem.Kind.DELIVERY && i.identity().equals(b));
                if (ia == null || ib == null) throw new IllegalArgumentException("intervention " + iv.id() + ": both messages must be in flight at the branch point to be swapped");
                agenda.removeIf(i -> i.kind() == AgendaItem.Kind.DELIVERY && (i.identity().equals(a) || i.identity().equals(b)));
                agenda.add(withIntervention(ia.withDue(ib.due()), ivRef, origin));
                agenda.add(withIntervention(ib.withDue(ia.due()), ivRef, origin));
            }
            case Intervention.CONFIG_PATCH -> {
                configOverrides.put((String) p.get("key"), (String) p.get("value"));
                configRefs.put((String) p.get("key"), ivRef);
            }
            case Intervention.RESEED_STREAM -> {
                NodeSlot s = byName.get((String) p.get("node"));
                if (s == null) throw new IllegalArgumentException("intervention " + iv.id() + ": unknown node " + p.get("node"));
                s.randomStates.put((String) p.get("stream"), Canon.num(p.get("seed")));
                reseedRefs.put(s.name + "|" + p.get("stream"), ivRef);
            }
            case Intervention.PATCH_STATE -> { /* applied before the event was recorded, see applyInterventions */ }
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
        d.put("time", lastEventTime);
        d.put("step", lastEventStep);
        if (time != lastEventTime || stepCount != lastEventStep) { // only when steps that recorded nothing came after the last event
            d.put("liveTime", time);
            d.put("liveStep", stepCount);
        }
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
        d.put("config", configOverrides);
        if (!configRefs.isEmpty()) {
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<String, Ref> en : configRefs.entrySet()) m.put(en.getKey(), en.getValue().toMap());
            d.put("configRefs", m);
        }
        if (!reseedRefs.isEmpty()) {
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<String, Ref> en : reseedRefs.entrySet()) m.put(en.getKey(), en.getValue().toMap());
            d.put("reseedRefs", m);
        }
        d.put("stopStatus", stopRequested && stopStatus != null ? stopStatus.name() : null);
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
