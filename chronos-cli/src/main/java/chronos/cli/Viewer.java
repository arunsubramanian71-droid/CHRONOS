package chronos.cli;

import chronos.analysis.*;
import chronos.model.*;
import chronos.store.TimelineStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The live local viewer (FR-50, Stretch, D-18): a read-only web page served by the JDK's built-in HTTP server. It lets you pick a
 * timeline, drag a slider through its events, see the world at that point (node states, messages in flight, pending timers, clocks),
 * click an event for its causal explanation, and compare two timelines. It holds no logic of its own: every answer comes from the
 * same time-travel session, comparator and exporters the command line uses, and it never writes to the store.
 *
 * Safety: it binds to the loopback address unless told otherwise, accepts only GET and HEAD, answers only for timeline ids that
 * exist in the store, and serves a fixed page with no external resource. Requests are handled one at a time on a single thread,
 * so the sessions it caches need no locking.
 */
public final class Viewer implements AutoCloseable {
    private static final int MAX_EVENTS = 5000;

    private final TimelineStore store;
    private final HttpServer server;
    private final Map<String, TimeTravelSession> sessions = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TimeTravelSession> eldest) {
            return size() > 4;
        }
    };
    private final String page;

    public Viewer(TimelineStore store, String bind, int port) throws IOException {
        this.store = store;
        try (InputStream in = Viewer.class.getResourceAsStream("viewer.html")) {
            if (in == null) throw new IllegalStateException("viewer.html is missing from the jar");
            this.page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null); // the dispatch thread runs the handlers one after the other
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "the viewer is read-only");
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", page);
            } else if (path.equals("/api/timelines")) {
                send(ex, 200, "application/json", Canon.json(timelines()));
            } else if (path.equals("/api/compare")) {
                String a = known(q.get("a")), b = known(q.get("b"));
                send(ex, 200, "text/plain; charset=utf-8", new TimelineComparator(store).compare(a, b, false).text());
            } else if (path.startsWith("/api/timeline/")) {
                String[] p = path.substring("/api/timeline/".length()).split("/", 3);
                String id = known(URLDecoder.decode(p[0], StandardCharsets.UTF_8));
                String what = p.length > 1 ? p[1] : "";
                String arg = p.length > 2 ? URLDecoder.decode(p[2], StandardCharsets.UTF_8) : null;
                switch (what) {
                    case "events" -> send(ex, 200, "application/json", Canon.json(events(id)));
                    case "at" -> send(ex, 200, "application/json", Canon.json(worldAt(id, Integer.parseInt(arg))));
                    case "past" -> send(ex, 200, "application/json", Canon.json(past(id, arg)));
                    case "why" -> send(ex, 200, "text/plain; charset=utf-8", session(id).why(arg));
                    case "knows" -> send(ex, 200, "text/plain; charset=utf-8", session(id).knowledgeText(arg));
                    default -> send(ex, 404, "text/plain; charset=utf-8", "unknown resource");
                }
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (UnknownTimelineException | IllegalArgumentException | IndexOutOfBoundsException e) {
            send(ex, 400, "text/plain; charset=utf-8", "bad request: " + e.getMessage());
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain; charset=utf-8", "internal error: " + e);
        } finally {
            ex.close();
        }
    }

    private String known(String id) {
        if (id == null || !store.timelineIds().contains(id)) throw new UnknownTimelineException(String.valueOf(id));
        return id;
    }

    private TimeTravelSession session(String id) {
        return sessions.computeIfAbsent(id, k -> new TimeTravelSession(store, k));
    }

    private List<Object> timelines() {
        List<Object> out = new ArrayList<>();
        for (String id : store.timelineIds()) {
            TimelineHeader h = store.header(id);
            TimelineSummary s = store.summary(id);
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("parent", h.parentId() == null ? "" : h.parentId());
            m.put("scenario", h.scenario());
            m.put("seed", h.seed());
            m.put("status", s == null ? "INCOMPLETE" : s.status().name());
            m.put("events", s == null ? 0L : s.eventCount());
            m.put("violation", s == null || s.firstViolationEventId() == null ? "" : s.firstViolationInvariant() + " at " + s.firstViolationEventId());
            m.put("violationEvent", s == null || s.firstViolationEventId() == null ? "" : s.firstViolationEventId());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> events(String id) {
        TimeTravelSession s = session(id);
        List<Event> all = store.events(id);
        List<Object> es = new ArrayList<>();
        for (Event e : all) {
            if (es.size() >= MAX_EVENTS) break;
            Map<String, Object> m = new TreeMap<>();
            m.put("id", e.eventId());
            m.put("seq", e.seq());
            m.put("node", e.nodeId());
            m.put("kind", e.kind().name());
            m.put("t", e.virtualTime());
            m.put("lamport", e.lamport());
            m.put("vector", Exports.vec(e.vector()));
            m.put("text", Exports.describe(e, s.graph()));
            List<Object> ps = new ArrayList<>();
            for (Parent p : e.parents()) ps.add(List.of(p.id(), p.kind().name()));
            m.put("parents", ps);
            es.add(m);
        }
        Map<String, Object> out = new TreeMap<>();
        out.put("nodes", store.header(id).nodes());
        out.put("total", (long) all.size());
        out.put("truncated", all.size() > MAX_EVENTS);
        out.put("events", es);
        return out;
    }

    /** The causal evidence for an event: its causal past (the suspects) and the shortest chains from faults, inputs and starts, each hop with its edge kind. */
    private Map<String, Object> past(String id, String eventId) {
        TimeTravelSession s = session(id);
        int pos = s.graph().pos(eventId);
        if (pos < 0) throw new IllegalArgumentException("no event " + eventId + " in " + id);
        List<Object> past = new ArrayList<>();
        for (int p : s.graph().causalPast(pos)) past.add(s.graph().event(p).eventId());
        List<Object> chains = new ArrayList<>();
        for (List<Integer> chain : s.whyChains(pos)) {
            List<Object> hops = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                Map<String, Object> h = new TreeMap<>();
                h.put("event", s.graph().event(chain.get(i)).eventId());
                if (i + 1 < chain.size()) {
                    EdgeKind k = s.graph().edgeBetween(chain.get(i), chain.get(i + 1));
                    h.put("edgeToNext", k.name());
                    h.put("certain", k != EdgeKind.OMISSION); // OMISSION is a potential link, every other edge in a chain is certain
                }
                hops.add(h);
            }
            chains.add(hops);
        }
        Map<String, Object> out = new TreeMap<>();
        out.put("event", eventId);
        out.put("past", past);
        out.put("chains", chains);
        List<Object> silence = new ArrayList<>();
        for (TimeTravelSession.Silence si : s.silence(pos)) silence.add(si.dropEventId() + " (potential: " + si.messageId() + " to " + si.node() + (si.faultRule() == null ? "" : " dropped by rule " + si.faultRule()) + ")");
        out.put("possibleSilence", silence);
        return out;
    }

    private Map<String, Object> worldAt(String id, int seq) {
        TimeTravelSession s = session(id);
        if (seq < 0 || seq >= s.graph().size()) throw new IllegalArgumentException("position " + seq + " is outside 0.." + (s.graph().size() - 1));
        Event e = s.goTo(seq);
        Map<String, Object> out = new TreeMap<>();
        out.put("seq", (long) seq);
        out.put("event", e.eventId());
        out.put("text", Exports.describe(e, s.graph()));
        Map<String, Object> states = new TreeMap<>();
        for (String n : s.timelineHeaderNodes()) states.put(n, s.stateOf(n));
        out.put("states", states);
        List<Object> fl = new ArrayList<>();
        for (TimeTravelSession.InFlight m : s.inflight())
            fl.add(m.messageId() + "  " + m.type() + "  " + m.from() + " -> " + m.to() + "  due " + m.dueAt());
        out.put("inflight", fl);
        List<Object> ts = new ArrayList<>();
        for (TimeTravelSession.PendingTimer t : s.timers()) ts.add(t.timerId() + "  " + t.name() + " on " + t.node() + "  due " + t.dueAt());
        out.put("timers", ts);
        Map<String, Object> clocks = new TreeMap<>();
        for (Map.Entry<String, Stamp> en : s.clocks().entrySet()) clocks.put(en.getKey(), "L" + en.getValue().lamport() + " " + Exports.vec(en.getValue().vector()));
        out.put("clocks", clocks);
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
