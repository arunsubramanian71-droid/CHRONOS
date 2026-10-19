package chronos.model;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CanonTest {
    @Test
    void mapKeyOrderDoesNotChangeTheBytes() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", List.of(1, 2));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", List.of(1L, 2L));
        b.put("b", 1L);
        assertEquals(Canon.json(a), Canon.json(b));
        assertEquals(Canon.digest(a), Canon.digest(b));
        assertEquals("{\"a\":[1,2],\"b\":1}", Canon.json(a));
    }

    @Test
    void floatsAndUnorderedSetsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Canon.normalize(1.5));
        assertThrows(IllegalArgumentException.class, () -> Canon.normalize(new HashSet<>(List.of("x"))));
        assertThrows(IllegalArgumentException.class, () -> Canon.normalize(Map.of(1, "x")));
        assertThrows(IllegalArgumentException.class, () -> Canon.normalize(new Object()));
    }

    @Test
    void parseRoundTripKeepsDigestsStable() {
        Map<String, Object> m = Map.of("k", List.of("a", true, 5, Map.of("z", 1, "y", 2)));
        assertEquals(Canon.digest(m), Canon.digest(Canon.parse(Canon.json(m))));
    }

    @Test
    void malformedJsonIsReported() {
        assertThrows(IllegalArgumentException.class, () -> Canon.parse("{\"a\":"));
    }

    @Test
    void identityKeyedDecisionsDependOnlyOnIdentity() {
        long x = IdentityHash.of(42, "latency", "p#17");
        assertEquals(x, IdentityHash.of(42, "latency", "p#17"));
        assertNotEquals(x, IdentityHash.of(42, "latency", "p#18"));
        assertNotEquals(x, IdentityHash.of(43, "latency", "p#17"));
        for (int i = 0; i < 100; i++) assertTrue(IdentityHash.bounded(1, 6, "k", "m" + i) < 6);
    }

    @Test
    void generatorStateIsOneNumberAndReproducible() {
        SeededRandom a = new SeededRandom(99), b = new SeededRandom(99);
        long s = 99;
        for (int i = 0; i < 50; i++) {
            long v = a.nextLong();
            assertEquals(v, b.nextLong());
            assertEquals(v, SeededRandom.valueAt(s));
            s = SeededRandom.advance(s);
        }
        assertEquals(s, a.state());
    }

    @Test
    void eventLineRoundTripAndDigests() {
        Event e = new Event("A#2", 2, "T0", "A", EventKind.MESSAGE_SENT, 0, 2, List.of(2, 0, 0),
                List.of(new Parent("A#1", EdgeKind.PROGRAM_ORDER), new Parent("A#1", EdgeKind.STEP_EFFECT)),
                Map.of("to", "B", "type", "ping", "body", Map.of(), "deliverAt", 10), "abc", Map.of("volatile", Map.of(), "durable", Map.of()), null);
        Event back = Event.fromLine(e.toLine());
        assertEquals(e, back);
        assertEquals(e.contentDigest(false), back.contentDigest(false));
        // structural digest ignores virtual time and timing fields, the strict one does not
        Event later = new Event("A#2", 9, "T9", "A", EventKind.MESSAGE_SENT, 5, 2, List.of(2, 0, 0), e.parents(),
                Map.of("to", "B", "type", "ping", "body", Map.of(), "deliverAt", 15), "abc", null, null);
        assertEquals(e.contentDigest(false), later.contentDigest(false));
        assertNotEquals(e.contentDigest(true), later.contentDigest(true));
        // origin is part of the digest (D-25)
        Event iv = new Event("A#2", 2, "T0", "A", EventKind.MESSAGE_SENT, 0, 2, List.of(2, 0, 0), e.parents(), e.payload(), "abc", null, "iv:x");
        assertNotEquals(e.contentDigest(false), iv.contentDigest(false));
    }

    @Test
    void faultRuleValidationFollowsD13() {
        assertThrows(IllegalArgumentException.class, () -> FaultRule.nth("r", FaultRule.FaultAction.DROP, "*", "b", "T", 1, null));
        assertDoesNotThrow(() -> FaultRule.probabilistic("r", FaultRule.FaultAction.DROP, "*", "*", "*", 50, null));
        Map<String, Object> bad = new TreeMap<>(Map.of("messageId", "p#1", "maxFirings", 2));
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("r", FaultRule.TriggerType.ON_MESSAGE_ID, FaultRule.FaultAction.DROP, bad, false));
        assertThrows(IllegalArgumentException.class, () -> new FaultRule("bad#id", FaultRule.TriggerType.AT_TIME, FaultRule.FaultAction.CRASH, Map.of("node", "a", "at", 1), false));
        assertThrows(IllegalArgumentException.class, () -> FaultPlan.of(FaultRule.crash("r", "a", 1), FaultRule.restart("r", "a", 2)));
        assertTrue(FaultPlan.of(FaultRule.probabilistic("r", FaultRule.FaultAction.DROP, "*", "*", "*", 50, null)).hasNonLocalRules());
    }
}
