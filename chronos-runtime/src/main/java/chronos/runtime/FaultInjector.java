package chronos.runtime;

import chronos.model.Canon;
import chronos.model.FaultPlan;
import chronos.model.FaultRule;
import chronos.model.Ref;

import java.util.*;

/**
 * Fault Injector (C12): evaluates fault-plan triggers, keeps the progress of every rule, remembers which faults are active
 * and which were (for OMISSION edges, D-23) and reports coverage. Faults are applied only by the engine inside the step
 * cycle, never inside node code. Rule progress, per-sender message ordinals and the fault history are world state and are
 * captured by every checkpoint (Section 14).
 */
final class FaultInjector {
    private final Map<String, FaultRule> rules = new LinkedHashMap<>();
    private final Map<String, String> origins = new TreeMap<>();   // "ruleId" or "ruleId/clear" -> "iv:<id>"
    private final Map<String, Ref> ivRefs = new TreeMap<>();       // "iv:<id>" -> its INTERVENTION_APPLIED event
    private final Map<String, Long> firings = new TreeMap<>();
    private final Map<String, Long> ordinals = new TreeMap<>();    // per-sender message ordinals, key "from>to|type"
    private final List<ActiveFault> active = new ArrayList<>();
    private final List<ActiveFault> history = new ArrayList<>();
    private final Map<String, String> reorderPending = new TreeMap<>();  // REORDER rule -> the armed (n-th) message id
    private final Set<String> triggered = new TreeSet<>();               // AFTER_EVENT rules already scheduled
    private final Map<String, Long> shifts = new TreeMap<>();            // partition rule -> how many ms an intervention lengthened it
    private final Map<String, FaultRule> ghosts = new LinkedHashMap<>(); // rules an intervention removed: they still match (to link what they would have changed to the intervention) but do nothing
    private final Map<String, Ref> laneMarks = new TreeMap<>();          // node -> intervention whose removal displaces that node's next event
    private final Map<String, Long> ghostStarts = new TreeMap<>();      // removed AFTER_EVENT rules: when their trigger event happened in this run (their would-be start)
    private List<Ref> ghostRefs = new ArrayList<>();

    FaultInjector(FaultPlan plan) {
        for (FaultRule r : plan.rules()) addRule(r, null);
    }

    void addRule(FaultRule r, String origin) {
        if (rules.containsKey(r.id())) throw new IllegalArgumentException("duplicate fault rule id: " + r.id());
        rules.put(r.id(), r);
        firings.putIfAbsent(r.id(), 0L);
        if (origin != null) origins.put(r.id(), origin);
    }

    void replaceRule(FaultRule r) {
        if (!rules.containsKey(r.id())) throw new IllegalArgumentException("unknown fault rule: " + r.id());
        rules.put(r.id(), r);
    }

    void removeRule(String id) {
        rules.remove(id);
        firings.remove(id);
        origins.remove(id);
        origins.remove(id + "/clear");
    }

    /** Keeps a removed rule as a ghost (see {@link #ghosts}): the kinds whose effect on a later event can be traced are message rules, partitions, crashes and pauses. */
    void ghost(FaultRule r, String ivEventId) {
        ghosts.put(r.id(), r);
        origins.put("ghost:" + r.id(), ivEventId);
    }

    /** A removed rule that waits for a named event: remember when that event happens, which is when the rule would have fired. */
    void ghostTrigger(String eventId, long now) {
        for (FaultRule g : ghosts.values())
            if (g.trigger() == FaultRule.TriggerType.AFTER_EVENT && eventId.equals(g.str("event")) && !ghostStarts.containsKey(g.id())) ghostStarts.put(g.id(), now);
    }

    /** The instant at which a removed rule's already-scheduled firing would have happened (its trigger event was before the branch point). */
    void ghostStart(String ruleId, long at) {
        ghostStarts.putIfAbsent(ruleId, at);
    }

