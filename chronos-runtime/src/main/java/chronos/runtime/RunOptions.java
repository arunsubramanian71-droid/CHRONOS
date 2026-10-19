package chronos.runtime;

import chronos.sdk.Scenario;

/**
 * Options of one run. {@code checkpointInterval} is K of Section 14 (a checkpoint every K steps; 0 disables periodic
 * checkpoints; one is always written at the end of a run).
 */
public record RunOptions(long horizon, boolean stopOnViolation, long checkpointInterval) {
    public static final long DEFAULT_CHECKPOINT_INTERVAL = 1000;

    public static RunOptions of(Scenario s) {
        return new RunOptions(s.defaultHorizon(), s.stopOnViolation(), DEFAULT_CHECKPOINT_INTERVAL);
    }

    public RunOptions withHorizon(long h) {
        return new RunOptions(h, stopOnViolation, checkpointInterval);
    }

    public RunOptions withCheckpointInterval(long k) {
        return new RunOptions(horizon, stopOnViolation, k);
    }

    public RunOptions withStopOnViolation(boolean b) {
        return new RunOptions(horizon, b, checkpointInterval);
    }
}
