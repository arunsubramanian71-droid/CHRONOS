# CHRONOS: implementation status

**Last verified:** 2026-10-20, macOS (Apple Silicon, Apple M2 Max), JDK 21.0.12.1 (Homebrew OpenJDK), Maven 3.10.0.
**Authoritative source:** `CHRONOS System Specification v0.2.pdf` (unchanged; SHA-256 `792a4247…a53e`).
**Overall:** a working, tested, demonstrable implementation of the Milestone S core plus most of the depth items, the CLI, the static
HTML report, benchmarks and documentation. **Full v0.2 compliance is NOT claimed**; see "Not implemented" and `docs/DEVIATIONS.md`.

## Verification results (every line was actually executed)

| What | Command | Result |
|---|---|---|
| Maven runs on JDK 21 | `mvn -version` after `JAVA_HOME=$(/usr/libexec/java_home -v 21)`; the build also has an enforcer rule `[21,22)` | `Java version: 21.0.12.1` |
| Compilation + all tests + packaging, from scratch | `mvn -ntp clean package` | **BUILD SUCCESS**, 14 s, 8 modules |
| Unit and integration tests | same command | **119 tests, 0 failures, 0 errors, 0 skipped** |
| Packaging | same command | `chronos-cli/target/chronos.jar` (2.6 MB), `chronos-bench/target/chronos-bench.jar` (5.6 MB), both self-contained |
| Flagship demonstration from the packaged jar | `java -jar chronos-cli/target/chronos.jar --data chronos-data demo` | **24 of 24 checks passed**, exit 0 (transcript: `docs/demo-output.txt`) |
| Cross-JVM determinism | `chronos determinism re --seed 42 --jvm 2` (in `CliTest`) | the same log hash in this JVM and in 2 fresh JVMs |
| Persistence and reload in a fresh context | `PersistenceTest`, `AcceptanceTest.ad2` | saved by one `TimelineStore`, loaded and replay-verified by another; a **real child process killed mid-write** leaves a readable log |
| Replay verification | `LawsTest.m1*`, `SoakTest` | 1,350 chaos runs plus every demo replayed and verified event by event; a mutated recording is caught at exactly the mutated event |
| HTML report | `chronos report T0 --against T0.1`; `CliTest`; opened in a browser during development | generated, self-contained (no external reference, checked by the program), diagram/cone/path rendering inspected |
| Benchmarks and soak | `chronos-bench.jar all` (JDK 21), `... jmh` | results in `docs/BENCHMARKS.md`; **10,000-seed chaos soak: 0 violations** |

### Test inventory (119)

| Module | Tests | Classes |
|---|---|---|
| chronos-model | 15 | `ClockServiceTest` 7, `CanonTest` 8 |
| chronos-store | 15 | `TimelineStoreTest` |
| chronos-runtime | 16 | `EngineTest` |
| chronos-analysis | 16 | `CausalGraphTest` 6, `ComparatorTest` 10 |
| chronos-cli | 49 | `AcceptanceTest` 5, `LawsTest` 16 (M1-M14), `KnownAnswerTest` 7, `PersistenceTest` 4, `CliTest` 11, `SoakTest` 4, `GoldenFileTest` 2 |
| chronos-bench | 8 | `BuildRulesTest` (dependency direction, banned APIs, approved libraries, soak harness) |

## What is implemented (modules)

| Module | Main / test code | State |
|---|---|---|
| chronos-model | 25 files, 1,084 lines / 188 | Events, ids, kinds, edge kinds, clocks, canonical encoding, digests, fault/intervention/image types, `LogReader`, `EventSink`, `DeterminismViolation`. |
| chronos-sdk | 13 / 391 / 0 | Node API, context, `NodeState` (volatile + durable zones), codec, scenario, invariant, world view, registry, code fingerprint. Exercised through the runtime and CLI tests. |
| chronos-store | 3 / 651 / 267 | Segmented JSONL log, commit lines with checksums, torn-tail recovery, checkpoints, catalogue, segment resolution, async writer, in-memory indexes. |
| chronos-runtime | 13 / 1,678 / 357 | Deterministic engine, agenda, fault injector (partition/heal, drop, delay, duplicate, crash, restart, probabilistic), invariants with witnesses, interventions, checkpoint capture/restore. |
| chronos-demo | 10 / 807 / 0 | ping-pong, causal demo, RE (+ two-backup variant), lost update, blocked commit, control, gossip, bad citizen. |
| chronos-analysis | 16 / 2,239 / 449 | Causal graph, exports, replay (R1, R2, R3 API, R4), branching, time travel, comparator, counterfactual engine with 5 verdicts, chaos generator, campaigns with a worker pool. |
| chronos-cli | 6 / 1,176 / 1,393 | Hand-written CLI (run, ls, show, inspect, session, verify, determinism, branch/counterfactual, compare, causes, campaign, report, delete, demo), HTML report, flagship demo. |
| chronos-bench | 3 / 670 / 171 | JMH benchmarks, whole-system harness (B1-B14, million-event run, soak), build-rule checks. |

Totals: about 8,700 lines of main code and 2,800 lines of tests. The git history has seven commits on `main` (local only).

## Acceptance demonstrations and known-answer scenarios

