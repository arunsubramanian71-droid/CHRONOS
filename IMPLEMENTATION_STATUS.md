# CHRONOS: implementation status

**Last verified:** 2026-10-20, macOS (Apple Silicon, Apple M2 Max), JDK 21.0.12.1 (Homebrew OpenJDK), Maven 3.10.0.
**Authoritative source:** `CHRONOS System Specification v0.2.pdf` (unchanged; SHA-256 `792a4247…a53e`).
**Overall:** a working, tested, demonstrable implementation of the Milestone S core plus most of the depth items, the CLI, the static
HTML report, benchmarks and documentation. **Full v0.2 compliance is NOT claimed**; see "Not implemented" and `docs/DEVIATIONS.md`.

## Recovery checkpoint (updated at each milestone)

* Branch `main`, no remote. Everything below was run on the final code (JDK 21.0.12.1, Maven 3.10.0, Apple M2 Max).
* `mvn -ntp clean package`: BUILD SUCCESS, **233 tests, 0 failures** (macOS). The same source (git HEAD 8cc912d, exported with `git archive`) in a Linux container (Ubuntu, Temurin 21.0.12.1, aarch64): BUILD SUCCESS, **233 tests, 0 failures** (summed from the surefire reports); packaged demo 24 of 24, exit 0.
* Long randomized campaigns (exact counts): `InterventionFuzzTest` `CHRONOS_FUZZ_SEEDS=20000` (seeds 1..20000): passed (788 s, earlier code). **Fresh range** `CHRONOS_FUZZ_FROM=20001 CHRONOS_FUZZ_SEEDS=40000` (20,000 seeds; every branch and nested branch replay-verified, and `chronos check` over every store written): the first attempt at 20,001..26,000 **found DEV-52 at seed 20726**; after the fix 20,001..40,000 passed (1,065 s). `SoakTest` `CHRONOS_SOAK_SEEDS=6000` (6,000 chaos runs per demo scenario, graph self-check and replay each): passed (before DEV-52, which does not touch recording). `M10CatalogueTest` `CHRONOS_M10_SEEDS=4000`: **0 residual of 8,752 comparisons** on the final comparator; `=30000`: 0 of 66,427. The six seeds of the earlier report (281, 552, 1945, 2696, 2715, 3079) and six more are pinned in `M10CatalogueTest.m10HoldsForTheSeedsWhoseRaceWasDecidedTheOtherWay...` (DEV-47).
* `chronos-bench.jar all`: all NFR rows met (`docs/BENCHMARKS.md`, regenerated from `docs/benchmark-results.csv`); 10,000-seed KA-0 soak: 0 violations.
* No background process is running. Open items: see "Not implemented" and "Open specification questions" below; the next step, if work continues, is a Windows run (`docs/PORTABILITY.md`) and a real blind session (`docs/BLIND_PROTOCOL.md`), both of which need something this environment does not have.
* To resume: `git status && git log --oneline | head`, then `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -ntp clean package`.

## Verification results (every line was actually executed)

