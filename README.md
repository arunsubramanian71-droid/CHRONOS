# CHRONOS

**Record, rewind, replay, branch and compare executions of a distributed application.**

CHRONOS runs a distributed application inside a world it fully controls (a deterministic discrete-event simulation), turns the
run into structured, causally linked data, and then lets you navigate, replay, branch and compare that data. Think of it as
*Git for executions*: every run is an immutable timeline, you can check out any moment, branch from it with one change, and
diff two branches down to the first causal divergence.

It is implemented from the architecture contract `CHRONOS System Specification v0.2.pdf` (included, unchanged).
Terms such as *Node API*, *event*, *step*, *timeline*, *intervention*, *OMISSION edge* and *first causal divergence* are used
exactly as the specification defines them.

> **Status in one paragraph.** All eight modules exist and build (verified on macOS and on Linux); 233 automated tests pass; the flagship
> demonstration (acceptance demonstrations AD-1 to AD-6 and known-answer scenarios KA-0, KA-2, KA-3, KA-4 on the replicated store "RE")
> runs from one command with 24 self-checks; the whole fault catalogue and the whole intervention catalogue of the specification are
> implemented and tested. What is **not** done (for example the blind
> evaluation with a second person, Windows verification), and what deviates from the specification, is listed honestly in
> [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md), [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md) and
> [`docs/DEVIATIONS.md`](docs/DEVIATIONS.md). Full v0.2 compliance is **not** claimed.

**Submission and demonstration:** [`SUBMISSION_README.md`](SUBMISSION_README.md) (prerequisites, build, test, integrity check, limitations) and
[`docs/PROFESSOR_DEMO.md`](docs/PROFESSOR_DEMO.md) (a guided demonstration; `tools/professor-demo.sh` runs it).

## The idea in five moves

1. **Record.** Every meaningful happening (message sent or delivered, timer fired, fault injected, state changed) is an immutable
   event with a deterministic identity, virtual time, a Lamport value, a vector clock with `N + 1` slots (one per node, `env` last)
   and causal parents with edge kinds.
2. **Understand.** Events form a causal graph (PROGRAM_ORDER, STEP_EFFECT, MESSAGE, TIMER, FAULT, WITNESS and OMISSION edges).
   `why` walks from a symptom back to its faults, inputs and starts.
3. **Rewind and replay.** Any past moment is reconstructed from the log without running application code; the whole run can be
   re-executed and every regenerated event is checked against the recording (deterministic replay).
4. **Branch and change.** From any step boundary a branch shares the parent's history by reference, applies one intervention
   (heal a partition earlier, drop or delay a message, crash a node, ...) and runs forward through the *same* engine.
5. **Compare and explain.** Two timelines are aligned by event identity, causal roots are found separately inside each timeline,
   and the earliest root is reported as the **first causal divergence** with the explanation path to the failure.

## Architecture (eight Maven modules, dependencies point only downwards)

| Module | Responsibility (specification component) | May depend on |
|---|---|---|
| `chronos-model` | Events, ids, kinds, edge kinds, clocks (C4), canonical encoding and digests, fault-plan / intervention / world-image types, `LogReader`, `EventSink` | nothing |
| `chronos-sdk` | The **Node API** (C1, D-26): node, node context, state with volatile/durable zones, state codec, scenario, invariant, world view | model |
| `chronos-store` | Append-only segmented JSON-lines log with commit lines and checksums, checkpoints, timeline catalogue, segment resolution, asynchronous writer (C6) | model |
| `chronos-runtime` | Single-threaded deterministic engine (C2), record-mode decision source (C3), recorder (C5), fault injector (C12), invariant checker (C15), checkpoint capture/restore | sdk, model |
| `chronos-demo` | Demo applications and scenarios (C17): ping-pong, causal demo, **RE**, lost update, blocked commit, control, gossip, bad citizen | sdk, model |
| `chronos-analysis` | Causal graph (C7), state reconstruction (C8), replay and branch decision sources, replay engine (C9), time-travel session (C10), timeline manager (C11), counterfactual engine (C13), comparator (C14) | runtime, sdk, store, model |
| `chronos-cli` | Hand-written command line, exporters and the static HTML report (C16) | analysis, runtime, store, demo, sdk, model |
| `chronos-bench` | JMH micro-benchmarks, whole-system harness, chaos soak, build-time rule checks (C18) | all |

The direction of dependency and the determinism hygiene (no wall-clock, unseeded randomness, raw threads ... in model, sdk,
runtime and demo) are **enforced by the build**: `chronos-bench/src/test/.../BuildRulesTest` fails the build on a violation.

Key design points taken from the specification: one decision source for all nondeterminism (D-19), identity-keyed randomness and
stable tie-breaking `(due time, target node index, event-kind rank, identity)` (D-06), canonical JSON written with Jackson (the
only runtime library) under CHRONOS's own canonical rules (D-09), environment events keyed by what they concern (D-24),
INTERVENTION_APPLIED on env (D-25), static self-contained HTML report (D-18).

## Prerequisites

