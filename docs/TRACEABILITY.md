# Requirements traceability

Maps `CHRONOS System Specification v0.2.pdf` to code and tests. Status values:

* **COMPLETE** — implemented and covered by an automated test that was actually run (results in `IMPLEMENTATION_STATUS.md`).
* **SIMPLIFIED** — implemented in a bounded form; the difference is in `docs/DEVIATIONS.md`.
* **NOT IMPLEMENTED** — not built.
* **NOT VERIFIED** — built but not demonstrated in this environment.

Test classes: `EngineTest`, `ClockServiceTest`, `CanonTest` (model/runtime unit); `TimelineStoreTest` (store); `CausalGraphTest`,
`ComparatorTest` (analysis); `AcceptanceTest`, `LawsTest`, `KnownAnswerTest`, `PersistenceTest`, `SoakTest`, `GoldenFileTest`, `FaultCatalogueTest`, `CliTest`
(chronos-cli, integration with the demo applications); `BuildRulesTest` (chronos-bench, build-time rules).
Paths are relative to the module's `src/main/java/chronos/<package>/`.

## Decisions D-01 to D-26

| D | Decision | Where | Tests | Status |
|---|---|---|---|---|
| D-01 | Controlled runtime + Node API | sdk `Node`, `NodeContext`; runtime `Engine` | all | COMPLETE |
| D-02 | Single-threaded engine per world | runtime `Engine` | `BuildRulesTest` (no raw threads in runtime) | COMPLETE |
| D-03 | Simulation only | whole design | `BuildRulesTest` (no sockets) | COMPLETE |
| D-04 | Volatile/durable zones, state codec, step-end commit, crash wipes volatile, restart | sdk `NodeState`, `StateCodec`; runtime `Engine` (crash/restart) | `EngineTest.crashWipesVolatile...`, `KnownAnswerTest` (KA-0 control uses the durable zone), `SoakTest` | COMPLETE |
| D-05 | Handler-level events | runtime `Engine` | `EngineTest` | COMPLETE |
| D-06 | Identity-keyed randomness, per-node streams, own generator, stable tie-break | model `SeededRandom`, `IdentityHash`; runtime `AgendaItem`, `RecordDecisionSource` | `CanonTest`, `EngineTest.tieBreaking...`, `LawsTest.m8` | COMPLETE |
| D-07 | Lamport + vector, N+1 slots, shown everywhere | model `ClockService`, `Event`; analysis `Exports` | `ClockServiceTest`, `EngineTest.pingPong...`, `CausalGraphTest.exports...` | COMPLETE |
| D-08 | Event id = node + counter; message id = send id | runtime `Engine` | `EngineTest` | COMPLETE |
| D-09 | Canonical JSON via Jackson, own digest rules | model `Canon` | `CanonTest` | COMPLETE |
| D-10 | Digest every step, full state on change | runtime `Engine.finishTrigger` | `LawsTest.m2` | COMPLETE |
| D-11 | Segmented JSON-lines log, commit lines, derived indexes, checkpoints, no DB | store `TimelineStore`, `TimelineWriter`, `TimelineIndex` | `TimelineStoreTest`, `PersistenceTest` | COMPLETE (persisted, validated, rebuildable: DEV-16) |
| D-12 | Immutable timelines, branch references prefix | store `TimelineStore.events/segments`; analysis `TimelineManager` | `LawsTest.m6, m11`, `TimelineStoreTest` | COMPLETE |
| D-13 | Fault rules keyed by time/identity; triggers at time, after a named event, on message id, nth message, probabilistic; `nonLocal` wildcard | model `FaultRule`, `FaultPlan`; runtime `FaultInjector` | `CanonTest.faultRuleValidationFollowsD13`, `LawsTest.m8`, `FaultCatalogueTest` | COMPLETE (`maxFirings` validated only: DEV-19; the Stretch trigger "on node state" is COMPLETE: `FaultRule.onState`, first time a watched field equals a value, `FaultCatalogueTest.aRuleCanFireTheFirstTimeANodesStateSatisfiesAPredicate`) |
| D-14 | Own causal graph on dense arrays | analysis `CausalGraph` | `CausalGraphTest`, `LawsTest.m3_m4` | COMPLETE |
| D-15 | Continue policy | analysis `BranchDecisionSource`, `TimelineManager` | `LawsTest.m6, m7` | COMPLETE (Continue is the default; the Stretch Pinned policy is opt-in, `--pinned`: DEV-35, `PinnedPolicyTest`) |
| D-16 | First causal divergence, per-timeline taint, anchor rule | analysis `TimelineComparator` | `ComparatorTest`, `AcceptanceTest.ad5`, `LawsTest.m10` | COMPLETE (fallback when no anchor: DEV-10) |
| D-17 | Global invariants, last writers, recording accessor, WITNESS edges | runtime `Engine.checkInvariants`, `RecordingView` | `EngineTest.invariantViolation...`, `LawsTest.m14` | COMPLETE |
| D-18 | Static self-contained HTML report; live viewer is Stretch | cli `HtmlReport` | `CliTest`, `AcceptanceTest` (demo AD-6) | COMPLETE (the Stretch live viewer is FR-50 below) |
| D-19 | One decision source, one engine for record/replay/branch | runtime `DecisionSource`; analysis `ReplayDecisionSource`, `BranchDecisionSource` | `LawsTest.m1, m5, m6` | COMPLETE |
| D-20 | Java 21, Maven, JUnit 5, JMH, hand-written fakes, own property generator | all poms; `EngineTest` `Fakes`; `SeededRandom` | `BuildRulesTest` | COMPLETE |
| D-21 | Threads: engine single, one async writer, fixed worker pool | store `TimelineWriter`; analysis `CounterfactualEngine`, `Campaign` | `LawsTest.m12`, `TimelineStoreTest.theBoundedQueue...` | COMPLETE |
| D-22 | Scenarios are Java classes registered by name | sdk `Scenario`, `ScenarioRegistry`; demo `DemoRegistry` | `CliTest`, `LawsTest.m1` | COMPLETE |
| D-23 | OMISSION edge | runtime `FaultInjector.omissionSources`, `Engine.doTimer` | `AcceptanceTest.ad1`, `KnownAnswerTest` (KA-1, KA-3), `FaultCatalogueTest` (pause scope) | COMPLETE (partition scope refined, literal scope available and compared: DEV-03, `OmissionScopeStudyTest`) |
| D-24 | Env events keyed by what they concern | runtime `Engine` (`drop:`, `fault:`, `iv:`, `inv:`, `dup:`) | `LawsTest.m13`, `EngineTest.everyCopy...` | COMPLETE (+ copy suffixes: DEV-12) |
| D-25 | INTERVENTION_APPLIED on env; origin in digest | runtime `Engine.applyInterventions`; model `Event.contentDigest` | `AcceptanceTest.ad4`, `LawsTest.m7, m10`, `CanonTest.eventLineRoundTrip...` | COMPLETE |
| D-26 | Node API in its own module | `chronos-sdk` | `BuildRulesTest` | COMPLETE |

