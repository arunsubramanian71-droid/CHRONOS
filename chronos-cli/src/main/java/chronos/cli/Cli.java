package chronos.cli;

import chronos.analysis.*;
import chronos.analysis.CounterfactualEngine.Verdict;
import chronos.demo.DemoRegistry;
import chronos.model.*;
import chronos.runtime.RunResult;
import chronos.sdk.Scenario;
import chronos.store.TimelineStore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** The commands of the CHRONOS CLI. Each one drives real components; nothing here is canned output. */
final class Cli {
    static final String USAGE = """
            CHRONOS: record, replay, rewind, branch and compare executions of a distributed application.

            usage: chronos [--data DIR] <command> [options]        (data directory: --data, $CHRONOS_DATA or ./chronos-data)

              scenarios                                   list the registered scenarios
              run <scenario> [--seed N] [--param k=v]... [--plan default|none|chaos] [--horizon MS] [--no-stop]
                                [--timeline ID] [--checkpoint-every K] [--fault SPEC]...        record a run as a new timeline
                  --fault SPEC adds a fault rule: partition:AT:DUR:a,b|c  crash:NODE:AT  restart:NODE:AT  pause:NODE:AT:DUR
                  skew:NODE:AT:OFFSET[:DUR]  slow:FROM:TO:FACTOR:AT[:DUR]  drop:MSGID  delay:MSGID:MS  dup:MSGID
                  reorder:FROM:TO:TYPE:N  after:EVENT:crash:NODE  after:EVENT:pause:NODE:DUR
                  state:WATCHNODE:volatile.field=VALUE:crash:NODE  state:WATCHNODE:volatile.field=VALUE:pause:NODE:DUR
              ls                                          the timeline tree
              show <timeline> [--table|--diagram|--dot|--json] [--limit N]    print the recorded history
              inspect <timeline> [--goto violation|SEQ|EVENT] [--back N] [--step N] [--state NODE]... [--world]
                                [--inflight] [--timers] [--why [EVENT]] [--effects EVENT] [--concurrent EVENT]
              session <timeline>                          time-travel commands read from stdin (type 'help')
              inject <timeline> --fault SPEC [--fault SPEC]...   record a new run of that timeline's scenario and seed with extra faults added to its plan
              verify|replay <timeline> [--from-checkpoint SEQ]   replay and verify every regenerated event against the log
              determinism <scenario> [--seed N] [--param k=v]... [--jvm N]    run twice (and in N fresh JVMs), compare log hashes
              branch|counterfactual <timeline> [--at SEQ|EVENT|violation] [--horizon MS] [--label TEXT]
                                [--heal-earlier MS | --heal-later MS [--rule R]] [--remove-fault R] [--drop MSG]
                                [--delay MSG:MS] [--duplicate MSG] [--crash NODE:AT] [--restart NODE:AT] [--pause NODE:AT:DUR] [--skew NODE:AT:OFFSET[:DUR]]
                                [--add-input NODE:AT:JSON] [--remove-input INDEX] [--swap MSG:MSG] [--config KEY=VALUE]
                                [--reseed NODE:STREAM:SEED] [--patch-state NODE:volatile.field=VALUE (synthetic)]...   run a what-if, print the verdict
              compare <A> <B> [--strict]                  first causal divergence, cone and explanation path
              causes <timeline> [--max-size K] [--threads N]    which fault rules are critical (combinations)
              serve [--port 8080] [--bind 127.0.0.1] [--seconds N]   live local viewer (read-only web page on the JDK HTTP server)
              reindex [timeline] [--check]       rebuild the derived index files from the log (--check: only report whether they load)
              (any branching command) --pinned    use the Pinned continuation policy instead of Continue (D-15)
              blind prepare [--token HEX] | begin | answer --root RULE... [--notes TEXT] | grade     blind-debugging kit (docs/BLIND_PROTOCOL.md)
              explore <scenario> [--max-faults K] [--times 100,300] [--duration D] [--nth N] [--kinds crash,partition,drop,pause] [--candidate SPEC]...
                      [--max-cases N] [--stop-first] [--no-prune] [--base none|default] [--save] [--save-max N] [--list]   bounded systematic fault-schedule exploration
              check [timeline]... [--no-replay] [--json] [--strict]   verify the store: logs, checksums, clocks, lineage, indexes, checkpoints, replay; actionable diagnostics
              minimize <timeline> [--max-runs N] [--no-shrink] [--save]   shrink a failing run's fault plan (delta debugging) to a 1-minimal one that fails the same way
              incident export <timeline> [--out FILE] | import FILE | info FILE | reproduce FILE    portable, checksummed incident bundle (lineage, evidence, reproduction)
              counterexample <file>               re-execute a saved counterexample and check the same violation appears
              local-replay <timeline> [node]      re-execute one node (or each node) alone from its recorded inputs and check its outputs (R5)
              search <timeline> [--max N] [--threads N] [--keep]   intervention search: which single changes to the causal past make the failure disappear
              campaign <scenario> --seeds A-B [--threads N] [--chaos] [--param k=v]... [--keep]
              report <timeline> [--against B] [--out FILE]  static self-contained HTML report
              delete <timeline>                           delete a leaf timeline
              demo [--full]                               the acceptance demonstrations AD-1 to AD-6 on RE, end to end
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final InputStream in;
    private final BlindKit.Registry registry = new BlindKit.Registry();
    private Path dataDir;
    private TimelineStore store;
    private TimelineManager manager;

    Cli(PrintStream out, PrintStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
    }

    int execute(List<String> argv) {
        List<String> a = new ArrayList<>(argv);
        String dataOpt = null;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).equals("--data") && i + 1 < a.size()) {
                dataOpt = a.get(i + 1);
                a.subList(i, i + 2).clear();
                break;
            }
        if (a.isEmpty() || a.get(0).equals("help") || a.get(0).equals("--help") || a.get(0).equals("-h")) {
            out.print(USAGE);
            return a.isEmpty() ? 1 : 0;
        }
        String cmd = a.remove(0);
        Args args = Args.parse(a);
        dataDir = Path.of(dataOpt != null ? dataOpt : System.getenv().getOrDefault("CHRONOS_DATA", "chronos-data"));
        if (cmd.equals("scenarios")) return scenarios();
        store = new TimelineStore(dataDir).compressNewTimelines(args.has("compress"));
        manager = new TimelineManager(store, registry).withPinnedContinuation(args.has("pinned"));
        return switch (cmd) {
            case "run" -> run(args);
            case "ls" -> {
                out.print(manager.tree().isEmpty() ? "(no timelines yet: try 'chronos run re')\n" : manager.tree());
                yield 0;
            }
            case "show" -> show(args);
            case "inspect" -> inspect(args);
            case "session" -> session(args);
            case "verify", "replay" -> verify(args);
            case "inject" -> inject(args);
            case "determinism" -> determinism(args);
            case "hash" -> hash(args);
            case "branch", "counterfactual" -> branch(args);
            case "compare" -> compare(args);
            case "causes" -> causes(args);
            case "search" -> search(args);
            case "local-replay" -> localReplay(args);
            case "serve" -> serve(args);
            case "blind" -> blind(args);
            case "explore" -> explore(args);
            case "minimize" -> minimize(args);
            case "check" -> check(args);
            case "incident" -> incident(args);
            case "counterexample" -> counterexample(args);
            case "reindex" -> {
                for (String id : args.positionalCount() > 0 ? List.of(args.positional(0, "timeline")) : store.timelineIds()) {
                    String before = store.index(id).source();
                    if (args.has("check")) out.println(id + ": indexes " + before);
                    else {
                        store.rebuildIndex(id);
                        out.println(id + ": index file rewritten from the log (was " + before + ")");
                    }
                }
                yield 0;
            }
            case "campaign" -> campaign(args);
            case "report" -> report(args);
            case "delete" -> {
                String id = args.positional(0, "timeline");
                store.delete(id);
                out.println("deleted " + id);
                yield 0;
            }
            case "demo" -> new Demo(this, args).run();
            default -> throw new CliException("unknown command '" + cmd + "' (try: chronos help)");
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // recording
    // ------------------------------------------------------------------------------------------------------------

    private int scenarios() {
        out.println("registered scenarios (D-22):");
        for (String n : registry.names()) out.println("  " + n);
        out.println("parameters: re/re2 partition=on|off, ka2 delay=on|off, ka3 crash=on|off, gossip nodes=N horizon=MS");
        return 0;
    }

    Map<String, String> params(Args a) {
        Map<String, String> m = new TreeMap<>();
        for (String kv : a.all("param")) {
            int eq = kv.indexOf('=');
            if (eq < 1) throw new CliException("--param expects key=value, got '" + kv + "'");
            m.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return m;
    }

    private int run(Args a) {
        String scenario = a.positional(0, "scenario");
        extraFaults = new ArrayList<>();
        int n = 0;
        for (String spec : a.all("fault")) extraFaults.add(parseFault(spec, ++n));
        record(scenario, a.num("seed", 42), params(a), a.get("plan", a.has("chaos") ? "chaos" : "default"), a.has("horizon") ? a.num("horizon", 0) : null,
                a.has("no-stop") ? Boolean.FALSE : null, a.num("checkpoint-every", 25), a.get("timeline"));
        extraFaults = List.of();
        return 0;
    }

    private FaultPlan injectBase;

    /** inject: record a new run of an existing timeline's scenario, seed and fault plan with extra faults added (FR-47). */
    private int inject(Args a) {
        String tl = a.positional(0, "timeline");
        TimelineHeader h = store.header(tl);
        if (h.parentId() != null) throw new CliException(tl + " is a branch; inject adds faults to a recorded run (use `branch` for interventions)");
        extraFaults = new ArrayList<>();
        int n = 0;
        for (String spec : a.all("fault")) extraFaults.add(parseFault(spec, ++n));
        if (extraFaults.isEmpty()) throw new CliException("inject needs at least one --fault (see: chronos help)");
        injectBase = h.faultPlan();
        try {
            record(h.scenario(), h.seed(), h.params(), "default", h.horizon(), h.stopOnViolation() ? null : Boolean.FALSE, a.num("checkpoint-every", 25), a.get("timeline"));
        } finally {
            extraFaults = List.of();
            injectBase = null;
        }
        return 0;
    }

    /** Records a scenario into the store and prints what happened. Returns the result; the new timeline id is in {@link #lastTimeline}. */
    RunResult record(String scenario, long seed, Map<String, String> params, String planMode, Long horizon, Boolean stop, long ckpt, String timelineId) {
        Scenario sc = registry.create(scenario, params);
        long h = horizon != null ? horizon : sc.defaultHorizon();
        FaultPlan plan = switch (planMode) {
            case "default" -> null;
            case "none" -> FaultPlan.empty();
            case "chaos" -> ChaosPlanGenerator.generate(sc, seed, h);
            default -> throw new CliException("--plan must be default, none or chaos");
        };
        if (!extraFaults.isEmpty()) {
            FaultPlan base = injectBase != null ? injectBase : plan != null ? plan : sc.defaultFaultPlan();
            for (FaultRule r : extraFaults) base = base.with(r);
            plan = base;
        }
        String id = timelineId != null ? timelineId : manager.allocateRootId();
        Recording.Spec spec = Recording.Spec.of(scenario, seed).withParams(params).withPlan(plan).withHorizon(horizon).withStopOnViolation(stop).withCheckpointInterval(ckpt);
        RunResult r = Recording.record(store, registry, id, spec);
        lastTimeline = id;
        out.println("recorded " + id + ": " + scenario + (params.isEmpty() ? "" : " " + params) + " seed=" + seed + "  -> " + r.status() + ", " + r.eventCount() + " events, final virtual time "
                + r.finalVirtualTime() + " ms");
        out.println("  log hash " + LogHash.of(store.events(id)) + "   stored under " + dataDir.resolve("timelines").resolve(id));
        if (store.header(id).faultPlan().localityNote() != null) out.println("  NOTE: " + store.header(id).faultPlan().localityNote());
        if (!r.faultCoverage().isEmpty()) out.println("  fault coverage: " + r.faultCoverage() + (r.unfiredRules().isEmpty() ? "" : "   WARNING: rules that never fired: " + r.unfiredRules()));
        if (r.violated()) {
            out.println("  INVARIANT VIOLATED: " + r.firstViolationInvariant() + " at " + r.firstViolationEventId());
            TimeTravelSession s = new TimeTravelSession(store, id);
            s.goToViolation();
            out.print(indent(s.why(null), "  "));
        }
        return r;
    }

    String lastTimeline;
    private List<FaultRule> extraFaults = List.of();

    /** Parses a --fault specification into a rule (ids are generated: f1, f2, ...). */
    FaultRule parseFault(String spec, int n) {
        String[] p = spec.split(":");
        String id = "f" + n + "-" + p[0];
        try {
            return switch (p[0]) {
                case "partition" -> {
                    List<List<String>> groups = new ArrayList<>();
                    for (String g : p[3].split("\\|")) groups.add(List.of(g.split(",")));
                    yield FaultRule.partition(id, Long.parseLong(p[1]), p[2].isEmpty() ? null : Long.parseLong(p[2]), groups);
                }
                case "crash" -> FaultRule.crash(id, p[1], Long.parseLong(p[2]));
                case "restart" -> FaultRule.restart(id, p[1], Long.parseLong(p[2]));
                case "pause" -> FaultRule.pause(id, p[1], Long.parseLong(p[2]), Long.parseLong(p[3]));
                case "skew" -> FaultRule.clockSkew(id, p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p.length > 4 ? Long.parseLong(p[4]) : null);
                case "slow" -> FaultRule.slowLink(id, p[1], p[2], Long.parseLong(p[3]), Long.parseLong(p[4]), p.length > 5 ? Long.parseLong(p[5]) : null);
                case "drop" -> FaultRule.onMessage(id, FaultRule.FaultAction.DROP, spec.substring(5), null);
                case "dup" -> FaultRule.onMessage(id, FaultRule.FaultAction.DUPLICATE, spec.substring(4), null);
                case "delay" -> FaultRule.onMessage(id, FaultRule.FaultAction.DELAY, p[1], Long.parseLong(p[2]));
                case "reorder" -> FaultRule.reorder(id, p[1], p[2], p[3], Long.parseLong(p[4]));
                case "state" -> {
                    int eq = p[2].indexOf('=');
                    if (eq < 1) throw new CliException("--fault state:NODE:field=VALUE:crash:NODE");
                    String raw = p[2].substring(eq + 1);
                    Object val = raw.matches("-?\\d+") ? (Object) Long.parseLong(raw) : raw.equals("true") || raw.equals("false") ? (Object) Boolean.parseBoolean(raw) : raw;
                    yield p[3].equals("crash") ? FaultRule.onState(id, p[1], p[2].substring(0, eq), val, FaultRule.FaultAction.CRASH, Map.of("node", p[4]))
                            : FaultRule.onState(id, p[1], p[2].substring(0, eq), val, FaultRule.FaultAction.PAUSE, Map.of("node", p[4], "duration", Long.parseLong(p[5])));
                }
                case "after" -> p[2].equals("crash") ? FaultRule.after(id, p[1], FaultRule.FaultAction.CRASH, Map.of("node", p[3]))
                        : FaultRule.after(id, p[1], FaultRule.FaultAction.PAUSE, Map.of("node", p[3], "duration", Long.parseLong(p[4])));
                default -> throw new CliException("unknown fault kind '" + p[0] + "' in --fault " + spec);
            };
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            throw new CliException("malformed --fault " + spec + " (see: chronos help)");
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // inspecting
    // ------------------------------------------------------------------------------------------------------------

    private int show(Args a) {
        String tl = a.positional(0, "timeline");
        TimelineHeader h = store.header(tl);
        List<Event> events = store.events(tl);
        CausalGraph g = CausalGraph.build(events);
        TimelineSummary sum = store.summary(tl);
        if (a.has("json")) out.println(Exports.json(g, h.nodes()));
        else if (a.has("dot")) out.print(Exports.dot(g, h.nodes()));
        else if (a.has("diagram")) out.print(Exports.spaceTime(g, h.nodes()));
        else {
            out.println(tl + ": " + h.scenario() + " " + h.params() + " seed=" + h.seed() + " nodes=" + h.nodes() + (h.parentId() != null ? "  branch of " + h.parentId() + " at " + h.branchSeq() : ""));
            if (h.faultPlan().localityNote() != null) out.println("  NOTE: " + h.faultPlan().localityNote());
            out.println("  " + (sum == null ? "RECORDING (unfinished)" : sum.status() + ", " + events.size() + " events, final virtual time " + sum.finalVirtualTime() + " ms") + "; graph: " + g.size() + " vertices, " + g.edgeCount() + " edges");
            int limit = (int) a.num("limit", 200);
            out.print(Exports.eventTable(events.subList(0, Math.min(limit, events.size())), h.nodes()));
            if (events.size() > limit) out.println("... " + (events.size() - limit) + " more (use --limit)");
        }
        return 0;
    }

    private int inspect(Args a) {
        String tl = a.positional(0, "timeline");
        TimeTravelSession s = new TimeTravelSession(store, tl);
        if (a.has("goto")) goTo(s, a.get("goto"));
        if (a.has("back")) printEvent(s, s.back((int) a.num("back", 1)));
        if (a.has("step")) printEvent(s, s.step((int) a.num("step", 1)));
        for (String n : a.all("state")) out.println("state of " + n + " at " + s.current().eventId() + ": " + Canon.json(s.stateOf(n)));
        if (a.has("world")) out.print(s.world());
        if (a.has("inflight")) {
            out.println("in flight at " + s.current().eventId() + ":");
            for (TimeTravelSession.InFlight m : s.inflight()) out.println("  " + m.messageId() + " " + m.type() + " " + m.from() + " -> " + m.to() + " due at " + m.dueAt());
        }
        if (a.has("timers")) {
            out.println("pending timers at " + s.current().eventId() + ":");
            for (TimeTravelSession.PendingTimer t : s.timers()) out.println("  " + t.timerId() + " " + t.name() + " on " + t.node() + " due at " + t.dueAt());
        }
        if (a.has("why")) out.print(s.why(a.get("why").isEmpty() ? null : a.get("why")));
        if (a.has("effects")) {
            List<Event> ef = s.effects(a.get("effects"));
            out.println(ef.size() + " events could have been influenced by " + a.get("effects") + ":");
            ef.stream().limit(40).forEach(e -> out.println("  " + e.eventId() + " " + Exports.describe(e, s.graph())));
        }
        if (a.has("knows")) out.print(s.knowledgeText(a.get("knows").isEmpty() ? null : a.get("knows")));
        if (a.has("cut")) out.print(cutText(s.graph(), a.get("cut")));
        if (a.has("concurrent")) {
            List<Event> c = s.concurrentWith(a.get("concurrent"));
            out.println(c.size() + " events are concurrent with " + a.get("concurrent") + " (no causal relation)");
        }
        if (!(a.has("goto") || a.has("back") || a.has("step") || a.has("state") || a.has("world") || a.has("inflight") || a.has("timers") || a.has("why") || a.has("effects") || a.has("concurrent") || a.has("knows") || a.has("cut")))
            out.print(s.world());
        return 0;
    }

    private void goTo(TimeTravelSession s, String spec) {
        Event e;
        if (spec.equals("violation")) e = s.goToViolation();
        else if (spec.matches("\\d+")) e = s.goTo(Integer.parseInt(spec));
        else e = s.goToEvent(spec);
        printEvent(s, e);
    }

    private void printEvent(TimeTravelSession s, Event e) {
        out.println("cursor " + s.cursor() + ": " + e.eventId() + " [" + e.nodeId() + " t=" + e.virtualTime() + " L" + e.lamport() + " " + Exports.vec(e.vector()) + "] " + Exports.describe(e, s.graph()));
    }

    private int session(Args a) {
        String tl = a.positional(0, "timeline");
        TimeTravelSession s = new TimeTravelSession(store, tl);
        out.println("CHRONOS time travel on " + tl + " (" + store.events(tl).size() + " events). Type 'help'. The past is immutable: going live creates a branch.");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.println("> " + line);
                if (line.equals("quit") || line.equals("exit")) break;
                try {
                    s = sessionCommand(s, line);
                } catch (CliException | IllegalArgumentException | IllegalStateException e) {
                    out.println("  error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return 0;
    }

    private TimeTravelSession sessionCommand(TimeTravelSession s, String line) {
        String[] t = line.split("\\s+");
        String c = t[0];
        String arg = t.length > 1 ? t[1] : null;
        switch (c) {
            case "help" -> out.println("  goto <seq|event|violation>  step [n]  back [n]  next-on <node>  prev-on <node>  find <kind=K|node=N|text=S|time=MS|violation> [back]\n"
                    + "  state <node>  world  inflight  timers  why [event]  effects [event]  concurrent-with [event]  knows [event]  cut <event>[,<event>...]  watch <node.zone.field>\n"
                    + "  diff <timeline>  run-to <find terms>  branch <intervention options as in 'chronos branch'>  quit");
            case "goto" -> goTo(s, need(arg, "goto <seq|event|violation>"));
            case "step" -> printEvent(s, s.step(arg == null ? 1 : Integer.parseInt(arg)));
            case "back" -> printEvent(s, s.back(arg == null ? 1 : Integer.parseInt(arg)));
            case "next-on" -> printEvent(s, s.nextOn(need(arg, "next-on <node>")));
            case "prev-on" -> printEvent(s, s.prevOn(need(arg, "prev-on <node>")));
            case "find" -> {
                java.util.function.Predicate<Event> p = findPredicate(Arrays.asList(t).subList(1, t.length), s);
                boolean back = Arrays.asList(t).contains("back");
                Optional<Event> e = s.find(p, !back);
                if (e.isPresent()) printEvent(s, e.get());
                else out.println("  no such event");
            }
            case "state" -> out.println("  " + Canon.json(s.stateOf(need(arg, "state <node>"))));
            case "world" -> out.print(indent(s.world(), "  "));
            case "inflight" -> s.inflight().forEach(m -> out.println("  " + m.messageId() + " " + m.type() + " " + m.from() + " -> " + m.to() + " due " + m.dueAt()));
            case "timers" -> s.timers().forEach(m -> out.println("  " + m.timerId() + " " + m.name() + " on " + m.node() + " due " + m.dueAt()));
            case "why" -> out.print(indent(s.why(arg), "  "));
            case "effects" -> s.effects(arg).stream().limit(30).forEach(e -> out.println("  " + e.eventId() + " " + Exports.describe(e, s.graph())));
            case "knows" -> out.print(indent(s.knowledgeText(arg), "  "));
            case "cut" -> out.print(indent(cutText(s.graph(), arg), "  "));
            case "concurrent-with" -> out.println("  " + s.concurrentWith(arg).size() + " concurrent events");
            case "watch" -> {
                String q = need(arg, "watch <node.zone.field>");
                int dot = q.indexOf('.');
                for (TimeTravelSession.FieldChange ch : s.watch(q.substring(0, dot), q.substring(dot + 1)))
                    out.println("  " + ch.eventId() + " (position " + ch.position() + "): " + Canon.json(ch.before()) + " -> " + Canon.json(ch.after()));
            }
            case "diff" -> out.print(indent(s.diff(need(arg, "diff <timeline>")), "  "));
            case "run-to" -> {
                // stepped replay with a breakpoint (R3, FR-24): re-execute the application, verifying every event, and pause the LIVE world
                java.util.function.Predicate<Event> p = findPredicate(Arrays.asList(t).subList(1, t.length), s);
                ReplayEngine.PausedWorld w = manager.replayEngine().runUntil(s.timelineId(), p);
                if (w.hit() == null) {
                    out.println("  no event matched; the replay ran to the end of the recording (" + w.verified() + " events verified)");
                } else {
                    s.goToEvent(w.hit().eventId());
                    out.println("  breakpoint hit at " + w.hit().eventId() + "; the live world is paused at the step boundary ending at position " + w.position() + " (" + w.verified() + " events verified so far)");
                    TimeTravelSession atBoundary = new TimeTravelSession(store, s.timelineId());
                    atBoundary.goTo((int) w.position());
                    for (String n : store.header(s.timelineId()).nodes()) {
                        boolean same = Canon.json(w.engine().stateOf(n)).equals(Canon.json(atBoundary.stateOf(n)));
                        out.println("    live " + n + ": " + Canon.json(w.engine().stateOf(n)) + (same ? "   (equals the state reconstructed from the log)" : "   MISMATCH with the log"));
                    }
                }
            }
            case "branch" -> {
                // going live always creates a branch (Section 16): the cursor's step boundary is the branch point
                List<String> rest = new ArrayList<>(Arrays.asList(t).subList(1, t.length));
                Args ba = Args.parse(rest);
                long at = manager.roundToBoundary(s.timelineId(), s.cursor());
                String child = doBranch(s.timelineId(), at, ba, true);
                out.println("  switched to " + child);
                return new TimeTravelSession(store, child);
            }
            default -> throw new CliException("unknown session command '" + c + "' (type help)");
        }
        return s;
    }

    private static String need(String v, String usage) {
        if (v == null) throw new CliException("usage: " + usage);
        return v;
    }

    private java.util.function.Predicate<Event> findPredicate(List<String> terms, TimeTravelSession s) {
        java.util.function.Predicate<Event> p = e -> true;
        for (String term : terms) {
            if (term.equals("back")) continue;
            if (term.equals("violation")) p = p.and(e -> e.kind() == EventKind.INVARIANT_VIOLATED);
            else if (term.startsWith("kind=")) { EventKind k = EventKind.valueOf(term.substring(5).toUpperCase()); p = p.and(e -> e.kind() == k); }
            else if (term.startsWith("node=")) { String n = term.substring(5); p = p.and(e -> e.nodeId().equals(n)); }
            else if (term.startsWith("time=")) { long t0 = Long.parseLong(term.substring(5)); p = p.and(e -> e.virtualTime() >= t0); }
            else if (term.startsWith("text=")) { String x = term.substring(5).toLowerCase(); p = p.and(e -> Exports.describe(e, s.graph()).toLowerCase().contains(x)); }
            else throw new CliException("find expects violation, kind=K, node=N, time=MS or text=S, got '" + term + "'");
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------------------------
    // verification and determinism
    // ------------------------------------------------------------------------------------------------------------

    private int verify(Args a) {
        String tl = a.positional(0, "timeline");
        ReplayEngine.Result r = a.has("from-checkpoint") ? manager.replayEngine().verifyFrom(tl, a.num("from-checkpoint", 0)) : manager.replayEngine().verify(tl);
        out.println(r.summary());
        for (String w : r.warnings()) out.println("  warning: " + w);
        return r.ok() ? 0 : 2;
    }

    private int determinism(Args a) {
        String scenario = a.positional(0, "scenario");
        Recording.Spec spec = Recording.Spec.of(scenario, a.num("seed", 42)).withParams(params(a)).withCheckpointInterval(0);
        Recording.DeterminismReport r = Recording.determinismCheck(registry, spec);
        out.println("run twice in this JVM: " + (r.identical() ? "IDENTICAL" : "DIFFERENT at " + r.firstDifference()) + "  (" + r.events() + " events, hash " + r.hashA() + ")");
        boolean ok = r.identical();
        int jvms = (int) a.num("jvm", 0);
        for (int i = 1; i <= jvms; i++) {
            String h = externalHash(scenario, spec);
            boolean same = h.equals(r.hashA());
            ok &= same;
            out.println("fresh JVM launch " + i + ": hash " + h + "  " + (same ? "matches" : "DIFFERS"));
        }
        out.println(ok ? "DETERMINISTIC" : "NONDETERMINISM DETECTED");
        return ok ? 0 : 2;
    }

    private String externalHash(String scenario, Recording.Spec spec) {
        try {
            List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), "chronos.cli.Main", "hash", scenario, "--seed", Long.toString(spec.seed())));
            for (Map.Entry<String, String> e : spec.params().entrySet()) { cmd.add("--param"); cmd.add(e.getKey() + "=" + e.getValue()); }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new CliException("the fresh JVM failed: " + o);
            return o;
        } catch (IOException | InterruptedException e) {
            throw new CliException("cannot launch a fresh JVM: " + e);
        }
    }

    private int hash(Args a) {
        Recording.MemoryRun run = Recording.recordInMemory(registry, "T-det", Recording.Spec.of(a.positional(0, "scenario"), a.num("seed", 42)).withParams(params(a)).withCheckpointInterval(0));
        out.println(LogHash.of(run.log().events()));
        return 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // branching, counterfactuals, comparison
    // ------------------------------------------------------------------------------------------------------------

    private int branch(Args a) {
        String tl = a.positional(0, "timeline");
        List<Intervention> ivs = parseInterventions(a, tl);
        long at = a.has("at") ? resolvePosition(tl, a.get("at")) : defaultBranchPoint(tl, ivs);
        doBranch(tl, at, a, false);
        return 0;
    }

    /** Runs the counterfactual and prints the verdict and the comparison. Returns the new timeline id. */
    String doBranch(String tl, long at, Args a, boolean quiet) {
        List<Intervention> ivs = parseInterventions(a, tl);
        if (ivs.isEmpty()) out.println("  (no intervention given: a no-op branch, which must be event-identical to its parent, law M7)");
        CounterfactualEngine cf = new CounterfactualEngine(manager);
        CounterfactualEngine.Report rep = cf.run(new CounterfactualEngine.Query(tl, at, ivs, a.has("horizon") ? a.num("horizon", 0) : null, a.get("label")));
        lastTimeline = rep.timelineId();
        lastReport = rep;
        out.print(rep.text());
        if (!a.has("quiet")) out.print(rep.divergence().text());
        return rep.timelineId();
    }

    CounterfactualEngine.Report lastReport;

    long resolvePosition(String tl, String spec) {
        List<Event> es = store.events(tl);
        if (spec.equals("violation")) {
            for (Event e : es) if (e.kind() == EventKind.INVARIANT_VIOLATED) return e.seq();
            throw new CliException(tl + " has no violation");
        }
        if (spec.matches("\\d+")) return Long.parseLong(spec);
        for (Event e : es) if (e.eventId().equals(spec)) return e.seq();
        throw new CliException("no event " + spec + " in " + tl);
    }

    List<Intervention> parseInterventions(Args a, String tl) {
        List<Intervention> out = new ArrayList<>();
        int n = 0;
        for (String v : a.all("heal-earlier")) out.add(Intervention.shiftHeal("heal-early" + suffix(n++, out), partitionRule(a, tl), -num(v, "--heal-earlier")));
        for (String v : a.all("heal-later")) out.add(Intervention.shiftHeal("heal-late" + suffix(n++, out), partitionRule(a, tl), num(v, "--heal-later")));
        for (String v : a.all("remove-fault")) out.add(Intervention.removeFault("remove-" + safe(v), v));
        for (String v : a.all("drop")) out.add(Intervention.dropMessage("drop-" + safe(v), v));
        for (String v : a.all("duplicate")) out.add(Intervention.duplicateMessage("dup-" + safe(v), v));
        for (String v : a.all("delay")) {
            String[] p = v.split(":");
            if (p.length != 2) throw new CliException("--delay expects MSG:MS (for example p#14:250)");
            out.add(Intervention.delayMessage("delay-" + safe(p[0]), p[0], num(p[1], "--delay")));
        }
        for (String v : a.all("crash")) {
            String[] p = v.split(":");
            if (p.length != 2) throw new CliException("--crash expects NODE:AT (for example b:2100)");
            out.add(Intervention.crashNode("crash-" + safe(p[0]), p[0], num(p[1], "--crash")));
        }
        for (String v : a.all("restart")) {
            String[] p = splitN(v, 2, "--restart NODE:AT");
            out.add(Intervention.restartNode("restart-" + safe(p[0]), p[0], num(p[1], "--restart")));
        }
        for (String v : a.all("pause")) {
            String[] p = splitN(v, 3, "--pause NODE:AT:DURATION");
            out.add(Intervention.pauseNode("pause-" + safe(p[0]), p[0], num(p[1], "--pause"), num(p[2], "--pause")));
        }
        for (String v : a.all("skew")) {
            String[] p = v.split(":");
            if (p.length < 3) throw new CliException("--skew expects NODE:AT:OFFSET[:DURATION]");
            out.add(Intervention.skewClock("skew-" + safe(p[0]), p[0], num(p[1], "--skew"), num(p[2], "--skew"), p.length > 3 ? num(p[3], "--skew") : null));
        }
        for (String v : a.all("add-input")) {
            String[] p = v.split(":", 3);
            if (p.length != 3) throw new CliException("--add-input expects NODE:AT:JSON (for example client:300:{\"op\":\"write\",\"key\":\"k\",\"value\":1})");
            Map<String, Object> body = Canon.parseMap(p[2]);
            out.add(Intervention.addInput("input-" + safe(p[0]) + "-" + p[1], p[0], num(p[1], "--add-input"), body));
        }
        for (String v : a.all("remove-input")) out.add(Intervention.removeInput("remove-input-" + v, num(v, "--remove-input")));
        for (String v : a.all("swap")) {
            String[] p = splitN(v, 2, "--swap MSG:MSG");
            out.add(Intervention.swapDeliveries("swap-" + safe(p[0]), p[0], p[1]));
        }
        for (String v : a.all("config")) {
            int eq = v.indexOf('=');
            if (eq < 1) throw new CliException("--config expects KEY=VALUE");
            out.add(Intervention.configPatch("config-" + v.substring(0, eq), v.substring(0, eq), v.substring(eq + 1)));
        }
        for (String v : a.all("reseed")) {
            String[] p = splitN(v, 3, "--reseed NODE:STREAM:SEED");
            out.add(Intervention.reseedStream("reseed-" + safe(p[0]), p[0], p[1], num(p[2], "--reseed")));
        }
        for (String v : a.all("patch-state")) {
            int eq = v.indexOf('=');
            String[] p = eq < 1 ? new String[0] : v.substring(0, eq).split(":", 2);
            if (p.length != 2) throw new CliException("--patch-state expects NODE:volatile.field=VALUE");
            String raw = v.substring(eq + 1);
            Object val = raw.matches("-?\\d+") ? (Object) Long.parseLong(raw) : raw.equals("true") || raw.equals("false") ? (Object) Boolean.parseBoolean(raw) : raw;
            out.add(Intervention.patchState("patch-" + safe(p[0]) + "-" + safe(p[1]), p[0], p[1], val));
        }
        return out;
    }

    private static String[] splitN(String v, int n, String usage) {
        String[] p = v.split(":");
        if (p.length != n) throw new CliException("expected " + usage + ", got '" + v + "'");
        return p;
    }

    private static String suffix(int n, List<Intervention> existing) {
        return existing.isEmpty() ? "" : "-" + (n + 1);
    }

    private static long num(String v, String opt) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new CliException(opt + " expects a number, got '" + v + "'");
        }
    }

    private static String safe(String s) {
        return s.replace('#', '_').replace(':', '_');
    }

    private String partitionRule(Args a, String tl) {
        if (a.has("rule")) return a.get("rule");
        List<String> rules = store.header(rootOf(tl)).faultPlan().rules().stream().filter(r -> r.action() == FaultRule.FaultAction.PARTITION).map(FaultRule::id).toList();
        if (rules.size() != 1) throw new CliException("name the partition rule with --rule (the plan has " + rules + ")");
        return rules.get(0);
    }

    private String rootOf(String tl) {
        TimelineHeader h = store.header(tl);
        return h.parentId() == null ? tl : rootOf(h.parentId());
    }

    /** A sensible branch point: where the first intervention starts to matter (Section 19: "just before" the suspect). */
    long defaultBranchPoint(String tl, List<Intervention> ivs) {
        if (ivs.isEmpty()) return store.groupEnds(tl).get(store.groupEnds(tl).size() / 2);
        List<Event> es = store.events(tl);
        Intervention iv = ivs.get(0);
        long target = switch (iv.type()) {
            case Intervention.SHIFT_HEAL -> firstFaultEvent(es, (String) iv.params().get("ruleId"));
            case Intervention.REMOVE_FAULT -> firstFaultEvent(es, (String) iv.params().get("ruleId")) - 1;
            case Intervention.CRASH_NODE, Intervention.RESTART_NODE, Intervention.PAUSE_NODE, Intervention.SKEW_CLOCK, Intervention.ADD_INPUT -> {
                long at = Canon.num(iv.params().get("at"));
                long last = 0;
                for (Event e : es) if (e.virtualTime() < at) last = e.seq();
                yield last;
            }
            case Intervention.REMOVE_INPUT -> manager.bootEnd(tl);
            case Intervention.SWAP_DELIVERIES -> {
                long last = 0;
                for (Event e : es) if (e.eventId().equals(iv.params().get("a")) || e.eventId().equals(iv.params().get("b"))) last = Math.max(last, e.seq());
                yield last;
            }
            case Intervention.CONFIG_PATCH, Intervention.RESEED_STREAM, Intervention.PATCH_STATE -> store.groupEnds(tl).get(store.groupEnds(tl).size() / 2);
            default -> {
                String msg = (String) iv.params().get("messageId");
                for (Event e : es) if (e.eventId().equals(msg)) yield e.seq() - 1;
                throw new CliException("message " + msg + " was never sent in " + tl);
            }
        };
        return manager.roundToBoundary(tl, Math.max(0, target));
    }

    private static long firstFaultEvent(List<Event> es, String rule) {
        for (Event e : es) if (e.kind() == EventKind.FAULT_INJECTED && rule.equals(e.payload().get("rule"))) return e.seq();
        throw new CliException("fault rule " + rule + " never fired in this timeline");
    }

    private int compare(Args a) {
        DivergenceReport d = new TimelineComparator(store).compare(a.positional(0, "timeline A"), a.positional(1, "timeline B"), a.has("strict"));
        out.print(d.text());
        return 0;
    }

    private int causes(Args a) {
        String tl = a.positional(0, "timeline");
        CounterfactualEngine.CauseSearch cs = new CounterfactualEngine(manager).findCriticalFaultSets(tl, (int) a.num("max-size", 2), (int) a.num("threads", 2));
        out.println("remove which fault rules to make the failure disappear? (each trial is a real counterfactual branch)");
        for (Map.Entry<Set<String>, Verdict> e : cs.tried().entrySet()) out.println("  remove " + e.getKey() + " -> " + e.getValue());
        out.println(cs.minimalCriticalSets().isEmpty() ? "no combination of up to " + a.num("max-size", 2) + " rules is critical" : "MINIMAL CRITICAL SETS: " + cs.minimalCriticalSets());
        return 0;
    }

    /** The minimal consistent cut containing the given comma-separated events: per-node frontier, size, and a consistency check. */
    static String cutText(CausalGraph g, String arg) {
        if (arg == null || arg.isBlank()) throw new CliException("cut <event>[,<event>...]");
        List<Integer> members = new ArrayList<>();
        for (String id : arg.split("[, ]+")) {
            int p = g.pos(id);
            if (p < 0) throw new CliException("no event " + id);
            members.add(p);
        }
        BitSet cut = g.minimalCut(members);
        StringBuilder sb = new StringBuilder();
        sb.append("minimal consistent cut containing ").append(arg).append(": ").append(cut.cardinality()).append(" of ").append(g.size()).append(" events\n");
        sb.append("  frontier (last event per lane):\n");
        for (Map.Entry<String, Integer> en : g.frontier(cut).entrySet()) sb.append("    ").append(en.getKey()).append(": ").append(g.event(en.getValue()).eventId()).append(" at t=").append(g.event(en.getValue()).virtualTime()).append('\n');
        sb.append("  consistent: ").append(g.isConsistentCut(cut)).append('\n');
        return sb.toString();
    }

    /** Bounded systematic exploration of fault schedules (not fuzzing): every subset of at most K candidate faults, in a fixed order. */
    private int explore(Args a) {
        String scenario = a.positional(0, "scenario");
        Map<String, String> params = params(a);
        long seed = a.num("seed", 42);
        Long horizon = a.has("horizon") ? a.num("horizon", 0) : null;
        SystematicExplorer ex = new SystematicExplorer(registry);
        List<SystematicExplorer.Candidate> cands = new ArrayList<>();
        int n = 0;
        for (String spec : a.all("candidate")) {
            FaultRule r = parseFault(spec, ++n);
            cands.add(new SystematicExplorer.Candidate(r.id(), r));
        }
        if (cands.isEmpty()) {
            List<Long> times = new ArrayList<>();
            for (String t : a.get("times", "100,300,500").split(",")) times.add(Long.parseLong(t.trim()));
            Set<String> kinds = new TreeSet<>(Arrays.asList(a.get("kinds", "crash,partition,drop,pause").split(",")));
            cands = ex.generate(scenario, params, seed, horizon, new SystematicExplorer.Grid(times, a.num("duration", 200), (int) a.num("nth", 2), kinds));
        }
        FaultPlan base = a.get("base", "none").equals("default") ? registry.create(scenario, params).defaultFaultPlan() : FaultPlan.empty();
        SystematicExplorer.Config cfg = new SystematicExplorer.Config(scenario, params, seed, horizon, base, cands, (int) a.num("max-faults", 2), a.num("max-cases", 5000),
                a.has("stop-first"), !a.has("no-prune"));
        SystematicExplorer.Report rep = ex.explore(cfg);
        out.print(rep.text());
        if (a.has("list")) for (SystematicExplorer.Candidate c : cands) out.println("  candidate " + c.name() + ": " + Canon.json(c.rule().toMap()));
        int kept = 0;
        for (SystematicExplorer.Finding f : rep.findings()) {
            if (!a.has("save") || kept++ >= a.num("save-max", 5)) continue;
            String id = manager.allocateRootId();
            Path file = Counterexample.save(store, registry, id, cfg, f);
            out.println("  saved counterexample " + id + " (" + file + "); try: chronos inspect " + id + " --goto violation --why   |   chronos counterexample " + file);
        }
        return a.has("fail-on-violation") && !rep.findings().isEmpty() ? 1 : 0;
    }

    /** Whole-store integrity check with actionable diagnostics; exit 0 = no failure (warnings allowed unless --strict), 2 = damage found. */
    private int check(Args a) {
        List<String> only = new ArrayList<>();
        for (int i = 0; i < a.positionalCount(); i++) only.add(a.positional(i, "timeline"));
        StoreChecker.Report rep = new StoreChecker(store, registry).check(only, !a.has("no-replay"));
        out.print(a.has("json") ? rep.json() + "\n" : rep.text());
        return !rep.ok() || (a.has("strict") && rep.count(StoreChecker.Status.WARN) > 0) ? 2 : 0;
    }

    /** Delta-debugging a recorded failing run: the smallest fault plan (rules, then parameters, then horizon) that violates the same invariant. */
    private int minimize(Args a) {
        String tl = a.positional(0, "timeline");
        TimelineHeader h = store.header(tl);
        if (h.parentId() != null) throw new CliException(tl + " is a branch; minimize works on a recorded run with a fault plan (for a branch use `causes` or `search`)");
        FailureMinimizer.Report rep;
        try {
            rep = new FailureMinimizer(registry).minimize(new FailureMinimizer.Config(h.scenario(), h.params(), h.seed(), h.faultPlan(), h.horizon(), a.num("max-runs", 2000), !a.has("no-shrink")));
        } catch (IllegalArgumentException e) {
            throw new CliException(tl + ": " + e.getMessage());
        }
        out.print(rep.text());
        if (a.has("save")) {
            String id = manager.allocateRootId();
            Map<String, Object> origin = new TreeMap<>();
            Map<String, Object> mz = new TreeMap<>();
            mz.put("from", tl);
            mz.put("originalRules", rep.original().rules().size());
            mz.put("minimalRules", rep.minimal().rules().size());
            mz.put("removed", rep.removed());
            mz.put("executions", rep.executions());
            mz.put("oneMinimal", rep.oneMinimal());
            origin.put("minimization", mz);
            List<String> names = new ArrayList<>();
            for (FaultRule r : rep.minimal().rules()) names.add(r.id());
            Path file = Counterexample.saveRun(store, registry, id, h.scenario(), h.params(), h.seed(), rep.minimalHorizon(), rep.minimal(), names, rep.invariant(), rep.violationEvent(),
                    rep.traceDigest(), rep.minimalEvents(), origin);
            out.println("  saved minimal failure as " + id + " (" + file + ")");
            out.println("  try: chronos inspect " + id + " --goto violation --why   |   chronos counterexample " + file + "   |   chronos incident export " + id);
        }
        return rep.oneMinimal() ? 0 : 3;
    }

    /** Portable incident bundles: export a timeline with its lineage and evidence, validate and import one, reproduce its failure. */
    private int incident(Args a) {
        String sub = a.positional(0, "export|import|info|reproduce");
        try {
            switch (sub) {
                case "export" -> {
                    String tl = a.positional(1, "timeline");
                    Path outFile = Path.of(a.get("out", tl + ".chronos-incident"));
                    IncidentBundle.export(store, tl, outFile);
                    out.println("incident bundle written: " + outFile + " (" + Files.size(outFile) / 1024 + " KiB; timeline " + tl + " and its ancestors, checksummed; checkpoints and indexes are rebuilt on import)");
                    out.println("on another machine: chronos --data DIR incident import " + outFile.getFileName() + "   then   chronos --data DIR incident reproduce " + outFile.getFileName());
                    return 0;
                }
                case "info" -> {
                    printInfo(IncidentBundle.inspect(Path.of(a.positional(1, "bundle file")), registry));
                    return 0;
                }
                case "import" -> {
                    IncidentBundle.Info info = IncidentBundle.importInto(store, Path.of(a.positional(1, "bundle file")), registry);
                    out.println("imported incident " + info.incident() + " (" + info.lineage() + ") into " + dataDir + "; try: chronos inspect " + info.incident() + " --goto violation --why");
                    for (String w : info.warnings()) out.println("  WARNING: " + w);
                    return 0;
                }
                case "reproduce" -> {
                    IncidentBundle.Reproduction r = IncidentBundle.reproduce(Path.of(a.positional(1, "bundle file")), registry);
                    for (String c : r.checks()) out.println("  [ok] " + c);
                    if (r.info() != null) for (String w : r.info().warnings()) out.println("  WARNING: " + w);
                    for (String p : r.problems()) out.println("  [FAIL] " + p);
                    out.println(r.ok() ? "INCIDENT REPRODUCED" : "INCIDENT NOT REPRODUCED");
                    return r.ok() ? 0 : 2;
                }
                default -> throw new CliException("incident export|import|info|reproduce");
            }
        } catch (IncidentBundle.BundleException e) {
            out.println("bundle REJECTED: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            throw new CliException("cannot read or write the bundle: " + e.getMessage());
        }
    }

    private void printInfo(IncidentBundle.Info i) {
        out.println("bundle valid: incident " + i.incident() + " (lineage " + i.lineage() + "), format version " + i.formatVersion() + ", written by " + i.tool());
        out.println("  scenario " + i.scenario() + " " + i.params() + " seed=" + i.seed() + ", " + i.status() + ", " + i.events() + " events, log hash " + i.logHash());
        out.println("  violation: " + (i.invariant() == null ? "none" : i.invariant() + " at " + i.violationEvent()));
        for (String w : i.warnings()) out.println("  WARNING: " + w);
    }

    /** Re-executes a counterexample document and checks that the same execution and violation appear. */
    private int counterexample(Args a) {
        Path file = Path.of(a.positional(0, "counterexample file"));
        Counterexample.Result r = Counterexample.verify(registry, file, store);
        if (r.ok()) {
            out.println("counterexample " + file.getFileName() + " REPRODUCED: the schedule re-executes to the same trace (" + r.traceDigest().substring(0, 12) + ") and the same violation");
            return 0;
        }
        out.println("counterexample " + file.getFileName() + " NOT REPRODUCED:");
        for (String p : r.problems()) out.println("  - " + p);
        return 2;
    }

    /** The blind-debugging kit (Section 25): prepare a case from a token, begin, answer, grade. See docs/BLIND_PROTOCOL.md. */
    private int blind(Args a) {
        String sub = a.positional(0, "prepare|begin|answer|grade");
        Path marker = dataDir.resolve("blind.json");
        switch (sub) {
            case "prepare" -> {
                long token = Long.parseUnsignedLong(a.get("token", Long.toHexString(System.nanoTime() ^ 0x5DEECE66DL)), 16);
                BlindKit.Case c = BlindKit.caseFor(token);
                extraFaults = new ArrayList<>(c.plan().rules());
                RunResult r;
                try {
                    r = record("mystery", 42, Map.of("m", Long.toHexString(token)), "none", null, Boolean.FALSE, 25, null);
                } finally {
                    extraFaults = List.of();
                }
                if (!r.violated()) throw new CliException("internal error: the prepared case does not fail");
                out.println("blind case prepared in " + dataDir + " as " + lastTimeline + ". Give the debugger ONLY this data directory.");
                out.println("(the planted cause is kept out of this output: it is recomputed from the token " + Long.toHexString(token) + " by `blind grade`)");
                return 0;
            }
            case "begin" -> {
                try {
                    Files.writeString(marker, Canon.json(Map.of("begin", System.currentTimeMillis())) + "\n");
                } catch (IOException e) {
                    throw new CliException("cannot write " + marker + ": " + e.getMessage());
                }
                out.println("timer started. Use only CHRONOS (inspect, branch, compare, search, causes, report ...) to find the cause, then: chronos blind answer --root RULE [--root RULE] --notes \"what was confusing\"");
                return 0;
            }
            case "answer" -> {
                try {
                    Map<String, Object> m = new TreeMap<>(Files.exists(marker) ? Canon.parseMap(Files.readString(marker)) : Map.of());
                    long now = System.currentTimeMillis();
                    m.put("answer", new ArrayList<>(a.all("root")));
                    m.put("notes", a.get("notes", ""));
                    m.put("end", now);
                    if (m.containsKey("begin")) m.put("seconds", (now - Canon.num(m.get("begin"))) / 1000);
                    Files.writeString(marker, Canon.json(m) + "\n");
                } catch (IOException e) {
                    throw new CliException("cannot write " + marker + ": " + e.getMessage());
                }
                out.println("answer recorded. The preparer runs: chronos --data " + dataDir + " blind grade");
                return 0;
            }
            case "grade" -> {
                try {
                    if (!Files.exists(marker)) throw new CliException("no answer recorded (run `blind answer` first)");
                    Map<String, Object> m = new TreeMap<>(Canon.parseMap(Files.readString(marker)));
                    if (!m.containsKey("answer")) throw new CliException("no answer recorded (run `blind answer` first)");
                    TimelineHeader h = store.header("T0");
                    long token = Long.parseUnsignedLong(h.params().get("m"), 16);
                    BlindKit.Case c = BlindKit.caseFor(token);
                    List<String> given = new ArrayList<>();
                    for (Object o : Canon.list(m.get("answer"))) given.add((String) o);
                    boolean ok = BlindKit.correct(c, given);
                    out.println("planted cause: " + c.truth() + " (" + c.description() + ")");
                    out.println("answer       : " + given + (m.containsKey("seconds") ? ", after " + m.get("seconds") + " s" : ""));
                    out.println("notes        : " + m.get("notes"));
                    out.println("DIAGNOSIS " + (ok ? "CORRECT" : "INCORRECT"));
                    m.put("correct", ok);
                    m.put("planted", new ArrayList<>(c.truth()));
                    Files.writeString(marker, Canon.json(m) + "\n");
                    return ok ? 0 : 1;
                } catch (IOException e) {
                    throw new CliException("cannot read " + marker + ": " + e.getMessage());
                }
            }
            default -> throw new CliException("blind prepare|begin|answer|grade");
        }
    }

    private int serve(Args a) {
        String bind = a.get("bind", "127.0.0.1");
        int port = (int) a.num("port", 8080);
        try (Viewer v = new Viewer(store, bind, port)) {
            v.start();
            out.println("CHRONOS live viewer (read-only) on http://" + bind + ":" + v.port() + "/   (Ctrl-C to stop)");
            out.flush();
            if (a.has("seconds")) {
                try {
                    Thread.sleep(a.num("seconds", 0) * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            throw new CliException("cannot start the viewer on " + bind + ":" + port + ": " + e.getMessage());
        }
        return 0;
    }

    private int localReplay(Args a) {
        String tl = a.positional(0, "timeline");
        NodeLocalReplay nlr = new NodeLocalReplay(store, registry);
        List<String> nodes = a.positionalCount() > 1 ? List.of(a.positional(1, "node")) : store.header(tl).nodes();
        int bad = 0;
        for (String n : nodes) {
            NodeLocalReplay.Result r = nlr.replay(tl, n);
            out.print(r.text());
            if (!r.ok()) bad++;
        }
        return bad == 0 ? 0 : 1;
    }

    private int search(Args a) {
        String tl = a.positional(0, "timeline");
        InterventionSearch.Result r = new InterventionSearch(manager).search(tl, (int) a.num("max", 64), (int) a.num("threads", 4), a.has("keep"));
        out.print(r.text());
        return 0;
    }

    private int campaign(Args a) {
        String scenario = a.positional(0, "scenario");
        String range = a.get("seeds", "1-50");
        String[] p = range.split("-");
        long from = Long.parseLong(p[0]), to = p.length > 1 ? Long.parseLong(p[1]) : from;
        Map<String, String> params = params(a);
        List<Campaign.Entry> res = Campaign.run(registry, scenario, params, from, to, (int) a.num("threads", 4), a.has("chaos"), a.has("horizon") ? a.num("horizon", 0) : null, null);
        long bad = res.stream().filter(Campaign.Entry::violated).count();
        out.println("campaign " + scenario + " seeds " + from + "-" + to + (a.has("chaos") ? " with chaos plans" : "") + ": " + res.size() + " runs, " + bad + " ended in an invariant violation");
        for (Campaign.Entry e : res)
            if (e.violated()) {
                out.println("  seed " + e.seed() + ": " + e.violation() + " (" + e.events() + " events), plan " + e.plan().rules().stream().map(FaultRule::id).toList());
                if (a.has("keep")) record(scenario, e.seed(), params, a.has("chaos") ? "chaos" : "default", a.has("horizon") ? a.num("horizon", 0) : null, null, 25, null);
            }
        return 0;
    }

    private int report(Args a) {
        String tl = a.positional(0, "timeline");
        String against = a.get("against");
        Path file = Path.of(a.get("out", HtmlReport.defaultOut(dataDir, tl, against).toString()));
        generateReport(tl, against, file);
        return 0;
    }

    /** Generates the self-contained HTML report and verifies it (exists, non-empty, no external references). */
    Path generateReport(String tl, String against, Path file) {
        CounterfactualEngine.Report cf = null;
        if (against != null && tl.equals(store.header(against).parentId())) cf = new CounterfactualEngine(manager).assess(tl, against);
        String cmd = "java -jar chronos-cli/target/chronos.jar --data " + dataDir + " report " + tl + (against != null ? " --against " + against : "");
        String html = new HtmlReport(store, registry, 600, 3000).render(tl, against, cf, cmd);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (HtmlReport.referencesExternalResources(html)) throw new IllegalStateException("the report references an external resource; it must be self-contained");
        out.println("report written: " + file.toAbsolutePath() + " (" + html.length() / 1024 + " KiB, self-contained, no network access)");
        // the machine-readable companion: manifest, recording, verification and comparison, recomputed from the store
        Path json = Path.of(file.toString().replaceAll("\\.html?$", "") + ".verification.json");
        try {
            Files.writeString(json, VerificationSummary.json(VerificationSummary.build(store, manager.replayEngine(), registry, tl, against, cf, dataDir.toString())), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println("verification summary written: " + json.toAbsolutePath() + " (machine-readable: manifest, replay and integrity verdicts, comparison)");
        return file;
    }

    // ------------------------------------------------------------------------------------------------------------

    TimelineStore store() {
        return store;
    }

    TimelineManager manager() {
        return manager;
    }

    PrintStream out() {
        return out;
    }

    Path dataDir() {
        return dataDir;
    }

    chronos.sdk.ScenarioRegistry registry() {
        return registry;
    }

    static String indent(String s, String pad) {
        return s.lines().map(l -> pad + l).collect(Collectors.joining("\n", "", "\n"));
    }
}
