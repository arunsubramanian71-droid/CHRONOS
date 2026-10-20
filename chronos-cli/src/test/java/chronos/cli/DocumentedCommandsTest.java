package chronos.cli;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every `chronos <command> ...` that the documents tell a reader to type names a command the program has, and every `--flag` in such a line is one the
 * program reads. Commands in the executable scripts of docs/DEMO_SCRIPTS.md are additionally executed by {@link DemoScriptsTest}.
 */
class DocumentedCommandsTest {
    private static final Path ROOT = Path.of("..");
    private static final List<String> DOCS = List.of("README.md", "SUBMISSION_README.md", "docs/USER_GUIDE.md", "docs/PROFESSOR_DEMO.md", "docs/VIVA_GUIDE.md",
            "docs/HUMAN_STUDIES.md", "docs/PORTABILITY.md", "docs/BLIND_PROTOCOL.md", "docs/EVALUATION.md", "docs/TEACHING_NOTES.md", "docs/ARCHITECTURE.md", "docs/study-materials/nfr15-participant-card.md",
            "docs/study-materials/blind-debugger-card.md", "docs/study-materials/unplanned-card.md");

    private static Set<String> commands() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Main.run(new String[]{"help"}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()), new ByteArrayInputStream(new byte[0]));
        Set<String> names = new TreeSet<>();
        for (String line : o.toString(StandardCharsets.UTF_8).split("\\R")) {
            Matcher m = Pattern.compile("^  ([a-z][a-z|-]*)( |$)").matcher(line);
            if (m.find()) names.addAll(Arrays.asList(m.group(1).split("\\|")));
        }
        return names;
    }

    @Test
    void everyCommandAndFlagTheDocumentsTellTheReaderToTypeExists() throws IOException {
        Set<String> commands = commands();
        assertTrue(commands.containsAll(Set.of("run", "check", "minimize", "explore", "report", "demo", "incident", "blind")), commands.toString());
        String cli = Files.readString(ROOT.resolve("chronos-cli/src/main/java/chronos/cli/Cli.java")) + Files.readString(ROOT.resolve("chronos-cli/src/main/java/chronos/cli/Main.java"));
        Pattern line = Pattern.compile("(?:^|[`$ ])(?:chronos|java -jar [\\w./-]*chronos\\.jar|chronos\\.jar)((?:\\s+--data\\s+\\S+)?)\\s+([a-z][a-z-]*)\\b([^`\\n]*)");
        List<String> problems = new ArrayList<>();
        int checked = 0, flags = 0;
        for (String doc : DOCS) {
            Path p = ROOT.resolve(doc);
            if (!Files.exists(p)) continue;
            for (String text : Files.readString(p).split("\\R")) {
                Matcher m = line.matcher(text);
                while (m.find()) {
                    String cmd = m.group(2);
                    if (!commands.contains(cmd) && !Set.of("jar", "help").contains(cmd)) {
                        // prose such as "chronos is ..." or "chronos explains": only lines that look like a command line count
                        if (!text.contains("`chronos " + cmd) && !text.contains("$ chronos " + cmd) && !text.trim().startsWith("chronos " + cmd) && !text.contains("chronos.jar " + cmd) && !text.contains("chronos.jar --data")) continue;
                        problems.add(doc + ": unknown command '" + cmd + "' in: " + text.trim());
                        continue;
                    }
                    checked++;
                    Matcher f = Pattern.compile("(?<![\\w-])--([a-z][a-z0-9-]*)").matcher(m.group(3));
                    while (f.find()) {
                        flags++;
                        String flag = f.group(1);
                        if (!cli.contains("\"" + flag + "\"") && !flag.equals("data")) problems.add(doc + ": the program never reads --" + flag + " (in: " + text.trim() + ")");
                    }
                }
            }
        }
        assertTrue(checked > 60, "documented command lines checked: " + checked);
        assertTrue(flags > 30, "documented flags checked: " + flags);
        assertEquals(List.of(), problems);
    }
}