## Functional requirements FR-01 to FR-52

| FR | Requirement (short) | Where / tests | Status |
|---|---|---|---|
| 01 | Node API handlers; effects through context | sdk `Node`, `NodeContext`; `EngineTest` | COMPLETE (+`onClientInput`, `onRestart`: DEV-06) |
| 02 | Deterministic single-threaded scheduler, stable tie-break | runtime `Agenda`, `AgendaItem`; `EngineTest` | COMPLETE |
| 03 | Simulated network, per-link latency, no sockets | `Scenario.baseLatency/latencyJitter`; `RecordDecisionSource` | COMPLETE |
| 04 | Timers set/cancel in virtual time | `Engine.Ctx`; `EngineTest` | COMPLETE |
| 05 | Seeded per-node and per-purpose streams, own generator | `SeededRandom`, `IdentityHash`; `CanonTest` | COMPLETE |
| 06 | Scenario declares nodes, config, workload, fault plan, invariants; runs by name with seed and horizon | `Scenario`, `DemoRegistry`, CLI `run`; `CliTest` | COMPLETE |
| 07 | Deterministic event identity | `Engine`; `LawsTest.m13` | COMPLETE |
| 08 | Virtual time, Lamport, vector, parents with kinds | `Event`, `ClockService`; `ClockServiceTest`, `EngineTest` | COMPLETE |
| 09 | Immutable events, append-only log | `Event` (record), store; `LawsTest.m11` | COMPLETE |
| 10 | One atomic group per step via a pluggable sink | `Recorder`, `EventSink`; `EngineTest.stepGroups...` | COMPLETE |
| 11, 12 | Lamport rules; vector compare before/after/equal/concurrent | `ClockService`, `VectorClock`; `ClockServiceTest` | COMPLETE |
| 13 | Graph from a live stream or a stored log | `GraphSink`, `CausalGraph.build`; `CausalGraphTest` | COMPLETE (live sink not separately tested) |
| 14 | Causal past/future, happens-before, concurrent, path | `CausalGraph`; `CausalGraphTest` | COMPLETE |
| 15 | Vector vs reachability self-check | `CausalGraph.selfCheck`; `LawsTest.m3_m4`, `SoakTest` | COMPLETE |
| 16 | Segmented log, format version, torn final record | store; `TimelineStoreTest`, `PersistenceTest` (kills a real process) | COMPLETE |
| 17 | Catalogue: metadata, parent, branch point | `TimelineStore`; `TimelineStoreTest` | COMPLETE |
| 18 | Indexes by position, node, kind, virtual time | `TimelineIndex`, `TimelineStore.index/rebuildIndex`, CLI `reindex`, bench B6b | COMPLETE (DEV-16): persisted in `index/main.idx`, validated on load, rebuilt from the log if missing or suspect; `TimelineStoreTest` (round trip incl. branches and 2,100 events, damage cases, NFR-04), `CliTest.reindex...`, `LawsTest.m11`; no byte-offset table, and loading them is not faster than rebuilding (measured) |
| 18b | Log compression (cut line 6) | `TimelineStore.compressNewTimelines`, `TimelineWriter` (gzip), CLI `--compress`, B12/B6b | COMPLETE as opt-in segment compression (DEV-34); `TimelineStoreTest.compressed...`, `CliTest.compressedRecordings...`; per-vector delta compression NOT DONE (its spec trigger did not occur) |
| 19 | Canonical state, digest every step | `Engine.finishTrigger`; `LawsTest.m2` | COMPLETE |
| 20 | Reconstruct state, in-flight, timers from the log only | `TimeTravelSession`; `LawsTest.m2`, `AcceptanceTest.ad3` | COMPLETE |
| 21 | Checkpoints at interval and branch points; restorable | `Engine.captureImage/restore`; `LawsTest.m5` | COMPLETE |
| 22, 23 | Replay from start or checkpoint; verify every event; report first mismatch | `ReplayEngine`, `VerifyingSink`; `LawsTest.m1`, `AcceptanceTest.ad2` | COMPLETE |
| 24 | Stepped replay with breakpoints | `ReplayEngine.runUntil`, session `run-to`; `SoakTest.steppedReplay...`, `CliTest.session...` | COMPLETE (DEV-17) |
| 24b | R5 Node-local replay (Stretch) | `NodeLocalReplay` (analysis), CLI `local-replay` | COMPLETE for nodes with a real input sequence (DEV-32); `NodeLocalReplayTest` (every node of 10 scenarios x default/chaos x 25 seeds, tampered output, tampered state, bad citizen, refusal for PATCH_STATE), `CliTest.localReplay...` |
| 25, 26, 27 | Time-travel session: goto, step, back, next/prev, find; inspect; causal queries | `TimeTravelSession`, CLI `inspect`, `session`; `AcceptanceTest.ad3`, `CliTest.session...` | COMPLETE |
| 28 | State watch | `TimeTravelSession.watch`; `LawsTest.m14`, `CliTest.session...` | COMPLETE |
| 29 | Branch at any step boundary sharing the prefix by reference | `TimelineManager`; `LawsTest.m6` | COMPLETE |
| 30 | Timeline tree; inspect; delete leaves | `TimelineManager.tree`, `TimelineStore.delete`; `CliTest.deleteOnlyLeaf...` | COMPLETE |
| 31 | Fault plans with triggers and actions | `FaultRule`, `FaultInjector`, `Engine`; `FaultCatalogueTest`, `EngineTest`, CLI `--fault` | COMPLETE: partition + heal, drop, delay, crash (Must); restart, duplicate, reorder, pause, clock skew (Should); slow link (Could); triggers at time, after event, on message id, nth message, probabilistic and the Stretch trigger "on node state" |
| 32 | Faults recorded with causal edges incl. OMISSION | `Engine`; `EngineTest`, `AcceptanceTest.ad1` | COMPLETE |
| 33 | Probabilistic faults by identity hashing | `RecordDecisionSource`, `ReplayDecisionSource`; `EngineTest.probabilistic...` | COMPLETE |
| 34 | Interventions at a branch point, run to horizon or violation | `Engine.applyInterventions`, `TimelineManager`; `AcceptanceTest.ad4`, `FaultCatalogueTest`, `CliTest` | COMPLETE: the whole catalogue of Section 19 (drop, delay, swap/reorder, duplicate, crash/restart/pause, create/move/remove a partition, clock skew, add/remove input, config patch, reseed, patch state); DEV-27 to DEV-29 |
| 35 | Verdict, new timeline, divergence summary | `CounterfactualEngine`; `KnownAnswerTest` | COMPLETE (all five verdicts tested) |
| 36 | Parallel isolated counterfactuals | `CounterfactualEngine.runAll`; `LawsTest.m12` | COMPLETE |
| 37 | Seed campaign | `Campaign`, CLI `campaign`; `CliTest.campaign...` | COMPLETE |
| 38 | Intervention search over the causal past | `InterventionSearch` (analysis), CLI `search`, bench B11; `findCriticalFaultSets` for rule combinations (DEV-14) | SIMPLIFIED (Stretch): single-change but-for search over message drops, scripted-input removals and fault-rule removals in the causal past of the first violation (DEV-30); `KnownAnswerTest.interventionSearch_*`, `CliTest.searchRanks...` |
| 38b | Consistent cuts (Stretch) | `CausalGraph.minimalCut/isConsistentCut/inconsistencies/cutFromFrontier/frontier`, CLI `inspect --cut`, session `cut` | COMPLETE (DEV-31); `CausalGraphTest.consistentCutsAreClosedUnderCausation`, `CliTest.knowledgeView...` |
| 38c | Knowledge view (Could) | `TimeTravelSession.knowledge/knowledgeText`, CLI `inspect --knows`, session `knows` | SIMPLIFIED (DEV-31: knowledge = causal past; states are log reconstructions); `CliTest.knowledgeViewShowsWhatTheBackupHadNotHeard...` |
| 39, 40 | Align by identity; classify; first local divergence | `TimelineComparator`; `ComparatorTest` | COMPLETE |
| 41, 42 | Causal roots; first causal divergence; explanation path | `TimelineComparator`; `AcceptanceTest.ad5`, `ComparatorTest` | COMPLETE |
| 43 | Flag suspected application nondeterminism | `TimelineComparator.classifyLocal`; `ComparatorTest.anOutputDifference...` | COMPLETE |
| 44 | Invariants after every step; violation recorded; WITNESS edges | `Engine`; `EngineTest`, `LawsTest.m14` | COMPLETE |
| 45 | Text space-time diagram; DOT and JSON | `Exports`; `CausalGraphTest`, `CliTest` | COMPLETE |
| 46 | Static self-contained HTML report | `HtmlReport`; `CliTest`, demo AD-6 (rendered in a browser during development) | COMPLETE |
| 47 | CLI: run, replay, inspect, branch, inject, compare | `Main`, `Cli`; `CliTest` | COMPLETE |
| 48 | One command runs a scenario twice and compares hashes | CLI `determinism` (+ `--jvm N`); `CliTest.determinismAcrossFreshJvmLaunches` | COMPLETE |
| 49 | Demos: ping-pong, causal-ordering, RE, two more known-answer | demo module | COMPLETE (KA-2, KA-3, KA-4 + control + bad citizen) |
| 50 | Live local web viewer (Stretch) | `Viewer` + `viewer.html` (cli), `chronos serve` | COMPLETE as a read-only viewer (DEV-33); `ViewerTest` (endpoints, bad requests, 405 on writes, store unchanged); the page was opened and driven in a browser |
| 51 | INTERVENTION_APPLIED on env, id `iv:<id>`, origin in digest | `Engine.applyInterventions` | COMPLETE |
| 52 | OMISSION edges: partition scope (12a), crash and pause scopes (12b) | `FaultInjector`; `FaultCatalogueTest`, `KnownAnswerTest` | COMPLETE (partition scope refined, literal scope available and compared: DEV-03, `OmissionScopeStudyTest`) |

