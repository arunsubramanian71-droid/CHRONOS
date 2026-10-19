package chronos.bench;

import chronos.model.*;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmarks (Section 26): small mechanisms where JVM warm-up and optimisation make naive timing misleading.
 * B3: how does vector-clock cost grow with the node count? B4: how big and how slow is event encoding?
 * Run: java -jar chronos-bench/target/chronos-bench.jar jmh
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class JmhBenchmarks {

    @State(Scope.Thread)
    public static class Clocks {
        @Param({"3", "10", "20", "50", "100"})
        public int nodes;
        ClockService cs;
        Stamp incoming;

        @Setup
        public void setup() {
            cs = new ClockService(nodes);
            incoming = new ClockService(nodes).stamp(nodes - 1, List.of());
        }
    }

    /** B3: a local or send event: increment own slot (allocates the stamp). */
    @Benchmark
    public Stamp vectorLocalEvent(Clocks c) {
        return c.cs.stamp(0, List.of());
    }

    /** B3: a receive: slot-wise maximum with the message's vector, then increment. */
    @Benchmark
    public Stamp vectorReceive(Clocks c) {
        return c.cs.stamp(0, List.of(c.incoming));
    }

    @State(Scope.Thread)
    public static class Encoding {
        Event withState, withoutState;
        Map<String, Object> state;

        @Setup
        public void setup() {
            state = Map.of("volatile", Map.of("role", "PRIMARY", "hb", 12L, "data", Map.of("x", 1L, "y", 2L, "z", 7L)), "durable", Map.of());
            withState = new Event("p#41", 120, "T0", "p", EventKind.TIMER_FIRED, 1200, 55, List.of(1, 40, 3, 7), List.of(new Parent("p#40", EdgeKind.PROGRAM_ORDER),
                    new Parent("p#12", EdgeKind.TIMER)), Map.of("timerId", "p#12", "name", "heartbeat"), Canon.digest(state), state, null);
            withoutState = withState.withState(withState.stateDigestAfter(), null);
        }
    }

    /** B4: encoding an event that carries a full state record (a step that changed the state). */
    @Benchmark
    public String encodeEventWithState(Encoding e) {
        return e.withState.toLine();
    }

    /** B4: encoding an ordinary event with only the digest. */
    @Benchmark
    public String encodeEventWithoutState(Encoding e) {
        return e.withoutState.toLine();
    }

    @Benchmark
    public String stateDigest(Encoding e) {
        return Canon.digest(e.state);
    }

    @Benchmark
    public Event decodeEvent(Encoding e) {
        return Event.fromLine(e.withState.toLine());
    }

    @State(Scope.Thread)
    public static class Rng {
        SeededRandom r = new SeededRandom(1);
    }

    @Benchmark
    public long ownRandomGenerator(Rng r) {
        return r.r.nextLong();
    }

    @Benchmark
    public long identityKeyedDecision() {
        return IdentityHash.bounded(42, 6, "latency", "p#17");
    }
}
