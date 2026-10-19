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
the invariant read (the WITNESS edges). **Notice what is missing: the partition is not in the chain.** Here the receiver simply never gets
two ticks and nothing waits for them with a timer, so the silence left no event behind (a fault is linked to the *timers* that noticed
it, specification D-23). `why` tells you what happened; to find what was *necessary*, ask the counterfactual questions in section 5. In
the replicated store (`chronos run re`) a watchdog timer notices the silence, so the chain does start at the partition. Names you will see: **timeline** (`T0`, `T1`: one recorded run),
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