## Non-functional requirements NFR-01 to NFR-20

| NFR | Target | Evidence | Status |
|---|---|---|---|
| 01 | Byte-identical logs, 100% over 1,000 seeded runs of each demo | harness `nfr01`: 8 demo scenarios × {default, chaos} × 1,000 seeds, each run twice on different thread counts = 16,000 pairs, all identical (`docs/BENCHMARKS.md`); `SoakTest.nfr01...` repeats it with 200 seeds in the suite; fresh-JVM determinism in `CliTest`; the Linux build reproduces the macOS golden logs | COMPLETE |
| 02 | Clock rules on every event | `LawsTest.m3_m4`, `SoakTest` self-check on 1,350 chaos runs | COMPLETE |
| 03 | Reconstructed state = live state | `LawsTest.m2` | COMPLETE |
| 04 | Recorded timelines never modified | `LawsTest.m11`, `TimelineStoreTest.readingNeverModifiesAnyFile` | COMPLETE |
| 05 | >= 50,000 events/s in memory, >= 20,000 persisted | `docs/BENCHMARKS.md` (B2) | see BENCHMARKS.md |
| 06 | Seek < 1 s at 100k, < 5 s at 1M | `docs/BENCHMARKS.md` (B6, XL) | see BENCHMARKS.md |
| 07 | Replay within 2x of the original | `docs/BENCHMARKS.md` (B7, XL) | see BENCHMARKS.md |
| 08 | Branch cost independent of prefix length | `docs/BENCHMARKS.md` (B8) | see BENCHMARKS.md |
| 09 | 3 to 20 nodes, up to ~1,000,000 events | `docs/BENCHMARKS.md` (XL: 1,018,235 events, 10 nodes) | see BENCHMARKS.md |
| 10 | Measure and report bytes per event | `docs/BENCHMARKS.md` (B4, B12) | COMPLETE |
| 11 | Torn last record detected and discarded | `TimelineStoreTest`, `PersistenceTest` | COMPLETE |
| 12 | Components testable in isolation | hand-written fakes in `EngineTest`, `TraceBuilder`, `MemoryTimelines` | COMPLETE |
| 13 | Single direction of dependency, enforced by the build | `BuildRulesTest` + Maven | COMPLETE |
| 14 | Linux, Windows, macOS with Java 21 | macOS (Apple Silicon, JDK 21, Maven 3.10.0): full build and tests; **Linux** (Ubuntu 24.04, aarch64, Temurin 21, Maven 3.9.16, in a container on a copy of the source): `mvn package` BUILD SUCCESS, all tests incl. the golden files generated on macOS and the process-kill test | COMPLETE on macOS and Linux; **NOT VERIFIED on Windows** |
| 15 | New member runs the demo from the README in < 10 minutes, three commands | README: set JAVA_HOME, `mvn -ntp package`, `java -jar ... demo`; the demo itself takes seconds | NOT VERIFIED with a new person |
| 16 | Output speaks CHRONOS vocabulary and cites event ids | all reports | COMPLETE |
| 17 | Every component has a teaching note and a runnable demo | Javadoc teaching notes on every public type; `docs/VIVA_GUIDE.md` (concept to code to command) | SIMPLIFIED (notes are in Javadoc and the guide, not one per component) |
| 18 | Format version on stored files; old logs refused clearly | `TimelineStoreTest.aLogWithAnUnknownFormatVersionIsRefused` | COMPLETE |
| 18b | Log compression (cut line 6) | `TimelineStore.compressNewTimelines`, `TimelineWriter` (gzip), CLI `--compress`, B12/B6b | COMPLETE as opt-in segment compression (DEV-34); `TimelineStoreTest.compressed...`, `CliTest.compressedRecordings...`; per-vector delta compression NOT DONE (its spec trigger did not occur) |
| 19 | < 5 runtime libraries, each justified | `BuildRulesTest` (Jackson is the only runtime library; JMH is build-time in the bench module) | COMPLETE |
| 20 | Build-time scan for wall-clock, unseeded randomness, raw threads | `BuildRulesTest` (with a negative control that detects the bad citizen) | COMPLETE |

