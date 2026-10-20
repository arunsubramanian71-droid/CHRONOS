package chronos.analysis;

import chronos.model.*;
import chronos.runtime.*;
import chronos.sdk.CodeFingerprint;
import chronos.sdk.Scenario;
import chronos.sdk.ScenarioRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Replay Engine (C9, Section 15): re-executes the application under the recorded decisions and compares what it produces
 * with the recording, event by event. It installs the replay decision source in the ordinary engine (D-19), so replay
 * correctness is a property of one engine, not of a second code path.
 *
 *   R1 verification replay from the start   {@link #verify}
 *   R2 resumed replay from a checkpoint     {@link #verifyFrom}
 *   R3 stepped replay with breakpoints      {@link #runUntil}
 *   R4 prefix replay for branching          {@link #buildAt}
 *
 * A branch is replayed by walking its lineage: the engine reproduces the root timeline and, at every branch point, applies
 * that branch's interventions (which are verified like any other events) before going on (Section 17).
 */
public final class ReplayEngine {
    private final LogReader log;
    private final ScenarioRegistry registry;

    public ReplayEngine(LogReader log, ScenarioRegistry registry) {
        this.log = log;
        this.registry = registry;
    }

    /** Outcome of a verification replay. */
    public record Result(boolean ok, long eventsVerified, long startSeq, String logHash, DeterminismViolation violation, List<String> warnings) {
        public String summary() {
            return ok ? "REPLAY VERIFIED: " + eventsVerified + " events matched (from position " + startSeq + "), log hash " + logHash
                    : violation.report();
        }
    }

    /** A live world paused at a step boundary after a replay (R3, R4). */
    public record PausedWorld(Engine engine, long position, Event hit, long verified, List<String> warnings, Scenario scenario, TimelineHeader header) {}

    /** R1: replay the whole timeline from the start and verify every event. */
    public Result verify(String timelineId) {
        return verifyFrom(timelineId, null);
    }

    /** R2: replay from the checkpoint at (or the latest one before) a position; null starts from the beginning. */
    public Result verifyFrom(String timelineId, Long checkpointSeq) {
        WorldImage start = null;
        if (checkpointSeq != null) {
            start = log.checkpointAtOrBefore(timelineId, checkpointSeq).orElseThrow(() -> new IllegalArgumentException("no checkpoint at or before " + checkpointSeq + " in " + timelineId));
        }
        Replay r = new Replay(timelineId, -1, start, null);
        DeterminismViolation v = r.run();
        List<Event> rec = log.events(timelineId);
        if (v == null) {
            long expectedEnd = rec.size();
            if (r.engine.nextSeq() < expectedEnd) {
                Event next = rec.get((int) r.engine.nextSeq());
                v = new DeterminismViolation("replay ended after " + r.engine.nextSeq() + " events but the recording has more (next recorded event " + next.eventId() + ")",
                        next, null, r.sink.lastMatching(), List.of("replay produced no event at position " + next.seq()));
            } else {
                // the original run may have gone on stepping through agenda items that recorded nothing (for example a restart rule for
                // a node that was not crashed); run the replay to the same end so the end state is comparable. Any event such a
                // trailing step produces is a genuine mismatch: the recording has already ended.
                try {
                    while (r.engine.step()) { /* silent steps only */ }
                } catch (DeterminismViolation dv) {
                    v = dv;
                }
            }
            if (v == null) {
                TimelineSummary sum = log.summary(timelineId);
                if (sum != null && !r.engine.endStateDigest().equals(sum.endStateDigest()))
                    v = new DeterminismViolation("end-state digest differs from the recorded summary", null, null, r.sink.lastMatching(),
                            List.of("endStateDigest: expected " + sum.endStateDigest() + " got " + r.engine.endStateDigest()));
            }
        }
        return new Result(v == null, r.sink.verified(), start == null ? 0 : start.seq() + 1, LogHash.of(rec), v, r.warnings);
    }

    /** R4: a live world at step boundary p of a timeline, reached by prefix replay (verified as it goes). */
    public PausedWorld buildAt(String timelineId, long p) {
        if (!log.groupEnds(timelineId).contains(p)) throw new IllegalArgumentException("position " + p + " of " + timelineId + " is not a step boundary");
        WorldImage start = log.boundaryCheckpointAtOrBefore(timelineId, p).orElse(null);
        Replay r = new Replay(timelineId, p, start, null);
        DeterminismViolation v = r.run();
        if (v != null) throw v;
        return new PausedWorld(r.engine, r.engine.nextSeq() - 1, null, r.sink.verified(), r.warnings, r.scenario, r.root);
    }

    /** R3: stepped replay: runs (verifying) until an event satisfies the breakpoint, then pauses the live world. */
    public PausedWorld runUntil(String timelineId, Predicate<Event> breakpoint) {
        Replay r = new Replay(timelineId, -1, null, breakpoint);
        DeterminismViolation v = r.run();
        if (v != null) throw v;
        return new PausedWorld(r.engine, r.engine.nextSeq() - 1, r.sink.hit(), r.sink.verified(), r.warnings, r.scenario, r.root);
    }

    // ------------------------------------------------------------------------------------------------------------

    private final class Replay {
        final String timelineId;
        final List<Event> rec;
        final List<TimelineHeader> lineage = new ArrayList<>();
        final TimelineHeader root;
        final Scenario scenario;
        final VerifyingSink sink;
        final Engine engine;
        final long target;
        final List<String> warnings = new ArrayList<>();
        int ci;           // next branch of the lineage whose interventions are still to be applied
        boolean booted;

        Replay(String tl, long upTo, WorldImage start, Predicate<Event> breakpoint) {
            this.timelineId = tl;
            this.rec = log.events(tl);
            this.target = upTo < 0 ? rec.size() - 1 : upTo;
            for (TimelineHeader h = log.header(tl); h != null; h = h.parentId() == null ? null : log.header(h.parentId())) lineage.add(0, h);
            this.root = lineage.get(0);
            this.scenario = registry.create(root.scenario(), root.params());
            if (!scenario.version().equals(root.codeVersion()))
                warnings.add("declared code version differs: recorded " + root.codeVersion() + ", current " + scenario.version());
            else if (root.codeFingerprint() != null && !CodeFingerprint.of(scenario).equals(root.codeFingerprint()))
                warnings.add("compiled class fingerprint differs from the recording: replaying against changed code");
            List<String> names = scenario.nodes().stream().map(n -> n.name()).toList();
            if (!names.equals(root.nodes())) throw new IllegalStateException("scenario " + root.scenario() + " declares nodes " + names + " but the recording has " + root.nodes());
            DecisionSource ds = new ReplayDecisionSource(rec, new RecordDecisionSource(scenario, root.seed()));
            this.sink = new VerifyingSink(rec, breakpoint);
            long startSeq = start == null ? 0 : start.seq() + 1;
            Recorder recorder = new Recorder(tl, startSeq, List.of(sink));
            recorder.setHashing(false);
            // horizon of whichever timeline of the lineage is in force at the starting position
            TimelineHeader inForce = root;
            long from = start == null ? -1 : start.seq();
            for (int i = 1; i < lineage.size(); i++) if (lineage.get(i).branchSeq() < from) inForce = lineage.get(i);
            RunOptions opts = new RunOptions(inForce.horizon(), inForce.stopOnViolation(), 0);
            if (start == null) {
                engine = Engine.create(scenario, root.seed(), root.faultPlan(), opts, ds, recorder);
                ci = 1;
            } else {
                engine = Engine.restore(scenario, start, ds, recorder, opts);
                if (upTo < 0) engine.resumeStopState(start); // a full verification must end where the original run ended
                booted = true;
                ci = 1;
                while (ci < lineage.size() && lineage.get(ci).branchSeq() < start.seq()) ci++;
            }
        }

        /** Runs the replay; returns the determinism violation or null. */
        DeterminismViolation run() {
            try {
                if (!booted) {
                    engine.boot();
                    booted = true;
                }
                while (true) {
                    while (ci < lineage.size() && engine.nextSeq() == lineage.get(ci).branchSeq() + 1 && target > lineage.get(ci).branchSeq()) {
                        TimelineHeader b = lineage.get(ci++);
                        // a branch made at the same position as its own branch point has none of that parent's own events in its prefix,
                        // so the parent's interventions (recorded after that position) are not part of its history
                        if (ci < lineage.size() && lineage.get(ci).branchSeq() == b.branchSeq()) continue;
                        engine.setOptions(new RunOptions(b.horizon(), b.stopOnViolation(), 0));
                        engine.applyInterventions(b.interventions());
                    }
                    if (engine.nextSeq() > target || sink.hit() != null) break;
                    if (!engine.step()) break;
                }
                return null;
            } catch (DeterminismViolation dv) {
                return dv;
            }
        }
    }
}
