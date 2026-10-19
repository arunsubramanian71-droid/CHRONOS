package chronos.model;

import java.util.List;

/** Vector clock comparison (Section 11). */
public final class VectorClock {
    private VectorClock() {}

    public enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }

    /** BEFORE: every slot of a is <= b and they are not equal. CONCURRENT: neither is below the other. */
    public static Order compare(List<Integer> a, List<Integer> b) {
        if (a.size() != b.size()) throw new IllegalArgumentException("vector sizes differ: " + a.size() + " vs " + b.size());
        boolean less = false, greater = false;
        for (int i = 0; i < a.size(); i++) {
            int x = a.get(i), y = b.get(i);
            if (x < y) less = true;
            else if (x > y) greater = true;
        }
        if (less && greater) return Order.CONCURRENT;
        if (less) return Order.BEFORE;
        if (greater) return Order.AFTER;
        return Order.EQUAL;
    }

    public static boolean happenedBefore(List<Integer> a, List<Integer> b) {
        return compare(a, b) == Order.BEFORE;
    }

    public static boolean concurrent(List<Integer> a, List<Integer> b) {
        return compare(a, b) == Order.CONCURRENT;
    }

    public static int[] merge(int[] a, int[] b) {
        int[] r = new int[a.length];
        for (int i = 0; i < r.length; i++) r[i] = Math.max(a[i], b[i]);
        return r;
    }
}
