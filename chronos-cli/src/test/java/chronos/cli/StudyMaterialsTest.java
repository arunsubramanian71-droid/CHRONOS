package chronos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** The human-study materials stay consistent with each other and with the specification's wording, and never claim a result that has no data. */
class StudyMaterialsTest {
    private static final Path ROOT = Path.of("..");

    private static String read(String rel) throws IOException {
        return Files.readString(ROOT.resolve(rel));
    }

    private static List<String> header(String rel) throws IOException {
        return Arrays.asList(read(rel).split("\\R")[0].split(","));
    }

    @Test
    void theSheetsHaveTheColumnsTheAnalysisReadsAndTheCardsQuoteTheSpecification() throws IOException {
        String py = read("tools/study_analyse.py");
        Map<String, String> sheets = Map.of("nfr15", "docs/study-results/nfr15-template.csv", "blind", "docs/study-results/blind-template.csv", "unplanned", "docs/study-results/unplanned-template.csv");
        for (Map.Entry<String, String> e : sheets.entrySet()) {
            List<String> cols = header(e.getValue());
            String fn = py.substring(py.indexOf("def " + e.getKey() + "("), py.indexOf("\ndef ", py.indexOf("def " + e.getKey() + "(") + 5) < 0 ? py.length() : py.indexOf("\ndef ", py.indexOf("def " + e.getKey() + "(") + 5));
            Matcher m = Pattern.compile("r\\[\"([a-z0-9_]+)\"\\]").matcher(fn);
            int used = 0;
            while (m.find()) {
                assertTrue(cols.contains(m.group(1)), e.getKey() + ": the analysis reads column " + m.group(1) + " which the template does not have");
                used++;
            }
            assertTrue(used >= 3, e.getKey() + ": the analysis must actually read the sheet");
        }
        String a = read("docs/study-materials/nfr15-participant-card.md");
        assertTrue(a.contains("Under 10 minutes, three commands") && a.contains("NFR-15") && a.contains("never"), "Study A states the specified criterion and who may take part");
        String b = read("docs/study-materials/blind-debugger-card.md") + read("docs/study-materials/unplanned-card.md");
        assertTrue(b.contains("using only CHRONOS") && b.contains("how long it took") && b.contains("by hand and with the tool"), "Study B quotes both halves of the specification's protocol");
    }

    @Test
    void noStudyIsClaimedWithoutItsData() throws IOException {
        String studies = read("docs/HUMAN_STUDIES.md");
        Path results = ROOT.resolve("docs/study-results");
        for (String s : List.of("nfr15", "blind", "unplanned")) {
            boolean data = Files.exists(results.resolve(s + ".csv"));
            if (!data) assertTrue(studies.contains("NOT RUN"), "no " + s + ".csv exists, so the studies must be documented as not run");
        }
        String matrix = read("tools/completion_matrix.py");
        if (!Files.exists(results.resolve("nfr15.csv"))) assertTrue(matrix.contains("\"NFR-15\": (\"BLOCKED BY EXTERNAL INPUT\""), "NFR-15 stays blocked until a real session is recorded");
        if (!Files.exists(results.resolve("blind.csv"))) assertTrue(matrix.contains("\"Blind\": (\"BLOCKED BY EXTERNAL INPUT\""));
        assertTrue(read("tools/study_analyse.py").contains("SELFTEST"), "the analysis refuses self-test rows");
    }
}
