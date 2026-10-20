# CHRONOS: submission guide

CHRONOS records, replays, branches and compares executions of a distributed application running inside a fully controlled, deterministic
simulation (think "Git for executions"). It is implemented from `CHRONOS System Specification v0.2.pdf` (included, unchanged).
Full compliance with v0.2 is **not** claimed; see "Known limitations" below.

## 1. Prerequisites

| Needed | Version | Notes |
|---|---|---|
| JDK | **21 exactly** (21.x) | The build has a Maven enforcer rule `[21,22)` and refuses other JDKs, so a newer JDK on the PATH will fail the build with a clear message. On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. |
| Maven | 3.9 or newer | Verified with 3.10.0 (macOS) and 3.9.16 (Linux container). |
| Internet | first build only | Maven downloads Jackson, JUnit and plugins once. |
| Python 3 | optional | only to regenerate `docs/BENCHMARKS.md` from the benchmark CSV. |

No other software is needed. The simulator itself never uses threads, wall-clock time or unseeded randomness.

## 2. Build and test (from a clean copy of this archive or of the GitHub repository)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # macOS; on Linux point JAVA_HOME at a JDK 21
mvn -ntp clean package                                 # compiles 8 modules, runs all 238 tests, builds the jars (about 1-2 minutes)
```

Expected: `BUILD SUCCESS` and 238 tests, 0 failures (15 model, 21 store, 16 runtime, 18 analysis, 160 cli, 8 bench).
The result is `chronos-cli/target/chronos.jar` (self-contained command line) and `chronos-bench/target/chronos-bench.jar`.
The archive also contains a prebuilt `dist/chronos.jar` (built from exactly this source) for running the demo without Maven; it still needs a JDK 21 or newer to run.

## 3. Run the demonstration

```bash
tools/professor-demo.sh                                 # a guided 3-minute tour in ./demo-data (never deletes anything)
java -jar chronos-cli/target/chronos.jar --data chronos-data demo     # all acceptance demos AD-1..AD-6: "24 of 24 checks passed", exit code 0
```

`docs/PROFESSOR_DEMO.md` explains each step and what to say. Every other command is listed by `java -jar chronos-cli/target/chronos.jar help`.

## 4. Check a data directory (integrity command)

```bash
java -jar chronos-cli/target/chronos.jar --data demo-data check            # logs, checksums, clocks, lineage, indexes, checkpoints, full replay
java -jar chronos-cli/target/chronos.jar --data demo-data check --json     # machine-readable
java -jar chronos-cli/target/chronos.jar --data demo-data check --strict   # warnings also give exit code 2
```

On the demonstration data it reports exactly one failure: the deliberately nondeterministic node of AD-2 ("STORE INTACT, REPLAY FAILS").

## 5. Where the outputs are

| Output | Location |
|---|---|
| Recorded timelines (append-only logs, checkpoints, indexes) | `<data dir>/timelines/<id>/` |
| HTML report (self-contained, no network) | `<data dir>/reports/T0-vs-T0.1.html` |
| Machine-readable verification summary and experiment manifest | `<data dir>/reports/T0-vs-T0.1.verification.json` |
| Saved counterexamples (re-executable, checksummed) | `<data dir>/counterexamples/` |
| Sample outputs committed to the repository | `docs/sample-report-T0-vs-T0.1.html`, `docs/sample-report-T0-vs-T0.1.verification.json`, `docs/demo-output.txt` |

## 6. Run the tests selectively

```bash
mvn -ntp -pl chronos-cli -am test -Dtest=LawsTest,KnownAnswerTest,AcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false
CHRONOS_M10_SEEDS=4000 mvn -ntp -pl chronos-cli -am test -Dtest=M10CatalogueTest -Dsurefire.failIfNoSpecifiedTests=false   # about 2 minutes
CHRONOS_FUZZ_FROM=20001 CHRONOS_FUZZ_SEEDS=26000 mvn -ntp -pl chronos-cli -am test -Dtest=InterventionFuzzTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 7. Documents

`README.md` (overview, architecture), `docs/PROFESSOR_DEMO.md` (demonstration guide), `IMPLEMENTATION_STATUS.md` (authoritative status),
`docs/TRACEABILITY.md` (requirement to code to test), `docs/DEVIATIONS.md` (every interpretation, extension, defect fixed: DEV-01 to DEV-54),
`docs/COMPLETION_MATRIX.md` (every requirement with a status derived from the tests that ran), `docs/HUMAN_STUDIES.md` (the two studies that need real participants: checklist, cards, sheets; `tools/study-kit.sh`, `tools/study_analyse.py`), `docs/USER_GUIDE.md`, `docs/ARCHITECTURE.md`, `docs/EVALUATION.md`, `docs/BENCHMARKS.md`, `docs/VIVA_GUIDE.md`, `docs/PORTABILITY.md`.

## 8. Known limitations (say these first)

* Verified on macOS (Apple Silicon) and Linux (Ubuntu aarch64 container). **Windows is not verified** (its first CI run failed on three test-portability defects, DEV-53; see `docs/PORTABILITY.md` for the current CI status). The CI workflow is `.github/workflows/build.yml`.
* **Not implemented:** per-vector delta compression (the specification's trigger condition did not occur). The **blind-debugging evaluation** needs a second person; the kit is built (`chronos blind`) but no session has been run, so no blind result is claimed.
* **Decisions:** DEV-03 (OMISSION scope for partitions) is closed with a final "evidence scope"; Q1 (deadline, weekly hours) is closed as a retrospective planning item; Q6 uses the specification's default (demonstration, report, viva) as a working assumption because no official rubric is available.
* Explanations marked "potential" (OMISSION edges, displaced events, the possible-silence section) are hypotheses, not proved causes.
* The systematic explorer and `minimize` give guarantees only inside their stated bounds (1-minimal, not minimum; "no violation" means none in the bound).
* Timings in `docs/BENCHMARKS.md` are from one machine (Apple M2 Max).
* Test counts and results above were measured on the commit named in `IMPLEMENTATION_STATUS.md`; run section 2 to reproduce them.
