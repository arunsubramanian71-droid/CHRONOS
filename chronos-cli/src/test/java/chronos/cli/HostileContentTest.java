package chronos.cli;

import chronos.analysis.*;
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

/**
 * Untrusted content: node names, notes, message types and bodies, state values and invariant messages all come from the application under test
 * (or from a bundle someone sent), so a report or the viewer must show them as text and never as markup, script or attribute.
 */
class HostileContentTest {
    @TempDir
    Path tmp;

    private static final String EVIL = TestRegistry.Hostile.EVIL;

    @Test
    void theHtmlReportShowsHostileApplicationContentAsTextOnly() {
        Env env = new Env(tmp);
        env.record("hostile", 1, Map.of(), FaultPlan.empty(), Boolean.FALSE);
        String t0 = env.last;
        assertTrue(env.store.summary(t0).firstViolationInvariant() != null, "the hostile scenario does violate its invariant, so every section is rendered");
        var br = env.manager.branch(t0, env.manager.bootEnd(t0), List.of(), null, "label " + EVIL);
        String html = new HtmlReport(env.store, env.registry, 400, 200).render(t0, br.timelineId(), null, "chronos report " + t0);
        String html1 = new HtmlReport(env.store, env.registry, 400, 200).render(t0, null, null, "chronos report " + t0);
        for (String page : List.of(html, html1)) {
            assertFalse(page.contains(EVIL), "the raw hostile string must not appear");
            assertFalse(page.contains("<script>alert"), "no injected script element");
            assertFalse(page.contains("<img src=x"), "no injected element");
            assertFalse(page.contains("onerror=alert(2)>"), "no injected attribute");
            assertFalse(page.contains("<!--"), "no injected comment");
            assertFalse(page.contains("a<b>"), "node names are escaped (diagram, tables)");
            assertFalse(page.contains("c\"d"), "a quote in a node name cannot close an attribute");
            assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "the content is still shown, escaped");
            assertFalse(HtmlReport.referencesExternalResources(page));
            // exactly the report's own inline scripts remain
            int scripts = 0;
            for (int i = page.indexOf("<script"); i >= 0; i = page.indexOf("<script", i + 1)) scripts++;
            assertTrue(scripts <= 2, "script elements: " + scripts);
        }
    }

    @Test
    void theViewerServesHostileContentAsJsonAndTheFixedPageRendersItAsText() throws Exception {
        Env env = new Env(tmp);
        env.record("hostile", 1, Map.of(), FaultPlan.empty(), Boolean.FALSE);
        String t0 = env.last;
        HttpClient http = HttpClient.newHttpClient();
        try (Viewer v = new Viewer(env.store, "127.0.0.1", 0)) {
            v.start();
            for (String path : List.of("/api/timelines", "/api/timeline/" + t0 + "/events", "/api/timeline/" + t0 + "/why/inv:inv%3Ci%3E%231")) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertFalse(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), path + " must not be served as HTML");
                assertTrue(r.headers().firstValue("X-Content-Type-Options").isPresent() || !r.headers().firstValue("Content-Type").orElse("").contains("html"), path);
            }
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + v.port() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(page.body().contains("innerHTML"), "the fixed page never assigns markup");
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        }
    }
}
