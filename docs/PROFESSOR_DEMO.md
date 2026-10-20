# Demonstration guide (for the evaluator's session)

Prerequisites and build: see `SUBMISSION_README.md` sections 1 and 2. Run everything from the project root. The whole tour is one script:

```bash
tools/professor-demo.sh            # uses ./demo-data; pass another empty directory as the first argument to keep several runs
```

## What CHRONOS does, in one minute

A distributed application (here, a replicated store with a primary `p`, a backup `b` and a `client`) runs inside a deterministic simulation:
one thread, virtual time, every random choice derived from a seed. Everything that happens is recorded as an immutable **event** with a Lamport
clock, a vector clock and causal parents. From that recording CHRONOS can (1) replay the run and prove it reproduces exactly, (2) show the world at any
past moment, (3) branch from any moment with one change and run forward, (4) compare two timelines down to the first causal divergence, and
(5) explain a failure by walking its causal chains back to the fault that started it.

## Architecture in one table

| Module | Role |
|---|---|
| `chronos-model` | events, ids, clocks, canonical JSON, fault and intervention types (depends on nothing) |
| `chronos-sdk` | the Node API an application is written against |
| `chronos-store` | append-only segmented JSON-lines log with checksums, checkpoints, catalogue, indexes |
| `chronos-runtime` | the deterministic engine, fault injector, invariants, recorder |
| `chronos-demo` | demo applications (RE, KA scenarios, gossip, a deliberately nondeterministic node) |
| `chronos-analysis` | causal graph, replay, time travel, branching, counterfactuals, comparator, explorer, minimizer, integrity checker |
| `chronos-cli` | the `chronos` command line, HTML report, live viewer |
| `chronos-bench` | benchmarks and build-rule tests that enforce the dependency direction and determinism hygiene |

## The tour, step by step (what to show, what to say)

| Step | Command (from the script) | What to point at |
|---|---|---|
| 1 | `chronos run re --no-stop` | The run violates `single-primary` (split brain). The printed `why` chain goes from the partition fault through an **OMISSION** edge to the backup's watchdog timer and the violation. Say: OMISSION is a *potential* link, everything else is certain. |
| 2 | `chronos verify T0` | `REPLAY VERIFIED: 72 events matched`: the run was re-executed and every regenerated event equals the recording (ids, clocks, parents, payloads, state digests). This is **deterministic replay**. |
| 3 | `chronos inspect T0 --goto violation --world` | **Time travel**: node states, in-flight messages, pending timers and clocks at the violation, reconstructed from the log. |
| 4 | `chronos branch T0 --heal-earlier 200` | **Counterfactual**: a branch T0.1 that heals the partition 200 ms earlier. Verdict `FAILURE_DISAPPEARS`: the partition was a but-for cause (in this world). |
| 5 | `chronos compare T0 T0.1` | The **first causal divergence** is the intervention itself (`iv:heal-early`), with the explanation path and the cone of affected events. |
| 6 | `chronos report T0 --against T0.1` | Open `demo-data/reports/T0-vs-T0.1.html` (self-contained) and `T0-vs-T0.1.verification.json` (experiment manifest, replay and integrity verdicts, comparison). |
| 7 | `chronos check` | **Integrity check**: header, catalogue, lineage, checksums, clocks, index, checkpoints, full replay, each with a status and the action to take. |
| 8 | `chronos run bad --seed 3`, `chronos verify T1` | A node that secretly uses real time: replay reports `DETERMINISM VIOLATION` at the first differing event (exit code 2 is the expected outcome). |
| 9 | `chronos check` | Now says `STORE INTACT, REPLAY FAILS`: the files are sound, but one run is not deterministic. |
| 10 | `chronos run re --seed 5 --plan chaos`, `chronos minimize T2 --save` | Delta debugging: 5 random faults shrink to the 1 that matters (the partition), saved as a re-executable counterexample. |
| 11 | `chronos demo` | All acceptance demonstrations AD-1 to AD-6 and known-answer scenarios, 24 self-checks, exit code 0. |

