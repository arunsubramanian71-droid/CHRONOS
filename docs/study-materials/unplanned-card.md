# Study B, part 2: failures nobody planned, diagnosed by hand and with the tool

**Protocol (verbatim idea, Section 25):** "We also run campaigns on scenarios to find failures we did not plan, and diagnose them by hand and with the tool to compare answers."

## Material
`tools/study-kit.sh unplanned-prepare DIR` runs a chaos campaign of the replicated store RE (`campaign re --seeds 1-400 --chaos`), takes the first five seeds that fail, and writes
* `DIR/tool/caseN/` : a data directory for the **with-CHRONOS** arm,
* `DIR/hand/caseN.log` : the raw event log (JSON lines) for the **by-hand** arm; the diagnoser gets this file, a text editor or `grep`, and the description of RE in `README.md`/`docs/USER_GUIDE.md`, and no `chronos` command,
* `DIR/key/` : the reference (CHRONOS's own `causes` and `minimize` results, i.e. a real counterfactual). Never shown to a diagnoser.

## Procedure
For each case, one diagnoser works by hand (30 minutes) and one (or the same person on a different case) works with the tool (30 minutes). Each writes the fault rule id(s) they believe caused the failure and the minutes taken. Alternate which cases go to which arm.

## Record (`docs/study-results/unplanned.csv`)
`case,seed,diagnoser,method (tool|hand),minutes,answer_rule_ids,matches_reference (yes|no),confusions`. Compare the two answers for each case and each with the reference; report agreement and disagreements case by case, including the cases where the tool and the reference disagree.
