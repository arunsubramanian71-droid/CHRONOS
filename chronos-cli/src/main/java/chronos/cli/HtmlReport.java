package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.sdk.ScenarioRegistry;
import chronos.store.TimelineStore;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The static, self-contained HTML report (FR-46, D-18, AD-6): one file, inline CSS, inline SVG and a few lines of inline
 * JavaScript, no network calls and no server. It shows the scenario and nodes, a space-time diagram with a lane per node
 * (env last) and the causal arrows, the event table with all N + 1 vector slots, the causal path to the failure, the replay
 * verification result, fault and intervention information and, when a second timeline is given, the baseline-versus-branch
 * comparison with the divergence cone and the explanation path highlighted. It holds no logic of its own: every number comes
 * from the analysis components.
 */
final class HtmlReport {
    private final TimelineStore store;
    private final ScenarioRegistry registry;
    private final int maxDiagramEvents;
    private final int maxTableRows;

    HtmlReport(TimelineStore store, ScenarioRegistry registry, int maxDiagramEvents, int maxTableRows) {
        this.store = store;
        this.registry = registry;
        this.maxDiagramEvents = maxDiagramEvents;
        this.maxTableRows = maxTableRows;
    }

    String render(String timeline, String against, CounterfactualEngine.Report cf, String regenerateCommand) {
        TimelineHeader h = store.header(timeline);
        TimelineSummary sum = store.summary(timeline);
        List<Event> events = store.events(timeline);
        CausalGraph graph = CausalGraph.build(events);
        ReplayEngine.Result replay = new ReplayEngine(store, registry).verify(timeline);
        DivergenceReport div = against == null ? null : new TimelineComparator(store).compare(timeline, against, false);

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>CHRONOS report ").append(esc(timeline)).append(against != null ? " vs " + esc(against) : "").append("</title>\n<style>").append(CSS).append("</style></head><body>\n");
        sb.append("<header><h1>CHRONOS <span>execution report</span></h1><p>").append(esc(h.scenario())).append(" &middot; timeline <b>").append(esc(timeline)).append("</b>");
        if (against != null) sb.append(" compared with <b>").append(esc(against)).append("</b>");
        sb.append("</p></header>\n<main>\n");

        // ---------------------------------------------------------------- summary
        sb.append("<section><h2>Scenario and run</h2><div class=\"cards\">");
        card(sb, "Scenario", h.scenario() + (h.params().isEmpty() ? "" : " " + h.params()));
        card(sb, "Seed", Long.toString(h.seed()));
        card(sb, "Nodes", String.join(", ", h.nodes()) + " (+ env)");
        card(sb, "Status", sum == null ? "RECORDING (no summary: unfinished)" : sum.status().name());
        card(sb, "Events", Integer.toString(events.size()));
        card(sb, "Final virtual time", sum == null ? "?" : sum.finalVirtualTime() + " ms");
        card(sb, "Horizon", h.horizon() + " ms");
        card(sb, "Stop on violation", Boolean.toString(h.stopOnViolation()));
        if (h.parentId() != null) card(sb, "Branch of", h.parentId() + " at position " + h.branchSeq());
        sb.append("</div>");
        if (sum != null && sum.firstViolationEventId() != null)
            sb.append("<p class=\"bad\">First invariant violation: <b>").append(esc(sum.firstViolationEventId())).append("</b> (").append(esc(sum.firstViolationInvariant())).append(")</p>");
        else sb.append("<p class=\"good\">No invariant was violated in this timeline.</p>");
        sb.append("</section>\n");

        // ---------------------------------------------------------------- replay verification and persistence
        sb.append("<section><h2>Replay verification</h2>");
        if (replay.ok()) sb.append("<p class=\"good\">VERIFIED: the engine re-executed the application under the recorded decisions and regenerated all <b>").append(replay.eventsVerified())
                .append("</b> events exactly (ids, kinds, times, Lamport and vector clocks, parents, payloads, state digests).</p><p>Log hash (SHA-256): <code>").append(esc(replay.logHash())).append("</code></p>");
        else sb.append("<p class=\"bad\">MISMATCH</p><pre>").append(esc(replay.violation().report())).append("</pre>");
        for (String w : replay.warnings()) sb.append("<p class=\"warn\">Warning: ").append(esc(w)).append("</p>");
        if (h.faultPlan().localityNote() != null) sb.append("<p class=\"warn\">Note: ").append(esc(h.faultPlan().localityNote())).append("</p>");
        sb.append("<h3>Persistence</h3><ul>");
        sb.append("<li>format version ").append(h.formatVersion()).append(", code version ").append(esc(h.codeVersion())).append(", class fingerprint <code>").append(esc(String.valueOf(h.codeFingerprint()))).append("</code></li>");
        sb.append("<li>storage: <code>").append(esc(store.root().getFileName() + "/timelines/" + timeline)).append("</code>; segments ").append(esc(store.segments(timeline).stream().map(sg -> sg.timelineId() + " [" + sg.fromSeq() + ".." + sg.toSeq() + "]").collect(Collectors.joining(", ")))).append("</li>");
        sb.append("<li>checkpoints at positions ").append(esc(store.checkpointSeqs(timeline).toString())).append("; step boundaries: ").append(store.groupEnds(timeline).size()).append("</li>");
        sb.append("<li>unfinished tail lines discarded on load: ").append(store.discardedTailLines(timeline)).append("</li></ul></section>\n");

        // ---------------------------------------------------------------- faults and interventions
        sb.append("<section><h2>Faults and interventions</h2>");
        if (h.faultPlan().rules().isEmpty()) sb.append("<p>No fault plan.</p>");
        else {
            sb.append("<table><thead><tr><th>rule</th><th>trigger</th><th>action</th><th>parameters</th><th>fired</th></tr></thead><tbody>");
            Map<String, Long> cov = sum == null ? Map.of() : sum.faultCoverage();
            for (FaultRule r : h.faultPlan().rules())
                sb.append("<tr><td>").append(esc(r.id())).append("</td><td>").append(r.trigger()).append("</td><td>").append(r.action()).append("</td><td><code>")
                        .append(esc(Canon.json(r.params()))).append("</code></td><td>").append(cov.getOrDefault(r.id(), 0L)).append(cov.getOrDefault(r.id(), 0L) == 0 ? " &#9888; never fired" : "").append("</td></tr>");
            sb.append("</tbody></table>");
        }
        if (!h.interventions().isEmpty()) {
            sb.append("<h3>Interventions applied at the branch point (").append(h.branchSeq()).append(")</h3><ul>");
            for (Intervention iv : h.interventions()) sb.append("<li><b>").append(esc(iv.eventId())).append("</b> ").append(iv.type()).append(" <code>").append(esc(Canon.json(iv.params()))).append("</code></li>");
            sb.append("</ul>");
        }
        sb.append("</section>\n");

        // ---------------------------------------------------------------- counterfactual verdict
        if (cf != null) {
            sb.append("<section><h2>Counterfactual verdict</h2><p class=\"verdict\">").append(cf.verdict()).append("</p><p>").append(esc(cf.explanation())).append("</p>")
                    .append("<p>baseline failure: ").append(esc(cf.baselineFailure() == null ? "none" : cf.baselineFailure())).append("<br>branch failure: ").append(esc(cf.branchFailure() == null ? "none" : cf.branchFailure()))
                    .append("<br>events in the causal future of the intervention: ").append(cf.affectedEvents()).append("</p>")
                    .append("<p class=\"small\">A counterfactual is evidence about the simulated world under CHRONOS's rules, not proof about reality.</p></section>\n");
        }

        // ---------------------------------------------------------------- comparison
        Set<String> coneA = div == null ? Set.of() : new HashSet<>(div.coneEventsA());
        Set<String> coneB = div == null ? Set.of() : new HashSet<>(div.coneEventsB());
        Set<String> rootsA = new HashSet<>(), rootsB = new HashSet<>();
        Set<String> pathIds = new HashSet<>();
        if (div != null) {
            for (TimelineComparator.Root r : div.roots()) (r.side() == 'A' ? rootsA : rootsB).add(r.eventId());
            for (DivergenceReport.Hop hop : div.path()) {
                pathIds.add(hop.from());
                pathIds.add(hop.to());
            }
        }

        // ---------------------------------------------------------------- diagram of the main timeline
        sb.append("<section><h2>Space-time diagram: ").append(esc(timeline)).append("</h2>");
        legend(sb, div != null);
        sb.append(diagram(events, graph, h.nodes(), coneA, rootsA, div != null && timeline.equals(div.failureTimeline()) ? pathIds : Set.of()));
        sb.append("</section>\n");

        // ---------------------------------------------------------------- the path to the failure (single timeline)
        if (sum != null && sum.firstViolationEventId() != null) {
            sb.append("<section><h2>Why did it fail?</h2><p>The causal past of <b>").append(esc(sum.firstViolationEventId())).append("</b> and the chains of certain causes and OMISSION links back to their sources (faults first):</p><pre>");
            TimeTravelSession s = new TimeTravelSession(store, timeline);
            s.goToViolation();
            sb.append(esc(s.why(null))).append("</pre></section>\n");
        }

        // ---------------------------------------------------------------- comparison section
        if (div != null) {
            List<Event> bEvents = store.events(against);
            sb.append("<section><h2>Comparison: ").append(esc(timeline)).append(" (reference) vs ").append(esc(against)).append("</h2>");
            for (String w : div.warnings()) sb.append("<p class=\"warn\">Warning: ").append(esc(w)).append("</p>");
            sb.append("<p>Shared prefix: <b>").append(div.sharedPrefix()).append("</b> events (identical by construction). After it: ").append(esc(div.counts().toString())).append("</p>");
            if (div.identical()) sb.append("<p class=\"good\">No divergence: the timelines are identical after their shared prefix.</p>");
            else {
                sb.append("<p>First causal divergence: <b class=\"bad\">").append(esc(div.primary().eventId())).append("</b> (").append(div.primary().cls()).append(" on <b>").append(esc(div.primary().nodeId()))
                        .append("</b> at t=").append(div.primary().virtualTime()).append(" ms, present only in ").append(esc(div.primary().side() == 'A' ? timeline : against)).append(").</p>");
                sb.append("<p>Divergence cone: ").append(div.coneA()).append(" events in ").append(esc(timeline)).append(", ").append(div.coneB()).append(" in ").append(esc(against))
                        .append("; nodes affected: ").append(esc(div.affectedNodes().toString())).append(".</p>");
                sb.append("<h3>Causal roots</h3><table><thead><tr><th></th><th>event</th><th>timeline</th><th>node</th><th>t (ms)</th><th>class</th></tr></thead><tbody>");
                for (TimelineComparator.Root r : div.roots())
                    sb.append("<tr").append(r == div.primary() ? " class=\"primary\"" : "").append("><td>").append(r == div.primary() ? "&#9733;" : "").append("</td><td>").append(esc(r.eventId())).append("</td><td>")
                            .append(esc(r.side() == 'A' ? timeline : against)).append("</td><td>").append(esc(r.nodeId())).append("</td><td>").append(r.virtualTime()).append("</td><td>").append(r.cls()).append("</td></tr>");
                sb.append("</tbody></table><p class=\"small\">Taint is computed separately inside each timeline; roots are ordered by virtual time, node order (env last) and event id. Lamport and vector values are never compared across timelines (D-16).</p>");
                sb.append("<h3>Each node's first local divergence</h3><ul>");
                for (TimelineComparator.LocalDivergence l : div.firstLocal())
                    sb.append("<li><b>").append(esc(l.node())).append("</b>: ").append(esc(String.valueOf(l.eventA()))).append(" vs ").append(esc(String.valueOf(l.eventB()))).append(" &mdash; ").append(esc(l.kind())).append("</li>");
                sb.append("</ul>");
                if (div.failureEvent() != null) {
                    sb.append("<h3>Explanation path in ").append(esc(div.failureTimeline())).append(" to ").append(esc(div.failureEvent())).append("</h3>");
                    if (div.pathNote() != null) sb.append("<p class=\"small\">").append(esc(div.pathNote())).append("</p>");
                    sb.append("<ol class=\"path\">");
                    for (DivergenceReport.Hop hop : div.path()) sb.append("<li>").append(esc(hop.text())).append("</li>");
                    sb.append("</ol>");
                }
                for (String f : div.flags()) sb.append("<p class=\"warn\">").append(esc(f)).append("</p>");
                if (!div.unexplained().isEmpty()) sb.append("<p class=\"bad\">Unexplained divergent events: ").append(esc(div.unexplained().toString())).append("</p>");
            }
            sb.append("<p>Outcomes: ").append(esc(timeline)).append(": ").append(esc(div.outcomeA())).append(" &middot; ").append(esc(against)).append(": ").append(esc(div.outcomeB())).append("</p>");
            sb.append("<h3>Space-time diagram: ").append(esc(against)).append("</h3>");
            sb.append(diagram(bEvents, CausalGraph.build(bEvents), store.header(against).nodes(), coneB, rootsB, !timeline.equals(div.failureTimeline()) ? pathIds : Set.of()));
            sb.append("</section>\n");
        }

        // ---------------------------------------------------------------- final states
        sb.append("<section><h2>Node states at the end</h2><table><thead><tr><th>node</th><th>state (volatile / durable zone)</th></tr></thead><tbody>");
        TimeTravelSession end = new TimeTravelSession(store, timeline);
        for (String n : h.nodes()) sb.append("<tr><td>").append(esc(n)).append("</td><td><code>").append(esc(Canon.json(end.stateOf(n)))).append("</code></td></tr>");
        sb.append("</tbody></table></section>\n");

        // ---------------------------------------------------------------- event table
        sb.append("<section><h2>Events</h2><p><input id=\"filter\" placeholder=\"filter events (node, kind, id, text) ...\" size=\"40\"> ").append(events.size() > maxTableRows ? "showing the first " + maxTableRows + " of " + events.size() : events.size() + " events").append("</p>");
        List<String> slots = new ArrayList<>(h.nodes());
        slots.add("env");
        sb.append("<div class=\"scroll\"><table id=\"events\"><thead><tr><th>seq</th><th>event id</th><th>node</th><th>kind</th><th>t (ms)</th><th>Lamport</th><th>vector [").append(esc(String.join(",", slots)))
                .append("]</th><th>causal parents</th><th>what happened</th></tr></thead><tbody>");
        for (Event e : events.subList(0, Math.min(events.size(), maxTableRows))) {
            sb.append("<tr id=\"row-").append(e.seq()).append("\" class=\"k-").append(cls(e)).append("\"><td>").append(e.seq()).append("</td><td>").append(esc(e.eventId())).append("</td><td>").append(esc(e.nodeId())).append("</td><td>")
                    .append(e.kind()).append("</td><td>").append(e.virtualTime()).append("</td><td>").append(e.lamport()).append("</td><td>").append(Exports.vec(e.vector())).append("</td><td>");
            sb.append(e.parents().stream().filter(p -> p.kind() != EdgeKind.PROGRAM_ORDER && p.kind() != EdgeKind.STEP_EFFECT).map(p -> esc(p.id()) + "&nbsp;<small>" + p.kind() + "</small>").distinct().collect(Collectors.joining(", ")));
            sb.append("</td><td>").append(esc(Exports.describe(e, graph))).append(e.origin() != null ? " <small class=\"iv\">origin " + esc(e.origin()) + "</small>" : "").append("</td></tr>");
        }
        sb.append("</tbody></table></div></section>\n");

        // ---------------------------------------------------------------- timelines
        sb.append("<section><h2>Timeline tree</h2><pre>").append(esc(new TimelineManager(store, registry).tree())).append("</pre></section>\n");
        sb.append("</main><footer>Regenerate this report: <code>").append(esc(regenerateCommand)).append("</code><br>Self-contained: no network access, no server. Generated from the recorded log by CHRONOS (specification v0.2).</footer>\n");
        sb.append("<script>").append(JS).append("</script></body></html>\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void card(StringBuilder sb, String k, String v) {
        sb.append("<div class=\"card\"><small>").append(esc(k)).append("</small><b>").append(esc(v)).append("</b></div>");
    }

    private static void legend(StringBuilder sb, boolean cmp) {
        sb.append("<p class=\"legend\"><i class=\"lg send\"></i>send <i class=\"lg recv\"></i>delivery <i class=\"lg timer\"></i>timer <i class=\"lg fault\"></i>fault <i class=\"lg drop\"></i>drop <i class=\"lg viol\"></i>violation <i class=\"lg iv\"></i>intervention &nbsp;|&nbsp; ")
                .append("<span class=\"e-message\">&#9472; message</span> <span class=\"e-fault\">&#9472; fault</span> <span class=\"e-omission\">&#9548; omission</span> <span class=\"e-witness\">&#9548; witness</span>");
        if (cmp) sb.append(" &nbsp;|&nbsp; <span class=\"ring cone\">&#9711;</span> divergence cone <span class=\"ring root\">&#9711;</span> causal root <span class=\"e-path\">&#9472;</span> explanation path");
        sb.append(" &nbsp; (hover an event to light its arrows, click it to jump to its row)</p>");
    }

    private String diagram(List<Event> events, CausalGraph g, List<String> nodes, Set<String> cone, Set<String> roots, Set<String> path) {
        int n = Math.min(events.size(), maxDiagramEvents);
        List<String> lanes = new ArrayList<>(nodes);
        lanes.add("env");
        int step = 18, left = 90, laneH = 62, top = 28;
        int width = left + n * step + 30, height = top + lanes.size() * laneH + 10;
        StringBuilder sb = new StringBuilder();
        if (events.size() > n) sb.append("<p class=\"small\">Diagram limited to the first ").append(n).append(" of ").append(events.size()).append(" events (the table lists more).</p>");
        sb.append("<div class=\"scroll\"><svg width=\"").append(width).append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\" role=\"img\" aria-label=\"space-time diagram\">");
        for (int i = 0; i < lanes.size(); i++) {
            int y = top + i * laneH + laneH / 2;
            sb.append("<text x=\"8\" y=\"").append(y + 4).append("\" class=\"lane\">").append(esc(lanes.get(i))).append("</text><line x1=\"").append(left - 10).append("\" y1=\"").append(y).append("\" x2=\"").append(width - 10)
                    .append("\" y2=\"").append(y).append("\" class=\"laneline\"/>");
        }
        // time ticks: virtual time every ~10 events
        for (int i = 0; i < n; i += 8) sb.append("<text x=\"").append(left + i * step).append("\" y=\"14\" class=\"tick\">t=").append(events.get(i).virtualTime()).append("</text>");
        Map<String, Integer> laneOf = new HashMap<>();
        for (int i = 0; i < lanes.size(); i++) laneOf.put(lanes.get(i), i);
        for (int i = 0; i < n; i++) {
            Event e = events.get(i);
            for (Parent p : e.parents()) {
                if (p.kind() == EdgeKind.PROGRAM_ORDER) continue;
                int pi = g.pos(p.id());
                if (pi < 0 || pi >= n) continue;
                Event pe = events.get(pi);
                if (p.kind() == EdgeKind.STEP_EFFECT && pe.nodeId().equals(e.nodeId())) continue;
                int x1 = left + pi * step, y1 = top + laneOf.get(pe.nodeId()) * laneH + laneH / 2;
                int x2 = left + i * step, y2 = top + laneOf.get(e.nodeId()) * laneH + laneH / 2;
                boolean onPath = path.contains(e.eventId()) && path.contains(pe.eventId());
                sb.append("<line class=\"edge e-").append(p.kind().name().toLowerCase()).append(onPath ? " e-path" : "").append("\" data-from=\"").append(esc(pe.eventId())).append("\" data-to=\"").append(esc(e.eventId()))
                        .append("\" x1=\"").append(x1).append("\" y1=\"").append(y1).append("\" x2=\"").append(x2).append("\" y2=\"").append(y2).append("\"><title>").append(p.kind()).append(": ")
                        .append(esc(pe.eventId())).append(" &#8594; ").append(esc(e.eventId())).append("</title></line>");
            }
        }
        for (int i = 0; i < n; i++) {
            Event e = events.get(i);
            int cx = left + i * step, cy = top + laneOf.get(e.nodeId()) * laneH + laneH / 2;
            String extra = (cone.contains(e.eventId()) ? " cone" : "") + (roots.contains(e.eventId()) ? " root" : "");
            sb.append("<circle class=\"ev k-").append(cls(e)).append(extra).append("\" data-id=\"").append(esc(e.eventId())).append("\" data-seq=\"").append(e.seq()).append("\" cx=\"").append(cx).append("\" cy=\"").append(cy)
                    .append("\" r=\"").append(e.kind() == EventKind.INVARIANT_VIOLATED ? 8 : 5).append("\"><title>").append(esc(e.eventId() + " " + e.kind() + "  t=" + e.virtualTime() + " ms  L=" + e.lamport() + "  " + Exports.vec(e.vector()) + "\n" + Exports.describe(e, g))).append("</title></circle>");
        }
        return sb.append("</svg></div>").toString();
    }

