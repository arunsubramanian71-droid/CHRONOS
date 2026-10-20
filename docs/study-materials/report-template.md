# Evaluation chapter: human studies (template)

*Fill this in only from real sessions. Every figure must come from `tools/study_analyse.py` run on the sheets in `docs/study-results/`. Delete any section whose study was not run and say so in "Not run".*

## 1. What was done
* Dates, facilitator(s), commit of the repository used (`session.env`), operating systems, JDK and Maven versions.
* Study A (NFR-15): number of participants n = __ ; eligibility as in `docs/study-materials/nfr15-participant-card.md`.
* Study B (blind debugging): number of sessions n = __ , debuggers = __ , case kinds covered = __ ; unplanned-failure cases = __ .

## 2. Study A results (NFR-15: under 10 minutes, three commands)
Table: participant, OS, commands typed, elapsed time, demo result read out, PASS/FAIL (from `study_analyse.py nfr15`). Then: every point of hesitation or error, every README sentence that confused anyone, and what was changed in the README because of it (with the commit).

## 3. Study B results
* Table of sessions (from `study_analyse.py blind`): case kind, correct or not, minutes, first decisive command, what was confusing.
* Unplanned failures (from `study_analyse.py unplanned`): per case the by-hand answer, the with-tool answer and the reference; agreement and every disagreement explained.
* The three most frequent confusions and the change made to the output or the guide because of each.

## 4. What the studies can and cannot show
n is small; participants were (describe: students, colleagues, the author's friends); the kit plants causes in the fault plan only, not bugs in application code; the debugger knew it was a study; times include the participant's reading. State which claims the data supports ("the tool led to the right diagnosis in x of n sessions") and which it does not (any rate or comparison of significance).

## 5. Not run
List every study or part that was not run, and why.

## 6. Disposition of the requirements
NFR-15: PASS / FAIL / NOT RUN (with n). Blind protocol: RUN (n sessions) / NOT RUN. Update `tools/completion_matrix.py` OVERRIDES and regenerate `docs/COMPLETION_MATRIX.md` only when a study has really been run.
