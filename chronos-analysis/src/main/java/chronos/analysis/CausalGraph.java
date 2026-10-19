package chronos.analysis;

import chronos.model.*;

import java.util.*;

/**
 * Causal Graph Engine (C7, D-14): a directed acyclic graph with one vertex per event and one edge per causal link, on dense
 * integer ids (the log position) and compact primitive arrays. It is acyclic by construction: every parent has a smaller
 * position than its child, so the log order is already a topological order (Section 13).
 *
 * It can be built incrementally while recording ({@link #append}, used by {@link GraphSink}) or in one pass over a stored log.
 * Happens-before is answered by vector clocks in time proportional to the node count; the graph reachability search is the
 * independent second answer used by {@link #selfCheck} (FR-15, laws M3 and M4).
 */
public final class CausalGraph {
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Integer> idToPos = new HashMap<>();
    private int[] parentStart = new int[1024];
    private int[] parentTo = new int[4096];
    private byte[] parentKind = new byte[4096];
    private int edgeCount;
    private int[] childStart;
    private int[] childTo;
    private byte[] childKind;

    public static CausalGraph build(List<Event> events) {
        CausalGraph g = new CausalGraph();
        for (Event e : events) g.append(e);
        return g;
    }

    /** Appends the next event. Its position must be the next dense id; every parent must already be in the graph. */
    public void append(Event e) {
        int pos = events.size();
        if (e.seq() != pos) throw new IllegalArgumentException("event " + e.eventId() + " has position " + e.seq() + " but would be vertex " + pos);
        if (idToPos.putIfAbsent(e.eventId(), pos) != null) throw new CorruptLogException("duplicate event id " + e.eventId());
        if (parentStart.length <= pos + 1) parentStart = Arrays.copyOf(parentStart, Math.max(parentStart.length * 2, pos + 2));
        parentStart[pos] = edgeCount;
        for (Parent p : e.parents()) {
            Integer pp = idToPos.get(p.id());
            if (pp == null || pp >= pos)
                throw new CorruptLogException("event " + e.eventId() + " has parent " + p.id() + " that does not precede it in the log");
            if (edgeCount == parentTo.length) {
                parentTo = Arrays.copyOf(parentTo, edgeCount * 2);
                parentKind = Arrays.copyOf(parentKind, edgeCount * 2);
            }
            parentTo[edgeCount] = pp;
            parentKind[edgeCount] = (byte) p.kind().ordinal();
            edgeCount++;
        }
        parentStart[pos + 1] = edgeCount;
        events.add(e);
        childStart = null;
    }

    public int size() {
        return events.size();
    }

    public int edgeCount() {
        return edgeCount;
    }

    public Event event(int pos) {
        return events.get(pos);
    }

    public List<Event> events() {
        return Collections.unmodifiableList(events);
    }

    /** Dense position of an event id, or -1. */
    public int pos(String id) {
        return idToPos.getOrDefault(id, -1);
    }

    // ----- edges -----

    public int parentCount(int v) {
        return parentStart[v + 1] - parentStart[v];
    }

    public int parent(int v, int i) {
        return parentTo[parentStart[v] + i];
    }

    public EdgeKind parentKind(int v, int i) {
        return EdgeKind.values()[parentKind[parentStart[v] + i]];
    }

    private void buildChildren() {
        if (childStart != null) return;
        int n = events.size();
        int[] count = new int[n + 1];
        for (int e = 0; e < edgeCount; e++) count[parentTo[e] + 1]++;
        for (int i = 0; i < n; i++) count[i + 1] += count[i];
        childStart = count.clone();
        childTo = new int[edgeCount];
        childKind = new byte[edgeCount];
        int[] fill = Arrays.copyOf(count, n);
        for (int v = 0; v < n; v++)
            for (int e = parentStart[v]; e < parentStart[v + 1]; e++) {
                int p = parentTo[e];
                childTo[fill[p]] = v;
                childKind[fill[p]] = parentKind[e];
                fill[p]++;
            }
    }

    public int childCount(int v) {
        buildChildren();
        return childStart[v + 1] - childStart[v];
    }

    public int child(int v, int i) {
        buildChildren();
        return childTo[childStart[v] + i];
    }

    public EdgeKind childKind(int v, int i) {
        buildChildren();
        return EdgeKind.values()[childKind[childStart[v] + i]];
    }

    // ----- queries (Section 13) -----

    /** Could a have influenced b? Compares vector clocks: cost proportional to the node count. */
    public boolean happensBefore(int a, int b) {
        return a != b && VectorClock.compare(events.get(a).vector(), events.get(b).vector()) == VectorClock.Order.BEFORE;
    }

