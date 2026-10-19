package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The blind-debugging kit builds cases that really fail for the planted reason, hides what they are built on, and grades honestly. */
class BlindKitTest {
    @TempDir
    Path tmp;

    @Test
    void everyCaseFailsAndItsPlantedCauseIsConfirmedByCounterfactualsForManyTokens() {
        BlindKit.Registry reg = new BlindKit.Registry();
        int[] perKind = new int[BlindKit.CASES];
        for (long token = 1; token <= 60; token++) {
            BlindKit.Case c = BlindKit.caseFor(token);
            perKind[(int) (token % BlindKit.CASES)]++;
            TimelineStore store = new TimelineStore(tmp.resolve("t" + token));
            TimelineManager mgr = new TimelineManager(store, reg).withCheckpointInterval(10);
            FaultPlan plan = FaultPlan.empty();
            for (FaultRule r : c.plan().rules()) plan = plan.with(r);
            var run = Recording.record(store, reg, "T0", Recording.Spec.of("mystery", 42).withParams(Map.of("m", Long.toHexString(token))).withPlan(plan).withStopOnViolation(false));
            assertTrue(run.violated(), "token " + token + " (" + c.description() + ") must fail");
            assertEquals("mystery", store.header("T0").scenario());
            assertTrue(new ReplayEngine(store, reg).verify("T0").ok());
            // but-for: removing the whole planted cause makes the failure disappear; removing a proper subset does not (re2)
            List<Event> ev = store.events("T0");
            long first = Long.MAX_VALUE;
            for (Event e : ev) if (e.kind() == EventKind.FAULT_INJECTED) { first = Math.min(first, e.seq()); }
            long at = mgr.roundToBoundary("T0", first - 1);
            List<Intervention> all = new ArrayList<>();
            int i = 0;
            for (String rule : c.truth()) all.add(Intervention.removeFault("rm" + (++i), rule));
            var cf = new CounterfactualEngine(mgr);
            assertEquals(CounterfactualEngine.Verdict.FAILURE_DISAPPEARS, cf.run(new CounterfactualEngine.Query("T0", at, all, null, null)).verdict(), "token " + token + ": removing the planted cause");
            if (c.truth().size() > 1)
                assertEquals(CounterfactualEngine.Verdict.FAILURE_PERSISTS, cf.run(new CounterfactualEngine.Query("T0", at, List.of(all.get(0)), null, null)).verdict(), "token " + token + ": one of two causes is not enough");
            // grading
            List<String> right = new ArrayList<>();
            for (String rule : c.truth()) right.add("fault:" + rule + "#1");
            assertTrue(BlindKit.correct(c, right));
            assertTrue(BlindKit.correct(c, new ArrayList<>(c.truth())));
            assertFalse(BlindKit.correct(c, List.of("fault:nonsense#1")));
            if (c.truth().size() > 1) assertFalse(BlindKit.correct(c, List.of(right.get(0))));
        }
        for (int n : perKind) assertTrue(n >= 10, Arrays.toString(perKind));
    }

    private int cli(String data, String stdin, String... args) {
        List<String> full = new ArrayList<>(List.of("--data", data));
        full.addAll(List.of(args));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int code = Main.run(full.toArray(new String[0]), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(o, true, StandardCharsets.UTF_8), new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        last = o.toString(StandardCharsets.UTF_8);
        return code;
    }

    private String last;

    @Test
    void theWholeProtocolRunsFromTheCommandLineAndDoesNotLeakTheAnswer() {
        String d = tmp.resolve("case").toString();
        assertEquals(0, cli(d, "", "blind", "prepare", "--token", "1b"), last);
        assertFalse(last.contains("partition") || last.contains("-cut"), "the preparation output does not name the cause: " + last);
        String show = new Object() {
            String get() { cli(d, "", "show", "T0"); return last; }
        }.get();
        assertTrue(show.contains("mystery"), show);
        assertFalse(show.contains(" re ") || show.contains("ka2"), "the case is not labelled with the application it is built on");
        assertEquals(0, cli(d, "", "blind", "begin"), last);
        assertEquals(1, cli(d, "", "blind", "grade"), "no answer yet: " + last);
        // 0x1b = 27 -> case 2 (blocked commit): find the planted cause with the tools, as a debugger would
        assertEquals(0, cli(d, "", "inspect", "T0", "--goto", "violation", "--why"));
        String why = last;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("fault:(f[0-9a-f]{4}-[a-z]+)#1").matcher(why);
        assertTrue(m.find(), why);
        assertEquals(0, cli(d, "", "blind", "answer", "--root", m.group(1), "--notes", "the first causal chain starts at the crash"), last);
        assertEquals(0, cli(d, "", "blind", "grade"), last);
        assertTrue(last.contains("DIAGNOSIS CORRECT") && last.contains("planted cause: [" + m.group(1) + "]"), last);
        // a wrong answer is graded as wrong
        String d2 = tmp.resolve("case2").toString();
        assertEquals(0, cli(d2, "", "blind", "prepare", "--token", "10"));
        assertEquals(0, cli(d2, "", "blind", "answer", "--root", "fault:wrong#1"));
        assertEquals(1, cli(d2, "", "blind", "grade"));
        assertTrue(last.contains("DIAGNOSIS INCORRECT"), last);
    }
}
