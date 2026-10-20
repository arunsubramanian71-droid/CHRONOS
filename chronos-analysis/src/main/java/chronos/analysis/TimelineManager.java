package chronos.analysis;

import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;
import chronos.store.TimelineWriter;

import java.util.*;

/**
 * Timeline Manager (C11): the catalogue's single authority for identity and ancestry (Section 17). It owns the timeline id
 * policy and branch creation; the store persists the catalogue (D-11). Ids are allocated before any run starts, so naming is
 * deterministic even with parallel branching.
 *
 * Branch creation (Section 17): choose the parent and a branch point rounded to a step boundary; obtain a live world there by
 * restoring the nearest checkpoint and prefix-replaying (verifying as it goes); create the child timeline with a new id and
 * branchSeq = p; install the branch decision source; record INTERVENTION_APPLIED events (D-25) and apply the interventions;
 * run to the horizon, a violation or an empty agenda, with positions continuing after p; write the final checkpoint and status.
 */
public final class TimelineManager {
    private final TimelineStore store;
    private final ScenarioRegistry registry;
    private final ReplayEngine replay;
    private final Set<String> reserved = new TreeSet<>();
    private long checkpointInterval = RunOptions.DEFAULT_CHECKPOINT_INTERVAL;
    private boolean pinned;

    public TimelineManager(TimelineStore store, ScenarioRegistry registry) {
        this.store = store;
        this.registry = registry;
        this.replay = new ReplayEngine(store, registry);
    }

    /** The scenario a timeline was recorded from (built from its header, as replay does). */
    public Scenario scenarioOf(String timelineId) {
        TimelineHeader h = store.header(timelineId);
        return registry.create(h.scenario(), h.params());
    }

    /** Branches made from now on use the Pinned continuation policy (D-15, Stretch) instead of Continue. It is recorded in the branch's header. */
    public TimelineManager withPinnedContinuation(boolean on) {
        this.pinned = on;
        return this;
    }

    public TimelineManager withCheckpointInterval(long k) {
        this.checkpointInterval = k;
        return this;
    }

    public TimelineStore store() {
        return store;
    }

    public ReplayEngine replayEngine() {
        return replay;
    }

    public synchronized String allocateRootId() {
        for (int n = 0; ; n++) {
            String id = "T" + n;
            if (!store.exists(id) && reserved.add(id)) return id;
        }
    }

    public synchronized String allocateChildId(String parent) {
        for (int k = 1; ; k++) {
            String id = parent + "." + k;
            if (!store.exists(id) && reserved.add(id)) return id;
        }
    }

    private synchronized void release(String id) {
        reserved.remove(id);
    }

    /** The result of a branch run. */
    public record BranchResult(String timelineId, long branchSeq, RunResult result, TimelineHeader header) {}

    /** True if the timeline or any ancestor was created with a synthetic intervention (it is permanently marked, Section 19). */
    public boolean isSynthetic(String timelineId) {
        for (TimelineHeader h = store.header(timelineId); h != null; h = h.parentId() == null ? null : store.header(h.parentId()))
            for (Intervention iv : h.interventions()) if (iv.synthetic()) return true;
        return false;
    }

    /**
     * The first position at which a world can be restored: the end of the boot sequence (every NODE_STARTED, then the effects
     * of the start handlers). Group boundaries inside the boot sequence are commit points of the log but not worlds: only some
     * nodes have started there. Found from the group structure and causal edges: a boot group starts with a NODE_STARTED, or with an
     * effect of a start handler (a node event whose step trigger is a NODE_STARTED), or is the invariant check on those.
     */
    public long bootEnd(String timelineId) {
        List<Event> es = store.events(timelineId);
        Set<String> starts = new HashSet<>();
        long end = -1, from = 0;
        for (long groupEnd : store.groupEnds(timelineId)) {
            Event first = es.get((int) from);
            boolean boot;
            if (first.kind() == EventKind.NODE_STARTED) {
                boot = true;
            } else if (first.nodeId().equals(Event.ENV)) { // the invariant check after boot; a step that begins on env (a fault) is not boot
                boot = first.kind() == EventKind.INVARIANT_VIOLATED && first.parents().stream().anyMatch(p -> p.kind() == EdgeKind.STEP_EFFECT && starts.contains(p.id()));
            } else { // the effects of a start handler: a node event, not a trigger, whose step trigger is a NODE_STARTED
                boot = !first.kind().isTrigger() && first.parents().stream().anyMatch(p -> p.kind() == EdgeKind.STEP_EFFECT && starts.contains(p.id()));
            }
            if (!boot) break;
            for (long i = from; i <= groupEnd; i++) if (es.get((int) i).kind() == EventKind.NODE_STARTED) starts.add(es.get((int) i).eventId());
            end = groupEnd;
            from = groupEnd + 1;
        }
        return end;
    }

