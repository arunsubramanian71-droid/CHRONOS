# Demonstration scripts: one short, runnable script per subsystem

The specification's definition of done (Section 24) asks for "a runnable demonstration written down as a short script" for every
phase. Each script below is a fenced block tagged `chronos-script`; `DemoScriptsTest` runs every block in a fresh data directory, one
command per `$` line, and checks the lines that follow it, so these scripts cannot drift from the program:

* `$ <arguments>`: the arguments of `chronos` (the data directory is added by the test; by hand use `chronos --data DIR ...` or the
  `CHRONOS_DATA` variable);
* `  expect: <text>`: the command's output must contain this text;
* `  exit: N`: the exit code must be N (default 0).

By hand, with `chronos` standing for `java -jar chronos-cli/target/chronos.jar`. The flagship, all-in-one version of the acceptance
demonstrations is `chronos demo` (transcript: `demo-output.txt`).

## The tutorial application (see USER_GUIDE.md)

```chronos-script
$ run tutorial
  expect: COMPLETED
$ run tutorial --fault partition:100:200:sender|receiver --no-stop
  expect: INVARIANT VIOLATED: keeps-up
$ branch T1 --remove-fault f1-partition
  expect: VERDICT: FAILURE_DISAPPEARS
$ branch T1 --heal-earlier 100
  expect: VERDICT: FAILURE_PERSISTS
$ compare T1 T1.1
  expect: FIRST CAUSAL DIVERGENCE
$ search T1
  expect: CRITICAL
$ verify T1.1
  expect: VERIFIED
```

## Phases 1 to 6: record a controlled run, read its clocks and causality (Milestone A)

```chronos-script
$ scenarios
  expect: pingpong
$ run pingpong
  expect: recorded T0: pingpong
$ show T0
  expect: MESSAGE_DELIVERED
$ show T0 --diagram
  expect: A
$ show T0 --dot
  expect: digraph
$ show T0 --json
  expect: "events"
$ run causal
  expect: recorded T1: causal
$ inspect T1 --why
  expect: why
```

## Phase 4 and FR-48: the determinism check (same scenario twice, same log hash)

```chronos-script
$ determinism re --seed 42 --jvm 1
  expect: DETERMINISTIC
$ determinism gossip --seed 7 --param nodes=5 --param horizon=800
  expect: IDENTICAL
```

## Phases 7 and 8: persistence, reconstruction, witnesses (Milestone B)

```chronos-script
$ run re --no-stop
  expect: INVARIANT VIOLATED: single-primary
$ ls
  expect: T0  re seed=42
$ inspect T0 --goto violation --world
  expect: PRIMARY
$ inspect T0 --goto violation --state p --state b
  expect: state of b
$ reindex --check
  expect: T0: indexes persisted
$ run re --no-stop --compress
  expect: recorded T1
$ verify T1
  expect: VERIFIED
```

## Phase 9: deterministic replay, and the deliberately bad node (AD-2, Milestone C)

```chronos-script
$ run re --no-stop
$ verify T0
  expect: VERIFIED
$ verify T0 --from-checkpoint 25
  expect: VERIFIED
$ run bad --seed 3
$ verify T1
  expect: DETERMINISM VIOLATION
  exit: 2
$ local-replay T1
  expect: MISMATCH
  exit: 1
$ check
  expect: FAIL replay
  expect: not deterministic
  expect: STORE INTACT, REPLAY FAILS
  expect: 1 timeline(s) do not replay deterministically
  exit: 2
$ check T0 --strict
  expect: STORE OK: 1 timelines
```

## Phases 10a and 10b: time travel (AD-3)

```chronos-script
$ run re --no-stop
$ inspect T0 --goto violation --world
  expect: in-flight messages
$ inspect T0 --goto violation --back 3
  expect: cursor
$ inspect T0 --goto violation --inflight --timers
  expect: pending timers
$ inspect T0 --knows b#12
  expect: knowledge of b
$ inspect T0 --cut b#12
  expect: consistent: true
$ run re --no-stop --param partition=off
$ session T0 --help
```

## Phases 11 to 13: branch, intervene, verdict (AD-4)

