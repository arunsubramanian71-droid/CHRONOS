package chronos.analysis;

import chronos.model.*;

import java.util.*;

/**
 * Comparator and Divergence Analyzer (C14, Section 20, D-16). To compare two timelines CHRONOS matches their events by
 * identity, finds where each node first stops matching, keeps only the differences that were not themselves caused by an
 * earlier difference (the causal roots), and reports the earliest root as the first causal divergence, together with the
 * causal path from it to the failure.
 *
 * The algorithm (A is the reference, B the other):
 *  1. skip the shared prefix; 2. align by event id (env events by their identity keys) and classify every event as Same,
 *  Changed, Missing, Extra or Reordered by content digest; 3. find each node's first local divergence; 4. collect the divergent
 *  set D; 5. find the causal roots inside each timeline separately (an event of D is a root if none of its parents is tainted;
 *  taint never crosses from one timeline to the other); 6. order all roots by (virtual time, node order with env last, event
 *  id), never comparing Lamport or vector values across timelines; within one timeline a root that happened before another
 *  comes first; 7. draw the divergence cone; 8. explain the path in the timeline that contains the failure, from the primary
 *  root or, if it lives only in the other timeline, from its anchor: the last Same event on the root's node before it.
 */
public final class TimelineComparator {
    public enum EventClass { SAME, CHANGED, MISSING, EXTRA, REORDERED }

    /** A causal root: a divergent event none of whose parents is tainted. side is 'A' or 'B'. */
    public record Root(char side, String eventId, String nodeId, long virtualTime, EventClass cls, int pos) {}

    /** A node's first local mismatch, and what kind of mismatch it is. */
    public record LocalDivergence(String node, String eventA, String eventB, String kind) {}

    private final LogReader log;

    public TimelineComparator(LogReader log) {
        this.log = log;
    }