    /** The largest step boundary at or before a position at which a world can be restored (not inside the boot sequence): "a world can only be restored between steps" (Section 17 rule 2). */
    public long roundToBoundary(String timelineId, long atSeq) {
        long best = -1;
        long boot = bootEnd(timelineId);
        for (long e : store.groupEnds(timelineId)) {
            if (e <= atSeq && e >= boot) best = e;
            else if (e > atSeq) break;
        }
        if (best < 0) throw new IllegalArgumentException("position " + atSeq + " is before the first step boundary of " + timelineId + " at which a world exists (" + boot + ", the end of the boot sequence)");
        return best;
    }

    public BranchResult branch(String parentId, long atSeq, List<Intervention> ivs, Long horizon, String label) {
        return branchAs(allocateChildId(parentId), parentId, atSeq, ivs, horizon, label);
    }

    /** Branches into a pre-allocated child id (used by parallel counterfactuals). */
    public BranchResult branchAs(String childId, String parentId, long atSeq, List<Intervention> ivs, Long horizon, String label) {
        TimelineSummary parentSummary = store.summary(parentId);
        if (parentSummary == null) throw new IllegalStateException("timeline " + parentId + " is not completed; only completed timelines can be branched (they are immutable)");
        if (horizon != null && horizon < 0) throw new IllegalArgumentException("the horizon must not be negative (got " + horizon + " ms)");
        long p = roundToBoundary(parentId, atSeq);
        TimelineHeader parentHeader = store.header(parentId);
        ReplayEngine.PausedWorld w = replay.buildAt(parentId, p);
        Engine engine = w.engine();
        Scenario scenario = w.scenario();
        long h = horizon != null ? horizon : parentHeader.horizon();
        TimelineHeader child = parentHeader.asBranch(childId, parentId, p, ivs, h, label);
        if (pinned) child = child.withParam(TimelineHeader.CONTINUATION_PARAM, "pinned");
        WorldImage atBranchPoint = engine.captureImage();
        TimelineWriter writer = store.openWriter(child);
        boolean finished = false;
        try {
            writer.checkpoint(atBranchPoint); // a checkpoint is stored at every branch point (Section 17 rule 5)
            DecisionSource policy = new RecordDecisionSource(scenario, child.seed());
            if (pinned) policy = new PinnedPolicy(policy, store.events(parentId));
            DecisionSource branchSource = new BranchDecisionSource(new ReplayDecisionSource(store.events(parentId), policy), policy, p, engine::nextSeq);
            engine.recorder().switchTo(childId, List.of(writer));
            engine.setDecisionSource(branchSource);
            engine.setOptions(new RunOptions(h, child.stopOnViolation(), checkpointInterval));
            engine.applyInterventions(ivs);
            RunResult result = engine.runToEnd();
            finished = true;
            return new BranchResult(childId, p, result, child);
        } finally {
            writer.close();
            if (!finished) { // a refused intervention (unknown node, self-swap...) must not leave a half-written branch behind
                store.discardUnfinished(childId);
                release(childId);
            }
        }
    }

    /** The timeline tree as text: id, scenario, seed, status, event count. */
    public String tree() {
        StringBuilder sb = new StringBuilder();
        for (String id : store.timelineIds()) {
            TimelineHeader h = store.header(id);
            int depth = h.parentId() == null ? 0 : id.split("\\.").length - 1;
            TimelineSummary s = store.summary(id);
            sb.append("  ".repeat(depth)).append(id).append("  ").append(h.scenario()).append(" seed=").append(h.seed());
            if (h.parentId() != null) {
                sb.append("  branch of ").append(h.parentId()).append(" at ").append(h.branchSeq());
                if (!h.interventions().isEmpty()) {
                    sb.append(" [");
                    for (Intervention iv : h.interventions()) sb.append(iv.eventId()).append(' ').append(iv.type()).append(' ');
                    sb.setLength(sb.length() - 1);
                    sb.append(']');
                }
            }
            if (h.label() != null) sb.append("  \"").append(h.label()).append('"');
            sb.append("  ").append(s == null ? "RECORDING?" : s.status() + " events=" + (s.lastSeq() + 1)
                    + (s.firstViolationEventId() != null ? " violation=" + s.firstViolationEventId() : "")).append('\n');
        }
        return sb.toString();
    }
}