Other things worth showing if asked: `chronos explore re --param partition=off --max-faults 2 --times 100,300 --save` (every fault schedule up to a bound, with an
explicit coverage statement), `chronos incident export T0` (a portable, checksummed bundle another machine can import and reproduce), `chronos serve --port 8080` (read-only live viewer).

## Evidence that is backed by an actual run

| Claim | Evidence |
|---|---|
| 238 automated tests pass, 0 failures | `mvn -ntp clean package`, run on macOS and in a Linux container (see `IMPLEMENTATION_STATUS.md`); re-run on the extracted submission archive |
| 24 of 24 demonstration checks | `chronos demo`, exit code 0 (`docs/demo-output.txt`) |
| Law M10 (the first causal divergence is the intervention; every root of the branch is in its future) | `M10CatalogueTest`; on the final code a 4,000-seed sweep gave 0 residual of 8,752 comparisons and a 12,000-seed sweep 0 of 26,461 (`IMPLEMENTATION_STATUS.md`) |
| Branches replay through their lineage | `InterventionFuzzTest`: seeds 1-10,000 and 40,001-50,000 on the final code (and 20,001-40,000 on the code before the OMISSION change), every branch and nested branch replay-verified, `chronos check` over every store written |
| Performance targets | `docs/BENCHMARKS.md` (one machine, Apple M2 Max) |

## Honest answers to likely questions

* *Is it fully compliant with the specification?* No. `docs/TRACEABILITY.md` lists every requirement with its status; `docs/DEVIATIONS.md` lists every interpretation, extension and defect (DEV-01 to DEV-54). Not done: the blind evaluation and the new-user run (NFR-15), which need real participants (`docs/HUMAN_STUDIES.md`); per-vector delta compression is a conditional requirement whose trigger was measured and is not met.
* *Is the causal analysis a proof of cause?* Certain edges (message, timer, step effect, fault) are real causes in the run. OMISSION edges, "displaced" events and the possible-silence section are labelled **potential**: they are hypotheses a reader can check. A counterfactual verdict is true for that simulated world, not for production.
* *How do you know replay is really deterministic?* One decision source for all nondeterminism, identity-keyed randomness, a single thread, and a build rule that bans wall-clock and unseeded randomness in the core modules. Evidence: replay of every demo and 16,000 seeded run pairs identical, cross-JVM hash equality, and a deliberately bad node that replay catches.
* *Why is the OMISSION scope for partitions what it is?* The specification's literal sentence gives an OMISSION edge to every node on a side of a partition, which also blames the partition for timers that wait for nothing (4 of the 5 edges in RE are the isolated node's own heartbeats). Requiring that the node heard from the other side avoids that but misses a partition that starts before the first contact. The final rule adds one thing: the partition dropped a message addressed to that node during the timer's wait. It never adds an edge the literal rule would not (`OmissionScopeStudyTest`, DEV-03).
* *Did you implement delta compression of vector clocks?* No, and the specification makes it conditional on vector-clock cost dominating at 20 nodes. Benchmark B3b measures it: a clock stamp is about 3% of the time per event and the vector field about 30% of an encoded event at 20 nodes, so the trigger is not met (`docs/BENCHMARKS.md`).
* *Has a person other than you used it?* No. The usability test (NFR-15) and the blind debugging protocol need participants; the protocols and sheets are ready (`docs/HUMAN_STUDIES.md`) and nothing is claimed about them.
* *Is the explorer a model checker?* No. It runs every subset of at most K declared faults for one seed; "no violation" means none inside that bound. `minimize` returns a 1-minimal plan (no single rule can be removed), not necessarily the smallest.
* *What if the log is damaged?* Each step group has a SHA-256 commit line; a torn tail is dropped, anything else is refused. `chronos check` reports the problem with the action. A rewrite that also repairs every checksum and the summary is caught only by replay against the code.
* *What would you do next?* Run the blind debugging study and the new-user run (NFR-15) with real participants, try applications written by someone else against the Node API, and a run on a Windows machine beyond the CI job.