| What | Command | Result |
|---|---|---|
| Maven runs on JDK 21 | `mvn -version` after `JAVA_HOME=$(/usr/libexec/java_home -v 21)`; the build also has an enforcer rule `[21,22)` | `Java version: 21.0.12.1` |
| Compilation + all tests + packaging, from scratch (macOS) | `mvn -ntp clean package` | **BUILD SUCCESS**, ~14 s, 8 modules |
| Unit, integration and acceptance tests | same command | **233 tests, 0 failures, 0 errors, 0 skipped** (macOS) |
| **Linux** build and tests | `docker run maven:3.9-eclipse-temurin-21 ... mvn -ntp package` on a copy of the source (Ubuntu 24.04 aarch64, Temurin 21, Maven 3.9.16) | **BUILD SUCCESS, 233 tests, 0 failures** (re-run on the final source, git 8cc912d); the golden files generated on macOS pass on Linux (NFR-14, R16); the packaged jar's demo gives 24 of 24 checks on Linux too |
| Packaging | same command | `chronos-cli/target/chronos.jar` (2.6 MB), `chronos-bench/target/chronos-bench.jar` (5.6 MB), both self-contained |
| Flagship demonstration from the packaged jar | `java -jar chronos-cli/target/chronos.jar --data chronos-data demo` | **24 of 24 checks passed**, exit 0 (`docs/demo-output.txt`) |
| Cross-JVM determinism | `chronos determinism re --seed 42 --jvm 2` (in `CliTest`) | the same log hash in this JVM and in 2 fresh JVMs |
| NFR-01 at full size | `chronos-bench.jar nfr01 --seeds 1000` | 8 demos x {default, chaos} x 1,000 seeds = 16,000 pairs, **all identical** |
| Persistence and reload in a fresh context | `PersistenceTest`, `AcceptanceTest.ad2` | saved by one `TimelineStore`, loaded and replay-verified by another; a **real child process killed mid-write** leaves a readable log |
| Replay verification | `LawsTest.m1*`, `SoakTest`, `FaultCatalogueTest` | chaos runs on every demo, every checkpoint of every demo, branches through their lineage; a mutated recording is caught at exactly the mutated event |
| HTML report | `chronos report T0 --against T0.1`; `CliTest`; opened in a browser during development | generated, self-contained (no external reference, checked by the program), diagram/cone/path rendering inspected |
| Benchmarks, soak, slow disk | `chronos-bench.jar all` (JDK 21, re-run on the final code, now including B11), `... jmh` | `docs/BENCHMARKS.md`; **10,000-seed chaos soak: 0 violations**; NFR-05 to NFR-09 all met |
| Evaluation on unplanned failures | `chronos-bench.jar eval --seeds 400` | 800 chaos runs, 100 failures, root fault named in 94, but-for confirmed by counterfactual in 73 (`docs/EVALUATION.md`) |

### Test inventory (233)

| Module | Tests | Classes |
|---|---|---|
| chronos-model | 15 | `ClockServiceTest` 7, `CanonTest` 8 |
| chronos-store | 21 | `TimelineStoreTest` |
| chronos-runtime | 16 | `EngineTest` |
| chronos-analysis | 18 | `CausalGraphTest` 7, `ComparatorTest` 11 |
| chronos-cli | 155 | `AcceptanceTest` 5, `LawsTest` 20 (M1-M14), `KnownAnswerTest` 9, `PersistenceTest` 4, `CliTest` 20, `PinnedPolicyTest` 3, `AnalyserMutationTest` 3, `M10CatalogueTest` 5, `FailureMinimizerTest` 7, `StoreCheckerTest` 9, `HostileContentTest` 2, `RefusedRunCleanupTest` 4, `VerificationSummaryTest` 2, `BranchAtFinalEventTest` 2, `LawSweepTest` 3, `DemoScriptsTest` 1, `BlindKitTest` 2, `TraceabilityTest` 2, `InterventionFuzzTest` 1, `CorruptionFuzzTest` 3, `OmissionScopeStudyTest` 2, `NodeLocalReplayTest` 5, `ViewerTest` 3, `SystematicExplorerTest` 7, `IncidentBundleTest` 5, `ParserRobustnessTest` 2, `CliRobustnessTest` 1, `SoakTest` 4, `GoldenFileTest` 2, `FaultCatalogueTest` 16 |
| chronos-bench | 8 | `BuildRulesTest` (dependency direction, banned APIs, approved libraries, soak harness) |

## What is implemented (modules)