## Laws M1 to M14 (Section 25)

| Law | Test |
|---|---|
| M1 Replay | `LawsTest.m1_replayingAny...`, `m1_replayFromEveryCheckpoint...`, `m1_aMutatedRecording...`; `SoakTest` (1,350 chaos runs replay-verified) |
| M2 Reconstruction | `LawsTest.m2_stateRebuiltFromTheLog...` |
| M3 Clocks (incl. FAULT/WITNESS/OMISSION edges) | `LawsTest.m3_m4...`, `m3_faultWitnessAndOmission...`, `ClockServiceTest` |
| M4 Graph | `LawsTest.m3_m4...`, `CausalGraphTest` |
| M5 Snapshot | `EngineTest.checkpointRestoreThenRunEquals...`, `LawsTest.m5_everyStoredCheckpoint...` |
| M6 Prefix | `LawsTest.m6_aBranchPrefix...` (incl. branch of branch), `AcceptanceTest.ad4` |
| M7 No-op | `LawsTest.m7_aBranchWithNoInterventions...` |
| M8 Locality | `LawsTest.m8_nodesWithNoCausalConnection...` (drop, delay, duplicate, crash interventions) |
| M9 Identity | `LawsTest.m9...` |
| M10 Root | `LawsTest.m10...`, `M10CatalogueTest` (every intervention type; removal of every rule of 60 random plans; all branches replay), `AcceptanceTest.ad5`. Clause 1 holds in every test; clause 2 holds except the measured renumbering exception, lengthened partitions and config/reseed: DEV-28, DEV-29, DEV-36 |
| M11 Immutability | `LawsTest.m11...` |
| M12 Thread independence | `LawsTest.m12...` |
| M13 Environment identity | `LawsTest.m13...`, `EngineTest.everyCopy...` |
| M14 Witnesses | `LawsTest.m14...` |

