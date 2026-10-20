#!/usr/bin/env bash
# Facilitator tooling for the two human studies of docs/HUMAN_STUDIES.md. It prepares clean, reproducible material and records times and grades.
# It never produces study results by itself: every number in a result file comes from a person's session (or from `self-test`, which labels itself).
#
#   tools/study-kit.sh env-check
#   tools/study-kit.sh nfr15-prepare DIR [COMMIT]     a clean checkout of the repository at a pinned commit for the participant (no build)
#   tools/study-kit.sh nfr15-clock DIR start|stop      the stopwatch of Study A; `stop` prints the elapsed time and the pass/fail against 10 minutes
#   tools/study-kit.sh blind-prepare DIR [ROUND]       five blind sessions, one per case kind, with the preparer's key kept apart from what the debugger gets
#   tools/study-kit.sh blind-grade DIR                 grades every answered session and writes DIR/blind-results.csv
#   tools/study-kit.sh unplanned-prepare DIR           five chaos failures nobody planned: a data directory (tool arm), the raw log (hand arm) and a reference key
#   tools/study-kit.sh self-test                       checks all of the above without a person (SELF-TEST, not study data)
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
if [ -x /usr/libexec/java_home ]; then export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"; fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${CHRONOS_JAR:-$ROOT/chronos-cli/target/chronos.jar}"; [ -f "$JAR" ] || JAR="$ROOT/dist/chronos.jar"
REPO_URL="${CHRONOS_REPO_URL:-https://github.com/arunsubramanian71-droid/CHRONOS.git}"
BRANCH="${CHRONOS_BRANCH:-publish-no-assistant-trailers}"
chronos() { "$JAVA" -jar "$JAR" "$@"; }
die() { echo "study-kit: $*" >&2; exit 1; }
need_jar() { [ -f "$JAR" ] || die "no jar: run 'mvn -ntp clean package' first (the facilitator needs it; the NFR-15 participant builds their own)"; }

cmd_env_check() {
  local ok=0
  echo "java:  $("$JAVA" -version 2>&1 | head -1)"; "$JAVA" -version 2>&1 | head -1 | grep -q '"21\.' || { echo "  -> JDK 21 required for the build"; ok=1; }
  echo "mvn:   $(mvn -version 2>/dev/null | head -1 || echo missing)"; command -v mvn >/dev/null || { echo "  -> Maven 3.9+ required"; ok=1; }
  echo "git:   $(git --version 2>/dev/null || echo missing)"
  echo "os:    $(uname -srm)"
  [ $ok -eq 0 ] && echo "ENVIRONMENT OK" || echo "ENVIRONMENT NOT READY (installing prerequisites is outside the timed part of Study A)"
  return $ok
}

cmd_nfr15_prepare() {
  local dir="${1:-}" commit="${2:-}"; [ -n "$dir" ] || die "usage: nfr15-prepare DIR [COMMIT]"
  [ ! -e "$dir" ] || die "$dir exists; choose a new directory (nothing is deleted)"
  mkdir -p "$dir"
  git clone -q --branch "$BRANCH" "$REPO_URL" "$dir/CHRONOS" || die "clone failed"
  if [ -n "$commit" ]; then git -C "$dir/CHRONOS" checkout -q "$commit" || die "no such commit"; fi
  rm -rf "$dir/CHRONOS/.git"            # the participant gets the source tree, as from the submission archive
  local head; head="$(git ls-remote "$REPO_URL" "$BRANCH" | cut -c1-40)"
  printf 'commit=%s\nbranch=%s\nprepared=%s\n' "${commit:-$head}" "$BRANCH" "$(date -u +%FT%TZ)" > "$dir/session.env"
  cp docs/study-materials/nfr15-facilitator-sheet.csv "$dir/sheet.csv"
  cat <<EOF
Prepared $dir/CHRONOS (commit $(grep commit= "$dir/session.env" | cut -d= -f2), no build output, no data directory, no Maven cache of yours).
Note: on a machine that never built the project Maven downloads about 35 MB first; that time counts (it is part of what a new person experiences).
Next: run  tools/study-kit.sh env-check  on the participant's machine, hand over $dir/CHRONOS and docs/study-materials/nfr15-participant-card.md,
then  tools/study-kit.sh nfr15-clock $dir start  when the participant opens the README, and  ... stop  when they read out the demo result.
EOF
}

