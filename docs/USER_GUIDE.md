# CHRONOS user guide

For the idea and the architecture see `README.md` and `ARCHITECTURE.md`; for every command see `chronos help`; for one short runnable
script per subsystem see `DEMO_SCRIPTS.md` (a test runs them). This guide walks through the things you will actually do, using the
smallest application there is.

In the commands below `chronos` means `java -jar chronos-cli/target/chronos.jar`; add `--data DIR` (or set `CHRONOS_DATA`) to choose where
timelines are stored (default `./chronos-data`).

## 1. Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # macOS; on Linux point JAVA_HOME at a JDK 21
mvn -ntp package                                       # builds, runs the tests, produces chronos-cli/target/chronos.jar
```

## 2. The tutorial application

`chronos-demo/src/main/java/chronos/demo/Tutorial.java` is a complete application in about 80 lines. A sender ticks every 50 ms and
sends a numbered `tick` to a receiver; the receiver counts. The invariant `keeps-up` says the receiver is never more than one tick
behind. Without faults it holds. Run it, then break it with a partition:

```bash
chronos run tutorial                                   # recorded T0: no violation
chronos run tutorial --fault partition:100:200:sender\|receiver --no-stop
```

The second run prints the violated invariant and `why`: the causal chains from node starts and inputs to the violation, with the fields
the invariant read (the WITNESS edges). **Notice what the chain does not contain: the partition.** Here the receiver simply never gets
two ticks and nothing waits for them with a timer, so the silence left no event behind (a fault is linked to the *timers* that noticed
it, specification D-23). `why` therefore ends with a clearly labelled **possible silence** section: the messages that never reached the node
the invariant read, and the fault that dropped each. That is a hint, not a proof; the proof is the counterfactual in section 5. In the
replicated store (`chronos run re`) a watchdog timer notices the silence, so the chain itself starts at the partition. Names you will see: **timeline** (`T0`, `T1`: one recorded run),
**event** (`sender#3`: the 3rd event of node `sender`; events of the environment are `env`, named by what they concern, such as
`fault:f1-partition#1`), **edge kinds** (MESSAGE, TIMER, FAULT, OMISSION, WITNESS, ...).

## 3. Look around

```bash
chronos ls                                             # the timeline tree
chronos show T1                                        # the event table; also --diagram, --dot, --json
chronos inspect T1 --goto violation --world            # every node's state, messages in flight, timers and clocks at the violation
chronos inspect T1 --goto violation --back 3           # step back three events
chronos inspect T1 --goto violation --why              # why did it happen: chains from faults, inputs and starts
chronos session T1                                     # an interactive session (goto, step, back, find, watch, diff, run-to, ...)
```

Time travel reads only the log, so it never runs your code and never changes anything: a recorded timeline is immutable.

## 4. Prove the recording is reproducible

```bash
chronos verify T1                                      # re-execute the application under the recorded decisions and compare every event
chronos determinism tutorial --seed 42 --jvm 2         # same scenario twice here and in two fresh JVMs: same log hash
```

If your application secretly reads the clock, a hash order or an unseeded random number, `verify` stops at the first event that
differs and says what differs (the bad-citizen demo `chronos run bad` shows it).

## 5. Ask "what if?"

```bash
chronos branch T1 --remove-fault f1-partition          # branch before the partition, remove it, run on: FAILURE_DISAPPEARS
chronos branch T1 --heal-earlier 100                   # heal it 100 ms earlier: FAILURE_PERSISTS (the failure is at 150 ms, before the new heal)
chronos branch T1 --drop sender#4 --pinned             # drop one message (with the Pinned continuation policy)
chronos compare T1 T1.1                                # where do the two timelines first diverge, and the path to the failure (T1.1 = the removal)
chronos search T1                                      # which single changes in the causal past make the failure disappear
chronos report T1 --against T1.1                       # a static, self-contained HTML page with lanes, arrows and the divergence cone
```

A branch shares its parent's history by reference and stores only what happens after the branch point. The verdicts are
`FAILURE_DISAPPEARS`, `FAILURE_PERSISTS`, `DIFFERENT_FAILURE`, `NO_EFFECT` and `INCONCLUSIVE`. A counterfactual is evidence about the
simulated world under CHRONOS's rules, not proof about the real world, and a but-for test can miss a failure with two sufficient causes
(try `chronos causes` on `re2`).

## 6. Find failures you did not plan

```bash
chronos campaign tutorial --seeds 1-200 --chaos --keep # random fault plans over many seeds; the failing runs are kept as timelines
chronos inject T0 --fault crash:receiver:120           # add a fault to a recorded run
```

## 6b. Explore fault schedules systematically, and hand an incident to someone else

Random campaigns find failures; they cannot say what was *not* covered. `explore` enumerates **every** subset of at most K candidate
faults, in one fixed order, runs the scenario under each and checks the invariants:

```bash
chronos explore re --param partition=off --max-faults 2 --times 100,300 --kinds crash,partition,pause --save
chronos explore re --param partition=off --candidate partition:300:400:p\|b --candidate pause:client:150:20 --max-faults 2   # your own candidates
```

