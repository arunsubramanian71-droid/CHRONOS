# Portability: macOS, Linux, Windows (NFR-14)

| Platform | Status | Evidence |
|---|---|---|
| macOS (Apple Silicon, JDK 21.0.12, Maven 3.10.0) | **verified** | `mvn -ntp clean package`: all tests pass; demo 24 of 24; benchmarks |
| Linux (Ubuntu 24.04 aarch64 container, Temurin 21, Maven 3.9.16) | **verified** | `mvn -ntp package` on a copy of the source: all tests pass, golden files generated on macOS pass; packaged jar's demo 24 of 24 |
| Windows | **NOT VERIFIED** (no Windows machine or CI was available) | prepared, see below |

## What was done so that Windows should work

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
* `.github/workflows/build.yml` runs the same two commands on `ubuntu-latest`, `macos-latest` and `windows-latest` if the repository is ever
  published. **It has never been run.**

## How to verify on Windows (about 5 minutes with JDK 21 and Maven installed)

```bat
git clone <the repository>  &  cd Chronos
mvn -ntp package
java -jar chronos-cli\target\chronos.jar --data chronos-data demo
```

Expected: `BUILD SUCCESS`, 204 tests, 0 failures, and `demonstration finished: 24 of 24 checks passed - ALL PASSED`. Please record the result
(and any failure with its output) in `IMPLEMENTATION_STATUS.md`; until then Windows stays NOT VERIFIED. Likely places for a failure, if any:
the process-kill test (`PersistenceTest`, uses `Process.destroyForcibly`), and path separators in test expectations.