    private static String cls(Event e) {
        return switch (e.kind()) {
            case NODE_STARTED, NODE_RESTARTED -> "start";
            case MESSAGE_SENT -> "send";
            case MESSAGE_DELIVERED -> "recv";
            case TIMER_SET, TIMER_FIRED, TIMER_CANCELLED -> "timer";
            case FAULT_INJECTED, FAULT_CLEARED, MESSAGE_DUPLICATED, NODE_CRASHED -> "fault";
            case MESSAGE_DROPPED -> "drop";
            case INVARIANT_VIOLATED, NODE_FAILED -> "viol";
            case INTERVENTION_APPLIED -> "iv";
            default -> "other";
        };
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** True if the page references anything outside itself (used by the AD-6 self-check). */
    static boolean referencesExternalResources(String html) {
        String h = html.toLowerCase();
        return h.contains("src=\"http") || h.contains("href=\"http") || h.contains("url(http") || h.contains("@import") || h.contains("<link ") || h.contains("src=\"//") || h.contains("fetch(") || h.contains("xmlhttprequest");
    }

    static Path defaultOut(Path dataDir, String timeline, String against) {
        return dataDir.resolve("reports").resolve(timeline + (against == null ? "" : "-vs-" + against) + ".html");
    }

    private static final String CSS = """
            :root{--bg:#f7f8fa;--fg:#1c2330;--mut:#5c6677;--card:#fff;--line:#d9dee7;--accent:#2457c5;--good:#17794a;--bad:#c0262d;--warn:#9a6700;
            --send:#2457c5;--recv:#0e8a7d;--timer:#7a5af8;--fault:#e07b00;--drop:#8a8f99;--viol:#d62839;--iv:#b4259b;--start:#4b5565;--other:#9aa3b2}
            @media (prefers-color-scheme:dark){:root{--bg:#10141b;--fg:#e6e9ef;--mut:#99a3b5;--card:#181e28;--line:#2b3445;--accent:#7aa2ff;--good:#4fd18b;--bad:#ff6b73;--warn:#e3b341}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
            header{padding:22px 28px;background:var(--card);border-bottom:1px solid var(--line)}header h1{margin:0;font-size:22px;letter-spacing:.5px}header h1 span{font-weight:400;color:var(--mut)}header p{margin:4px 0 0;color:var(--mut)}
            main{padding:8px 28px 40px;max-width:1500px;margin:auto}section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:6px 20px 16px;margin:18px 0}
            h2{font-size:17px;margin:14px 0 8px}h3{font-size:14px;margin:14px 0 6px;color:var(--mut)}code,pre{font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}pre{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto}
            .cards{display:flex;flex-wrap:wrap;gap:10px}.card{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:8px 12px;min-width:140px}.card small{display:block;color:var(--mut)}
            .good{color:var(--good)}.bad{color:var(--bad)}.warn{color:var(--warn)}.small{color:var(--mut);font-size:12px}.verdict{font-size:20px;font-weight:700;color:var(--accent)}
            table{border-collapse:collapse;width:100%;font-size:12.5px}th,td{border-bottom:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}th{color:var(--mut);position:sticky;top:0;background:var(--card)}
            tr.primary td{background:rgba(214,40,57,.12)}tr.flash td{background:rgba(36,87,197,.25)}.scroll{overflow:auto;max-height:560px;border:1px solid var(--line);border-radius:8px}
            svg{display:block;background:var(--bg)}svg .lane{fill:var(--fg);font:12px system-ui;font-weight:600}svg .tick{fill:var(--mut);font:10px system-ui}svg .laneline{stroke:var(--line);stroke-width:1}
            svg .ev{stroke:var(--card);stroke-width:1.5;cursor:pointer}svg .ev.cone{stroke:#f2c200;stroke-width:3}svg .ev.root{stroke:var(--bad);stroke-width:3.5}
            .k-send{fill:var(--send)}.k-recv{fill:var(--recv)}.k-timer{fill:var(--timer)}.k-fault{fill:var(--fault)}.k-drop{fill:var(--drop)}.k-viol{fill:var(--viol)}.k-iv{fill:var(--iv)}.k-start{fill:var(--start)}.k-other{fill:var(--other)}
            tr.k-viol td{color:var(--bad)}tr.k-fault td:nth-child(2),tr.k-iv td:nth-child(2){font-weight:700}
            svg .edge{stroke-width:1.4;opacity:.75}.e-message{stroke:var(--send)}.e-fault{stroke:var(--fault)}.e-omission{stroke:var(--timer);stroke-dasharray:5 3}.e-witness{stroke:var(--viol);stroke-dasharray:2 3}.e-timer,.e-step_effect{stroke:var(--mut)}
            svg .edge.e-path{stroke:var(--fg);stroke-width:3.2;opacity:1}svg .edge.hot{stroke-width:3.4;opacity:1}
            .legend{font-size:12px;color:var(--mut)}.lg{display:inline-block;width:10px;height:10px;border-radius:50%;margin:0 3px 0 8px}.lg.send{background:var(--send)}.lg.recv{background:var(--recv)}.lg.timer{background:var(--timer)}.lg.fault{background:var(--fault)}.lg.drop{background:var(--drop)}.lg.viol{background:var(--viol)}.lg.iv{background:var(--iv)}
            .ring.cone{color:#f2c200}.ring.root{color:var(--bad)}.path li{margin:4px 0}small.iv{color:var(--iv)}input{padding:5px 8px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
            footer{padding:18px 28px;color:var(--mut);font-size:12px;border-top:1px solid var(--line)}@media (max-width:700px){main{padding:4px 12px}header{padding:14px 12px}}
            """;

    private static final String JS = """
            (function(){var q=function(s,r){return (r||document).querySelectorAll(s)};
            var f=document.getElementById('filter');
            if(f){f.addEventListener('input',function(){var v=f.value.toLowerCase();q('#events tbody tr').forEach(function(tr){tr.style.display=tr.textContent.toLowerCase().indexOf(v)<0?'none':''})})}
            q('svg .ev').forEach(function(c){
              c.addEventListener('mouseenter',function(){var id=c.getAttribute('data-id');q('svg .edge').forEach(function(e){if(e.getAttribute('data-from')===id||e.getAttribute('data-to')===id)e.classList.add('hot')})});
              c.addEventListener('mouseleave',function(){q('svg .edge.hot').forEach(function(e){e.classList.remove('hot')})});
              c.addEventListener('click',function(){var r=document.getElementById('row-'+c.getAttribute('data-seq'));if(r){r.scrollIntoView({block:'center'});r.classList.add('flash');setTimeout(function(){r.classList.remove('flash')},1600)}})});
            })();
            """;
}
