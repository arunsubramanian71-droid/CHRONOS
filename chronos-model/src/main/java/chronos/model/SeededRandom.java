package chronos.model;

/**
 * CHRONOS's own random generator (FR-05, D-06): SplitMix64. Its entire state is one long, so it can be snapshotted and is
 * identical on every JVM, unlike the JDK generator which hides its state.
 */
public final class SeededRandom {
    private static final long GAMMA = 0x9E3779B97F4A7C15L;
    private long state;

    public SeededRandom(long state) {
        this.state = state;
    }

    public long nextLong() {
        state += GAMMA;
        return mix(state);
    }

    public long state() {
        return state;
    }

    /** Pure step: the value drawn from a generator whose state is {@code state}, and nothing else. */
    public static long valueAt(long stateBefore) {
        return mix(stateBefore + GAMMA);
    }

    public static long advance(long stateBefore) {
        return stateBefore + GAMMA;
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