| Module | State |
|---|---|
| chronos-model | Events, ids, kinds, edge kinds, clocks, canonical encoding, digests, fault/intervention/image types, `LogReader`, `EventSink`, `DeterminismViolation`. |
| chronos-sdk | Node API, context, `NodeState` (volatile + durable zones), codec, scenario, invariant, world view, registry, code fingerprint. |
| chronos-store | Segmented JSONL log, commit lines with checksums, torn-tail recovery, checkpoints, catalogue, segment resolution, async writer (with a slow-disk benchmark hook), in-memory indexes. |
| chronos-runtime | Deterministic engine, agenda, fault injector (partition/heal, drop, delay, duplicate, reorder, crash, restart, pause, clock skew, slow link, probabilistic; triggers at time, after an event, on node state, on message id, nth message), invariants with witnesses, the whole intervention catalogue, checkpoint capture/restore. |
| chronos-demo | ping-pong, causal demo, RE (+ two-backup variant), lost update, blocked commit, control, gossip, bad citizen. |
| chronos-analysis | Causal graph, exports, replay (R1, R2, R3, R4), branching, time travel (world from the nearest checkpoint), comparator, counterfactual engine with 5 verdicts, chaos generator over the whole catalogue, campaigns with a worker pool. |
| chronos-cli | Hand-written CLI (`minimize`: delta-debugging a failing run's fault plan, DEV-48; run with `--fault`, ls, show, inspect, session with `run-to`, verify, determinism, branch/counterfactual with every intervention, compare, causes, search, campaign, report, delete, demo), HTML report, flagship demo. |
| chronos-bench | JMH benchmarks, whole-system harness (B1-B14, million-event run, soak, NFR-01, evaluation), build-rule checks, `tools/benchmarks_md.py` (generates `docs/BENCHMARKS.md`). |

Local git history on `main` (see `git log`); no remote.

## Acceptance demonstrations and known-answer scenarios

AD-1 to AD-6: **all verified** (tests and the live demo). KA-0 (control, 10,000-seed soak), KA-1, KA-2, KA-3, KA-4: **all verified**.
Details and the interpretation of AD-4/AD-5 (branch at the partition's injection boundary): `docs/TRACEABILITY.md`, `docs/DEVIATIONS.md` DEV-10.

## Not implemented / simplified (honest list)

Full matrix: `docs/TRACEABILITY.md`. The important ones:

* **NOT IMPLEMENTED:** per-vector delta compression (spec trigger did not occur), the **blind** debugging protocol
  (needs a second person; the kit `chronos blind` that supports it is built and tested, `docs/BLIND_PROTOCOL.md`), verification on Windows.
* **SIMPLIFIED:** `DIFFERENT_FAILURE` uses the invariant name and the set of fields read (DEV-08); world images are canonical JSON trees;
  `maxFirings` is validated only; the evaluation is not blind (`docs/EVALUATION.md`).
* **Targets:** all of NFR-01 and NFR-05 to NFR-09 were measured and met (`docs/BENCHMARKS.md`).

## Deviations from the specification

Interpretations, extensions, semantics for the later catalogue items and simplifications are recorded in `docs/DEVIATIONS.md`
(DEV-01 to DEV-52). The ones a reviewer should look at first: **DEV-03** (OMISSION scope for partitions also requires the node to have
heard from the other side), **DEV-02** (digest parent ids exclude PROGRAM_ORDER), **DEV-11** (heal-enabled FAULT edge), **DEV-10**
(AD-4/AD-5 branch point).

## Defects found by the tests and fixed (for the record)

1. **Path tie-break:** an explanation path could be a bare env PROGRAM_ORDER hop instead of the OMISSION chain (anchor-rule comparator test) -> DEV-09.
2. **`why` omitted faults that fire at send time** (KA-2: the delay never appeared) -> faults and interventions are always listed as sources.
3. **Duplicate env identities** when a message was duplicated more than once (10,000-seed chaos soak) -> per-copy identities (DEV-12).
4. **Verifier false mismatch** on trailing silent steps (chaos soak) -> replay runs to the same end (DEV-13).
5. **A checkpoint did not remember its run had stopped**, so a resumed replay ran past the recording (test of replay from every checkpoint of a stopped run) -> DEV-21.
6. **Bad citizen could coincidentally replay** (1-in-2,400 clock collision, seen once) -> more entropy (1e-6).
7. **Branch points inside the boot sequence** produced branches that did not continue their parent (`--remove-input` default; DEV-37).
8. **M10 failed for `REMOVE_FAULT`** (primary root was the removed fault; roots of the branch outside the intervention's future): ghost rules, lane marks, tie-break (DEV-36).
9. **Nested branches could not be replayed** when a checkpoint had been taken after a step that records nothing (found by the intervention fuzzer after 473 seeds): DEV-39.
10. **Grandchild branches at the parent's own branch point replayed with the parent's interventions** (fuzzer, seed 5219): DEV-41.
10b. **`SWAP_DELIVERIES` with the same message twice** produced duplicate ids (fuzzer, seed 654): DEV-40.
11. The unplanned-failure evaluation exposed that **`why` cannot name a fault whose silence no timer noticed** (tutorial case, 0/100): the labelled possible-silence section (DEV-38).
11b. **Readers of stored data raised `ClassCastException` on wrongly-typed fields** (parser-robustness fuzz): DEV-46.
11c. **`NullPointerException` on an unlisted file in an incident bundle** (bundle forgery test): fixed.
11b. **Branch summaries carried the hash of empty input**, **refused runs and branches left unfinished timelines behind**, **negative horizons were accepted** (found by `chronos check` and the tests written for it): DEV-49.
11c. **A branch at the last event of a finished timeline lost the run's silent tail** (fuzzer, fresh range, seed 20726): DEV-52.
12. Wrong test assumptions were corrected after checking the actual semantics, not by loosening assertions.

## Known limitations and risks

* Law M10 holds without exception in the default random sweep (120 interventions of every type), for every case of the catalogue, and in a
  30,000-seed run (66,427 comparisons: 0 residual). The comparator reports renumbered, absence-, silence- and timing-explained events as *displaced*,
  not as roots; the last three are labelled potential links (DEV-36, DEV-43, DEV-47), so a displaced event is a hypothesis the reader can check, not
  a proved cause. No sweep can prove there is no residual outside the seeds tried.
* Verified on macOS and Linux; Windows is not verified. The first full benchmark run accidentally used JDK 26 and was discarded and redone
  on JDK 21; anyone running the jars must set `JAVA_HOME` to JDK 21 for the build (the jars themselves run on any JDK >= 21).
* Replay-code-version checking is a declared version plus a best-effort class hash, with a warning on mismatch (Q7).
* Timings are from one machine (Apple M2 Max).

## Open specification questions

* **Q1** (deadline and weekly hours): OPEN. The 24-hour target is a conversation constraint, not an answer to Q1.
* **Q6** (institution's evaluation form): OPEN. Default (demonstration, report, viva) used.
* The specification marks D-23 to D-26 "New in v0.2" while its cover says all of D-01 to D-26 are approved; all four are implemented.

## Next highest-priority actions (if work continues)

1. Run the blind-debugging evaluation with a second person using `chronos blind` (`docs/BLIND_PROTOCOL.md`) and write the evaluation chapter (Phase 17).
2. Every Stretch and cut-line item named in the specification is now implemented (the Pinned policy as an opt-in, DEV-35); what remains is listed under "Not implemented" above.
3. Decide DEV-03 with the other author (keep the refined OMISSION scope or revert to the literal 12a rule).
4. Verify on Windows; then, after the project is judged complete, create the GitHub repository (deliberately **not** done: no remote exists and nothing was pushed).

## How to resume safely

```bash
cd /Users/arunhbk/Desktop/Chronos
git status && git log --oneline           # local repository, branch main, no remote
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -ntp clean package                    # must end in BUILD SUCCESS with 233 tests
java -jar chronos-cli/target/chronos.jar --data chronos-data demo     # 24 of 24 checks
```

## Git state

Local repository only (`main`); no remote was added and nothing was pushed. `chronos-data/` (generated timelines and reports) is git-ignored.

## Review patch (static review of an uploaded snapshot, 2026-10-20) — now verified

* The reviewer had no Maven; they changed `SystematicExplorer` so that `maxCases` bounds every subset processed (executed, duplicate, or pruned as a
  superset) instead of executions only, which stops a violating empty schedule from leaving an exponential enumeration unbounded
  (`SystematicExplorerTest.caseLimitAlsoBoundsEnumerationWhenSupersetPruningWouldSkipTheWholeSpace`; DEV-44). **Verified here:** compiled and passed in
  the full build (207 tests at that point). The same review added focused-diagnostics switches to `M10CatalogueTest`
  (`CHRONOS_M10_ONLY_SEEDS`, root details in the message), which exposed the residuals now fixed by DEV-47.
* The reviewer's six residual seeds (281, 552, 1945, 2696, 2715, 3079) were investigated from the event graphs: all six were races decided the other way
  (a crash-after trigger or a heartbeat that fired later, a watchdog that won against a delivery) and not a runtime defect; see DEV-47. Wider sweeps
  found six more of the same kind (4085, 8184, 9356, 10130, 22656, 23754), all fixed and pinned in `M10CatalogueTest.m10HoldsForTheSeeds...`.
* `CHRONOS_M10_SEEDS=30000` (66,427 comparisons, 13 intervention types): **0 residual** (692 s).
