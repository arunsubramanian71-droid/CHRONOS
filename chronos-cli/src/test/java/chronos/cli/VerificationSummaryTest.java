package chronos.cli;

import chronos.model.Canon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The machine-readable companion of a report: manifest, recording, verification and comparison, recomputed from the store and byte-stable. */
class VerificationSummaryTest {
    @TempDir
    Path tmp;

    private record R(int code, String out, String err) {}

    private R cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--data";
        full[1] = tmp.resolve("data").toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = Main.run(full, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        return new R(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aReportComesWithAVerificationSummaryThatSaysWhatWasRunVerifiedAndFound() throws Exception {
        assertEquals(0, cli("run", "re", "--no-stop").code());
        assertEquals(0, cli("branch", "T0", "--heal-earlier", "200").code());
        Path html = tmp.resolve("out/r.html");
        R rep = cli("report", "T0", "--against", "T0.1", "--out", html.toString());
        assertEquals(0, rep.code(), rep.err());
        Path json = tmp.resolve("out/r.verification.json");
        assertTrue(rep.out().contains("verification summary written") && Files.exists(json), rep.out());
        String text = Files.readString(json);
        Map<String, Object> m = Canon.parseMap(text);
        assertEquals(text, Canon.json(m) + "\n", "canonical");
        assertEquals("chronos-verification-summary", m.get("kind"));

        Map<String, Object> man = Canon.map(m.get("manifest"));
        assertEquals("re", man.get("scenario"));
        assertEquals(42L, Canon.num(man.get("seed")));
        assertFalse(Canon.list(man.get("faultPlan")).isEmpty());
        List<Object> lineage = Canon.list(man.get("lineage"));
        assertEquals(1, lineage.size(), "the manifest describes the reference timeline's lineage");
        assertEquals(Boolean.FALSE, man.get("synthetic"));

        Map<String, Object> rec = Canon.map(m.get("recording"));
        assertEquals("single-primary", Canon.map(rec.get("firstViolation")).get("invariant"));
        Map<String, Object> ver = Canon.map(m.get("verification"));
        assertEquals(Boolean.TRUE, ver.get("verified"));
        assertEquals(Boolean.TRUE, Canon.map(ver.get("replay")).get("ok"));
        assertEquals(rec.get("logHash"), Canon.map(ver.get("replay")).get("logHash"), "the replay reproduced exactly the recorded log");
        assertEquals(Boolean.TRUE, Canon.map(ver.get("integrity")).get("ok"));

        Map<String, Object> cmp = Canon.map(m.get("comparison"));
        List<Object> other = Canon.list(cmp.get("againstLineage"));
        assertEquals(2, other.size(), "and the compared timeline's lineage, with its interventions, is recorded beside the comparison");
        assertEquals("SHIFT_HEAL", Canon.map(Canon.list(Canon.map(other.get(1)).get("interventions")).get(0)).get("type"));
        assertTrue(Canon.map(cmp.get("firstCausalDivergence")).get("event").toString().startsWith("iv:"), "the first causal divergence is the intervention (law M10)");
        assertTrue(Canon.list(cmp.get("unexplained")).isEmpty());
        assertNotNull(Canon.map(m.get("counterfactual")).get("verdict"));
        assertTrue(Canon.list(m.get("reproduce")).stream().anyMatch(c -> c.toString().contains(" verify T0")));

        // the same store and arguments give the same bytes
        assertEquals(0, cli("report", "T0", "--against", "T0.1", "--out", html.toString()).code());
        assertEquals(text, Files.readString(json));
    }

    @Test
    void aDamagedStoreIsNotReportedAsVerified() throws Exception {
        assertEquals(0, cli("run", "re", "--no-stop").code());
        Path summary = tmp.resolve("data/timelines/T0/summary.json");
        Files.writeString(summary, Files.readString(summary).replaceAll("\"eventCount\":\\d+", "\"eventCount\":3"));
        assertEquals(0, cli("report", "T0", "--out", tmp.resolve("d.html").toString()).code());
        Map<String, Object> ver = Canon.map(Canon.parseMap(Files.readString(tmp.resolve("d.verification.json"))).get("verification"));
        assertEquals(Boolean.FALSE, ver.get("verified"));
        Map<String, Object> integ = Canon.map(ver.get("integrity"));
        assertEquals(Boolean.FALSE, integ.get("ok"));
        assertFalse(Canon.list(integ.get("problems")).isEmpty());
    }
}
