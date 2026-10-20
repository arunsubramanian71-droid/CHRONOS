# The two studies that need a real person (materials, procedure, scoring)

**Status (2026-10-20): NOT RUN. BLOCKED BY EXTERNAL INPUT.** Both studies need a participant who is not the author of this system. An
automated test, a script or an AI agent is not a participant, and nothing in this repository claims otherwise. This file is everything the
author needs to run them in about 90 minutes in total and to record the result honestly. When a study has been run, fill in section 4 of
that study, add the raw sheet to `docs/study-results/` and change the status line above and the rows `NFR-15` / "blind" in
`tools/completion_matrix.py` (they are listed there as BLOCKED BY EXTERNAL INPUT on purpose).

## Study A: NFR-15 usability (specification Section 8)

**Requirement.** "A new team member can run the flagship demo from the README: under 10 minutes, three commands."

**Who.** Someone who has not used CHRONOS, who is comfortable with a terminal, and who has JDK 21 and Maven installed (or the facilitator
installs them before the clock starts: installing prerequisites is not part of the 10 minutes, and that is written on the sheet).

**Procedure.** Hand over only the repository (the GitHub URL or the extracted `CHRONOS-submission.zip`) and the sentence "run the flagship
demo using the README". The facilitator does not speak except to read this protocol. Start a stopwatch when the participant opens the README.

1. The participant runs the commands they find. The README's three commands are (macOS: set `JAVA_HOME` first, `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`):
   `mvn -ntp clean package`, then `java -jar chronos-cli/target/chronos.jar --data chronos-data demo`. (Count what the participant actually typed.)
2. Stop the clock when the participant reads out "demonstration finished: 24 of 24 checks passed".
3. Record: elapsed time, number of commands typed, every point of hesitation or error, and what the participant says is unclear.

**Pass criteria (as specified).** Elapsed time under 10 minutes AND at most three commands. Record the facts even if it fails.

**Sheet (copy to `docs/study-results/nfr15-<date>.md`):**

| Field | Value |
|---|---|
| Date, facilitator, participant (initials), OS, JDK, Maven | |
| Commands typed (list) | |
| Elapsed time (mm:ss) | |
| Demo output read out? | |
| Errors, hesitations, unclear README passages | |
| PASS / FAIL against "< 10 min, 3 commands" | |

**What the author has measured meanwhile (not the specified evidence).** The same commands timed by the author on a clean extract of the
submission archive: see "NFR-15 dry run" in `docs/EVALUATION.md`.

## Study B: the blind debugging protocol (specification Section 25, risk R15)

**Requirement.** "One of us writes or seeds a bug in a scenario without telling the other, and the other must find the cause using only
CHRONOS. We record whether the tool led to the right diagnosis, how long it took, and what was confusing."

**Who.** Two people: a *preparer* (may be the author) and a *debugger* who has not seen the case. The debugger should have done the
README demo and read `docs/USER_GUIDE.md` first (30 minutes); that preparation time is recorded separately from the diagnosis time.

**Procedure.** Exactly as in `docs/BLIND_PROTOCOL.md` (the kit: `chronos blind prepare | begin | answer | grade`). Run at least five sessions, one
per case kind, with different tokens, and ideally two debuggers. The debugger may use only the `chronos` command line and the HTML report.

**Briefing to read to the debugger.** "Somewhere in this recording a fault caused a failure. You may use any `chronos` command and the
user guide, but not the source code of the demo applications and not `blind grade`. When you are ready, give the rule id of the fault you
believe is the root cause with `blind answer`. Say aloud what confuses you. You have 30 minutes."

**Data collected per session (CSV template `docs/study-results/blind-template.csv`):**
`session, token, case kind, debugger (initials), prior CHRONOS experience (none/some), minutes to answer, answer correct (grader exit code 0),
commands used, first command that gave the decisive clue, what was confusing, would a different output have helped`.

**Scoring.** Correct = `blind grade` exits 0. Time = from `blind begin` to `blind answer` (recorded by the kit). Report: number correct of N,
median and range of minutes, the three most frequent confusions. Compare, as the specification asks, with the tool-free baseline: give a
second debugger the same case with only the raw log files and the same time limit, and record the same fields.

**Analysis template (add to `docs/EVALUATION.md` when data exists):** a table of sessions; success rate with the number of sessions stated
(five sessions can show that the tool *can* lead to the right answer, not a rate); a paragraph on each confusion and the change made to the
output or the guide because of it; an explicit statement of what the sample cannot show.

## What must not be done

* Do not enter invented rows, and do not count a session that the author ran against his or her own case as blind.
* Do not describe the 800-run chaos evaluation or the 500 planted-cause automated evaluation in `docs/EVALUATION.md` as a blind study:
  they evaluate the analysers, not a person using the tool.