* macOS, Linux or Windows with **JDK 21** (verified on macOS / Apple Silicon and in a Linux container; Windows not verified) and **Maven 3.9+** (verified with 3.10.0).
* The build refuses to run on any other JDK (a Maven enforcer rule), because newer JDKs may also be installed.

```bash
# if several JDKs are installed, select 21 for this shell (macOS):
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version && mvn -version      # both must report Java 21
```

## Build and test

```bash
mvn -ntp package          # compiles all eight modules, runs all 165 tests, builds the two jars
```

Produces `chronos-cli/target/chronos.jar` (the CLI) and `chronos-bench/target/chronos-bench.jar` (benchmarks). Dependencies are
resolved from Maven Central on the first build.

## Run the flagship demonstration

```bash
java -jar chronos-cli/target/chronos.jar --data chronos-data demo
```

One command runs **AD-1 to AD-6** on RE and the known-answer scenarios **KA-0, KA-2, KA-3, KA-4**, printing what each
component actually produced and checking every claim (`[PASS]` / `[FAIL]`; the exit code is non-zero if any check fails). It
records timelines under `chronos-data/timelines/` and writes the HTML report to `chronos-data/reports/T0-vs-T0.1.html`:

```bash
open chronos-data/reports/T0-vs-T0.1.html       # macOS; any browser works, no server or network needed
```

A pre-generated example report is in [`docs/sample-report-T0-vs-T0.1.html`](docs/sample-report-T0-vs-T0.1.html).

### RE in two sentences

RE is a tiny replicated key-value store with a client, a primary `p` and a backup `b`. `p` heartbeats every 100 ms, `b` promotes
itself after 300 ms of silence, and a 400 ms partition (300 to 700 ms) cuts `p` from `b`, so two primaries exist and the
single-primary invariant breaks.

## Using the CLI step by step

`chronos --data DIR <command>` (or set `CHRONOS_DATA`; default `./chronos-data`). `chronos help` lists everything.
Below, `chronos` stands for `java -jar chronos-cli/target/chronos.jar`.

| Goal | Command |
|---|---|
| Run and record RE (the failing run, kept running to the horizon) | `chronos run re --seed 42 --no-stop` |
| Add faults to a run: pause, partition, crash, skew, slow link, reorder, after an event ... | `chronos run re --param partition=off --fault pause:p:300:400 --no-stop` (see `chronos help` for every `--fault` form) |
| What happened, as the Section 10 event table / space-time diagram / DOT / JSON | `chronos show T0` · `--diagram` · `--dot` · `--json` |
| Time travel: jump to the violation, show states, in-flight messages, timers, clocks | `chronos inspect T0 --goto violation --world` |
| Step backward three times | `chronos inspect T0 --goto violation --back 3` |
| Why did it fail? (causal past + chains from faults, inputs and starts) | `chronos inspect T0 --goto violation --why` |
| Interactive-style session from stdin (`goto`, `step`, `back`, `state`, `world`, `why`, `find`, `watch`, `diff`, `run-to`, `branch` ...) | `chronos session T0` |
| Stepped replay: re-execute (verifying) until a breakpoint and inspect the live world | `echo "run-to time=500 node=p" \| chronos session T0` |
| Replay and verify every regenerated event against the log | `chronos verify T0` or `chronos replay T0` (from a checkpoint: `--from-checkpoint 26`) |
| Inject more faults into a recorded run (new run, same scenario and seed) | `chronos inject T0 --fault partition:300:400:p\|b` |
| Same scenario twice in this JVM and in 2 fresh JVMs; compare log hashes | `chronos determinism re --seed 42 --jvm 2` |
| What if the partition had healed 200 ms earlier? | `chronos branch T0 --heal-earlier 200` |
| Other interventions | `--remove-fault RULE` · `--heal-later MS` · `--drop MSGID` · `--delay MSGID:MS` · `--duplicate MSGID` · `--swap MSG:MSG` · `--crash NODE:AT` · `--restart NODE:AT` · `--pause NODE:AT:DUR` · `--skew NODE:AT:OFFSET` · `--add-input NODE:AT:JSON` · `--remove-input N` · `--config K=V` · `--reseed NODE:STREAM:SEED` · `--patch-state NODE:volatile.f=V` (synthetic) · `--at SEQ\|EVENT\|violation` |
| Branch with the Pinned continuation policy (messages the baseline also sent keep their delivery instants where possible) | add `--pinned` to any branching command |
| Where do two timelines first diverge, and why? | `chronos compare T0 T0.1` |
| Which combination of fault rules is critical? (KA-4) | `chronos causes T0` |
| What had node b heard about p at this event? The smallest consistent cut containing events? | `chronos inspect T0 --knows b#12` · `chronos inspect T0 --cut b#12,client#3` (also `knows`/`cut` in `session`) |
| Browse timelines in a browser: slider, diagram, world, why, compare (read-only live viewer) | `chronos serve --port 8080`, then open http://127.0.0.1:8080/ |
| Store a recording gzip-compressed (opt-in, about 4.5x smaller; any command that records accepts it) | `chronos run re --compress` |
| Verify the whole store (checksums, clocks, lineage, indexes, replay) with the fix for each problem; JSON for scripts | `chronos check` · `chronos check --json --strict` |
| A machine-readable verification summary and experiment manifest beside every HTML report | `chronos report T0 --against T0.1` writes `reports/T0-vs-T0.1.verification.json` |
| Shrink a failing chaos run to the faults that matter (delta debugging, 1-minimal, reproducible) | `chronos run re --seed 5 --plan chaos` · `chronos minimize T0 --save` |
| Explore every fault schedule up to a bound (systematic, with counterexamples and an explicit coverage statement) | `chronos explore re --param partition=off --max-faults 2 --times 100,300 --save` · `chronos counterexample FILE` |
| Hand a failure to someone else as one checksummed file; validate, reproduce and import it elsewhere | `chronos incident export T0 --out F` · `incident info F` · `incident reproduce F` · `incident import F` |
| Check or rebuild the derived index files (`index/main.idx`) | `chronos reindex [T0] [--check]` |
| Blind-debugging kit for a second person (prepare a hidden case, time and grade a diagnosis) | `chronos blind prepare\|begin\|answer\|grade` |
| Re-run one node alone from its recorded inputs and check its outputs (R5) | `chronos local-replay T0 [b]` |
| Which single change to the causal past makes the failure disappear? (intervention search) | `chronos search T0 [--max 64] [--keep]` |
| Find a failing run by chaos (random fault plans, parallel) | `chronos campaign re --seeds 1-200 --chaos --param partition=off --keep` |
| Static HTML report, optionally with a branch for comparison | `chronos report T0 --against T0.1 [--out FILE]` |
| The timeline tree / delete a leaf | `chronos ls` · `chronos delete T0.1` |

