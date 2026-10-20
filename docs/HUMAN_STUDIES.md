# The two studies that need a real person

**Status: NOT RUN. BLOCKED BY EXTERNAL INPUT.** Both studies need a participant who is not the author of this system. An automated test, a script
or an AI agent is not a participant, and nothing in this repository claims otherwise. Everything that can be prepared without a person is
prepared and checked (`tools/study-kit.sh self-test`); this file is the checklist for the part that cannot.

Source of the requirements (the specification, not invented here):

| Study | Specification text | Where |
|---|---|---|
| A: usability | NFR-15 "A new team member can run the flagship demo from the README. Under 10 minutes, three commands." | Section 8, page 24 |
| B: blind debugging | "one of us writes or seeds a bug in a scenario without telling the other, and the other must find the cause using only CHRONOS. We record whether the tool led to the right diagnosis, how long it took, and what was confusing. These sessions become part of the evaluation chapter (Phase 17). We also run campaigns on scenarios to find failures we did not plan, and diagnose them by hand and with the tool to compare answers." | Section 25, page 95 |

Materials (all in this repository, all tested by `tools/study-kit.sh self-test`):

| What | File |
|---|---|
| Participant eligibility, exact words to say, task, success criteria, what to record: Study A | `docs/study-materials/nfr15-participant-card.md` |
| The same for the blind debugger (five case kinds) | `docs/study-materials/blind-debugger-card.md` |
| The same for the unplanned-failure comparison (by hand against with the tool) | `docs/study-materials/unplanned-card.md` |
| Clean pinned checkout, stopwatch, five blind sessions, grading, unplanned cases | `tools/study-kit.sh` |
| Data sheets (blank): NFR-15, blind, unplanned | `docs/study-results/nfr15-template.csv`, `blind-template.csv`, `unplanned-template.csv` |
| Analysis: n, pass counts, medians, per-kind results, confusions | `tools/study_analyse.py` |
| Final report template | `docs/study-materials/report-template.md` |

## Checklist for the facilitator

### Before: about 30 minutes, no participant needed
- [ ] `mvn -ntp clean package` on your own machine (the kit needs the jar for the blind and unplanned parts).
- [ ] `tools/study-kit.sh self-test` prints `SELF-TEST PASSED`.
- [ ] Decide the commit under test (default: the head of the published branch); note it on the sheet.
- [ ] Find the people: Study A needs **one or more people who have never seen CHRONOS**; Study B needs a **debugger** who has not seen the cases (and a preparer: you).

### Study A: NFR-15 (10 minutes per participant plus setup)
- [ ] On the participant's machine: `tools/study-kit.sh env-check` shows JDK 21 and Maven (install them first; that is not timed).
- [ ] `tools/study-kit.sh nfr15-prepare ~/study-a1` (clean checkout at the pinned commit, no `.git`, no build output).
- [ ] Hand over only `~/study-a1/CHRONOS`; read the card's sentence aloud; `tools/study-kit.sh nfr15-clock ~/study-a1 start` when the README is opened.
- [ ] Count commands, note hesitations, errors, unclear README sentences. Do not coach.
- [ ] `tools/study-kit.sh nfr15-clock ~/study-a1 stop` when the participant reads out "24 of 24 checks passed".
- [ ] Fill one row of `~/study-a1/sheet.csv`; copy it to `docs/study-results/nfr15.csv` (header from `nfr15-template.csv`).
- [ ] `python3 tools/study_analyse.py nfr15 docs/study-results/nfr15.csv`.

### Study B: blind debugging (about 40 minutes per session, five sessions)
- [ ] The debugger has done the README demo and read `docs/USER_GUIDE.md` (note the preparation time).
- [ ] `tools/study-kit.sh blind-prepare ~/study-b1` (add a number as a second argument for a second debugger, to get different rule names and times).
- [ ] Give the debugger **only** `~/study-b1/sessions/s1`, then s2 ... one at a time; keep `~/study-b1/key` private; read the card's briefing aloud.
- [ ] The debugger runs `chronos --data ... blind begin`, diagnoses, runs `blind answer --root RULE ... --notes "..."`; note commands used, first decisive command, confusions, aloud comments.
- [ ] `tools/study-kit.sh blind-grade ~/study-b1` after each session or at the end; add debugger initials, prior experience, commands used and confusions to the CSV; save as `docs/study-results/blind.csv`.
- [ ] `python3 tools/study_analyse.py blind docs/study-results/blind.csv`.
- [ ] Unplanned failures: `tools/study-kit.sh unplanned-prepare ~/study-b2`; five cases, each diagnosed once by hand (raw log only) and once with the tool; record in `docs/study-results/unplanned.csv`; `python3 tools/study_analyse.py unplanned docs/study-results/unplanned.csv`.

### After
- [ ] Fill `docs/study-materials/report-template.md` into `docs/EVALUATION.md` (a new section) using only the analysed numbers; say what the sample cannot show.
- [ ] Change the README or the output wherever a participant was confused, and record the commit.
- [ ] Only now update the two BLOCKED rows in `tools/completion_matrix.py` (NFR-15, blind) to the real result, run `python3 tools/completion_matrix.py`, and update the status lines in `IMPLEMENTATION_STATUS.md` and `docs/BLIND_PROTOCOL.md`.

## What the participant must do and what evidence comes back

| | Participant does | Evidence collected |
|---|---|---|
| Study A | follows the README alone; runs the three README commands; reads out the demo result | elapsed seconds (stopwatch), number of commands, errors and hesitations, README sentences that confused them, pass/fail against "under 10 minutes, three commands" |
| Study B | uses only `chronos` and the user guide on one case at a time; names the fault rule(s) with `blind answer` | `blind.json` per session (answer, seconds, grade), commands used, first decisive command, confusions |
| Study B part 2 | diagnoses five unplanned failures, some by hand from the raw log and some with the tool | answers, minutes, agreement with the reference, confusions |

## What must not be done

* Do not enter invented rows, and do not count a session the author ran on his or her own case as blind. `tools/study_analyse.py` refuses any row marked `SELFTEST`.
* Do not describe the 800-run chaos evaluation or the 500 planted-cause automated evaluation in `docs/EVALUATION.md` as a blind study: they evaluate the analysers, not a person using the tool.
* Do not change the pass criterion of Study A (under 10 minutes, three commands) or the grading of Study B (exactly the planted set) after seeing results.

## Limits of the preparation (honest)

The kit plants causes in the *fault plan* (five kinds); the specification's "writes or seeds a bug in a scenario" could also mean a bug in application code, which needs a preparer who writes it (the `mystery` scenario and the Node API make that possible but it is not automated). The by-hand arm of part 2 is a text file and a text editor: how experienced the diagnoser is will dominate the comparison, so report who they were.
