package chronos.cli;

import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything the tool reads from a file or a bundle must turn malformed input into a clear refusal (IllegalArgumentException or
 * CorruptLogException), never a NullPointerException, ClassCastException, IndexOutOfBounds or arithmetic error. Valid documents are
 * mutated structurally (a field removed, a value replaced by one of another type, a number made negative or huge, a string cut) and at the
 * character level, and every reader is required to either accept the result or refuse it cleanly.
 */
class ParserRobustnessTest {
    @TempDir
    Path tmp;

    private final SeededRandom rnd = new SeededRandom(2025);

    private long pick(long n) {
        return Math.floorMod(rnd.nextLong(), n);
    }

    private static final List<Object> REPLACEMENTS = Arrays.asList(null, "x", "", 7L, -1L, Long.MAX_VALUE, true, new ArrayList<>(), new TreeMap<String, Object>(), List.of("a", 1L));

    /** Returns a mutated deep copy of a JSON-like value: one random descendant is removed or replaced. */
    @SuppressWarnings("unchecked")
    private Object mutate(Object v) {
        Object copy = Canon.parse(Canon.json(v));
        List<Object[]> slots = new ArrayList<>(); // {container, key}
        collect(copy, slots);
        if (slots.isEmpty()) return copy;
        Object[] s = slots.get((int) pick(slots.size()));
        if (s[0] instanceof Map<?, ?> m) {
            if (pick(3) == 0) ((Map<String, Object>) m).remove(s[1]);
            else ((Map<String, Object>) m).put((String) s[1], REPLACEMENTS.get((int) pick(REPLACEMENTS.size())));
        } else {
            List<Object> l = (List<Object>) s[0];
            int i = (Integer) s[1];
            if (pick(3) == 0) l.remove(i);
            else l.set(i, REPLACEMENTS.get((int) pick(REPLACEMENTS.size())));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private void collect(Object v, List<Object[]> slots) {
        if (v instanceof Map<?, ?> m) for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            slots.add(new Object[]{m, e.getKey()});
            collect(e.getValue(), slots);
        } else if (v instanceof List<?> l) for (int i = 0; i < l.size(); i++) {
            slots.add(new Object[]{l, i});
            collect(l.get(i), slots);
        }
    }

    private void expectCleanOrAccepted(String what, Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException | CorruptLogException | TimelineHeader.UnsupportedFormatException clean) {
            // a refusal with a message is the correct outcome
            assertNotNull(clean.getMessage(), what);
        } catch (RuntimeException unclean) {
            fail(what + ": " + unclean.getClass().getSimpleName() + " escaped (" + unclean.getMessage() + ")", unclean);
        }
    }

