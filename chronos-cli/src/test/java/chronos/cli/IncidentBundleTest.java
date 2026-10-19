package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.IncidentBundle.BundleException;
import chronos.model.*;
import chronos.store.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/** Portable incident bundles: export, validated import, reproduction with causal evidence, and rejection of everything that is not trustworthy. */
class IncidentBundleTest {
    @TempDir
    Path tmp;

    private final TestRegistry reg = new TestRegistry();

    private Env failing(String where) {
        Env env = new Env(tmp.resolve(where));
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        env.manager.branch("T0", env.manager.roundToBoundary("T0", 35), List.of(Intervention.shiftHeal("heal", "partition-pb", -200)), null, null);
        return env;
    }

    private static Map<String, byte[]> read(Path bundle) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(bundle))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (!e.isDirectory()) m.put(e.getName(), z.readAllBytes());
        }
        return m;
    }

    private static void write(Path out, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** Rewrites a bundle after a mutation; with reseal the manifest's file table and checksum are recomputed so only the semantic checks can object. */
    private Path forge(Path bundle, String name, Consumer<Map<String, byte[]>> mutate, boolean reseal) throws Exception {
        Map<String, byte[]> entries = read(bundle);
        mutate.accept(entries);
        if (reseal) {
            Map<String, Object> m = Canon.parseMap(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
            Map<String, Object> files = new TreeMap<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet())
                if (!e.getKey().equals("manifest.json")) files.put(e.getKey(), Map.of("sha256", sha(e.getValue()), "size", (long) e.getValue().length));
            m.put("files", files);
            m.remove("checksum");
            m.put("checksum", Canon.sha256(Canon.json(m)));
            entries.put("manifest.json", (Canon.json(m) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Path out = tmp.resolve(name);
        write(out, entries);
        return out;
    }

    private static String manifestText(Map<String, byte[]> e) {
        return new String(e.get("manifest.json"), StandardCharsets.UTF_8);
    }

    @Test
    void anIncidentIsExportedImportedElsewhereAndReproducedWithItsCausalEvidence() throws Exception {
        Env env = failing("src");
        Path bundle = IncidentBundle.export(env.store, "T0", tmp.resolve("t0.chronos-incident"));
        // the same incident gives a byte-identical bundle
        Path again = IncidentBundle.export(env.store, "T0", tmp.resolve("again.chronos-incident"));
        assertArrayEquals(Files.readAllBytes(bundle), Files.readAllBytes(again));
        Map<String, byte[]> parts = read(bundle);
        assertTrue(parts.keySet().containsAll(List.of("manifest.json", "evidence.json", "files/T0/header.json", "files/T0/summary.json")));
        // inspection validates without writing, and shows what happened
        IncidentBundle.Info info = IncidentBundle.inspect(bundle, reg);
        assertEquals("T0", info.incident());
        assertEquals("single-primary", info.invariant());
        assertEquals("inv:single-primary#1", info.violationEvent());
        assertEquals(72, info.events());
        assertTrue(info.warnings().isEmpty(), info.warnings().toString());
        // import into a completely separate store (another "machine")
        TimelineStore other = new TimelineStore(tmp.resolve("elsewhere"));
        IncidentBundle.Info imported = IncidentBundle.importInto(other, bundle, reg);
        assertEquals(List.of("T0"), imported.lineage());
        assertEquals(env.events("T0"), other.events("T0"), "the imported log is the exported log, event for event");
        assertEquals("persisted", other.index("T0").source(), "derived indexes are rebuilt on import");
        // the causal evidence survives: the imported timeline explains the violation exactly as the bundle claims
        Map<String, Object> evidence = Canon.parseMap(new String(parts.get("evidence.json"), StandardCharsets.UTF_8));
        TimeTravelSession s = new TimeTravelSession(other, "T0");
        assertEquals(evidence.get("why"), s.why("inv:single-primary#1"));
        assertTrue(((String) evidence.get("why")).contains("fault:partition-pb#1"));
        assertEquals(Canon.map(evidence.get("violation")).get("eventId"), s.goToViolation().eventId());
        // reproduction: replay verification plus a fresh run from scenario, seed and plan, same trace and same violation
        IncidentBundle.Reproduction rep = IncidentBundle.reproduce(bundle, reg);
        assertTrue(rep.ok(), rep.problems().toString());
        assertTrue(rep.checks().stream().anyMatch(c -> c.contains("fresh execution")), rep.checks().toString());
        assertTrue(rep.checks().stream().anyMatch(c -> c.contains("the same violation recurs")));
        // a branch travels with its ancestors and replays through the lineage
        Path branchBundle = IncidentBundle.export(env.store, "T0.1", tmp.resolve("t01.chronos-incident"));
        TimelineStore third = new TimelineStore(tmp.resolve("third"));
        assertEquals(List.of("T0", "T0.1"), IncidentBundle.importInto(third, branchBundle, reg).lineage());
        assertTrue(new ReplayEngine(third, reg).verify("T0.1").ok());
        assertTrue(IncidentBundle.reproduce(branchBundle, reg).ok());
        // importing the same bundle again changes nothing and is accepted
        IncidentBundle.importInto(third, branchBundle, reg);
        assertEquals(List.of("T0", "T0.1"), third.timelineIds());
    }

    @Test
    void everyKindOfDamageOrForgeryIsRejectedAndNothingIsWritten() throws Exception {
        Env env = failing("src");
        Path good = IncidentBundle.export(env.store, "T0", tmp.resolve("good.chronos-incident"));
        TimelineStore target = new TimelineStore(tmp.resolve("target"));
        List<String> before = target.timelineIds();
        // (name, bundle, expected fragment of the refusal)
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{"a log byte flipped (file checksum)", forge(good, "c1.zip", e -> { byte[] b = e.get("files/T0/events/000000.log").clone(); b[b.length / 2] ^= 0x20; e.put("files/T0/events/000000.log", b); }, false), "checksum"});
        cases.add(new Object[]{"a log byte flipped and the file table re-sealed (the log's own commit checksums)", forge(good, "c2.zip", e -> { byte[] b = e.get("files/T0/events/000000.log").clone(); b[b.length / 3] ^= 0x01; e.put("files/T0/events/000000.log", b); }, true), "not valid"});
        cases.add(new Object[]{"the manifest edited", forge(good, "c3.zip", e -> e.put("manifest.json", manifestText(e).replace("\"seed\":42", "\"seed\":43").getBytes(StandardCharsets.UTF_8)), false), "manifest checksum"});
        cases.add(new Object[]{"an unsupported format version", forge(good, "c4.zip", e -> e.put("manifest.json", manifestText(e).replace("\"formatVersion\":1", "\"formatVersion\":9").getBytes(StandardCharsets.UTF_8)), true), "unsupported bundle format version 9"});
        cases.add(new Object[]{"another kind of document", forge(good, "c5.zip", e -> e.put("manifest.json", manifestText(e).replace("chronos-incident", "something-else").getBytes(StandardCharsets.UTF_8)), true), "not a CHRONOS incident bundle"});
        cases.add(new Object[]{"a path-traversal entry", forge(good, "c6.zip", e -> e.put("files/../../evil.txt", new byte[]{1}), false), "unsafe entry"});
        cases.add(new Object[]{"an absolute entry", forge(good, "c7.zip", e -> e.put("/etc/evil", new byte[]{1}), false), "unsafe entry"});
        cases.add(new Object[]{"an unexpected entry", forge(good, "c8.zip", e -> e.put("extra.bin", new byte[]{1}), true), "unexpected entry"});
        cases.add(new Object[]{"an unlisted file", forge(good, "c9.zip", e -> e.put("files/T0/events/000009.log", new byte[]{1}), false), "not listed"});
        cases.add(new Object[]{"a missing file", forge(good, "c10.zip", e -> e.remove("files/T0/summary.json"), false), "missing"});
        cases.add(new Object[]{"a missing manifest", forge(good, "c11.zip", e -> e.remove("manifest.json"), false), "no manifest"});
        cases.add(new Object[]{"an event count that is a lie", forge(good, "c12.zip", e -> e.put("manifest.json", manifestText(e).replace("\"events\":72", "\"events\":71").getBytes(StandardCharsets.UTF_8)), true), "event count"});
        cases.add(new Object[]{"a log hash that is a lie", forge(good, "c13.zip", e -> {
            String m = manifestText(e);
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"logHash\":\"([0-9a-f]{64})\"").matcher(m);
            assertTrue(mm.find());
            e.put("manifest.json", m.replace(mm.group(1), "0".repeat(64)).getBytes(StandardCharsets.UTF_8));
        }, true), "log hash"});
        cases.add(new Object[]{"causal evidence that the log does not imply", forge(good, "c14.zip", e -> e.put("evidence.json", new String(e.get("evidence.json"), StandardCharsets.UTF_8).replace("fault:partition-pb#1", "fault:nonsense#9").getBytes(StandardCharsets.UTF_8)), true), "causal evidence"});
        cases.add(new Object[]{"evidence naming another violation", forge(good, "c15.zip", e -> e.put("evidence.json", new String(e.get("evidence.json"), StandardCharsets.UTF_8).replace("\"single-primary\"", "\"other-invariant\"").getBytes(StandardCharsets.UTF_8)), true), "violation"});
        cases.add(new Object[]{"a lineage that does not end at the incident", forge(good, "c16.zip", e -> e.put("manifest.json", manifestText(e).replace("\"lineage\":[\"T0\"]", "\"lineage\":[\"T0\",\"T9\"]").getBytes(StandardCharsets.UTF_8)), true), "lineage"});
        cases.add(new Object[]{"a timeline id that is a path", forge(good, "c17.zip", e -> e.put("manifest.json", manifestText(e).replace("\"incident\":\"T0\"", "\"incident\":\"..\"").replace("\"lineage\":[\"T0\"]", "\"lineage\":[\"..\"]").getBytes(StandardCharsets.UTF_8)), true), "unsafe timeline id"});
        cases.add(new Object[]{"a header of the wrong shape (resealed)", forge(good, "c18.zip", e -> e.put("files/T0/header.json", new String(e.get("files/T0/header.json"), StandardCharsets.UTF_8).replace("\"seed\":42", "\"seed\":[]").getBytes(StandardCharsets.UTF_8)), true), "not valid"});
        cases.add(new Object[]{"a summary of the wrong shape (resealed)", forge(good, "c19.zip", e -> e.put("files/T0/summary.json", new String(e.get("files/T0/summary.json"), StandardCharsets.UTF_8).replace("\"eventCount\":72", "\"eventCount\":\"many\"").getBytes(StandardCharsets.UTF_8)), true), ""});
        Path notZip = tmp.resolve("not.zip");
        Files.writeString(notZip, "this is not a zip file");
        cases.add(new Object[]{"not a zip at all", notZip, "manifest"});
        cases.add(new Object[]{"a file that does not exist", tmp.resolve("absent.zip"), "no such bundle"});
        for (Object[] c : cases) {
            Path p = (Path) c[1];
            BundleException ex = assertThrows(BundleException.class, () -> IncidentBundle.importInto(target, p, reg), (String) c[0]);
            assertTrue(ex.getMessage().contains((String) c[2]), c[0] + ": refusal was '" + ex.getMessage() + "'");
            assertThrows(BundleException.class, () -> IncidentBundle.inspect(p, reg), "inspect refuses it too: " + c[0]);
            assertFalse(IncidentBundle.reproduce(p, reg).ok(), "reproduce refuses it too: " + c[0]);
            assertEquals(before, target.timelineIds(), "nothing was written for: " + c[0]);
        }
        assertEquals(List.of(), target.timelineIds());
        try (Stream<Path> s = Files.list(tmp.resolve("target"))) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".incoming")), "no staging directory is left behind");
        }
        // a different timeline already under the same id is a conflict, not an overwrite
        Env clash = new Env(tmp.resolve("clash"));
        clash.record("re", 7, Map.of("partition", "off"), null, Boolean.FALSE);
        BundleException conflict = assertThrows(BundleException.class, () -> IncidentBundle.importInto(clash.store, good, reg));
        assertTrue(conflict.getMessage().contains("already exists"), conflict.getMessage());
        assertEquals(List.of("T0"), clash.store.timelineIds());
        // and a good bundle still imports after all that
        assertEquals("T0", IncidentBundle.importInto(target, good, reg).incident());
    }

    @Test
    void randomDamageToABundleIsAlwaysCaughtAsARejectionNeverACrashOrASilentAcceptanceOfDifferentData() throws Exception {
        Env env = failing("src");
        Path good = IncidentBundle.export(env.store, "T0", tmp.resolve("good.chronos-incident"));
        byte[] bytes = Files.readAllBytes(good);
        List<Event> original = env.events("T0");
        SeededRandom rnd = new SeededRandom(99);
        int rejected = 0, accepted = 0;
        for (int trial = 0; trial < 300; trial++) {
            byte[] bad = bytes.clone();
            if (trial % 5 == 0) bad = Arrays.copyOf(bad, (int) Math.floorMod(rnd.nextLong(), (long) bad.length)); // truncated
            else for (int f = 0; f < 1 + trial % 3; f++) bad[(int) Math.floorMod(rnd.nextLong(), (long) bad.length)] ^= (byte) (1 + Math.floorMod(rnd.nextLong(), 255L));
            Path p = tmp.resolve("fz" + trial + ".chronos-incident");
            Files.write(p, bad);
            TimelineStore target = new TimelineStore(tmp.resolve("ft" + trial));
            try {
                IncidentBundle.importInto(target, p, reg);
                // accepted only if what arrived is exactly what was exported (damage in an ignorable place)
                assertEquals(original, target.events("T0"), "trial " + trial + ": an accepted bundle must contain exactly the exported events");
                accepted++;
            } catch (BundleException expected) {
                assertEquals(List.of(), target.timelineIds(), "trial " + trial + ": nothing written on rejection");
                rejected++;
            }
        }
        assertTrue(rejected >= 250, "rejected " + rejected + ", accepted " + accepted);
    }

    @Test
    void aBundleForAnApplicationThisToolDoesNotHaveCanBeInspectedButNotReproduced() throws Exception {
        Env env = failing("src");
        Path good = IncidentBundle.export(env.store, "T0", tmp.resolve("g.chronos-incident"));
        Path foreign = forge(good, "foreign.zip", e -> e.put("files/T0/header.json", new String(e.get("files/T0/header.json"), StandardCharsets.UTF_8).replace("\"scenario\":\"re\"", "\"scenario\":\"payments\"").getBytes(StandardCharsets.UTF_8)), true);
        IncidentBundle.Info info = IncidentBundle.inspect(foreign, reg);
        assertTrue(info.warnings().stream().anyMatch(w -> w.contains("no scenario named 'payments'")), info.warnings().toString());
        TimelineStore other = new TimelineStore(tmp.resolve("other"));
        IncidentBundle.importInto(other, foreign, reg); // the log is the source of truth: it can still be inspected
        assertTrue(new TimeTravelSession(other, "T0").world().contains("node states"));
        IncidentBundle.Reproduction rep = IncidentBundle.reproduce(foreign, reg);
        assertFalse(rep.ok());
        assertTrue(rep.problems().get(0).contains("no scenario named"), rep.problems().toString());
        // a timeline that is not finished cannot be exported
        env.store.openWriter(env.store.header("T0").renamed("U")).close();
        BundleException unfinished = assertThrows(BundleException.class, () -> IncidentBundle.export(env.store, "U", tmp.resolve("u.zip")));
        assertTrue(unfinished.getMessage().contains("not finished"), unfinished.getMessage());
    }

    @Test
    void theCommandLineRoundTripsAnIncidentBetweenTwoDataDirectories() {
        String a = tmp.resolve("A").toString(), b = tmp.resolve("B").toString();
        java.util.function.BiFunction<String, String[], String[]> run = (data, args) -> {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            List<String> full = new ArrayList<>(List.of("--data", data));
            full.addAll(List.of(args));
            int code = Main.run(full.toArray(new String[0]), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(o, true, StandardCharsets.UTF_8), InputStream.nullInputStream());
            return new String[]{Integer.toString(code), o.toString(StandardCharsets.UTF_8)};
        };
        assertEquals("0", run.apply(a, new String[]{"run", "re", "--no-stop"})[0]);
        String file = tmp.resolve("incident.chronos-incident").toString();
        String[] ex = run.apply(a, new String[]{"incident", "export", "T0", "--out", file});
        assertEquals("0", ex[0], ex[1]);
        String[] info = run.apply(b, new String[]{"incident", "info", file});
        assertEquals("0", info[0], info[1]);
        assertTrue(info[1].contains("violation: single-primary at inv:single-primary#1"), info[1]);
        String[] rep = run.apply(b, new String[]{"incident", "reproduce", file});
        assertEquals("0", rep[0], rep[1]);
        assertTrue(rep[1].contains("INCIDENT REPRODUCED") && rep[1].contains("same violation recurs"), rep[1]);
        String[] imp = run.apply(b, new String[]{"incident", "import", file});
        assertEquals("0", imp[0], imp[1]);
        String why = run.apply(b, new String[]{"inspect", "T0", "--goto", "violation", "--why"})[1];
        assertTrue(why.contains("chain from fault:partition-pb#1"), why);
        // a damaged copy is rejected with exit code 2 and a reason
        String damaged = tmp.resolve("damaged.chronos-incident").toString();
        try {
            Map<String, byte[]> parts = read(Path.of(file));
            byte[] log = parts.get("files/T0/events/000000.log");
            log[log.length / 2] ^= 0x10;
            write(Path.of(damaged), parts);
        } catch (IOException e) {
            fail(e);
        }
        String[] bad = run.apply(tmp.resolve("C").toString(), new String[]{"incident", "import", damaged});
        assertEquals("2", bad[0]);
        assertTrue(bad[1].contains("bundle REJECTED"), bad[1]);
        assertEquals("1", run.apply(a, new String[]{"incident", "frobnicate"})[0]);
    }
}
