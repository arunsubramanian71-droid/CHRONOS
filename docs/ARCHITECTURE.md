# CHRONOS architecture

CHRONOS is a deterministic discrete-event simulator for distributed applications, plus tools that turn a run into data you can
navigate, replay, branch and compare. This document describes how the code is organised and why. The specification
(`CHRONOS System Specification v0.2.pdf`) is the contract; deviations from it are in `DEVIATIONS.md`, and every requirement is
mapped to code and tests in `TRACEABILITY.md`.

## The one idea

A node is a function of the inputs it receives. If the runtime controls *which* input comes next, *when*, and what the network does to
it, then a run is a function of (code, configuration, seed, fault plan). CHRONOS therefore:

1. runs applications inside a world it fully controls (single thread per world, virtual time, one decision source for every choice),
2. records every effect as an immutable event with causal parents (the log is the source of truth),
3. derives everything else from the log: node states, in-flight messages, the causal graph, indexes, reports,
4. re-executes the same engine under recorded decisions to verify, and under changed decisions to branch.

## Modules and the direction of dependency

```
chronos-model      events, ids, clocks, canonical JSON, digests, fault/intervention/image types, LogReader, EventSink
chronos-sdk        the Node API (what an application writes against): Node, NodeContext, NodeState, Scenario, Invariant
chronos-store      segmented log, checkpoints, catalogue, persisted indexes, asynchronous writer          (model)
chronos-runtime    engine, agenda, fault injector, recorder, record-mode decision source, checkpoint capture/restore   (sdk, model)
chronos-demo       ping-pong, causal demo, RE, lost update, blocked commit, control, gossip, bad citizen   (sdk, model)
chronos-analysis   causal graph, replay, branching, time travel, comparator, counterfactuals, search, node-local replay   (runtime, store, sdk, model)
chronos-cli        command line, HTML report, live viewer, the flagship demonstration                    (all above)
chronos-bench      JMH and whole-system benchmarks, chaos soak, build-time rules                          (all)
```

`BuildRulesTest` (in `chronos-bench`) fails the build if a module depends on one it may not, if banned APIs (wall-clock, unseeded
randomness, raw threads, sockets, `identityHashCode`, ...) appear in model, sdk, runtime or demo, or if any runtime library other than
Jackson is used.

## The step cycle (the heart of `Engine`)

```
boot:   record every NODE_STARTED first, then the effects of the start handlers; check invariants
step:   take the item with the smallest key (due time, target node index, kind rank, identity)
        FAULT 0 < CLIENT_INPUT 1 < DELIVERY 2 < TIMER 3
        if its node is paused, hold it; else advance virtual time and
          DELIVERY      partition/crash decide a drop at delivery time, else run the node's onMessage
          TIMER         record OMISSION edges from faults that silenced the node while it waited, run onTimer
          CLIENT_INPUT  run onClientInput
          FAULT         inject or clear a fault from the plan (or an intervention)
        run the handler through the NodeContext (sends, timers, random draws, notes are recorded as events)
        digest the node's state after the step; update the last-writer map
        check every invariant through a recording accessor (WITNESS edges to the last writers it read)
        commit the whole step as one atomic group to the sinks (store writer, graph, replay verifier ...)
        capture a checkpoint every K steps
```

Everything non-deterministic goes through one seam, `DecisionSource`: message latency, node random draws and probabilistic fault
hits. *Record* mode computes them from the seed by identity-keyed hashing (the answer for message `p#17` never depends on how many
messages came before it). *Replay* mode reads them from the log. *Branch* mode reads the parent's log up to the branch point and then
computes. The `Pinned` policy wraps record mode (DEV-35). One engine serves record, replay and branch, so replay correctness is a
property of one code path (D-19).

## Events, identity and causality