    @Test
    void everyReaderRefusesMalformedDocumentsCleanly() {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), FaultPlan.of(FaultRule.nth("n", FaultRule.FaultAction.DROP, "p", "b", "Heartbeat", 2, null), FaultRule.crash("c", "b", 500),
                FaultRule.slowLink("s", "p", "b", 3, 100, 50L), FaultRule.onState("o", "p", "volatile.hb", 3L, FaultRule.FaultAction.PAUSE, Map.of("node", "b", "duration", 30L))), Boolean.FALSE);
        String t0 = env.last;
        TimelineHeader header = env.store.header(t0);
        TimelineSummary summary = env.store.summary(t0);
        List<Event> events = env.events(t0);
        WorldImage image = env.store.checkpointAtOrBefore(t0, events.size()).orElseThrow();
        Map<String, Object> rule = header.faultPlan().rules().get(0).toMap();
        List<Map<String, Object>> ivs = List.of(Intervention.removeFault("a", "n").toMap(), Intervention.shiftHeal("b", "n", 5).toMap(), Intervention.dropMessage("c", "p#1").toMap(),
                Intervention.addInput("d", "client", 5, Map.of("k", 1L)).toMap(), Intervention.patchState("e", "b", "volatile.role", "X").toMap(), Intervention.swapDeliveries("f", "p#1", "p#2").toMap());
        int trials = 0;
        for (int i = 0; i < 400; i++) {
            Object hm = mutate(header.toMap()), sm = mutate(summary.toMap()), rm = mutate(rule), im = mutate(ivs.get((int) pick(ivs.size())));
            Object em = mutate(Canon.parse(events.get((int) pick(events.size())).toLine())), wm = mutate(image.toMap());
            expectCleanOrAccepted("header " + Canon.json(hm), () -> TimelineHeader.fromMap(Canon.map(hm)));
            expectCleanOrAccepted("summary " + Canon.json(sm), () -> TimelineSummary.fromMap(Canon.map(sm)));
            expectCleanOrAccepted("fault rule " + Canon.json(rm), () -> FaultRule.fromMap(Canon.map(rm)));
            expectCleanOrAccepted("intervention " + Canon.json(im), () -> Intervention.fromMap(Canon.map(im)));
            expectCleanOrAccepted("event " + Canon.json(em), () -> Event.fromMap(Canon.map(em)));
            expectCleanOrAccepted("world image " + Canon.json(wm), () -> WorldImage.fromMap(Canon.map(wm)));
            trials += 6;
        }
        // character-level damage to the text forms
        String[] texts = {Canon.json(header.toMap()), Canon.json(summary.toMap()), events.get(10).toLine(), Canon.json(rule)};
        for (int i = 0; i < 600; i++) {
            StringBuilder sb = new StringBuilder(texts[i % texts.length]);
            int cut = (int) pick(sb.length());
            switch ((int) pick(3)) {
                case 0 -> sb.deleteCharAt(cut);
                case 1 -> sb.setCharAt(cut, (char) (32 + pick(95)));
                default -> sb.setLength(cut);
            }
            String text = sb.toString();
            int kind = i % texts.length;
            expectCleanOrAccepted("text " + text, () -> {
                switch (kind) {
                    case 0 -> TimelineHeader.fromMap(Canon.parseMap(text));
                    case 1 -> TimelineSummary.fromMap(Canon.parseMap(text));
                    case 2 -> Event.fromLine(text);
                    default -> FaultRule.fromMap(Canon.parseMap(text));
                }
            });
            trials++;
        }
        assertEquals(3000, trials);
    }

    @Test
    void faultPlansWithNonsenseParametersAreRefusedAtConstructionNotInsideTheEngine() {
        // rules that pass construction must run without an engine error: random but structurally valid parameter values, including extreme ones
        TestRegistry reg = new TestRegistry();
        long[] values = {0, 1, 2, 50, 399, 400, 100000, Long.MAX_VALUE / 4};
        int accepted = 0, refused = 0;
        for (int i = 0; i < 300; i++) {
            long a = values[(int) pick(values.length)], b = values[(int) pick(values.length)], c = values[(int) pick(values.length)];
            FaultRule r;
            try {
                r = switch ((int) pick(7)) {
                    case 0 -> FaultRule.partition("r", a, b == 0 ? null : b, List.of(List.of("p"), List.of("b")));
                    case 1 -> FaultRule.crash("r", "p", a);
                    case 2 -> FaultRule.pause("r", "b", a, b);
                    case 3 -> FaultRule.clockSkew("r", "b", a, b - c, c == 0 ? null : c);
                    case 4 -> FaultRule.slowLink("r", "p", "b", b, a, c == 0 ? null : c);
                    case 5 -> FaultRule.nth("r", FaultRule.FaultAction.DELAY, "p", "b", "Heartbeat", Math.max(1, a % 5), b);
                    default -> FaultRule.reorder("r", "p", "b", "*", Math.max(1, a % 5));
                };
            } catch (IllegalArgumentException | ArithmeticException refusal) {
                refused++;
                continue;
            }
            accepted++;
            try {
                var run = chronos.analysis.Recording.recordInMemory(reg, "Z", chronos.analysis.Recording.Spec.of("re", 42).withParams(Map.of("partition", "off")).withPlan(FaultPlan.of(r)).withHorizon(1000L).withCheckpointInterval(0));
                assertNotEquals(TimelineStatus.ABORTED, run.result().status(), r.toMap().toString());
                assertTrue(chronos.analysis.CausalGraph.build(run.log().events()).selfCheck(80).isEmpty(), r.toMap().toString());
            } catch (IllegalArgumentException | IllegalStateException refusal) {
                assertNotNull(refusal.getMessage(), "a rule the engine cannot run is refused with a message: " + r.toMap());
            }
        }
        assertTrue(accepted > 100, "accepted " + accepted + " refused " + refused);
    }
}
