package chronos.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Section 11 worked example, with N + 1 = 4 slots [A,B,C,env]: the fixed tests for the Lamport and vector rules. */
class ClockServiceTest {
    @Test
    void worldExampleFromSection11() {
        ClockService cs = new ClockService(3); // A=0, B=1, C=2, env=3
        Stamp a1 = cs.stamp(0, List.of());
        Stamp a2 = cs.stamp(0, List.of());               // A sends m1
        Stamp c1 = cs.stamp(2, List.of());
        Stamp b1 = cs.stamp(1, List.of(a2));             // B receives m1
        Stamp b2 = cs.stamp(1, List.of());               // B sends m2
        Stamp c2 = cs.stamp(2, List.of(b2));             // C receives m2

        assertEquals(new Stamp(1, List.of(1, 0, 0, 0)), a1);
        assertEquals(new Stamp(2, List.of(2, 0, 0, 0)), a2);
        assertEquals(new Stamp(1, List.of(0, 0, 1, 0)), c1);
        assertEquals(new Stamp(3, List.of(2, 1, 0, 0)), b1);
        assertEquals(new Stamp(4, List.of(2, 2, 0, 0)), b2);
        assertEquals(new Stamp(5, List.of(2, 2, 2, 0)), c2);

        // the questions the vectors answer exactly
        assertEquals(VectorClock.Order.BEFORE, VectorClock.compare(a1.vector(), c2.vector()));
        assertEquals(VectorClock.Order.CONCURRENT, VectorClock.compare(a2.vector(), c1.vector()));
        // Lamport misleads: c1 has the smaller number (1 < 2) although it is concurrent with a2
        assertTrue(c1.lamport() < a2.lamport());
    }

    @Test
    void lamportMisleadsWithoutMessages() {
        ClockService cs = new ClockService(2);
        Stamp a = cs.stamp(0, List.of());
        cs.stamp(1, List.of());
        cs.stamp(1, List.of());
        Stamp c3 = cs.stamp(1, List.of());
        assertTrue(a.lamport() < c3.lamport());
        assertEquals(VectorClock.Order.CONCURRENT, VectorClock.compare(a.vector(), c3.vector()));
    }

    @Test
    void envFollowsTheSameRulesAndMergesEveryIncomingEdge() {
        ClockService cs = new ClockService(2); // slots A, B, env
        Stamp w1 = cs.stamp(0, List.of());
        Stamp w2 = cs.stamp(1, List.of());
        Stamp viol = cs.stamp(2, List.of(w1, w2)); // a violation merges all witnesses before incrementing its own slot
        assertEquals(List.of(1, 1, 1), viol.vector());
        assertEquals(2, viol.lamport());
        assertEquals(VectorClock.Order.BEFORE, VectorClock.compare(w1.vector(), viol.vector()));
        assertEquals(VectorClock.Order.BEFORE, VectorClock.compare(w2.vector(), viol.vector()));
    }

    @Test
    void mergeIsCommutativeAssociativeIdempotent() {
        SeededRandom r = new SeededRandom(7);
        for (int i = 0; i < 200; i++) {
            int[] a = new int[4], b = new int[4], c = new int[4];
            for (int j = 0; j < 4; j++) {
                a[j] = (int) Math.floorMod(r.nextLong(), 10);
                b[j] = (int) Math.floorMod(r.nextLong(), 10);
                c[j] = (int) Math.floorMod(r.nextLong(), 10);
            }
            assertArrayEquals(VectorClock.merge(a, b), VectorClock.merge(b, a));
            assertArrayEquals(VectorClock.merge(VectorClock.merge(a, b), c), VectorClock.merge(a, VectorClock.merge(b, c)));
            assertArrayEquals(a, VectorClock.merge(a, a));
        }
    }

    @Test
    void compareRefusesDifferentSizes() {
        assertThrows(IllegalArgumentException.class, () -> VectorClock.compare(List.of(1, 2), List.of(1, 2, 3)));
    }

    @Test
    void equalVectorsCompareEqual() {
        assertEquals(VectorClock.Order.EQUAL, VectorClock.compare(List.of(1, 2), List.of(1, 2)));
        assertEquals(VectorClock.Order.AFTER, VectorClock.compare(List.of(2, 2), List.of(1, 2)));
    }

    @Test
    void snapshotRestoreRoundTrip() {
        ClockService cs = new ClockService(2);
        cs.stamp(0, List.of());
        cs.stamp(1, List.of(cs.current(0)));
        ClockService other = new ClockService(2);
        other.restore(cs.snapshot());
        assertEquals(cs.current(0), other.current(0));
        assertEquals(cs.current(1), other.current(1));
        assertEquals(cs.stamp(0, List.of()), other.stamp(0, List.of()));
    }
}