    private static Long startOf(FaultRule g, Map<String, Long> ghostStarts) {
        if (g.params().containsKey("at")) return g.num("at");
        return ghostStarts.get(g.id());
    }

    void markLane(String node, Ref iv) {
        laneMarks.put(node, iv);
    }

    /** The intervention whose removal displaced this node's next event, once; null if none. */
    Ref takeLaneMark(String node) {
        return laneMarks.isEmpty() ? null : laneMarks.remove(node);
    }

    /** The interventions whose removed message rules would have matched the message just passed to matchSend. */
    List<Ref> takeGhostRefs() {
        List<Ref> r = ghostRefs;
        ghostRefs = new ArrayList<>();
        return r;
    }

    /** Interventions that removed a partition which, in the original run, would have cut from-to at this moment. */
    List<Ref> ghostPartitionRefs(String from, String to, long now) {
        if (ghosts.isEmpty()) return List.of();
        List<Ref> out = new ArrayList<>();
        for (FaultRule g : ghosts.values()) {
            if (g.action() != FaultRule.FaultAction.PARTITION) continue;
            Long start = startOf(g, ghostStarts);
            if (start == null) continue;
            Long dur = g.params().containsKey("duration") ? g.num("duration") : null;
            if (now < start || (dur != null && now >= start + dur)) continue;
            int gf = -1, gt = -1, i = 0;
            for (Object grp : Canon.list(g.params().get("groups"))) {
                List<Object> members = Canon.list(grp);
                if (members.contains(from)) gf = i;
                if (members.contains(to)) gt = i;
                i++;
            }
            Ref iv = ivRef(origins.get("ghost:" + g.id()));
            if (gf >= 0 && gt >= 0 && gf != gt && iv != null) out.add(iv);
        }
        return out;
    }

    /**
     * Interventions that removed a fault which, in the original run, would have given a timer firing an OMISSION edge (D-23): the
     * same test as {@link #omissionSources}, applied to the removed rules' would-be windows. The firing differs from the original
     * only by that missing edge, and the intervention is why.
     */
    List<Ref> ghostOmissionRefs(NodeSlot slot, long setAt, long fireAt) {
        if (ghosts.isEmpty()) return List.of();
        List<Ref> out = new ArrayList<>();
        for (FaultRule g : ghosts.values()) {
            Ref iv = ivRef(origins.get("ghost:" + g.id()));
            if (iv == null) continue;
            ActiveFault f;
            Long gs = startOf(g, ghostStarts);
            if (gs == null && g.action() != FaultRule.FaultAction.RESTART) continue; // the rule's trigger has not happened (yet): it would not have fired
            switch (g.action()) {
                case CRASH -> {
                    long start = gs;
                    Long end = null;
                    List<FaultRule> restarts = new ArrayList<>(rules.values());
                    restarts.addAll(ghosts.values());
                    for (FaultRule r : restarts) {
                        Long rs = startOf(r, ghostStarts);
                        if (r.action() == FaultRule.FaultAction.RESTART && g.str("node").equals(r.str("node")) && rs != null && rs >= start && (end == null || rs < end)) end = rs;
                    }
                    f = new ActiveFault("crash", g.id(), iv, start, end, List.of(), g.str("node"));
                }
                case PAUSE -> f = new ActiveFault("pause", g.id(), iv, gs, gs + g.num("duration"), List.of(), g.str("node"));
                case PARTITION -> {
                    List<List<String>> groups = new ArrayList<>();
                    for (Object grp : Canon.list(g.params().get("groups"))) {
                        List<String> members = new ArrayList<>();
                        for (Object o : Canon.list(grp)) members.add((String) o);
                        groups.add(members);
                    }
                    f = new ActiveFault("partition", g.id(), iv, gs, g.params().containsKey("duration") ? gs + g.num("duration") : null, groups, null);
                }
                default -> { continue; }
            }
            if (f.start > fireAt || (f.end != null && f.end <= setAt)) continue;
            if (touches(slot, f) && !out.contains(iv)) out.add(iv);
        }
        // a crash that is still in force only because an intervention removed the restart that ended it
        for (ActiveFault c : active) {
            if (!c.kind.equals("crash") || !touches(slot, c)) continue;
            Ref iv = restartRemovedRef(c.node, c.start, fireAt);
            if (iv != null && !out.contains(iv)) out.add(iv);
        }
        return out;
    }

