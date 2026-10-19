package chronos.cli;

import chronos.analysis.Recording;
import chronos.model.Event;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden files (Section 25): checked-in event logs of the demo scenarios. Any difference must be a conscious, reviewed decision.
 * To accept an intended change: mvn -pl chronos-cli -am test -Dtest=GoldenFileTest -Dchronos.golden.update=true, then review the diff.
 */
class GoldenFileTest {
    private record Case(String name, String scenario, Map<String, String> params, long seed, Boolean stop) {}

    private static final List<Case> CASES = List.of(
            new Case("pingpong", "pingpong", Map.of(), 1, null),
            new Case("causal", "causal", Map.of(), 1, null),
            new Case("re", "re", Map.of(), 42, Boolean.FALSE),
            new Case("re2", "re2", Map.of(), 42, null),
            new Case("ka2", "ka2", Map.of(), 1, null),
            new Case("ka3", "ka3", Map.of(), 1, null),
            new Case("gossip", "gossip", Map.of("nodes", "3", "horizon", "150"), 5, null));

    @Test
    void theRecordedLogsEqualTheCheckedInGoldenFiles() throws IOException {
        boolean update = Boolean.getBoolean("chronos.golden.update");
        for (Case c : CASES) {
            Recording.MemoryRun run = Recording.recordInMemory(new TestRegistry(), "T0",
                    Recording.Spec.of(c.scenario(), c.seed()).withParams(c.params()).withStopOnViolation(c.stop()).withCheckpointInterval(0));
            List<String> actual = run.log().events().stream().map(Event::toLine).toList();
            String resource = "/golden/" + c.name() + ".jsonl";
            if (update) {
                Path out = Path.of("src/test/resources" + resource);
                Files.createDirectories(out.getParent());
                Files.write(out, actual, StandardCharsets.UTF_8);
                continue;
            }
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                assertNotNull(in, "missing golden file " + resource + " (generate with -Dchronos.golden.update=true)");
                List<String> golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                assertEquals(golden.size(), actual.size(), c.name() + ": number of events differs from the golden file");
                for (int i = 0; i < golden.size(); i++) assertEquals(golden.get(i), actual.get(i), c.name() + ": event at position " + i + " differs from the golden file");
            }
        }
    }

    @Test
    void thePingPongGoldenFileIsTheSectionTenRecording() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/golden/pingpong.jsonl")) {
            assertNotNull(in);
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertEquals(6, lines.size());
            Event a2 = Event.fromLine(lines.get(2));
            assertEquals("A#2", a2.eventId());
            assertEquals(List.of(2, 0, 0), a2.vector(), "all N + 1 slots are stored, env's slot included");
            assertEquals(List.of(3, 3, 0), Event.fromLine(lines.get(5)).vector());
        }
    }
}
