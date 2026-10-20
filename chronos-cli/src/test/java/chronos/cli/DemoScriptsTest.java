package chronos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** docs/DEMO_SCRIPTS.md is executable: every ```chronos-script block is run in a fresh data directory and its expectations are checked. */
class DemoScriptsTest {
    @TempDir
    Path tmp;

    private record Step(List<String> args, List<String> expects, int exit) {}

    private static List<List<Step>> parse(String md) {
        List<List<Step>> scripts = new ArrayList<>();
        List<Step> cur = null;
        List<String> args = null, expects = null;
        int exit = 0;
        for (String line : md.split("\\R")) { // any line ending: a Windows checkout may turn the document into CRLF, and a stray \r would become part of an expected text
            if (line.startsWith("```chronos-script")) {
                cur = new ArrayList<>();
                continue;
            }
            if (cur == null) continue;
            if (line.startsWith("```")) {
                if (args != null) cur.add(new Step(args, expects, exit));
                scripts.add(cur);
                cur = null;
                args = null;
                continue;
            }
            if (line.startsWith("$ ")) {
                if (args != null) cur.add(new Step(args, expects, exit));
                args = new ArrayList<>(Arrays.asList(line.substring(2).trim().split("\\s+")));
                expects = new ArrayList<>();
                exit = 0;
            } else if (line.startsWith("  expect: ")) {
                expects.add(line.substring("  expect: ".length()));
            } else if (line.startsWith("  exit: ")) {
                exit = Integer.parseInt(line.substring("  exit: ".length()).trim());
            }
        }
        return scripts;
    }

    @Test
    void everyScriptInTheDocumentRunsAndMeetsItsExpectations() throws IOException {
        String md = Files.readString(Path.of("..", "docs", "DEMO_SCRIPTS.md"));
        List<List<Step>> scripts = parse(md);
        assertTrue(scripts.size() >= 9, "scripts found: " + scripts.size());
        int n = 0, commands = 0;
        for (List<Step> script : scripts) {
            Path data = tmp.resolve("s" + n++);
            for (Step st : script) {
                List<String> full = new ArrayList<>(List.of("--data", data.toString()));
                full.addAll(st.args());
                ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
                String stdin = st.args().get(0).equals("session") ? "quit\n" : "";
                int code = Main.run(full.toArray(new String[0]), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8),
                        new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
                String all = o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8);
                String where = "script " + n + ": $ " + String.join(" ", st.args());
                assertEquals(st.exit(), code, where + "\n" + all);
                for (String ex : st.expects()) assertTrue(all.contains(ex), where + " should print '" + ex + "' but printed:\n" + all);
                commands++;
            }
        }
        assertTrue(commands >= 50, "commands run: " + commands);
    }
}