    /** The intervention that removed a RESTART of this node which, in the original run, would have happened between a crash's start and now; or null. */
    Ref restartRemovedRef(String node, long crashStart, long now) {
        for (FaultRule g : ghosts.values()) {
            if (g.action() != FaultRule.FaultAction.RESTART || !node.equals(g.str("node"))) continue;
            Long at = startOf(g, ghostStarts);
            if (at != null && at >= crashStart && at <= now) return ivRef(origins.get("ghost:" + g.id()));
        }
        return null;
    }

    FaultRule rule(String id) {
        return rules.get(id);
    }

    Collection<FaultRule> rules() {
        return rules.values();
    }

    void setOrigin(String key, String ivEventId) {
        origins.put(key, ivEventId);
    }

    String origin(String key) {
        return origins.get(key);
    }

    void registerIntervention(String ivEventId, Ref ref) {
        ivRefs.put(ivEventId, ref);
    }

    Ref ivRef(String ivEventId) {
        return ivEventId == null ? null : ivRefs.get(ivEventId);
    }

    void fired(String ruleId) {
        firings.merge(ruleId, 1L, Long::sum);
    }

    Map<String, Long> coverage() {
        Map<String, Long> m = new TreeMap<>();
        for (String id : rules.keySet()) m.put(id, firings.getOrDefault(id, 0L));
        return m;
    }

    /** Message-triggered rules that match a message being sent. Counts the sender's ordinal first (checkpointed state). */
    List<FaultRule> matchSend(String msgId, String from, String to, String type, DecisionSource ds, long seed, long now) {
        long ord = ordinals.merge(from + ">" + to + "|" + type, 1L, Long::sum);
        for (FaultRule g : ghosts.values()) { // a removed rule that would have hit this message: remember its intervention (decided by pure identity hashing, never by the decision source)
            boolean hit = switch (g.trigger()) {
                case ON_MESSAGE_ID -> msgId.equals(g.str("messageId"));
                case ON_NTH_MESSAGE -> matches(g, from, to, type) && ord == g.num("n");
                case PROBABILISTIC -> matches(g, from, to, type) && chronos.model.IdentityHash.bounded(seed, 1000, "prob", g.id(), msgId) < g.num("perMille");
                case AT_TIME -> g.action() == FaultRule.FaultAction.SLOW_LINK && wild(g.str("sender"), from) && wild(g.str("receiver"), to) && now >= g.num("at")
                        && (!g.params().containsKey("duration") || now < g.num("at") + g.num("duration")); // a slow link an intervention removed would have slowed this message
                default -> false;
            };
            Ref iv = hit ? ivRef(origins.get("ghost:" + g.id())) : null;
            if (iv != null) ghostRefs.add(iv);
        }
        List<FaultRule> hit = new ArrayList<>();
        for (FaultRule r : rules.values()) {
            switch (r.trigger()) {
                case ON_MESSAGE_ID -> {
                    if (msgId.equals(r.str("messageId"))) hit.add(r);
                }
                case ON_NTH_MESSAGE -> {
                    if (matches(r, from, to, type) && ord == r.num("n")) hit.add(r);
                }
                case PROBABILISTIC -> {
                    if (matches(r, from, to, type) && ds.probabilisticFires(r.id(), msgId, r.num("perMille"))) hit.add(r);
                }
                default -> { }
            }
        }
        return hit;
    }

    private static boolean matches(FaultRule r, String from, String to, String type) {
        return wild(r.str("sender"), from) && wild(r.str("receiver"), to) && wild(r.str("type"), type);
    }