Scenario parameters: `--param omission=literal` (any scenario: the specification's literal OMISSION scope for partitions, DEV-03), `--param partition=off` (re, re2), `delay=off` (ka2), `crash=off` (ka3), `nodes=N`, `horizon=MS` (gossip).
Scenarios: `pingpong`, `causal`, `re`, `re2` (two backups, KA-4), `ka2`, `ka3`, `control` (KA-0), `gossip`, `bad` (deliberately
nondeterministic node for AD-2). `chronos scenarios` lists them.

## Benchmarks and soak

```bash
java -jar chronos-bench/target/chronos-bench.jar all --csv bench-results.csv   # whole-system harness, 10,000-seed chaos soak, NFR-01 at full size
java -jar chronos-bench/target/chronos-bench.jar jmh                           # JMH micro-benchmarks (vector clocks, encoding)
java -jar chronos-bench/target/chronos-bench.jar eval --seeds 400              # evaluation on unplanned chaos failures (docs/EVALUATION.md)
```

Measured results (and which targets of Section 8 were met or missed) are in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md). `tools/regenerate-results.sh` rebuilds, reruns the demo, the benchmarks and the evaluation and rewrites the generated documents (`--long` adds the long randomized campaigns).

## Where things are

* `IMPLEMENTATION_STATUS.md`: current state, exact commands run and their results, known defects, next steps.
* `docs/TRACEABILITY.md`: every decision D-01..D-26, requirement FR/NFR, law M1..M14, known-answer scenario and acceptance
  demonstration mapped to code and tests, each marked COMPLETE / SIMPLIFIED / NOT IMPLEMENTED / NOT VERIFIED.
* `docs/DEVIATIONS.md`: where this implementation interprets, extends or departs from the specification, and why. Also the
  open questions Q1 and Q6.
* `docs/USER_GUIDE.md` (a task-oriented walk-through with the tutorial application), `docs/ARCHITECTURE.md`, `docs/TEACHING_NOTES.md` (one note per component),
  `docs/DEMO_SCRIPTS.md` (a short runnable script per subsystem, executed by a test), `docs/PORTABILITY.md` (macOS, Linux, Windows status), `docs/BLIND_PROTOCOL.md` (the kit for the blind debugging protocol).
* `docs/BENCHMARKS.md` (measured results), `docs/EVALUATION.md` (the experiment on unplanned failures and what it does not show),
  `docs/sample-report-T0-vs-T0.1.html`, `docs/demo-output.txt` (a real demo transcript), `docs/VIVA_GUIDE.md`.

## Limitations (summary; details in the documents above)

* Simulation only (D-03): no real network, no real processes; applications are written against the Node API.
* Not implemented: per-vector delta compression (its trigger in the specification did not occur, DEV-34), and a **blind** debugging evaluation with a second person
  (`docs/EVALUATION.md` evaluates the analysers on 800 unplanned chaos runs instead, and says what that does not show).
* Verified on macOS (Apple Silicon, JDK 21) and on Linux (Ubuntu 24.04 in a container); **Windows is not verified**.
* A counterfactual is evidence about the simulated world under CHRONOS's rules, not proof about reality (Section 19).
* Interpretations and extensions of the specification are recorded as DEV-01 to DEV-52 in `docs/DEVIATIONS.md`.
