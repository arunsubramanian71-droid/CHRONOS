package chronos.model;

import java.nio.charset.StandardCharsets;

/**
 * Identity-keyed decisions (D-06): the answer to "what latency does message p#17 get?" is a function of who is asking
 * (seed and identity), never of how many other questions were asked before. This removes "butterfly noise" between timelines.
 */
public final class IdentityHash {
    private IdentityHash() {}

    public static long of(long seed, String... parts) {
        long h = SeededRandom.mix(seed);
        for (String p : parts) {
            long f = 0xCBF29CE484222325L;
            for (byte b : p.getBytes(StandardCharsets.UTF_8)) {
                f ^= (b & 0xFF);
                f *= 0x100000001B3L;
            }
            h = SeededRandom.mix(h ^ f);
        }
        return h;
    }

    /** Non-negative bounded value derived from identity. */
    public static long bounded(long seed, long bound, String... parts) {
        return Math.floorMod(of(seed, parts), bound);
    }
}