    private static boolean wild(String pattern, String v) {
        return "*".equals(pattern) || pattern.equals(v);
    }

    void activate(ActiveFault f) {
        active.add(f);
    }

    void close(ActiveFault f, long endTime) {
        f.end = endTime;
        active.remove(f);
        history.add(f);
    }

    /** Partitions that have ended and cut the link a-b, which a message sent at sentAt may have crossed because they healed. */
    List<ActiveFault> healedPartitionsCrossed(String a, String b, long sentAt, long now) {
        List<ActiveFault> out = new ArrayList<>();
        for (ActiveFault f : history)
            if (f.kind.equals("partition") && f.cuts(a, b) && f.clearRef != null && f.start <= sentAt && sentAt <= f.end && f.end <= now) out.add(f);
        return out;
    }

    /** Interventions that healed a partition earlier than the original run did, when this delivery (link from-to, at time now) would still have been dropped by it. */
    List<Ref> shortenedPartitionRefs(String from, String to, long now) {
        List<Ref> out = new ArrayList<>();
        for (ActiveFault f : history)
            if (f.kind.equals("partition") && f.shortener != null && f.shortenedFrom != null && f.cuts(from, to) && f.end != null && now >= f.end && now < f.shortenedFrom && !out.contains(f.shortener)) out.add(f.shortener);
        return out;
    }

    /**
     * Interventions that moved the end of (or removed) a fault that silences this node, when that changes whether the fault was in force
     * during the timer's wait (D-23): the original run's timer firing has an OMISSION edge that this one lacks, or the reverse, and the
     * intervention is why.
     */
    List<Ref> windowAlterationRefs(NodeSlot slot, long setAt, long fireAt) {
        List<Ref> out = new ArrayList<>();
        List<ActiveFault> all = new ArrayList<>(history);
        all.addAll(active);
        for (ActiveFault f : all) {
            if (f.alterer == null || f.start > fireAt || !touches(slot, f)) continue;
            boolean actual = f.end == null || f.end > setAt, original = f.origEnd > setAt;
            if (actual != original && !out.contains(f.alterer)) out.add(f.alterer);
        }
        return out;
    }

    /** The earliest RESTART rule of a node after the given time that is still in the plan, or Long.MAX_VALUE. */
    long nextRestartAfter(String node, long now, String exceptRule) {
        long best = Long.MAX_VALUE;
        for (FaultRule r : rules.values())
            if (r.action() == FaultRule.FaultAction.RESTART && !r.id().equals(exceptRule) && node.equals(r.str("node")) && r.params().containsKey("at") && r.num("at") > now) best = Math.min(best, r.num("at"));
        return best;
    }

    /** Rules triggered by the recording of the given event that have not been scheduled yet. */
    List<FaultRule> afterEvent(String eventId) {
        List<FaultRule> out = new ArrayList<>();
        for (FaultRule r : rules.values())
            if (r.trigger() == FaultRule.TriggerType.AFTER_EVENT && eventId.equals(r.str("event")) && !triggered.contains(r.id())) out.add(r);
        return out;
    }

    /** ON_STATE rules watching a node that have not fired yet. */
    List<FaultRule> onState(String node) {
        List<FaultRule> out = new ArrayList<>();
        for (FaultRule r : rules.values())
            if (r.trigger() == FaultRule.TriggerType.ON_STATE && node.equals(r.str("watchNode")) && !triggered.contains(r.id())) out.add(r);
        return out;
    }

    void markTriggered(String ruleId) {
        triggered.add(ruleId);
    }

    void shiftedBy(String ruleId, long by) {
        shifts.put(ruleId, by);
    }

    long shift(String ruleId) {
        return shifts.getOrDefault(ruleId, 0L);
    }

    void armReorder(String ruleId, String msgId) {
        reorderPending.put(ruleId, msgId);
    }

