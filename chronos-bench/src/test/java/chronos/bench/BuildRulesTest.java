package chronos.bench;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build-time rules (NFR-13, NFR-19, NFR-20): the single direction of dependency, the dependency discipline and the
 * determinism hygiene scan. They run on every build, so a violation fails the build.
 */
class BuildRulesTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** Section 23: which module may depend on which. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "chronos-model", Set.of(),
            "chronos-sdk", Set.of("chronos-model"),
            "chronos-store", Set.of("chronos-model"),
            "chronos-runtime", Set.of("chronos-sdk", "chronos-model"),
            "chronos-demo", Set.of("chronos-sdk", "chronos-model"),
            "chronos-analysis", Set.of("chronos-runtime", "chronos-sdk", "chronos-store", "chronos-model"),
            "chronos-cli", Set.of("chronos-analysis", "chronos-runtime", "chronos-store", "chronos-demo", "chronos-sdk", "chronos-model"),
            "chronos-bench", Set.of("chronos-analysis", "chronos-runtime", "chronos-store", "chronos-demo", "chronos-sdk", "chronos-model", "chronos-cli"));

    private record Dep(String artifact, String scope) {}

    private static List<Dep> deps(String module) throws Exception {
        Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ROOT.resolve(module).resolve("pom.xml").toFile());
        NodeList deps = d.getDocumentElement().getElementsByTagName("dependency");
        List<Dep> out = new ArrayList<>();
        for (int i = 0; i < deps.getLength(); i++) {
            Element e = (Element) deps.item(i);
            String scope = e.getElementsByTagName("scope").getLength() > 0 ? e.getElementsByTagName("scope").item(0).getTextContent().trim() : "compile";
            out.add(new Dep(e.getElementsByTagName("artifactId").item(0).getTextContent().trim(), scope));
        }
        return out;
    }

    @Test
    void theRootPomListsTheEightModulesInTheApprovedBuildOrder() throws Exception {
        Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ROOT.resolve("pom.xml").toFile());
        NodeList mods = d.getElementsByTagName("module");
        List<String> got = new ArrayList<>();
        for (int i = 0; i < mods.getLength(); i++) got.add(mods.item(i).getTextContent().trim());
        assertEquals(List.of("chronos-model", "chronos-sdk", "chronos-store", "chronos-runtime", "chronos-demo", "chronos-analysis", "chronos-cli", "chronos-bench"), got);
    }

    @Test
    void everyModuleDependsOnlyOnWhatSection23AllowsAndTheGraphIsAcyclic_NFR13() throws Exception {
        for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet()) {
            Set<String> used = new TreeSet<>();
            for (Dep dep : deps(e.getKey())) if (dep.artifact().startsWith("chronos-")) used.add(dep.artifact());
            assertTrue(e.getValue().containsAll(used), e.getKey() + " depends on " + used + " but may only depend on " + e.getValue());
        }
        // acyclic: the allowed relation itself must have a topological order (Maven would refuse a cycle too)
        Set<String> done = new HashSet<>();
        while (done.size() < ALLOWED.size()) {
            boolean progress = false;
            for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet())
                if (!done.contains(e.getKey()) && done.containsAll(e.getValue())) {
                    done.add(e.getKey());
                    progress = true;
                }
            assertTrue(progress, "dependency cycle among " + ALLOWED.keySet());
        }
    }

    @Test
    void onlyApprovedThirdPartyLibrariesAreUsed_NFR19() throws Exception {
        Set<String> runtimeLibs = new TreeSet<>();
        for (String m : ALLOWED.keySet())
            for (Dep dep : deps(m))
                if (!dep.artifact().startsWith("chronos-") && !dep.scope().equals("test") && !dep.scope().equals("provided")) runtimeLibs.add(dep.artifact());
        // Jackson is the only runtime library (D-09); JMH is the build-time benchmarking dependency of chronos-bench (D-20)
        assertEquals(Set.of("jackson-databind", "jmh-core"), runtimeLibs);
        for (String m : ALLOWED.keySet())
            if (!m.equals("chronos-bench"))
                for (Dep dep : deps(m)) assertFalse(dep.artifact().startsWith("jmh"), m + " must not depend on JMH");
        assertTrue(runtimeLibs.size() < 5, "fewer than 5 runtime libraries");
        // no banned frameworks anywhere in any pom
        for (String m : ALLOWED.keySet()) {
            String pom = Files.readString(ROOT.resolve(m).resolve("pom.xml")).toLowerCase();
            for (String banned : List.of("spring", "kafka", "redis", "lombok", "log4j", "slf4j", "logback", "mockito", "picocli", "javafx", "hibernate", "postgres", "h2database"))
                assertFalse(pom.contains(banned), m + " mentions banned technology " + banned);
        }
    }

    // ---------------------------------------------------------------- determinism hygiene (NFR-20)

    private static final List<String> BANNED = List.of("System.currentTimeMillis", "System.nanoTime", "Instant.now", "LocalDateTime.now", "LocalDate.now", "ZonedDateTime.now",
            "new Random(", "ThreadLocalRandom", "Math.random", "UUID.randomUUID", "SecureRandom", "new Thread(", "Executors.", "ExecutorService", "Thread.sleep", "CompletableFuture",
            "java.net.Socket", "ServerSocket", "HttpClient", "HttpURLConnection", "System.identityHashCode", "IdentityHashMap", "parallelStream", "new Timer(", "ScheduledExecutor");

    /** Removes comments and string literals so the scan looks at code only. */
    static String code(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        return s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static List<String> scan(Path dir) throws IOException {
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(dir)) return hits;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String c = code(Files.readString(p));
                for (String b : BANNED) if (c.contains(b)) hits.add(ROOT.relativize(p).toString().replace('\\', '/') + " uses " + b);
            }
        }
        return hits;
    }

    @Test
    void noBannedNondeterministicApiInModelSdkRuntimeAndDemo_NFR20() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String m : List.of("chronos-model", "chronos-sdk", "chronos-runtime", "chronos-demo")) hits.addAll(scan(ROOT.resolve(m).resolve("src/main/java")));
        // the one deliberate exception: the bad citizen of acceptance demonstration AD-2 (Section 15)
        hits.removeIf(h -> h.contains("chronos/demo/bad/BadCitizen.java"));
        assertEquals(List.of(), hits, "determinism contract (Section 9) violated");
    }

    @Test
    void theScanReallyCatchesViolations_negativeControl() throws IOException {
        List<String> hits = scan(ROOT.resolve("chronos-demo/src/main/java/chronos/demo/bad"));
        assertTrue(hits.stream().anyMatch(h -> h.contains("System.nanoTime")), hits.toString());
        assertTrue(hits.stream().anyMatch(h -> h.contains("System.identityHashCode")), hits.toString());
        assertTrue(code("x = System.currentTimeMillis(); // not here").contains("currentTimeMillis"));
        assertFalse(code("// System.nanoTime() in a comment\n/* new Thread( */ String s = \"Thread.sleep\";").matches("(?s).*(nanoTime|new Thread\\(|Thread\\.sleep).*"));
    }

    @Test
    void theApplicationNodesOnlyUseTheContextForTimeRandomnessAndTheNetwork() throws IOException {
        // demo nodes must not touch java.io/nio files or static mutable state either
        try (Stream<Path> s = Files.walk(ROOT.resolve("chronos-demo/src/main/java"))) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java") && !f.toString().replace('\\', '/').contains("/bad/"))::iterator) {
                String c = code(Files.readString(p));
                assertFalse(c.contains("java.io.") || c.contains("java.nio."), p + " touches files");
                for (String line : c.split("\\R")) {
                    String t = line.trim();
                    boolean field = t.contains(" static ") || t.startsWith("static ");
                    if (field && !t.contains("final") && !t.contains("(") && (t.endsWith(";") || t.contains("=")))
                        fail(p + " has static mutable state: " + t);
                }
            }
        }
    }

    @Test
    void jmhBenchmarkListWasGeneratedByTheAnnotationProcessor() {
        assertNotNull(getClass().getResourceAsStream("/META-INF/BenchmarkList"), "JMH annotation processing did not run");
    }

    @Test
    void theSoakHarnessRunsAndReportsNoViolationsForTheControl() throws Exception {
        Path csv = Files.createTempFile("chronos-bench", ".csv");
        try {
            BenchMain.main(new String[]{"soak", "--soak-seeds", "120", "--csv", csv.toString()});
            String out = Files.readString(csv);
            assertTrue(out.contains("\"SOAK\"") && out.contains("\"violations\",\"0\""), out);
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
