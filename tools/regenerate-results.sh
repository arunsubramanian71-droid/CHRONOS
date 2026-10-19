#!/usr/bin/env bash
# Regenerates every measured result that the documents quote, from the current source, on the machine it is run on.
# Usage:  tools/regenerate-results.sh [--quick] [--long]
#   --quick  smaller benchmark run (skips the million-event runs and the 10,000-seed soak)
#   --long   also runs the long randomized campaigns once (intervention fuzz 5,000 seeds, M10 sweep 2,000 seeds, chaos soak 1,000 seeds per scenario)
# Needs JDK 21 (selected below on macOS if several are installed), Maven 3.9+, python3. Takes about 10 minutes (60+ with --long).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -x /usr/libexec/java_home ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "== environment"; "$JAVA" -version 2>&1 | head -1; mvn -version | head -1
QUICK=""; LONG=""
for a in "$@"; do [ "$a" = "--quick" ] && QUICK="--quick --no-xl --soak-seeds 300"; [ "$a" = "--long" ] && LONG=1; done

echo "== 1. clean build and all tests (acceptance, known-answer, laws, persistence, fuzzers, explorer, incident bundles, doc-integrity)"
mvn -ntp clean package | tee /tmp/chronos-build.log | grep -E "Tests run:.*Fail.*[0-9]$|BUILD"

echo "== 2. the flagship demonstration (AD-1..AD-6, KA-0, KA-2..KA-4); exit code 0 means all checks passed"
DATA="$(mktemp -d)"
"$JAVA" -jar chronos-cli/target/chronos.jar --data "$DATA" demo > docs/demo-output.txt
cp "$DATA/reports/T0-vs-T0.1.html" docs/sample-report-T0-vs-T0.1.html
tail -2 docs/demo-output.txt | head -1

echo "== 3. benchmarks against the non-functional requirements (writes docs/benchmark-results.csv, regenerates docs/BENCHMARKS.md)"
"$JAVA" -jar chronos-bench/target/chronos-bench.jar all --csv docs/benchmark-results.csv ${QUICK}
python3 chronos-bench/tools/benchmarks_md.py

echo "== 4. evaluation: unplanned chaos failures, randomised planted causes, systematic exploration against random sampling (writes docs/evaluation-output.txt)"
"$JAVA" -jar chronos-bench/target/chronos-bench.jar eval --seeds 400 --tokens 500 --csv /tmp/chronos-eval.csv 2>&1 | grep -E "^(CHRONOS|environment|method|EVAL)|^  seed" > docs/evaluation-output.txt
grep -E "^EVAL" docs/evaluation-output.txt | cut -c1-200

if [ -n "$LONG" ]; then
  echo "== 5. long randomized campaigns (each fixed-seed; failures print the seed)"
  CHRONOS_FUZZ_SEEDS=5000 mvn -ntp -pl chronos-cli -am test -Dtest='InterventionFuzzTest' -Dsurefire.failIfNoSpecifiedTests=false | grep -E "Tests run:.*Time|FAIL"
  CHRONOS_M10_SEEDS=2000 mvn -ntp -pl chronos-cli -am test -Dtest='M10CatalogueTest' -Dsurefire.failIfNoSpecifiedTests=false | grep -E "Tests run:.*Time|^M10 residual|FAIL"
  CHRONOS_SOAK_SEEDS=1000 mvn -ntp -pl chronos-cli -am test -Dtest='SoakTest' -Dsurefire.failIfNoSpecifiedTests=false | grep -E "Tests run:.*Time|FAIL"
fi
echo "== done: review 'git diff --stat docs/' and update IMPLEMENTATION_STATUS.md with the figures"
