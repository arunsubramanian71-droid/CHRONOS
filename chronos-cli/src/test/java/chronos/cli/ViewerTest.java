package chronos.cli;

import chronos.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** FR-50: the live local viewer serves the same answers as the command line, read-only, on the loopback address. */
class ViewerTest {
    @TempDir
    Path tmp;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(Viewer v, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theViewerAnswersFromTheStoreAndNeverChangesIt() throws Exception {
        Env env = new Env(tmp);
        env.record("re", 42, Map.of());
        String t0 = env.last;
        env.manager.branch(t0, env.manager.roundToBoundary(t0, 70), List.of(Intervention.shiftHeal("h", "partition-pb", -200)), null, null);
        String hashBefore = env.store.summary(t0).logHash();
        List<String> idsBefore = env.store.timelineIds();
        try (Viewer v = new Viewer(env.store, "127.0.0.1", 0)) {
            v.start();
            // the page: fixed, self-contained
            HttpResponse<String> page = get(v, "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("CHRONOS live viewer") && page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            // it fetches only from its own server (the report's stricter check also forbids fetch); nothing else outside the page
            String body = page.body().replace("http://www.w3.org/2000/svg", "").replace("http://www.w3.org/1999/xhtml", "");
            assertFalse(body.contains("http://") || body.contains("https://") || body.contains("<link ") || body.contains("@import") || body.contains("src=\"//"), "no external resource");
            assertFalse(body.contains("<script src"), "no external script");
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
            // the timeline list
            HttpResponse<String> list = get(v, "/api/timelines");
            List<Object> tls = Canon.list(Canon.parse(list.body()));
            assertEquals(idsBefore.size(), tls.size());
            Map<String, Object> first = Canon.map(tls.get(0));
            assertEquals("T0", first.get("id"));
            assertTrue(((String) first.get("violation")).startsWith("single-primary"), list.body());
            // events: same events and descriptions as the command line sees
            Map<String, Object> ev = Canon.map(Canon.parse(get(v, "/api/timeline/T0/events").body()));
            assertEquals((long) env.events(t0).size(), Canon.num(ev.get("total")));
            assertTrue(Canon.list(ev.get("nodes")).containsAll(List.of("client", "p", "b")));
            // the world at the violation equals what a session says
            long vseq = env.events(t0).stream().filter(e -> e.kind() == EventKind.INVARIANT_VIOLATED).findFirst().orElseThrow().seq();
            Map<String, Object> w = Canon.map(Canon.parse(get(v, "/api/timeline/T0/at/" + vseq).body()));
            Map<String, Object> states = Canon.map(w.get("states"));
            assertEquals("PRIMARY", Canon.map(Canon.map(states.get("b")).get("volatile")).get("role"));
            assertEquals("PRIMARY", Canon.map(Canon.map(states.get("p")).get("volatile")).get("role"));
            // why, knows and compare are the same text the CLI prints
            String why = get(v, "/api/timeline/T0/why/inv:single-primary%231").body();
            assertTrue(why.contains("fault:partition-pb#1"), why);
            assertTrue(get(v, "/api/timeline/T0/knows/b%2312").body().contains("knowledge of b"));
            String cmp = get(v, "/api/compare?a=T0&b=T0.1").body();
            assertTrue(cmp.contains("first causal divergence") || cmp.toLowerCase().contains("divergence"), cmp);
            // bad requests are refused, not crashed on
            assertEquals(400, get(v, "/api/timeline/nope/events").statusCode());
            assertEquals(400, get(v, "/api/timeline/T0/at/999999").statusCode());
            assertEquals(400, get(v, "/api/timeline/T0/at/abc").statusCode());
            assertEquals(400, get(v, "/api/timeline/..%2F..%2Fetc/events").statusCode());
            assertEquals(404, get(v, "/nothing").statusCode());
            // read-only: nothing but GET and HEAD
            for (String m : List.of("POST", "PUT", "DELETE")) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + "/api/timelines"))
                        .method(m, m.equals("DELETE") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, r.statusCode(), m);
            }
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + "/")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(idsBefore, env.store.timelineIds());
        assertEquals(hashBefore, env.store.summary(t0).logHash());
    }

    @Test
    void theServeCommandStartsAndStopsCleanly() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(), err = new java.io.ByteArrayOutputStream();
        int code = Main.run(new String[]{"--data", tmp.toString(), "serve", "--port", "0", "--seconds", "1"}, new java.io.PrintStream(out, true), new java.io.PrintStream(err, true), java.io.InputStream.nullInputStream());
        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("live viewer (read-only) on http://127.0.0.1:"), out.toString());
    }

    @Test
    void theCausalEvidenceEndpointSeparatesCertainFromPotentialLinksAndLargeTimelinesAreTruncatedHonestly() throws Exception {
        Env env = new Env(tmp.resolve("ev"));
        env.record("re", 42, Map.of(), null, Boolean.FALSE);
        env.record("gossip", 3, Map.of("nodes", "5", "horizon", "4000"), null, Boolean.FALSE); // well over 5,000 events
        try (Viewer v = new Viewer(env.store, "127.0.0.1", 0)) {
            v.start();
            Map<String, Object> past = Canon.map(Canon.parse(get(v, "/api/timeline/T0/past/inv:single-primary%231").body()));
            List<Object> chains = Canon.list(past.get("chains"));
            assertFalse(chains.isEmpty());
            boolean sawPotential = false, sawCertain = false;
            for (Object ch : chains)
                for (Object hop : Canon.list(ch)) {
                    Map<String, Object> h = Canon.map(hop);
                    if (!h.containsKey("edgeToNext")) continue;
                    boolean certain = Boolean.TRUE.equals(h.get("certain"));
                    assertEquals(!"OMISSION".equals(h.get("edgeToNext")), certain, "only OMISSION edges are potential: " + h);
                    sawPotential |= !certain;
                    sawCertain |= certain;
                }
            assertTrue(sawPotential && sawCertain, "the flagship chain has both a potential OMISSION link and certain ones");
            assertTrue(Canon.list(past.get("past")).size() > 10);
            assertEquals(400, get(v, "/api/timeline/T0/past/nope%231").statusCode());
            // a timeline over the display limit
            long total = env.events("T1").size();
            assertTrue(total > 5000, "events: " + total);
            Map<String, Object> ev = Canon.map(Canon.parse(get(v, "/api/timeline/T1/events").body()));
            assertEquals(total, Canon.num(ev.get("total")));
            assertEquals(Boolean.TRUE, ev.get("truncated"));
            assertEquals(5000, Canon.list(ev.get("events")).size());
            // the rest of the timeline is still reachable: the world at the very last position, and the causal evidence of an event beyond the limit
            assertEquals(200, get(v, "/api/timeline/T1/at/" + (total - 1)).statusCode());
            Event beyond = env.events("T1").get((int) total - 1);
            assertEquals(200, get(v, "/api/timeline/T1/past/" + java.net.URLEncoder.encode(beyond.eventId(), java.nio.charset.StandardCharsets.UTF_8)).statusCode());
            assertEquals(400, get(v, "/api/timeline/T1/at/" + total).statusCode());
        }
    }
}
