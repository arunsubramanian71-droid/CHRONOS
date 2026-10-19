package chronos.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/** The set of pending items, ordered by the stable key. Iteration for snapshots always goes through the sorted view. */
public final class Agenda {
    private final PriorityQueue<AgendaItem> queue = new PriorityQueue<>();

    public void add(AgendaItem item) {
        queue.add(item);
    }

    public AgendaItem peek() {
        return queue.peek();
    }

    public AgendaItem poll() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public boolean removeIf(Predicate<AgendaItem> p) {
        return queue.removeIf(p);
    }

    public AgendaItem find(Predicate<AgendaItem> p) {
        List<AgendaItem> sorted = sorted();
        for (AgendaItem i : sorted) if (p.test(i)) return i;
        return null;
    }

    public List<AgendaItem> sorted() {
        List<AgendaItem> l = new ArrayList<>(queue);
        l.sort(null);
        return l;
    }
}
