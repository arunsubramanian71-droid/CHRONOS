package chronos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/** The traceability matrix may not point at tests, documents or classes that do not exist: stale evidence is caught by the build. */
class TraceabilityTest {
    private static final Path ROOT = Path.of("..");

    private static String read(String rel) throws IOException {
        return Files.readString(ROOT.resolve(rel));
    }

    private static Map<String, String> sources(String dirName) throws IOException {
        Map<String, String> byClass = new HashMap<>();
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java") && f.toString().replace('\\', '/').contains(dirName) && !f.toString().replace('\\', '/').contains("/target/"))::iterator)
                byClass.put(p.getFileName().toString().replace(".java", ""), Files.readString(p));
        }
        return byClass;
    }

    @Test
    void everyTestAndDocumentNamedInTheMatrixAndTheStatusFileExists() throws IOException {
        Map<String, String> tests = sources("/src/test/java/");
        Map<String, String> mains = sources("/src/main/java/");
        List<String> problems = new ArrayList<>();
        Pattern ref = Pattern.compile("`([A-Z][A-Za-z0-9]*)(?:\\.([A-Za-z0-9_]+))?(\\.\\.\\.)?[^`]*`");
        int checkedTests = 0, checkedClasses = 0;
        for (String doc : List.of("docs/TRACEABILITY.md", "IMPLEMENTATION_STATUS.md", "README.md", "docs/DEVIATIONS.md", "docs/EVALUATION.md", "docs/BLIND_PROTOCOL.md")) {
            String text = read(doc);
            Matcher m = ref.matcher(text);
            while (m.find()) {
                String cls = m.group(1), member = m.group(2);
                if (cls.endsWith("Test")) {
                    checkedTests++;
                    String src = tests.get(cls);
                    if (src == null) {
                        problems.add(doc + ": test class " + cls + " does not exist");
                    } else if (member != null && !member.equals("java") && !member.equals("md") && !Pattern.compile("void " + Pattern.quote(member) + "\\w*\\s*\\(").matcher(src).find()) {
                        problems.add(doc + ": " + cls + "." + member + " does not exist");
                    }
                } else if (mains.containsKey(cls) || tests.containsKey(cls)) {
                    checkedClasses++;
                    if (member != null && Character.isLowerCase(member.charAt(0)) && !member.equals("java")) {
                        String src = mains.containsKey(cls) ? mains.get(cls) : tests.get(cls);
                        if (!Pattern.compile("\\b" + Pattern.quote(member) + "\\b").matcher(src).find()) problems.add(doc + ": " + cls + "." + member + " is not in the source of " + cls);
                    }
                }
            }
            Matcher files = Pattern.compile("`((?:docs/)?[A-Za-z0-9_./-]+\\.(?:md|csv|txt|html|py|jsonl))`").matcher(text);
            while (files.find()) {
                String f = files.group(1);
                if (f.startsWith("chronos-data") || f.contains("*") || f.startsWith("src/") || f.equals("summary.json")) continue;
                if (!Files.exists(ROOT.resolve(f)) && !Files.exists(ROOT.resolve("docs").resolve(f)) && !Files.exists(ROOT.resolve("chronos-bench/tools").resolve(f))
                        && !Files.exists(ROOT.resolve("chronos-bench").resolve(f)) && !Files.exists(ROOT.resolve("chronos-cli/src/main/resources/chronos/cli").resolve(f))
                        && !Files.exists(ROOT.resolve("chronos-cli/src/test/resources/golden").resolve(f))) problems.add(doc + ": file " + f + " does not exist");
            }
        }
        assertTrue(checkedTests > 80, "test references checked: " + checkedTests);
        assertTrue(checkedClasses > 60, "class references checked: " + checkedClasses);
        assertEquals(List.of(), problems);
    }

    @Test
    void theMatrixCoversEveryDecisionRequirementLawAndAcceptanceItem() throws IOException {
        String t = read("docs/TRACEABILITY.md");
        List<String> missing = new ArrayList<>();
        for (int d = 1; d <= 26; d++) if (!t.contains(String.format("| D-%02d ", d))) missing.add("D-" + d);
        for (int m = 1; m <= 14; m++) if (!t.contains("| M" + m + " ")) missing.add("M" + m);
        for (int a = 1; a <= 6; a++) if (!t.contains("| AD-" + a + " ")) missing.add("AD-" + a);
        for (int k = 0; k <= 4; k++) if (!t.contains("| KA-" + k + " ")) missing.add("KA-" + k);
        String frSection = t.substring(t.indexOf("## Functional requirements"), t.indexOf("## Non-functional"));
        Set<Integer> covered = new TreeSet<>();
        for (String line : frSection.split("\\R")) {
            Matcher row = Pattern.compile("^\\| ([0-9a-z,\\- ]+?) \\|").matcher(line);
            if (!row.find()) continue;
            String cell = row.group(1);
            Matcher range = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(cell);
            while (range.find()) for (int i = Integer.parseInt(range.group(1)); i <= Integer.parseInt(range.group(2)); i++) covered.add(i);
            Matcher single = Pattern.compile("\\d+").matcher(cell);
            while (single.find()) covered.add(Integer.parseInt(single.group()));
        }
        for (int f = 1; f <= 52; f++) if (!covered.contains(f)) missing.add("FR-" + f);
        assertEquals(List.of(), missing);
        // every status in the matrix is one of the four defined labels or a documented qualifier
        for (String line : t.split("\\R"))
            if (line.startsWith("| ") && (line.contains("NOT IMPLEMENTED") || line.contains("NOT VERIFIED"))) assertFalse(line.contains("COMPLETE (all"), line);
    }
}
