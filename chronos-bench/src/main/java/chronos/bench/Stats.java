package chronos.bench;

import java.util.Arrays;

/** Honest statistics (Section 26): at least five repetitions, the first discarded for warm-up; median, 95th and 99th percentile. */
final class Stats {
    final double median, p95, p99, min, max;
    final int n;

    Stats(double[] samples) {
        double[] s = samples.clone();
        Arrays.sort(s);
        n = s.length;
        min = s[0];
        max = s[n - 1];
        median = pct(s, 50);
        p95 = pct(s, 95);
        p99 = pct(s, 99);
    }

    private static double pct(double[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    /** Runs a measurement reps times (the first is discarded as warm-up) and returns the statistics of the rest. */
    static Stats measure(int reps, java.util.function.DoubleSupplier once) {
        double[] v = new double[reps];
        for (int i = 0; i < reps; i++) v[i] = once.getAsDouble();
        return new Stats(Arrays.copyOfRange(v, 1, reps));
    }
}
