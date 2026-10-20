#!/usr/bin/env bash
# A guided CHRONOS demonstration for an evaluator: about 3 minutes, every step is a real command whose output is shown.
# Usage:  tools/professor-demo.sh [DATA_DIR]      (default: a new directory ./demo-data; it is created, never deleted)
# Needs the packaged jar:  mvn -ntp clean package   (JDK 21, Maven 3.9+)   or the prebuilt dist/chronos.jar of the submission archive.
set -u
cd "$(dirname "$0")/.."
if [ -x /usr/libexec/java_home ]; then export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"; fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="chronos-cli/target/chronos.jar"; [ -f "$JAR" ] || JAR="dist/chronos.jar"
[ -f "$JAR" ] || { echo "no jar found: run 'mvn -ntp clean package' first"; exit 1; }
DATA="${1:-demo-data}"
if [ -e "$DATA/timelines" ]; then echo "$DATA already holds timelines; choose a new directory (nothing is deleted by this script)"; exit 1; fi
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
c() { printf '\033[2m$ chronos %s\033[0m\n' "$*"; "$JAVA" -jar "$JAR" --data "$DATA" "$@"; local rc=$?; echo "[exit code $rc]"; }

step "1. Record a run of the replicated store RE under a planted network partition (the failure to explain)"
c run re --no-stop
step "2. Deterministic replay: re-execute the whole run and compare every regenerated event with the recording"
c verify T0
step "3. Time travel: the world at the moment of the violation (states, in-flight messages, timers, clocks)"
c inspect T0 --goto violation --world
step "4. Counterfactual: heal the partition 200 ms earlier. Does the failure disappear?"
c branch T0 --heal-earlier 200
step "5. Compare the two timelines: the first causal divergence and why it is the cause"
c compare T0 T0.1
step "6. Report: a self-contained HTML page and a machine-readable verification summary"
c report T0 --against T0.1
step "7. Integrity check of everything stored (checksums, clocks, lineage, indexes, replay)"
c check
step "8. A deliberately nondeterministic node (AD-2): replay must catch it"
c run bad --seed 3
c verify T1
step "9. The check tells an intact store from a nondeterministic run (exit code 2 here is expected)"
c check
step "10. Shrink a noisy chaos failure to the faults that matter (delta debugging), saved as a reproducible counterexample"
c run re --seed 5 --plan chaos
c minimize T2 --save
step "11. Every acceptance demonstration AD-1 to AD-6 in one command (24 checks; exit code 0 = all passed)"
"$JAVA" -jar "$JAR" --data "$DATA/ad" demo | tail -4
echo "[exit code ${PIPESTATUS[0]}]"
printf '\nReports: %s/reports/ (T0-vs-T0.1.html, T0-vs-T0.1.verification.json)   Counterexample: %s/counterexamples/\n' "$DATA" "$DATA"
