# Portability: macOS, Linux, Windows (NFR-14)

| Platform | Status | Evidence |
|---|---|---|
| macOS (Apple Silicon, JDK 21.0.12, Maven 3.10.0) | **verified** | `mvn -ntp clean package`: all tests pass; demo 24 of 24; benchmarks |
| Linux (Ubuntu 24.04 aarch64 container, Temurin 21, Maven 3.9.16) | **verified** | `mvn -ntp package` on a copy of the source: all tests pass, golden files generated on macOS pass; packaged jar's demo 24 of 24 |
| GitHub Actions `ubuntu-latest` (x86-64) | **verified** | run 39451103248: full build, 238 tests, demonstration |
| GitHub Actions `macos-latest` | **verified** | same run |
| GitHub Actions `windows-latest` | **verified on the hosted runner** | same run: full build, 238 tests, demonstration. Not tested on a Windows machine of the author |

## What was done so that Windows works

* No platform-specific code: the engine, store and analysis use `java.nio.file` and `Path` only; timeline ids and event ids never become
  file names (files are `timelines/<id>/events/NNNNNN.log`, `checkpoints/NNNNNNNNNN.ckpt`, `index/main.idx`, ids are validated: no `/`, `\` or `..`).
* The log and checkpoint files are written by the program with `\n` line endings and read back with line readers, so they are portable;
  hashes are computed over the text the program wrote.
* `.gitattributes` pins the golden files (`*.jsonl`), Java sources and the HTML sample to LF so that a Windows checkout with
  `core.autocrlf=true` cannot change the bytes the golden-file test compares.
* Atomic file replacement (`Files.move` with `ATOMIC_MOVE`) is used for the catalogue, summary, checkpoints and index file; on Windows this
  works within one volume, which is where a timeline always lives.
* The program and the tests that start a child JVM (`chronos determinism --jvm`, the kill test) locate it through the `java.home` system property, never a `java` found on the PATH.
* The build refuses any JDK other than 21 (enforcer rule), on every platform.
* `.github/workflows/build.yml` runs the build (all tests; 235 at the time of the runs listed below) and the flagship demonstration on `ubuntu-latest`, `macos-latest` and `windows-latest` with JDK 21 (Temurin).
  History of its runs on https://github.com/arunsubramanian71-droid/CHRONOS (2026-10-20):

  | Run | Commit | Ubuntu | macOS | Windows | Note |
  |---|---|---|---|---|---|
  | 39395403462 | `2df09fd` | FAIL | PASS | FAIL | Ubuntu: Maven could not download `org.junit:junit-bom:5.11.4` (a published artifact; external download failure, cause not provable because the job log needs a login); Windows: three tests assumed `/` and LF (DEV-53) |
  | 39397381422 | `0e6674c` | PASS | PASS | PASS | after the DEV-53 fixes and the retried model-resolution step |
  | 39414554555 | `9e89ed5` | PASS | PASS | PASS | the benchmarked code; every job ran through packaging, all tests and the demonstration |
  | 39451103248 | `1ba0e19` | PASS | PASS | PASS | 238 tests; adds the study tooling, two documentation tests and a help-text fix |

  "PASS" means the job's steps *Build and test (all eight modules)* and *Flagship demonstration* both succeeded (checked through the GitHub API), not only the model-resolution step.

## How to verify on Windows yourself (about 5 minutes with JDK 21 and Maven installed)

```bat
git clone <the repository>  &  cd Chronos
mvn -ntp package
java -jar chronos-cli\target\chronos.jar --data chronos-data demo
```

Expected: `BUILD SUCCESS`, 238 tests, 0 failures, and `demonstration finished: 24 of 24 checks passed - ALL PASSED`. Please record the result
(and any failure with its output) in `IMPLEMENTATION_STATUS.md`. The Windows evidence so far is the GitHub-hosted `windows-latest` runner above, not a Windows machine of the author. Likely places for a failure, if any:
the process-kill test (`PersistenceTest`, uses `Process.destroyForcibly`), and path separators in test expectations.