cmd_nfr15_clock() {
  local dir="${1:-}" what="${2:-}"; [ -f "$dir/session.env" ] || die "usage: nfr15-clock DIR start|stop (DIR from nfr15-prepare)"
  case "$what" in
    start) grep -q '^start=' "$dir/session.env" && die "already started"; echo "start=$(date +%s)" >> "$dir/session.env"; echo "clock started $(date +%T)";;
    stop)  grep -q '^start=' "$dir/session.env" || die "not started"; grep -q '^stop=' "$dir/session.env" && die "already stopped"
           echo "stop=$(date +%s)" >> "$dir/session.env"
           local s e; s="$(grep '^start=' "$dir/session.env" | cut -d= -f2)"; e="$(grep '^stop=' "$dir/session.env" | cut -d= -f2)"
           printf 'elapsed %d:%02d (%d s). Under 10 minutes: %s. Now count the commands the participant typed and fill %s/sheet.csv\n' $(( (e-s)/60 )) $(( (e-s)%60 )) $((e-s)) "$([ $((e-s)) -lt 600 ] && echo YES || echo NO)" "$dir";;
    *) die "start|stop";;
  esac
}

# Tokens whose value modulo 5 is 0..4 give the five case kinds (BlindKit.caseFor); ROUND shifts them so that a second debugger gets different rule names and times.
cmd_blind_prepare() {
  local dir="${1:-}" round="${2:-0}"; [ -n "$dir" ] || die "usage: blind-prepare DIR [ROUND]"; need_jar
  [ ! -e "$dir" ] || die "$dir exists; choose a new directory"
  mkdir -p "$dir/key" "$dir/sessions"
  : > "$dir/key/preparer-key.txt"
  local k tok
  for k in 0 1 2 3 4; do
    tok=$(( 4100 + 5 * round * 7 + k ))                      # 4100 mod 5 = 0
    local hex; hex="$(printf '%x' "$tok")"
    mkdir -p "$dir/sessions/s$((k+1))"
    # the output of prepare shows the explanation of the planted failure: it is for the preparer only
    chronos --data "$dir/sessions/s$((k+1))" blind prepare --token "$hex" > "$dir/key/prepare-output-s$((k+1)).txt" 2>&1 || die "prepare failed for token $hex"
    echo "s$((k+1)) token=$hex kind=$k" >> "$dir/key/preparer-key.txt"
  done
  cat <<EOF
Prepared 5 blind sessions in $dir/sessions (one data directory each: give the debugger ONLY one of them, one at a time).
Keep $dir/key to yourself: it holds the tokens and the explanation printed when each case was built.
Per session: debugger runs  chronos --data $dir/sessions/sN blind begin ... blind answer --root RULE --notes "..."  (docs/study-materials/blind-debugger-card.md).
Afterwards:  tools/study-kit.sh blind-grade $dir
EOF
}

cmd_blind_grade() {
  local dir="${1:-}"; [ -d "$dir/sessions" ] || die "usage: blind-grade DIR"; need_jar
  local out="$dir/blind-results.csv"
  echo "session,token,case_kind,planted,answer,seconds,correct,debugger_notes" > "$out"
  local s n=0 answered=0
  for s in "$dir"/sessions/s*; do
    n=$((n+1)); local name; name="$(basename "$s")"
    if [ ! -f "$s/blind.json" ] || ! grep -q '"answer"' "$s/blind.json"; then echo "$name: not answered yet, skipped"; continue; fi
    answered=$((answered+1))
    local g; g="$(chronos --data "$s" blind grade 2>&1)"; local rc=$?
    local tok kind; tok="$(grep "^$name " "$dir/key/preparer-key.txt" | sed 's/.*token=\([0-9a-f]*\).*/\1/')"; kind="$(grep "^$name " "$dir/key/preparer-key.txt" | sed 's/.*kind=//')"
    python3 - "$s/blind.json" "$name" "$tok" "$kind" "$rc" >> "$out" <<'PY'
import json,sys,csv
b=json.load(open(sys.argv[1])); w=csv.writer(sys.stdout, lineterminator="\n")
w.writerow([sys.argv[2],sys.argv[3],sys.argv[4]," ".join(b.get("planted",[]))," ".join(b.get("answer",[])),b.get("seconds",""),"yes" if sys.argv[5]=="0" else "no",b.get("notes","")])
PY
    echo "$name: $(echo "$g" | tail -1)"
  done
  echo "graded $answered of $n sessions -> $out (add debugger identity, prior experience, commands used, confusions by hand: tools/study_analyse.py reads the completed sheet)"
}