The report states the space (candidates, subsets), what was executed, which executions were recognised as identical traces (a fault that
never fires changes nothing) and which schedules were skipped because they contain an already violating one (minimal counterexamples), how it
ended (`EXHAUSTED`, `CASE_LIMIT`, `FIRST_VIOLATION`) and, if it stopped early, how much of the bound was **not** explored. "No violation" always
means "none within this bound, with this seed and horizon". `--save` records each violating schedule as a normal timeline plus a checksummed
document in `counterexamples/`; `chronos counterexample FILE` re-executes it from the document alone and checks the same violation appears.

Is the store itself sound? `chronos check` verifies every timeline without changing anything and says what to do about each problem:

```bash
chronos check                    # logs, checksums, clocks, lineage, indexes, checkpoints and a full deterministic replay of each finished timeline
chronos check T0.1 --no-replay   # just that timeline (and skip the replay)
chronos check --json             # the same, machine-readable
chronos check --strict           # exit code 2 on warnings too (an interrupted write, a missing index...)
```

(On the demo data directory it reports exactly one failure, the deliberately nondeterministic node of AD-2: a store can be intact and still
hold a run that does not replay, and the check says which.) `OK` means every check passed, `WARN` means a recoverable condition with the action named (for example `chronos reindex T1`), `FAIL` names the
damaged record and tells you to restore the timeline from a backup or an incident bundle. Other timelines are still checked. Every `report` also
writes `<report>.verification.json`: the experiment manifest (scenario, parameters, seed, fault plan, interventions of the lineage, code
version), a fresh replay and integrity verdict, and for `--against` the divergence findings, so a result can be checked by a program.

A failure found by a random campaign usually carries faults that have nothing to do with it. `minimize` removes them:

```bash
chronos run re --seed 5 --plan chaos          # a chaos run with 5 fault rules that violates single-primary
chronos minimize T0 --save                    # delta debugging: 5 rules -> 1 (the partition), its duration 310 -> 172 ms, horizon 1000 -> 301 ms
chronos inspect T1 --goto violation --why     # the saved minimal failure explains itself
```

It re-executes the run from scratch under smaller and smaller fault plans (ddmin over the rules, then smaller `duration` and `by` values, then the
shortest horizon) and keeps only a plan that violates **the same invariant**. It then re-runs the result without each kept rule and prints what
happened, so the "1-minimal" claim is evidence, not assertion. It does not claim the smallest plan in the whole space, and `--max-runs N` makes it
stop early (exit code 3, "NOT FINISHED"). `--save` stores the minimal failure as a timeline plus a checksummed document in `counterexamples/`
(`chronos counterexample FILE`, `chronos incident export T1`).

To give a failure to someone else, bundle it: `chronos incident export T0 --out t0.chronos-incident` writes one checksummed file (the
timeline and its ancestors, the fault plan and interventions, the violation, the causal evidence, versions and reproduction steps). On the
other machine: `chronos --data DIR incident info FILE` validates it without writing anything, `incident reproduce FILE` re-executes the
application and checks the same trace and violation, `incident import FILE` adds it to a store for `inspect`, `why` and `compare`. A damaged,
forged or unsupported bundle is rejected with a reason and nothing is written. Reproduction needs the same application (scenario) on the
receiving side; without it the bundle can still be inspected, because the log is the source of truth.

## 7. Write your own application

An application is a set of `Node` classes plus a `Scenario` (nodes, workload, fault plan, invariants). Read `Tutorial.java`; the rules are:

* A node is a single-threaded event handler: `onStart`, `onMessage`, `onTimer`, `onClientInput`, `onRestart`. It must be effectively
  stateless: **all memory lives in `ctx.state()`** (a volatile zone, lost on a crash, and a durable zone, which survives).
* Every effect goes through the `NodeContext`: `send`, `setTimer`, `cancelTimer`, `random(stream)`, `now()`, `config`, `note`.
* Never use the clock, `Random`, threads, sleeping, static mutable state, or iteration over an identity-hashed collection. The determinism
  contract is what makes replay possible; `chronos verify` tells you when you broke it, and the build's `BuildRulesTest` bans the usual
  culprits in the demo module.
* An invariant reads node state only through the `WorldView` it is given (the reads are recorded, which is how the WITNESS edges are known).
* Register the scenario by name in `DemoRegistry.create` (scenarios are Java classes registered by name, D-22), rebuild, and run
  `chronos run <name>`.

## 8. Storage, size, speed

```bash
chronos run tutorial --compress                        # gzip the log segments (about 4.5x smaller)
chronos reindex --check                                # are the derived index files intact? (`chronos reindex T0` rewrites them from the log)
chronos serve --port 8080                              # a read-only live viewer: open http://127.0.0.1:8080/
java -jar chronos-bench/target/chronos-bench.jar all --csv bench.csv   # measurements against the targets of Section 8
```

## 9. When something goes wrong

| You see | Meaning |
|---|---|
| `DETERMINISM VIOLATION: mismatch at X` (exit code 2) | the application or a recording is not reproducible; the message names the first differing event and likely causes |
| `chronos: the stored log is not usable` (exit code 2) | a checksum failed: the log was modified or damaged after it was written |
| `position N is before the first step boundary ... at which a world exists` | a branch point inside the boot sequence; use a later position |
| `fault rule(s) [...] are flagged nonLocal` | the plan has wildcard-sender rules, so the locality law M8 does not apply to that run |
| `NOTE: ... synthetic` | the timeline contains a state edit no real execution could produce (`--patch-state`) |