    public DivergenceReport compare(String idA, String idB, boolean strict) {
        List<Event> ea = log.events(idA), eb = log.events(idB);
        TimelineHeader ha = log.header(idA), hb = log.header(idB);
        if (!ha.nodes().equals(hb.nodes())) throw new IllegalArgumentException("timelines have different node sets: " + ha.nodes() + " vs " + hb.nodes());
        List<String> warnings = new ArrayList<>();
        if (ha.seed() != hb.seed()) warnings.add("different seeds (" + ha.seed() + " vs " + hb.seed() + "): nearly everything differs from the first random draw");
        for (TimelineHeader hh : List.of(ha, hb)) if (hh.faultPlan().localityNote() != null) warnings.add(hh.timelineId() + ": " + hh.faultPlan().localityNote());
        List<String> order = new ArrayList<>(ha.nodes());
        order.add(Event.ENV);

        int prefix = (int) Math.min(sharedPrefix(idA, idB), Math.min(ea.size(), eb.size()));
        CausalGraph ga = CausalGraph.build(ea), gb = CausalGraph.build(eb);

        // 2. align by identity and classify
        Map<String, Event> mapB = new HashMap<>(), mapA = new HashMap<>();
        for (int i = prefix; i < eb.size(); i++) mapB.put(eb.get(i).eventId(), eb.get(i));
        for (int i = prefix; i < ea.size(); i++) mapA.put(ea.get(i).eventId(), ea.get(i));
        EventClass[] ca = new EventClass[ea.size()], cb = new EventClass[eb.size()];
        Arrays.fill(ca, EventClass.SAME);
        Arrays.fill(cb, EventClass.SAME);
        for (int i = prefix; i < ea.size(); i++) {
            Event a = ea.get(i), b = mapB.get(a.eventId());
            if (b == null) ca[i] = EventClass.MISSING;
            else if (!a.contentDigest(strict).equals(b.contentDigest(strict))) {
                ca[i] = EventClass.CHANGED;
                cb[(int) b.seq()] = EventClass.CHANGED;
            }
        }
        for (int i = prefix; i < eb.size(); i++) if (!mapA.containsKey(eb.get(i).eventId())) cb[i] = EventClass.EXTRA;
        markReordered(order, ea, eb, prefix, ca, cb);

        Map<EventClass, Integer> counts = new EnumMap<>(EventClass.class);
        for (EventClass c : EventClass.values()) counts.put(c, 0);
        for (int i = prefix; i < ea.size(); i++) counts.merge(ca[i], 1, Integer::sum);
        for (int i = prefix; i < eb.size(); i++) if (cb[i] == EventClass.EXTRA) counts.merge(EventClass.EXTRA, 1, Integer::sum);
        // Changed / Reordered pairs are counted once (from A); Extra only exists in B
        counts.put(EventClass.CHANGED, (int) java.util.stream.IntStream.range(prefix, ea.size()).filter(i -> ca[i] == EventClass.CHANGED).count());
        counts.put(EventClass.REORDERED, (int) java.util.stream.IntStream.range(prefix, ea.size()).filter(i -> ca[i] == EventClass.REORDERED).count());

        // 3. each node's first local divergence
        List<LocalDivergence> firstLocal = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (String n : order) {
            List<Integer> pa = suffixPositions(ea, n, prefix), pb = suffixPositions(eb, n, prefix);
            int i = 0;
            while (i < pa.size() && i < pb.size()) {
                Event a = ea.get(pa.get(i)), b = eb.get(pb.get(i));
                if (!a.eventId().equals(b.eventId()) || !a.contentDigest(strict).equals(b.contentDigest(strict))) break;
                i++;
            }
            if (i == pa.size() && i == pb.size()) continue;
            Event a = i < pa.size() ? ea.get(pa.get(i)) : null, b = i < pb.size() ? eb.get(pb.get(i)) : null;
            String kind = classifyLocal(a, b, ga, gb, ca, cb);
            firstLocal.add(new LocalDivergence(n, a == null ? null : a.eventId(), b == null ? null : b.eventId(), kind));
            if (kind.startsWith("output")) flags.add("suspected application nondeterminism on " + n + ": same inputs, different outputs at " + (a != null ? a.eventId() : b.eventId()));
        }

        // 4./5. the divergent set and the causal roots inside each timeline
        List<Root> roots = new ArrayList<>();
        BitSet inDA = new BitSet(), inDB = new BitSet();
        collectRoots('A', ea, ga, ca, prefix, roots, inDA);
        collectRoots('B', eb, gb, cb, prefix, roots, inDB);

        // 5b. a root that is only a renumbered copy of an event of the other timeline's lane is not an independent root (DEV-36)
        List<DivergenceReport.Displaced> displaced = new ArrayList<>();
        List<Root> demoted = new ArrayList<>();
        roots = demotePotentialEdgeOnly(roots, ea, eb, prefix, strict, displaced, demoted);
        roots = demoteExplained(roots, ea, eb, ca, cb, prefix, displaced, demoted);
        roots = demoteShiftedCauses(roots, ea, eb, ga, gb, prefix, displaced, demoted);

        // 6. order the roots
        List<Root> ordered = orderRoots(roots, order, ga, gb);
        Root primary = ordered.isEmpty() ? null : ordered.get(0);
        if (primary != null) for (Root r : ordered)
            if (r != primary && r.side() == primary.side() && (primary.side() == 'A' ? ga : gb).concurrent(primary.pos(), r.pos()))
                flags.add("concurrent roots: " + primary.eventId() + " and " + r.eventId() + " are causally unrelated, neither is causally first (the order shown is a display convention)");

        // 7. divergence cone
        List<Root> explaining = new ArrayList<>(ordered);
        explaining.addAll(demoted); // the consequences of a displaced event are explained too
        BitSet coneBitsA = ga.causalFutureOf(explaining.stream().filter(r -> r.side() == 'A').map(Root::pos).toList());
        BitSet coneBitsB = gb.causalFutureOf(explaining.stream().filter(r -> r.side() == 'B').map(Root::pos).toList());
        int coneA = 0, coneB = 0;
        List<String> unexplained = new ArrayList<>(), coneIdsA = new ArrayList<>(), coneIdsB = new ArrayList<>();
        for (int i = inDA.nextSetBit(0); i >= 0; i = inDA.nextSetBit(i + 1)) if (coneBitsA.get(i)) { coneA++; coneIdsA.add(ea.get(i).eventId()); } else unexplained.add(timelineLabel(idA, ea.get(i)));
        for (int i = inDB.nextSetBit(0); i >= 0; i = inDB.nextSetBit(i + 1)) if (coneBitsB.get(i)) { coneB++; coneIdsB.add(eb.get(i).eventId()); } else unexplained.add(timelineLabel(idB, eb.get(i)));
        TreeSet<String> affected = new TreeSet<>();
        for (int i = inDA.nextSetBit(0); i >= 0; i = inDA.nextSetBit(i + 1)) affected.add(ea.get(i).nodeId());
        for (int i = inDB.nextSetBit(0); i >= 0; i = inDB.nextSetBit(i + 1)) affected.add(eb.get(i).nodeId());

        // absorbed divergence: a node that diverged but ended in the same state
        for (LocalDivergence l : firstLocal) {
            if (l.node().equals(Event.ENV)) continue;
            String sa = lastStateDigest(ea, l.node()), sb = lastStateDigest(eb, l.node());
            if (sa != null && sa.equals(sb)) flags.add("absorbed divergence on " + l.node() + ": it diverged at " + (l.eventA() != null ? l.eventA() : l.eventB()) + " but both timelines end with the same state digest");
        }

        // outcomes
        String outA = outcome(idA, ea), outB = outcome(idB, eb);

        // 8. explanation path
        String failSide = firstViolation(ea) >= 0 ? "A" : firstViolation(eb) >= 0 ? "B" : null;
        String failTl = null, failEvent = null, note = null;
        List<DivergenceReport.Hop> hops = new ArrayList<>();
        if (failSide != null && primary != null) {
            boolean a = failSide.equals("A");
            List<Event> fe = a ? ea : eb;
            CausalGraph fg = a ? ga : gb;
            EventClass[] fc = a ? ca : cb;
            int failPos = firstViolation(fe);
            failTl = a ? idA : idB;
            failEvent = fe.get(failPos).eventId();
            int source = -1;
            if (primary.side() == failSide.charAt(0)) {
                source = primary.pos();
            } else {
                List<Event> re = primary.side() == 'A' ? ea : eb;
                EventClass[] rc = primary.side() == 'A' ? ca : cb;
                String anchorId = null;
                for (int i = primary.pos() - 1; i >= 0; i--)
                    if (re.get(i).nodeId().equals(primary.nodeId()) && (i < prefix || rc[i] == EventClass.SAME)) {
                        anchorId = re.get(i).eventId();
                        break;
                    }
                if (anchorId != null && fg.pos(anchorId) >= 0) {
                    source = fg.pos(anchorId);
                    note = "the first causal divergence " + primary.eventId() + " exists only in " + (primary.side() == 'A' ? idA : idB)
                            + "; the path starts at its anchor " + anchorId + ", the last Same event on " + primary.nodeId() + " before the divergence";
                }
            }
            Optional<List<Integer>> path = source >= 0 && source <= failPos ? fg.path(source, failPos) : Optional.empty();
            if (path.isEmpty()) {
                // fall back to the earliest root in the failure timeline that reaches the failure
                for (Root r : ordered) {
                    if (r.side() != failSide.charAt(0) || r.pos() > failPos) continue;
                    Optional<List<Integer>> p2 = fg.path(r.pos(), failPos);
                    if (p2.isPresent()) {
                        path = p2;
                        note = (note == null ? "" : note + "; ") + "no causal path from the primary root, so the path starts at the earliest root " + r.eventId() + " of " + failTl + " that reaches the failure";
                        break;
                    }
                }
            }
            if (path.isPresent()) {
                List<Integer> p = path.get();
                if (p.size() == 1) hops.add(new DivergenceReport.Hop(fe.get(p.get(0)).eventId(), fe.get(p.get(0)).eventId(), null,
                        fe.get(p.get(0)).eventId() + " is itself the failure: " + Exports.describe(fe.get(p.get(0)), fg)));
                for (int i = 0; i + 1 < p.size(); i++) {
                    Event x = fe.get(p.get(i)), y = fe.get(p.get(i + 1));
                    EdgeKind k = fg.edgeBetween(p.get(i), p.get(i + 1));
                    hops.add(new DivergenceReport.Hop(x.eventId(), y.eventId(), k, x.eventId() + " (" + Exports.describe(x, fg) + " at t=" + x.virtualTime() + ") --" + k + "--> "
                            + y.eventId() + " (" + Exports.describe(y, fg) + " at t=" + y.virtualTime() + ")"));
                }
            }
        }

        return new DivergenceReport(idA, idB, strict, prefix, counts, firstLocal, ordered, primary, coneA, coneB, new ArrayList<>(affected), unexplained,
                failTl, failEvent, hops, note, outA, outB, flags, warnings, coneIdsA, coneIdsB, displaced);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Positions identical in A and B by construction: both lineages share an ancestor up to the branch points (Section 17). */
    private long sharedPrefix(String a, String b) {
        if (a.equals(b)) return log.events(a).size();
        List<TimelineHeader> la = lineage(a), lb = lineage(b);
        int i = 0;
        while (i < la.size() && i < lb.size() && la.get(i).timelineId().equals(lb.get(i).timelineId())) i++;
        if (i == 0) return 0;
        long bpA = i < la.size() ? la.get(i).branchSeq() : log.events(a).size() - 1;
        long bpB = i < lb.size() ? lb.get(i).branchSeq() : log.events(b).size() - 1;
        return Math.min(bpA, bpB) + 1;
    }

    private List<TimelineHeader> lineage(String id) {
        LinkedList<TimelineHeader> l = new LinkedList<>();
        for (TimelineHeader h = log.header(id); h != null; h = h.parentId() == null ? null : log.header(h.parentId())) l.addFirst(h);
        return l;
    }

    private static List<Integer> suffixPositions(List<Event> es, String node, int prefix) {
        List<Integer> l = new ArrayList<>();
        for (int i = prefix; i < es.size(); i++) if (es.get(i).nodeId().equals(node)) l.add(i);
        return l;
    }

    /** Same events in a different local order at one node: both are marked Reordered. */
    private static void markReordered(List<String> order, List<Event> ea, List<Event> eb, int prefix, EventClass[] ca, EventClass[] cb) {
        for (String n : order) {
            List<Integer> pa = suffixPositions(ea, n, prefix), pb = suffixPositions(eb, n, prefix);
            Set<String> inB = new HashSet<>(), inA = new HashSet<>();
            for (int p : pb) inB.add(eb.get(p).eventId());
            for (int p : pa) inA.add(ea.get(p).eventId());
            List<Integer> fa = new ArrayList<>(), fb = new ArrayList<>();
            for (int p : pa) if (inB.contains(ea.get(p).eventId())) fa.add(p);
            for (int p : pb) if (inA.contains(eb.get(p).eventId())) fb.add(p);
            for (int i = 0; i < fa.size(); i++) {
                Event a = ea.get(fa.get(i)), b = eb.get(fb.get(i));
                if (!a.eventId().equals(b.eventId())) {
                    if (ca[fa.get(i)] == EventClass.SAME) ca[fa.get(i)] = EventClass.REORDERED;
                    if (cb[fb.get(i)] == EventClass.SAME) cb[fb.get(i)] = EventClass.REORDERED;
                }
            }
        }
    }

    /** An input difference has its cause elsewhere; an output difference despite identical inputs is suspected nondeterminism. */
    private static String classifyLocal(Event a, Event b, CausalGraph ga, CausalGraph gb, EventClass[] ca, EventClass[] cb) {
        Event e = a != null ? a : b;
        if (e.kind().isTrigger() || e.isEnv()) return "input difference (a message, timer or input arrived differently or not at all; the cause lies elsewhere)";
        if (a != null && b != null && a.eventId().equals(b.eventId())) {
            int pa = (int) a.seq(), pb = (int) b.seq();
            String tA = triggerOf(a, ga), tB = triggerOf(b, gb);
            if (tA != null && tA.equals(tB) && ca[ga.pos(tA)] == EventClass.SAME && cb[gb.pos(tB)] == EventClass.SAME)
                return "output difference despite identical inputs (suspected application nondeterminism, FR-43)";
        }
        return "input difference (the step's inputs differ)";
    }

    private static String triggerOf(Event e, CausalGraph g) {
        int pos = g.pos(e.eventId());
        for (int i = 0; i < g.parentCount(pos); i++) if (g.parentKind(pos, i) == EdgeKind.STEP_EFFECT) return g.event(g.parent(pos, i)).eventId();
        return null;
    }

    /** Walks the timeline in its own log order over its own causal graph: tainted = in D or has a tainted parent; root = in D with no tainted parent. */
    private static String contentKey(Event e) {
        return e.kind() + "|" + chronos.model.Canon.json(e.payload());
    }

    /**
     * Splits off the roots that are not independent: (1) a root whose kind and payload occur at a different position of the same node's
     * lane in the other timeline was only renumbered (DEV-36); (2) a root that is the node's first different event after a message to that
     * node was dropped by an event that is itself divergent (an intervention's drop) is the node noticing the missing input (silence).
     */
    /**
     * An OMISSION edge is an explanatory annotation ("the timer may have fired because messages never came"), derived from which messages a
     * fault dropped to the node (DEV-03). A root whose counterpart in the other timeline has the same kind, payload, state digest, origin and
     * every non-OMISSION parent, and differs only in its OMISSION parents, did not behave differently: the dropped messages that justify the
     * edge differ because of something upstream. It is reported as displaced (reason "potential-edge"), not as an independent root (DEV-54).
     */
    private static List<Root> demotePotentialEdgeOnly(List<Root> roots, List<Event> ea, List<Event> eb, int prefix, boolean strict,
                                                      List<DivergenceReport.Displaced> displaced, List<Root> demoted) {
        List<Root> keep = new ArrayList<>();
        for (Root r : roots) {
            List<Event> mine = r.side() == 'A' ? ea : eb, theirs = r.side() == 'A' ? eb : ea;
            Event e = mine.get(r.pos()), counterpart = null;
            for (int i = prefix; i < theirs.size(); i++) if (theirs.get(i).eventId().equals(e.eventId())) { counterpart = theirs.get(i); break; }
            if (counterpart == null || e.kind() != EventKind.TIMER_FIRED || !withoutOmission(e, strict).equals(withoutOmission(counterpart, strict))) {
                keep.add(r);
                continue;
            }
            Set<String> mineOm = omissionParents(e), theirOm = omissionParents(counterpart);
            TreeSet<String> differing = new TreeSet<>(mineOm);
            differing.addAll(theirOm);
            Set<String> both = new TreeSet<>(mineOm);
            both.retainAll(theirOm);
            differing.removeAll(both);
            if (differing.isEmpty()) {
                keep.add(r);
                continue;
            }
            displaced.add(new DivergenceReport.Displaced(r.side(), r.eventId(), r.nodeId(), differing.first(), DivergenceReport.Displaced.POTENTIAL_EDGE));
            demoted.add(r);
        }
        return keep;
    }

    private static Set<String> omissionParents(Event e) {
        Set<String> s = new TreeSet<>();
        for (Parent p : e.parents()) if (p.kind() == EdgeKind.OMISSION) s.add(p.id());
        return s;
    }

    /** The content digest of an event with its OMISSION parents left out. */
    private static String withoutOmission(Event e, boolean strict) {
        List<Parent> ps = new ArrayList<>();
        for (Parent p : e.parents()) if (p.kind() != EdgeKind.OMISSION) ps.add(p);
        return new Event(e.eventId(), e.seq(), e.timelineId(), e.nodeId(), e.kind(), e.virtualTime(), e.lamport(), e.vector(), ps, e.payload(), e.stateDigestAfter(), e.stateAfter(), e.origin())
                .contentDigest(strict);
    }

    private static List<Root> demoteExplained(List<Root> roots, List<Event> ea, List<Event> eb, EventClass[] ca, EventClass[] cb, int prefix,
                                              List<DivergenceReport.Displaced> displaced, List<Root> demoted) {
        Map<String, Map<String, String>> laneA = new HashMap<>(), laneB = new HashMap<>(); // node -> content key -> first event id
        for (int i = prefix; i < ea.size(); i++) laneA.computeIfAbsent(ea.get(i).nodeId(), k -> new HashMap<>()).putIfAbsent(contentKey(ea.get(i)), ea.get(i).eventId());
        for (int i = prefix; i < eb.size(); i++) laneB.computeIfAbsent(eb.get(i).nodeId(), k -> new HashMap<>()).putIfAbsent(contentKey(eb.get(i)), eb.get(i).eventId());
        List<Root> keep = new ArrayList<>();
        Set<String> eaPrefix = new HashSet<>(), ebPrefix = new HashSet<>();
        for (int i = 0; i < prefix; i++) {
            eaPrefix.add(ea.get(i).eventId());
            ebPrefix.add(eb.get(i).eventId());
        }
        for (Root r : roots) {
            if (r.nodeId().equals(Event.ENV)) {
                keep.add(r);
                continue;
            }
            Set<String> mineIdsInPrefix = r.side() == 'A' ? eaPrefix : ebPrefix;
            List<Event> mine = r.side() == 'A' ? ea : eb;
            EventClass[] myClass = r.side() == 'A' ? ca : cb;
            Event e = mine.get(r.pos());
            String other = (r.side() == 'A' ? laneB : laneA).getOrDefault(r.nodeId(), Map.of()).get(contentKey(e));
            if (other != null && !other.equals(r.eventId())) {
                displaced.add(new DivergenceReport.Displaced(r.side(), r.eventId(), r.nodeId(), other, DivergenceReport.Displaced.RENUMBERED));
                demoted.add(r);
                continue;
            }
            // the event this one replaces in the other timeline (same id) depended on a message or a fault that this timeline does not reproduce
            List<Event> theirs = r.side() == 'A' ? eb : ea;
            Event counterpart = null;
            for (int i = prefix; i < theirs.size(); i++) if (theirs.get(i).eventId().equals(r.eventId())) { counterpart = theirs.get(i); break; }
            if (counterpart != null) {
                String missingCause = null;
                for (Parent par : counterpart.parents()) {
                    if (par.kind() != EdgeKind.MESSAGE && par.kind() != EdgeKind.FAULT && par.kind() != EdgeKind.OMISSION) continue;
                    int at = -1;
                    for (int i = prefix; i < mine.size(); i++) if (mine.get(i).eventId().equals(par.id())) { at = i; break; }
                    boolean inPrefix = at < 0 && mineIdsInPrefix.contains(par.id());
                    if (!inPrefix && (at < 0 || myClass[at] != EventClass.SAME)) { missingCause = par.id(); break; }
                }
                if (missingCause != null) {
                    displaced.add(new DivergenceReport.Displaced(r.side(), r.eventId(), r.nodeId(), missingCause, DivergenceReport.Displaced.ABSENCE));
                    demoted.add(r);
                    continue;
                }
            }
            String dropped = null;
            for (int i = prefix; i < r.pos() && dropped == null; i++) {
                Event d = mine.get(i);
                if (d.kind() != EventKind.MESSAGE_DROPPED || myClass[i] == EventClass.SAME) continue;
                String mid = (String) d.payload().get("messageId");
                for (int j = 0; j < mine.size() && j < i; j++)
                    if (mine.get(j).eventId().equals(mid) && r.nodeId().equals(mine.get(j).payload().get("to"))) { dropped = mid; break; }
            }
            if (dropped != null && (e.kind() == EventKind.TIMER_FIRED || e.kind() == EventKind.MESSAGE_DELIVERED)) {
                displaced.add(new DivergenceReport.Displaced(r.side(), r.eventId(), r.nodeId(), dropped, DivergenceReport.Displaced.SILENCE));
                demoted.add(r);
            } else {
                keep.add(r);
            }
        }
        return keep;
    }

    /**
     * A root whose counterpart in the other timeline (same id) depended on a message or fault that exists here too, structurally the same, but at a
     * different time (the digest ignores timing in structural mode): the race between that event and this one was decided the other way. It is
     * explained, as a potential link, when the shifted event is itself downstream of another root or of a displaced event of this timeline (DEV-47).
     * The same holds when the event it replaces happened here later, under another id, or when what became of the message it handled (delayed, dropped)
     * is recorded here downstream of another root.
     */
    private static List<Root> demoteShiftedCauses(List<Root> roots, List<Event> ea, List<Event> eb, CausalGraph ga, CausalGraph gb,
                                                  int prefix, List<DivergenceReport.Displaced> displaced, List<Root> demoted) {
        List<Root> keep = new ArrayList<>(roots);
        for (Root r : roots) {
            if (r.nodeId().equals(Event.ENV)) continue;
            List<Event> mine = r.side() == 'A' ? ea : eb, theirs = r.side() == 'A' ? eb : ea;
            CausalGraph g = r.side() == 'A' ? ga : gb;
            Event counterpart = null;
            for (int i = prefix; i < theirs.size(); i++) if (theirs.get(i).eventId().equals(r.eventId())) { counterpart = theirs.get(i); break; }
            if (counterpart == null) continue;
            List<Integer> explainers = new ArrayList<>();
            for (Root k : keep) if (k != r && k.side() == r.side()) explainers.add(k.pos());
            for (Root d : demoted) if (d.side() == r.side()) explainers.add(d.pos());
            BitSet future = null;
            String shifted = null;
            // the event it replaces happened here too, later (a delayed delivery, a trigger that fired after the race): as an event of the same
            // content on this node's lane that is downstream of another root
            String key = contentKey(counterpart);
            Object handled = counterpart.payload().get("messageId"); // the message the replaced event handled, if any
            for (int i = prefix; i < mine.size() && shifted == null; i++) {
                Event m = mine.get(i);
                if (m.eventId().equals(r.eventId())) continue;
                boolean sameContent = m.nodeId().equals(r.nodeId()) && contentKey(m).equals(key);
                boolean sameMessage = handled != null && handled.equals(m.payload().get("messageId")); // what became of that message here: delayed, dropped, duplicated
                if (!sameContent && !sameMessage) continue;
                if (future == null) future = g.causalFutureOf(explainers);
                if (future.get(i)) shifted = m.eventId();
            }
            for (Parent par : counterpart.parents()) {
                if (shifted != null) break;
                if (par.kind() != EdgeKind.MESSAGE && par.kind() != EdgeKind.FAULT && par.kind() != EdgeKind.OMISSION) continue;
                Event here = null, there = null;
                int at = -1;
                for (int i = prefix; i < mine.size(); i++) if (mine.get(i).eventId().equals(par.id())) { here = mine.get(i); at = i; break; }
                for (int i = prefix; i < theirs.size(); i++) if (theirs.get(i).eventId().equals(par.id())) { there = theirs.get(i); break; }
                if (here == null || there == null || here.contentDigest(true).equals(there.contentDigest(true))) continue;
                if (future == null) future = g.causalFutureOf(explainers);
                if (future.get(at)) { shifted = par.id(); break; }
            }
            if (shifted != null) {
                displaced.add(new DivergenceReport.Displaced(r.side(), r.eventId(), r.nodeId(), shifted, DivergenceReport.Displaced.TIMING));
                demoted.add(r);
                keep.remove(r);
            }
        }
        return keep;
    }

    private static void collectRoots(char side, List<Event> es, CausalGraph g, EventClass[] cls, int prefix, List<Root> roots, BitSet inD) {
        boolean[] tainted = new boolean[es.size()];
        for (int pos = prefix; pos < es.size(); pos++) {
            boolean d = cls[pos] != EventClass.SAME;
            boolean parentTainted = false;
            for (int i = 0; i < g.parentCount(pos); i++) if (tainted[g.parent(pos, i)]) parentTainted = true;
            tainted[pos] = d || parentTainted;
            if (d) {
                inD.set(pos);
                if (!parentTainted) {
                    Event e = es.get(pos);
                    roots.add(new Root(side, e.eventId(), e.nodeId(), e.virtualTime(), cls[pos], pos));
                }
            }
        }
    }

    /** Orders roots by (virtual time, node order with env last, intervention events first, event id); within one timeline a root that happened before another comes first. */
    private static List<Root> orderRoots(List<Root> roots, List<String> nodeOrder, CausalGraph ga, CausalGraph gb) {
        Comparator<Root> key = Comparator.comparingLong(Root::virtualTime)
                .thenComparingInt(r -> r.eventId().startsWith("iv:") ? 0 : 1) // DEV-36: at equal time the intervention's own event is first (M10): it is applied at the branch point, before the step that follows
                .thenComparingInt(r -> nodeOrder.indexOf(r.nodeId()))
                .thenComparing(Root::eventId)
                .thenComparing(Root::side);
        List<Root> remaining = new ArrayList<>(roots);
        List<Root> out = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Root best = null;
            for (Root r : remaining) {
                boolean blocked = false;
                CausalGraph g = r.side() == 'A' ? ga : gb;
                for (Root o : remaining) if (o != r && o.side() == r.side() && g.happensBefore(o.pos(), r.pos())) { blocked = true; break; }
                if (!blocked && (best == null || key.compare(r, best) < 0)) best = r;
            }
            out.add(best);
            remaining.remove(best);
        }
        return out;
    }

    private static String timelineLabel(String tl, Event e) {
        return e.eventId() + "@" + tl;
    }

    private static int firstViolation(List<Event> es) {
        for (Event e : es) if (e.kind() == EventKind.INVARIANT_VIOLATED) return (int) e.seq();
        return -1;
    }

    private static String lastStateDigest(List<Event> es, String node) {
        for (int i = es.size() - 1; i >= 0; i--) if (es.get(i).nodeId().equals(node) && es.get(i).stateDigestAfter() != null) return es.get(i).stateDigestAfter();
        return null;
    }

    private String outcome(String id, List<Event> es) {
        TimelineSummary s = log.summary(id);
        int v = firstViolation(es);
        return (s == null ? "?" : s.status().name()) + (v >= 0 ? " (" + es.get(v).eventId() + " " + es.get(v).payload().get("invariant") + ")" : ", invariants held");
    }
}