cmd_unplanned_prepare() {
  local dir="${1:-}"; [ -n "$dir" ] || die "usage: unplanned-prepare DIR"; need_jar
  [ ! -e "$dir" ] || die "$dir exists; choose a new directory"
  mkdir -p "$dir/key" "$dir/tool" "$dir/hand"
  local seeds; seeds="$(chronos --data "$dir/key/scratch" campaign re --seeds 1-400 --chaos --threads 4 2>&1 | grep -E '^  seed [0-9]+:' | sed 's/^  seed \([0-9]*\):.*/\1/' | head -5)"
  [ -n "$seeds" ] || die "the campaign found no failing seed"
  local i=0 seed
  for seed in $seeds; do
    i=$((i+1))
    chronos --data "$dir/tool/case$i" run re --seed "$seed" --plan chaos --no-stop > "$dir/key/run-case$i.txt" 2>&1
    cp "$dir/tool/case$i/timelines/T0/events/"*.log "$dir/hand/case$i.log" 2>/dev/null || cp "$dir/tool/case$i/timelines/T0/events/"*.log.gz "$dir/hand/" 2>/dev/null
    { echo "case$i: scenario re, seed $seed, chaos plan"; chronos --data "$dir/tool/case$i" causes T0 2>&1 | tail -3; chronos --data "$dir/tool/case$i" minimize T0 2>&1 | grep -E "kept:|fault rules"; } > "$dir/key/reference-case$i.txt"
  done
  rm -rf "$dir/key/scratch"
  cat <<EOF
Prepared $i unplanned failures (chaos runs of RE that nobody designed) in $dir:
  tool/caseN/   data directory for the "with CHRONOS" arm        hand/caseN.log   the raw event log for the "by hand" arm
  key/          the reference answers (what CHRONOS's analysers and a real counterfactual say): do not show the diagnosers
Each case is diagnosed twice by the same or different people, one arm each (docs/study-materials/unplanned-card.md); then compare the answers.
EOF
}

cmd_self_test() {
  need_jar
  local t; t="$(mktemp -d)"; local fail=0
  echo "SELF-TEST: this checks the study tooling; nothing printed here is study data."
  cmd_env_check >/dev/null 2>&1 && echo "  [ok] env-check"
  # Study A tooling: pinned clean checkout from the local repository
  if [ -d "$ROOT/.git" ]; then
    CHRONOS_REPO_URL="file://$ROOT" cmd_nfr15_prepare "$t/a" "$(git -C "$ROOT" rev-parse HEAD)" >/dev/null 2>&1 || { echo "  [FAIL] nfr15-prepare"; fail=1; }
    [ -f "$t/a/CHRONOS/pom.xml" ] && [ ! -d "$t/a/CHRONOS/.git" ] && [ ! -d "$t/a/CHRONOS/chronos-cli/target" ] && echo "  [ok] nfr15-prepare: clean tree at the pinned commit, no .git, no build output" || { echo "  [FAIL] clean tree"; fail=1; }
  else
    mkdir -p "$t/a"; echo "prepared=selftest" > "$t/a/session.env"
    echo "  [skipped] nfr15-prepare needs a git checkout (this is an archive without .git); it clones the published repository"
  fi
  cmd_nfr15_clock "$t/a" start >/dev/null && cmd_nfr15_clock "$t/a" stop | grep -q "Under 10 minutes: YES" && echo "  [ok] nfr15-clock" || { echo "  [FAIL] nfr15-clock"; fail=1; }
  # Study B tooling: five kinds, answered by the tooling itself from the grader's planted set (checks the pipeline, not the tool)
  cmd_blind_prepare "$t/b" 0 >/dev/null 2>&1 || { echo "  [FAIL] blind-prepare"; fail=1; }
  local kinds; kinds="$(sed 's/.*kind=//' "$t/b/key/preparer-key.txt" | sort | tr '\n' ' ')"
  [ "$kinds" = "0 1 2 3 4 " ] && echo "  [ok] blind-prepare: the five sessions cover the five case kinds" || { echo "  [FAIL] kinds: $kinds"; fail=1; }
  local s
  for s in "$t"/b/sessions/s*; do
    chronos --data "$s" blind begin >/dev/null
    chronos --data "$s" blind answer --root SELFTEST-placeholder --notes "SELFTEST" >/dev/null   # the grader only reveals the planted set after an answer exists
    local planted; planted="$(chronos --data "$s" blind grade 2>&1 | sed -n 's/^planted cause: \[\([^]]*\)\].*/\1/p')"
    [ -n "$planted" ] || { echo "  [FAIL] cannot read planted cause of $s"; fail=1; continue; }
    local args=(); local r; for r in $(echo "$planted" | tr ',' ' '); do args+=(--root "$r"); done
    chronos --data "$s" blind answer "${args[@]}" --notes "SELFTEST" >/dev/null
  done
  cmd_blind_grade "$t/b" > "$t/grade.txt" 2>&1; local ok; ok="$(grep -c ',yes,' "$t/b/blind-results.csv")"
  [ "$ok" = "5" ] && echo "  [ok] blind-grade: 5 of 5 pipeline answers graded correct (answers were read from the grader: SELF-TEST)" || { echo "  [FAIL] blind-grade: $ok of 5"; fail=1; }
  # unplanned arm
  cmd_unplanned_prepare "$t/u" > "$t/u.txt" 2>&1 && [ "$(ls "$t/u/hand" | wc -l | tr -d ' ')" -ge 5 ] && [ -s "$t/u/key/reference-case1.txt" ] && echo "  [ok] unplanned-prepare: 5 cases, raw logs and reference answers" || { echo "  [FAIL] unplanned-prepare"; fail=1; }
  # analysis refuses self-test rows unless told
  printf 'session,token,case_kind,debugger,prior_chronos_experience,minutes_to_answer,answer_correct,commands_used,first_decisive_command,what_was_confusing,would_a_different_output_have_helped\ns1,1,0,SELFTEST-1,none,3,yes,5,causes,,\n' > "$t/blind.csv"
  python3 tools/study_analyse.py blind "$t/blind.csv" >/dev/null 2>&1 && { echo "  [FAIL] analysis accepted SELFTEST rows"; fail=1; } || echo "  [ok] study_analyse.py refuses rows marked SELFTEST"
  python3 tools/study_analyse.py blind "$t/blind.csv" --allow-selftest | grep -q "SELF-TEST" && echo "  [ok] study_analyse.py analyses them only with --allow-selftest, and says so" || { echo "  [FAIL] analysis"; fail=1; }
  rm -rf "$t"
  [ $fail -eq 0 ] && echo "SELF-TEST PASSED (tooling only; no study has been run)" || { echo "SELF-TEST FAILED"; return 1; }
}

case "${1:-}" in
  env-check) cmd_env_check;;
  nfr15-prepare) shift; cmd_nfr15_prepare "$@";;
  nfr15-clock) shift; cmd_nfr15_clock "$@";;
  blind-prepare) shift; cmd_blind_prepare "$@";;
  blind-grade) shift; cmd_blind_grade "$@";;
  unplanned-prepare) shift; cmd_unplanned_prepare "$@";;
  self-test) cmd_self_test;;
  *) sed -n 2,12p "$0"; exit 1;;
esac