## Known-answer scenarios and acceptance demonstrations

| ID | What must be reported | Test and command | Status |
|---|---|---|---|
| KA-0 | No violation, ever, under thousands of random fault plans | `KnownAnswerTest.ka0...` (150 seeds, graph + replay checked), `SoakTest`, harness soak (10,000 seeds), demo | COMPLETE |
| KA-1 | RE split brain: root is the partition; chain through the promotion; healing early makes the failure disappear | `KnownAnswerTest.ka1...`, `AcceptanceTest` | COMPLETE |
| KA-2 | Lost update: root is the delay; but-for test confirms | `KnownAnswerTest.ka2...` | COMPLETE |
| KA-3 | Blocked commit: root is the crash; without it no participant is undecided | `KnownAnswerTest.ka3...` | COMPLETE |
| KA-4 | Overdetermined: removing either partition alone reports FAILURE_PERSISTS | `KnownAnswerTest.ka4...`, CLI `causes` | COMPLETE |
| AD-1 | Failing RE run, invariant violated, chain from partition to violation | `AcceptanceTest.ad1`, demo | COMPLETE |
| AD-2 | Identical log hashes on replay; bad citizen flagged at the first mismatch | `AcceptanceTest.ad2`, `CliTest.verifyFlagsTheBadCitizen...`, demo | COMPLETE |
| AD-3 | Jump to the violation, show states and in-flight messages, step back three times | `AcceptanceTest.ad3`, demo | COMPLETE |
| AD-4 | Branch at the partition, heal 200 ms earlier, the violation disappears | `AcceptanceTest.ad4`, demo | COMPLETE (branch point: DEV-10) |
| AD-5 | Compare; the partition event as the anchor of the first causal divergence | `AcceptanceTest.ad5`, demo | COMPLETE (the *first causal divergence* is `iv:heal-early`, the path starts at the partition event; see DEV-10) |
| AD-6 | Open the generated static HTML report | `CliTest`, demo; viewed in a browser | COMPLETE |