    /** Armed REORDER rules for this link whose first message is waiting for its successor; they are disarmed. */
    List<String[]> takeReorders(String from, String to, String type) {
        List<String[]> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, String>> it = reorderPending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            FaultRule r = rules.get(e.getKey());
            if (r == null) {
                it.remove();
            } else if (matches(r, from, to, type)) {
                out.add(new String[]{e.getKey(), e.getValue()});
                it.remove();
            }
        }
        return out;
    }

    ActiveFault activeByRule(String ruleId) {
        for (ActiveFault f : active) if (f.ruleId.equals(ruleId)) return f;
        return null;
    }

    ActiveFault activePause(String node) {
        for (ActiveFault f : active) if (f.kind.equals("pause") && node.equals(f.node)) return f;
        return null;
    }

    List<ActiveFault> slowLinks(String from, String to) {
        List<ActiveFault> out = new ArrayList<>();
        for (ActiveFault f : active)
            if (f.kind.equals("slowlink") && wild((String) f.extra.get("sender"), from) && wild((String) f.extra.get("receiver"), to)) out.add(f);
        return out;
    }

    ActiveFault activePartition(String ruleId) {
        for (ActiveFault f : active) if (f.kind.equals("partition") && f.ruleId.equals(ruleId)) return f;
        return null;
    }

    ActiveFault activeCrash(String node) {
        for (ActiveFault f : active) if (f.kind.equals("crash") && node.equals(f.node)) return f;
        return null;
    }

    ActiveFault partitionCutting(String a, String b) {
        for (ActiveFault f : active) if (f.cuts(a, b)) return f;
        return null;
    }

    /**
     * Faults that silenced a node's inputs while one of its timers waited (D-23): a fault touches a node if, for a partition,
     * the node sits on one side and has heard from a node on the other side before, or, for a crash, the node has received
     * messages from the crashed node before. The fault must have been in force at some time in [setAt, fireAt].
     */
    List<ActiveFault> omissionSources(NodeSlot slot, long setAt, long fireAt) {
        List<ActiveFault> out = new ArrayList<>();
        List<ActiveFault> all = new ArrayList<>(history);
        all.addAll(active);
        for (ActiveFault f : all) {
            if (f.start > fireAt) continue;
            if (f.end != null && f.end <= setAt) continue;
            if (touches(slot, f) || silencedDuring(slot, f, setAt)) out.add(f);
        }
        return out;
    }

    /**
     * DEV-03, final scope for partitions: besides "the node has heard from the other side before", a partition also touches a node whose
     * timer waited while the partition dropped a message addressed to that node (a silenced message is exactly the thing an OMISSION edge
     * stands for). This keeps the precision of the heard-from test (an isolated node's own heartbeat timers wait for nothing and get no
     * edge) and closes its gap: a partition that begins before the first contact still explains the watchdog it caused. It never adds an
     * edge the specification's literal Phase 12a sentence would not add.
     */
    private boolean silencedDuring(NodeSlot slot, ActiveFault f, long setAt) {
        if (!f.kind.equals("partition") || f.groupOf(slot.name) < 0) return false;
        Long last = f.lastDropTo.get(slot.name);
        return last != null && last >= setAt;
    }

    /** DEV-03: when true, a partition touches every node that sits on a side of it, as the specification's Phase 12a sentence reads literally. */
    private boolean literalPartitionScope;

    void literalPartitionScope(boolean on) {
        this.literalPartitionScope = on;
    }

    private boolean touches(NodeSlot slot, ActiveFault f) {
        if (f.kind.equals("crash") || f.kind.equals("pause")) return !slot.name.equals(f.node) && slot.heardFrom.contains(f.node);
        if (!f.kind.equals("partition")) return false;
        int g = f.groupOf(slot.name);
        if (g < 0) return false;
        if (literalPartitionScope) return f.cutsOffFromAnother(slot.name);
        for (String heard : slot.heardFrom) {
            int hg = f.groupOf(heard);
            if (hg >= 0 && hg != g) return true;
        }
        return false;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new TreeMap<>();
        List<Object> rl = new ArrayList<>();
        for (FaultRule r : rules.values()) rl.add(r.toMap());
        m.put("rules", rl);
        m.put("origins", origins);
        Map<String, Object> ivs = new TreeMap<>();
        for (Map.Entry<String, Ref> e : ivRefs.entrySet()) ivs.put(e.getKey(), e.getValue().toMap());
        m.put("ivRefs", ivs);
        m.put("firings", firings);
        m.put("ordinals", ordinals);
        List<Object> al = new ArrayList<>();
        for (ActiveFault f : active) al.add(f.toMap());
        m.put("active", al);
        List<Object> hl = new ArrayList<>();
        for (ActiveFault f : history) hl.add(f.toMap());
        m.put("history", hl);
        m.put("reorderPending", reorderPending);
        m.put("triggered", new ArrayList<>(triggered));
        m.put("shifts", shifts);
        if (!ghosts.isEmpty()) {
            List<Object> gl = new ArrayList<>();
            for (FaultRule r : ghosts.values()) gl.add(r.toMap());
            m.put("ghosts", gl);
        }
        if (!ghostStarts.isEmpty()) m.put("ghostStarts", ghostStarts);
        if (!laneMarks.isEmpty()) {
            Map<String, Object> lm = new TreeMap<>();
            for (Map.Entry<String, Ref> e : laneMarks.entrySet()) lm.put(e.getKey(), e.getValue().toMap());
            m.put("laneMarks", lm);
        }
        return m;
    }

    static FaultInjector restore(Map<String, Object> m) {
        FaultInjector fi = new FaultInjector(FaultPlan.empty());
        for (Object o : Canon.list(m.get("rules"))) {
            FaultRule r = FaultRule.fromMap(Canon.map(o));
            fi.rules.put(r.id(), r);
        }
        for (Map.Entry<String, Object> e : Canon.map(m.get("origins")).entrySet()) fi.origins.put(e.getKey(), (String) e.getValue());
        for (Map.Entry<String, Object> e : Canon.map(m.get("ivRefs")).entrySet()) fi.ivRefs.put(e.getKey(), Ref.fromMap(Canon.map(e.getValue())));
        for (Map.Entry<String, Object> e : Canon.map(m.get("firings")).entrySet()) fi.firings.put(e.getKey(), Canon.num(e.getValue()));
        for (Map.Entry<String, Object> e : Canon.map(m.get("ordinals")).entrySet()) fi.ordinals.put(e.getKey(), Canon.num(e.getValue()));
        for (Object o : Canon.list(m.get("active"))) fi.active.add(ActiveFault.fromMap(Canon.map(o)));
        for (Object o : Canon.list(m.get("history"))) fi.history.add(ActiveFault.fromMap(Canon.map(o)));
        if (m.containsKey("reorderPending")) for (Map.Entry<String, Object> e : Canon.map(m.get("reorderPending")).entrySet()) fi.reorderPending.put(e.getKey(), (String) e.getValue());
        if (m.containsKey("triggered")) for (Object o : Canon.list(m.get("triggered"))) fi.triggered.add((String) o);
        if (m.containsKey("ghosts")) for (Object o : Canon.list(m.get("ghosts"))) {
            FaultRule r = FaultRule.fromMap(Canon.map(o));
            fi.ghosts.put(r.id(), r);
        }
        if (m.containsKey("ghostStarts")) for (Map.Entry<String, Object> e : Canon.map(m.get("ghostStarts")).entrySet()) fi.ghostStarts.put(e.getKey(), Canon.num(e.getValue()));
        if (m.containsKey("laneMarks")) for (Map.Entry<String, Object> e : Canon.map(m.get("laneMarks")).entrySet()) fi.laneMarks.put(e.getKey(), Ref.fromMap(Canon.map(e.getValue())));
        if (m.containsKey("shifts")) for (Map.Entry<String, Object> e : Canon.map(m.get("shifts")).entrySet()) fi.shifts.put(e.getKey(), Canon.num(e.getValue()));
        return fi;
    }
}