```chronos-script
$ run re --no-stop
$ branch T0 --heal-earlier 200
  expect: VERDICT: FAILURE_DISAPPEARS
$ branch T0 --remove-fault partition-pb
  expect: VERDICT: FAILURE_DISAPPEARS
$ branch T0 --heal-earlier 200 --pinned
  expect: continuation policy: Pinned
$ ls
  expect: T0.1
$ verify T0.1
  expect: VERIFIED
```

## Phase 14: compare two timelines, name the first causal divergence (AD-5, KA-1)

```chronos-script
$ run re --no-stop
$ branch T0 --heal-earlier 200
$ compare T0 T0.1
  expect: FIRST CAUSAL DIVERGENCE: iv:
  expect: explanation path
$ causes T0
  expect: FAILURE_DISAPPEARS
$ search T0
  expect: CRITICAL
```

## Phase 12b: the full fault catalogue and campaigns (KA-2, KA-3, KA-4)

```chronos-script
$ run ka2
  expect: replicas-converge
$ branch T0 --remove-fault delay-first-replicate
  expect: VERDICT: FAILURE_DISAPPEARS
$ run ka3
  expect: INVARIANT VIOLATED
$ run re2
  expect: INVARIANT VIOLATED
$ causes T2
  expect: MINIMAL CRITICAL SETS: [[partition-p-b1, partition-p-b2]]
$ run re --param partition=off --fault pause:p:300:400 --no-stop
  expect: recorded T3
$ inject T3 --fault crash:b:600
  expect: recorded T4
$ campaign re --seeds 1-40 --chaos --param partition=off
  expect: campaign re seeds 1-40
$ run control
  expect: recorded T5
```

## Systematic exploration of fault schedules (not fuzzing) and counterexamples

```chronos-script
$ explore re --param partition=off --max-faults 2 --times 100,300 --kinds crash,partition,pause --save --save-max 1
  expect: EXHAUSTED
  expect: single-primary
  expect: saved counterexample T0
$ inspect T0 --goto violation --why
  expect: chain from fault:
$ explore re --param partition=off --max-faults 2 --max-cases 10 --times 100,300
  expect: CASE_LIMIT
  expect: NOT explored
$ explore control --max-faults 1 --times 30,90
  expect: no invariant violation in the whole explored bound
```

## Failure minimization: from a noisy chaos failure to the faults that matter

```chronos-script
$ run re --seed 5 --plan chaos
  expect: VIOLATED
$ minimize T0 --save
  expect: fault rules: 5 -> 1
  expect: kept: c1-partition
  expect: shrunk: c1-partition.duration
  expect: check: without c1-partition: no invariant is violated
  expect: RESULT: 1-minimal
  expect: saved minimal failure as T1
$ inspect T1 --goto violation --why
  expect: chain from fault:c1-partition#1
$ minimize T1
  expect: fault rules: 1 -> 1
$ run ka3 --seed 13 --plan chaos
  expect: VIOLATED
$ minimize T2 --max-runs 3
  expect: NOT FINISHED
  exit: 3
```

## Integrity: `check`, refused operations leave nothing behind, and the report's verification summary

```chronos-script
$ run re --no-stop
$ branch T0 --heal-earlier 200
$ check
  expect: STORE OK: 2 timelines, 0 warnings, 0 failures
  expect: every finished timeline replayed deterministically
$ branch T0 --at 20 --crash ghost:5
  expect: unknown node ghost
  exit: 1
$ check --strict
  expect: STORE OK: 2 timelines
$ report T0 --against T0.1
  expect: verification summary written
$ check --json --no-replay
  expect: "ok":true
```

## Phase 15: the static HTML report (AD-6) and the live viewer

```chronos-script
$ run re --no-stop
$ branch T0 --heal-earlier 200
$ report T0 --against T0.1
  expect: report written
$ serve --port 0 --seconds 1
  expect: live viewer (read-only) on http://127.0.0.1:
```

## Phase 16: measurements

```bash
java -jar chronos-bench/target/chronos-bench.jar all --csv docs/benchmark-results.csv      # about 4 minutes
python3 chronos-bench/tools/benchmarks_md.py                                               # regenerates docs/BENCHMARKS.md
```

## Phase 17: the flagship demonstration, all acceptance demonstrations in one command

```bash
java -jar chronos-cli/target/chronos.jar --data chronos-data demo        # exit code 0 means all 24 checks passed
```