AD-1 to AD-6: **all verified** (tests and the live demo). KA-0 (control, 10,000-seed soak), KA-1, KA-2, KA-3, KA-4: **all verified**.
Details and the interpretation of AD-4/AD-5 (branch at the partition's injection boundary): `docs/TRACEABILITY.md`, `docs/DEVIATIONS.md` DEV-10.

## Not implemented / simplified (honest list)

Full matrix: `docs/TRACEABILITY.md`. The important ones:

* **NOT IMPLEMENTED:** pause/resume, clock-skew, reorder and slow-link faults; "after named event" and "on state" fault triggers;
  intervention types other than remove-fault, shift-heal, drop, delay, duplicate, crash; intervention search over the causal past
  (FR-38, Stretch); the live viewer (FR-50, Stretch); the knowledge view; node-local replay; persisted index files; the blind
  debugging protocol and evaluation chapter (need a second person); Linux and Windows verification.
* **SIMPLIFIED:** stepped replay with breakpoints is an API (`ReplayEngine.runUntil`), not a CLI command; `DIFFERENT_FAILURE` uses
  the invariant name and the set of fields read; world images are canonical JSON trees; NFR-01 was run as 200 seeds per scenario in
  the tests (10,000 for the control in the soak), not 1,000 per scenario for every demo.
* **Targets missed:** NFR-07 (replay within 2x) is **missed at 10,085 events (2.23x)**, met at 100,733 (1.88x) and 1,018,235 (1.76x).

## Deviations from the specification

Ten interpretations, four extensions and six simplifications are recorded in `docs/DEVIATIONS.md` (DEV-01 to DEV-20). The ones a
reviewer should look at first: **DEV-03** (OMISSION scope for partitions also requires the node to have heard from the other
side), **DEV-02** (digest parent ids exclude PROGRAM_ORDER), **DEV-11** (heal-enabled FAULT edge), **DEV-10** (AD-4/AD-5 branch point).

## Defects found by the tests and fixed (for the record)

1. **Path tie-break (real defect):** an explanation path could consist of a bare env PROGRAM_ORDER hop instead of the OMISSION chain
   (found by the anchor-rule comparator test) -> ties now prefer fewer PROGRAM_ORDER hops (DEV-09).
2. **`why` omitted faults that fire at send time** (KA-2: the delay never appeared) -> faults and interventions are always listed as sources.
3. **Duplicate env identities** when a message was duplicated more than once (found by the 10,000-seed chaos soak) -> per-copy identities (DEV-12).
4. **Verifier false mismatch** on trailing silent steps (found by the chaos soak on ping-pong) -> replay runs to the same end (DEV-13).
5. **Bad citizen could coincidentally replay** (1-in-2,400 collision on Apple Silicon's clock granularity, seen once in `CliTest`) -> more entropy (1e-6).
6. Several **wrong test assumptions** were corrected after checking the actual semantics, not by loosening the assertion
   (the cost rule makes `[A#2, A#3]` the correct cheapest path; "cone within D"; a tampered commit line vs an event line; checkpoint intervals).

## Known limitations and risks

* Lengthening a partition leaves the extra drops without a causal link to the intervention (M10 is tested for shorten/drop/delay); see `docs/DEVIATIONS.md`.
* `TimeTravelSession.world()` scans the log prefix (linear in position; 200 ms at 1M events) instead of starting at the nearest checkpoint.
* Verified on one machine and one OS. The first full benchmark run accidentally used JDK 26 and was discarded and redone on JDK 21;
  anyone running the jars must set `JAVA_HOME` to JDK 21 (the build refuses otherwise; the jars themselves run on any JDK >= 21).
* Replay-code-version checking is a declared version plus a best-effort class hash, with a warning on mismatch (Q7).

## Open specification questions

* **Q1** (deadline and weekly hours): OPEN. The 24-hour target is a conversation constraint, not an answer to Q1.
* **Q6** (institution's evaluation form): OPEN. Default (demonstration, report, viva) used.
* The specification marks D-23 to D-26 "New in v0.2" while its cover says all of D-01 to D-26 are approved; all four are implemented.

## Next highest-priority actions (if work continues)

1. Decide DEV-03 with the other author (keep the refined OMISSION scope or revert to the literal 12a rule).
2. Expose `ReplayEngine.runUntil` as a CLI/session `run-to` command; start `world()` from the nearest checkpoint.
3. Pause, clock-skew and reorder faults, and the remaining interventions (Phase 12b/13b depth).
4. Run the blind-debugging evaluation with a second person and write the evaluation chapter (Phase 17).
5. Verify on Linux/Windows; then, after the project is judged complete, create the GitHub repository (deliberately **not** done: no remote exists and nothing was pushed).

## How to resume safely

```bash
cd /Users/arunhbk/Desktop/Chronos
git status && git log --oneline           # local repository, branch main, no remote
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -ntp clean package                    # must end in BUILD SUCCESS with 119 tests
java -jar chronos-cli/target/chronos.jar --data chronos-data demo     # 24 of 24 checks
```

## Git state

Local repository only (`main`); no remote was added and nothing was pushed. `chronos-data/` (generated timelines and reports) is git-ignored.