* **Identity.** A node event is `node#n` (n counts that node's events). An event on `env` is keyed by what it concerns:
  `fault:<rule>#n`, `drop:<messageId>`, `dup:<messageId>`, `iv:<interventionId>`, `inv:<name>#n` (D-24). The env counter feeds clocks
  only, never identity, so adding an env event in a branch does not renumber the others (law M13).
* **Clocks.** Lamport value and a vector clock with N+1 slots (one per node, `env` last) on every event, stamped by merging the stamps
  of all causal parents, whatever the edge kind (law M3).
* **Edges.** `PROGRAM_ORDER`, `STEP_EFFECT`, `MESSAGE`, `TIMER`, `FAULT`, `WITNESS`, `OMISSION`. Certain edges explain; `OMISSION` is
  the potential edge from a fault to a timer that fired after a silence the fault caused (D-23). The content digest of an event excludes
  program-order parent ids and includes its origin (DEV-02, D-25).
* **The graph** (`CausalGraph`) is built on dense arrays; causal past and future are walks, `happensBefore` is a vector comparison,
  and a self-check proves the two agree (law M4).

## Storage

```
chronos-data/
  catalog.json                        timeline tree: parent, branch point, status, scenario
  timelines/T0/header.json            scenario, parameters, seed, nodes, fault plan, interventions, format version, code fingerprint
                events/000000.log     one JSON event per line; each step group closed by a commit line (count, last seq, SHA-256)
                       (or .log.gz when recorded with --compress)
                checkpoints/0000000100.ckpt   whole-world images at a configurable interval and at every branch point
                index/main.idx        derived indexes (node lanes, kind lanes, time samples); rebuilt from the log if missing or suspect
                summary.json          final status, counts, end-state digest, first violation, fault coverage, log hash
  timelines/T0.1/                     a branch: only the events after its branch point; header names parent and branch position
```

Rules: the log is append-only; lines after the last commit line are an unfinished step and are discarded on load (NFR-11); a completed
timeline's files never change (NFR-04, law M11); the indexes are derived (reading never rewrites them). The writer is one asynchronous
thread with a bounded queue (D-21).

## Analysis built on the log

| Component | What it does |
|---|---|
| `ReplayEngine` | rebuilds the world from scratch or from a checkpoint and re-executes under recorded decisions, comparing every regenerated event (`VerifyingSink`); reports the first mismatch (R1 to R4) |
| `TimeTravelSession` | a cursor over the log: goto, step, back, find, state, in-flight messages, timers, clocks, why, effects, watch, diff, knowledge; reads only the log and its indexes |
| `TimelineManager` | branches: restore the nearest checkpoint, prefix-replay to the branch point, apply interventions, run on; ids, tree |
| `CounterfactualEngine` | five verdicts, parallel branches on a fixed pool, minimal critical fault sets |
| `InterventionSearch` | which single change in the causal past of a violation makes it disappear (FR-38) |
| `TimelineComparator` | aligns two timelines by id, per-timeline taint, causal roots, the first causal divergence, the explanation path (D-16) |
| `NodeLocalReplay` | re-runs one node alone from its recorded inputs (R5) |
| `SystematicExplorer`, `Counterexample` | bounded, exhaustive enumeration of fault schedules (every subset of at most K candidates, fixed order, trace-equivalence and superset reductions, explicit coverage statement) with reproducible, checksummed counterexamples (DEV-44) |
| `IncidentBundle` | one checksummed ZIP carrying a timeline, its ancestors, its evidence and reproduction steps; validated in a staging store before anything is imported (DEV-45) |
| `Campaign`, `ChaosPlanGenerator` | seed campaigns and random fault plans |

## Threads

Engine: one thread per world. Store: one writer thread per recording. Counterfactuals and campaigns: a fixed worker pool, each branch
in its own isolated world; reports are collected in query order, so they do not depend on the number of threads (law M12). The viewer:
the JDK HTTP server's single dispatch thread. Nothing else.

## Where to start reading

`Engine.step` and `Engine.send` (runtime), `Event` and `ClockService` (model), `TimelineStore.readOwn` (store),
`ReplayEngine` and `TimelineComparator.compare` (analysis), `Cli.execute` (command line).