Test blocklist for the other verdicts: `NO_EFFECT`, `INCONCLUSIVE`, `DIFFERENT_FAILURE`, `FAILURE_DISAPPEARS`, `FAILURE_PERSISTS`
are each exercised in `KnownAnswerTest`.

## Phases (execution order of Section 24)

| Step / phase | Status |
|---|---|
| 1, 2 (understand, define the model) | COMPLETE (the specification review and `docs/DEVIATIONS.md`) |
| 3 skeleton, 4 recorder, 5 clocks, 6 graph — Milestone A | COMPLETE |
| 7 persistence, 8 state/snapshots — Milestone B | COMPLETE |
| 9 replay — Milestone C | COMPLETE |
| 10a, 11, 12a, 13a, 14a — Milestone S | COMPLETE |
| 10b deeper time travel | COMPLETE (`run-to` breakpoints, `find`, `watch`, `diff`, `effects`, `concurrent-with`, `next-on`, `prev-on`; world reconstruction from the nearest checkpoint) |
| 12b fault catalogue | COMPLETE (durable zone and restart, all faults, all triggers including "on node state", OMISSION scopes for crash and pause, sequential campaigns) |
| 13b full counterfactuals | COMPLETE (whole intervention catalogue, parallel pool, campaigns on the pool); `DIFFERENT_FAILURE` simplified (DEV-08) |
| 14b comparator hardening | COMPLETE (concurrent roots, absorbed divergence, suspected nondeterminism, KA-4) |
| 15a static HTML report | COMPLETE |
| 16 benchmarks | COMPLETE (`docs/BENCHMARKS.md`: B1-B14, a 1,018,235-event run with memory profile, a 10,000-seed chaos soak, NFR-01 at full size); B11 (intervention search) needs the harness re-run to be listed in BENCHMARKS.md |
| 17 documentation, demonstrations, evaluation | PARTIAL: README, status, traceability, deviations, benchmarks, viva guide, demo script, sample report, demo transcript and an evaluation on 800 unplanned chaos runs (`docs/EVALUATION.md`) are written; the **blind debugging protocol** (needs a second person) is NOT DONE |
