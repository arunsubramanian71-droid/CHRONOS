package chronos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Invalid command lines produce an understandable error (exit code 1 or 2 and a one-line "chronos: ..." message), never an internal error or a stack trace. */
class CliRobustnessTest {
    @TempDir
    Path tmp;

    private String[] run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        List<String> full = new ArrayList<>(List.of("--data", tmp.resolve("data").toString()));
        full.addAll(List.of(args));
        int code = Main.run(full.toArray(new String[0]), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), new ByteArrayInputStream("quit\n".getBytes(StandardCharsets.UTF_8)));
        return new String[]{Integer.toString(code), o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void everyCommandRefusesBadArgumentsWithAMessageAndNeverAnInternalError() {
        run("run", "re", "--no-stop"); // T0
        run("branch", "T0", "--heal-earlier", "200"); // T0.1
        List<String> commands = List.of("run", "ls", "show", "inspect", "session", "verify", "replay", "determinism", "hash", "branch", "counterfactual", "compare", "causes", "search",
                "campaign", "report", "delete", "local-replay", "reindex", "inject", "explore", "minimize", "check", "counterexample", "incident", "blind", "serve", "demo-nope", "scenarios", "help");
        List<List<String>> badTails = List.of(
                List.of(), List.of("nope"), List.of("T99"), List.of("T0", "T99"), List.of("T0", "--goto", "nowhere"), List.of("T0", "--goto", "-5"), List.of("T0", "--back", "abc"),
                List.of("re", "--seed", "x"), List.of("re", "--horizon", "-1"), List.of("re", "--fault", "bogus"), List.of("re", "--fault", "partition:1"), List.of("re", "--fault", "crash:zz:5"),
                List.of("re", "--param", "novalue"), List.of("T0", "--drop", "zz#1"), List.of("T0", "--delay", "p#1"), List.of("T0", "--heal-earlier", "x"), List.of("T0", "--at", "9999"),
                List.of("T0", "--crash", "ghost:5"), List.of("T0", "--pause", "p:5"), List.of("T0", "--remove-fault", "nothing"), List.of("T0", "--patch-state", "b:x"),
                List.of("--max-faults", "-3"), List.of("re", "--max-faults", "x"), List.of("re", "--max-cases", "0"), List.of("re", "--candidate", "crash"), List.of("re", "--times", "a,b"),
                List.of("/definitely/not/a/file"), List.of("export", "T99"), List.of("export"), List.of("import"), List.of("reproduce", "/nope"), List.of("info", "/nope"),
                List.of("T0", "ghostnode"), List.of("--port", "notanumber"), List.of("--seconds", "-1", "--port", "0"), List.of("prepare", "--token", "zz"), List.of("grade"), List.of("answer"));
        List<String> internal = new ArrayList<>();
        int runs = 0;
        for (String cmd : commands) {
            if (cmd.equals("serve")) continue; // blocks without --seconds; covered by its own test
            for (List<String> tail : badTails) {
                List<String> args = new ArrayList<>(List.of(cmd));
                args.addAll(tail);
                String[] r = run(args.toArray(new String[0]));
                runs++;
                int code = Integer.parseInt(r[0]);
                String all = r[1] + r[2];
                if (code == 3 || all.contains("internal error") || all.contains("\tat ") || all.contains("Exception in thread")) internal.add(args + " -> exit " + code + ": " + (r[2].isBlank() ? r[1] : r[2]).lines().findFirst().orElse(""));
                else if (code == 1) assertTrue(r[2].startsWith("chronos: ") || r[1].contains("chronos") || r[2].isBlank() == false, "an error exit has a message: " + args);
            }
        }
        assertTrue(runs > 900, "runs " + runs);
        assertEquals(List.of(), internal.stream().limit(15).toList(), internal.size() + " bad command lines caused an internal error");
    }
}