    /** Neither could influence the other: both happens-before checks fail. */
    public boolean concurrent(int a, int b) {
        return a != b && VectorClock.compare(events.get(a).vector(), events.get(b).vector()) == VectorClock.Order.CONCURRENT;
    }

    /** All suspects for e: everything that could have influenced it (walk parents backwards, all edges). Sorted by position. */
    public int[] causalPast(int e) {
        BitSet seen = new BitSet(events.size());
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(e);
        seen.set(e);
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int i = parentStart[v]; i < parentStart[v + 1]; i++) {
                int p = parentTo[i];
                if (!seen.get(p)) {
                    seen.set(p);
                    stack.push(p);
                }
            }
        }
        seen.clear(e);
        return seen.stream().toArray();
    }

    /** Everything e could have influenced (walk the children index forwards). Sorted by position. */
    public int[] causalFuture(int e) {
        buildChildren();
        BitSet seen = new BitSet(events.size());
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(e);
        seen.set(e);
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int i = childStart[v]; i < childStart[v + 1]; i++) {
                int c = childTo[i];
                if (!seen.get(c)) {
                    seen.set(c);
                    stack.push(c);
                }
            }
        }
        seen.clear(e);
        return seen.stream().toArray();
    }

    /** Causal future of several vertices at once (used for the divergence cone). */
    public BitSet causalFutureOf(Collection<Integer> roots) {
        buildChildren();
        BitSet seen = new BitSet(events.size());
        Deque<Integer> stack = new ArrayDeque<>();
        for (int r : roots) {
            seen.set(r);
            stack.push(r);
        }
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int i = childStart[v]; i < childStart[v + 1]; i++) {
                int c = childTo[i];
                if (!seen.get(c)) {
                    seen.set(c);
                    stack.push(c);
                }
            }
        }
        return seen;
    }

    /**
     * A chain explaining how a led to b: the cheapest path of causal links from a to b inside the past of b, where certain
     * and OMISSION edges cost 1 and other potential edges cost 2 (Section 13, Section 20 step 8). Empty if a cannot reach b.
     */
    public Optional<List<Integer>> path(int a, int b) {
        return path(a, b, e -> true);
    }

    /** Like {@link #path(int, int)} but restricted to edges accepted by the filter. */
    public Optional<List<Integer>> path(int a, int b, java.util.function.Predicate<EdgeKind> allowed) {
        if (a > b) return Optional.empty();
        if (a == b) return Optional.of(List.of(a));
        int n = b - a + 1;
        int[] dist = new int[n];
        int[] po = new int[n];   // tie-break: how many PROGRAM_ORDER hops the cheapest path uses (fewer = more "because-of")
        int[] prev = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[0] = 0;
        for (int v = a + 1; v <= b; v++) {
            int best = Integer.MAX_VALUE, bestPo = Integer.MAX_VALUE, bp = -1;
            for (int i = parentStart[v]; i < parentStart[v + 1]; i++) {
                int p = parentTo[i];
                if (p < a) continue;
                EdgeKind k = EdgeKind.values()[parentKind[i]];
                if (!allowed.test(k)) continue;
                int dp = dist[p - a];
                if (dp == Integer.MAX_VALUE) continue;
                int d = dp + k.cost();
                int pc = po[p - a] + (k == EdgeKind.PROGRAM_ORDER ? 1 : 0);
                // cheapest first; at equal cost prefer the path with fewer program-order hops, then the earlier parent
                if (d < best || (d == best && (pc < bestPo || (pc == bestPo && p < bp)))) {
                    best = d;
                    bestPo = pc;
                    bp = p;
                }
            }
            if (bp >= 0) {
                dist[v - a] = best;
                po[v - a] = bestPo;
                prev[v - a] = bp;
            }
        }
        if (dist[n - 1] == Integer.MAX_VALUE) return Optional.empty();
        LinkedList<Integer> out = new LinkedList<>();
        for (int v = b; v != -1; v = (v == a) ? -1 : prev[v - a]) out.addFirst(v);
        return Optional.of(out);
    }

    /** Total cost of a path under the Section 20 rule (certain and OMISSION edges 1, other potential edges 2). */
    public int pathCost(List<Integer> path) {
        int c = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            EdgeKind k = edgeBetween(path.get(i), path.get(i + 1));
            c += k == null ? 0 : k.cost();
        }
        return c;
    }

    /** The kind of the cheapest edge from parent to child, or null if there is none. */
    public EdgeKind edgeBetween(int parent, int child) {
        EdgeKind best = null;
        for (int i = parentStart[child]; i < parentStart[child + 1]; i++)
            if (parentTo[i] == parent) {
                EdgeKind k = EdgeKind.values()[parentKind[i]];
                if (best == null || k.cost() < best.cost()) best = k;
            }
        return best;
    }

    /**
     * The smallest consistent cut containing the given events: their causal past together with themselves. A cut is a set of
     * events that could have been observed as one global snapshot, because nothing in it depends on anything outside it.
     */
    public BitSet minimalCut(Collection<Integer> members) {
        BitSet cut = new BitSet(events.size());
        Deque<Integer> stack = new ArrayDeque<>();
        for (int m : members) if (!cut.get(m)) { cut.set(m); stack.push(m); }
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (int i = parentStart[v]; i < parentStart[v + 1]; i++) {
                int p = parentTo[i];
                if (!cut.get(p)) { cut.set(p); stack.push(p); }
            }
        }
        return cut;
    }

    /** A cut is consistent when it is closed under "caused by": no member has a parent outside the cut. */
    public boolean isConsistentCut(BitSet cut) {
        return inconsistencies(cut, 1).isEmpty();
    }

    /** Up to {@code limit} pairs {member, missing parent} that make a cut inconsistent (a received message whose send is outside, ...). */
    public List<int[]> inconsistencies(BitSet cut, int limit) {
        List<int[]> out = new ArrayList<>();
        for (int v = cut.nextSetBit(0); v >= 0 && out.size() < limit; v = cut.nextSetBit(v + 1))
            for (int i = parentStart[v]; i < parentStart[v + 1] && out.size() < limit; i++)
                if (!cut.get(parentTo[i])) out.add(new int[]{v, parentTo[i]});
        return out;
    }

    /** The cut that contains, for each named node, its events up to and including the given position (a "frontier" of local prefixes). */
    public BitSet cutFromFrontier(Map<String, Integer> lastPositionPerNode) {
        BitSet cut = new BitSet(events.size());
        for (Map.Entry<String, Integer> en : lastPositionPerNode.entrySet())
            for (int p : lane(en.getKey())) if (p <= en.getValue()) cut.set(p);
        return cut;
    }

    /** For each node (and env), the position of its last event in the cut; nodes with no event in the cut are absent. */
    public Map<String, Integer> frontier(BitSet cut) {
        Map<String, Integer> m = new TreeMap<>();
        for (int v = cut.nextSetBit(0); v >= 0; v = cut.nextSetBit(v + 1)) m.put(events.get(v).nodeId(), v);
        return m;
    }

    /** Positions of one node's events, in order. */
    public int[] lane(String node) {
        return events.stream().filter(e -> e.nodeId().equals(node)).mapToInt(e -> (int) e.seq()).toArray();
    }

    /**
     * The built-in self-check (FR-15): happens-before by vector clocks must equal reachability in the graph, and every edge
     * must point to an earlier position (law M4). Checks all pairs when the graph is small, otherwise a deterministic sample.
     * Returns descriptions of every disagreement; empty means the clocks, the graph and the recorder agree.
     */
    public List<String> selfCheck(int maxFullCheck) {
        List<String> problems = new ArrayList<>();
        int n = events.size();
        for (int v = 0; v < n; v++)
            for (int i = parentStart[v]; i < parentStart[v + 1]; i++)
                if (parentTo[i] >= v) problems.add("edge into " + events.get(v).eventId() + " points forward");
        int[] targets;
        if (n <= maxFullCheck) {
            targets = new int[n];
            for (int i = 0; i < n; i++) targets[i] = i;
        } else {
            SeededRandom r = new SeededRandom(0xC40A05L);
            targets = new int[maxFullCheck];
            for (int i = 0; i < targets.length; i++) targets[i] = (int) Math.floorMod(r.nextLong(), (long) n);
        }
        for (int b : targets) {
            BitSet past = new BitSet(n);
            for (int a : causalPast(b)) past.set(a);
            for (int a = 0; a < n; a++) {
                if (a == b) continue;
                boolean byVector = happensBefore(a, b);
                boolean byGraph = past.get(a);
                if (byVector != byGraph)
                    problems.add("vector clocks say " + events.get(a).eventId() + (byVector ? " happened before " : " did not happen before ")
                            + events.get(b).eventId() + " but graph reachability says " + (byGraph ? "it can reach it" : "it cannot"));
            }
            Event eb = events.get(b);
            for (int i = parentStart[b]; i < parentStart[b + 1]; i++) {
                Event pa = events.get(parentTo[i]);
                if (!(pa.lamport() < eb.lamport()))
                    problems.add("Lamport rule broken: " + pa.eventId() + " -> " + eb.eventId());
            }
        }
        return problems;
    }
}
