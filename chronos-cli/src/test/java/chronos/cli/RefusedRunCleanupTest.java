package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run or branch that is refused after its writer was opened (an unknown node in a fault rule or intervention) must leave nothing behind:
 * found by {@code chronos check} reporting an unfinished "T0.1 RECORDING?" after a refused {@code branch} (DEV-49). A finished timeline is never removed.
 */
class RefusedRunCleanupTest {
    @TempDir
    Path tmp;

    @Test
    void aRefusedBranchLeavesNoTimelineNoCatalogueEntryAndTheNextBranchTakesTheSameId() throws Exception {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t0 = env.last;
        List<String> before = env.store.timelineIds();
        long at = env.manager.roundToBoundary(t0, 20);
        assertThrows(IllegalArgumentException.class, () -> env.manager.branch(t0, at, List.of(Intervention.crashNode("c", "ghost", 400)), null, null));
        assertEquals(before, env.store.timelineIds(), "nothing was left in the store");
        assertFalse(Canon.map(env.store.readCatalog().get("timelines")).containsKey("T0.1"), "nor in the catalogue");
        assertTrue(new StoreChecker(env.store, env.registry).check(List.of(), true).ok());
        String child = env.manager.branch(t0, at, List.of(Intervention.crashNode("c", "b", 400)), null, null).timelineId();
        assertEquals("T0.1", child, "the id of the refused attempt is reused");
        StoreChecker.Report r = new StoreChecker(env.store, env.registry).check(List.of(), true);
        assertTrue(r.ok() && r.count(StoreChecker.Status.WARN) == 0, r.text());
    }

    @Test
    void aRefusedRunLeavesNoTimelineAndAFinishedOneIsNeverDiscarded() throws Exception {
        Env env = new Env(tmp);
        var sc = env.registry.create("re", Map.of());
        FaultPlan bad = FaultPlan.of(FaultRule.crash("zz-crash", "zz", 5));
        assertThrows(IllegalArgumentException.class, () -> Recording.record(env.store, env.registry, "T0", Recording.Spec.of("re", 42).withPlan(bad)));
        assertEquals(List.of(), env.store.timelineIds());
        assertTrue(Canon.map(env.store.readCatalog().get("timelines")).isEmpty());
        assertEquals("T0", env.manager.allocateRootId());
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        String t = env.last;
        assertFalse(env.store.discardUnfinished(t), "a finished timeline is not discarded");
        assertTrue(env.store.exists(t) && env.store.summary(t) != null);
        assertFalse(env.store.discardUnfinished("T99"), "an unknown id is not an error");
        assertNotNull(sc);
    }

    @Test
    void aNegativeHorizonIsRefusedBeforeAnythingIsWritten() throws Exception {
        Env env = new Env(tmp);
        var e = assertThrows(IllegalArgumentException.class, () -> Recording.record(env.store, env.registry, "T0", Recording.Spec.of("re", 42).withHorizon(-1L)));
        assertTrue(e.getMessage().contains("horizon"), e.getMessage());
        assertEquals(List.of(), env.store.timelineIds());
    }

    private record R(int code, String out, String err) {}

    private R cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] full = new String[args.length + 2];
        full[0] = "--data";
        full[1] = tmp.resolve("cli").toString();
        System.arraycopy(args, 0, full, 2, args.length);
        int code = Main.run(full, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        return new R(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommandsReportTheRefusalAndLeaveTheStoreClean() {
        assertEquals(1, cli("run", "re", "--fault", "crash:zz:5").code());
        assertEquals(1, cli("run", "re", "--horizon", "-1").code());
        assertEquals(1, cli("explore", "re", "--horizon", "-1").code());
        assertTrue(cli("ls").out().contains("no timelines yet"), cli("ls").out());
        assertEquals(0, cli("run", "re", "--no-stop").code());
        R negative = cli("branch", "T0", "--at", "20", "--horizon", "-5", "--remove-fault", "partition-pb");
        assertEquals(1, negative.code());
        assertTrue(negative.err().contains("horizon"), negative.err());
        R refused = cli("branch", "T0", "--at", "20", "--crash", "ghost:5");
        assertEquals(1, refused.code());
        assertTrue(refused.err().contains("unknown node ghost"), refused.err());
        assertFalse(cli("ls").out().contains("RECORDING?"), cli("ls").out());
        R check = cli("check", "--strict");
        assertEquals(0, check.code(), check.out());
    }
}
