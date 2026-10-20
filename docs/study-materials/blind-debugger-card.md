# Study B: card for the debugger (blind debugging protocol, specification Section 25)

**Protocol (verbatim idea):** one author seeds a bug in a scenario without telling the other, and the other must find the cause using only CHRONOS.
We record whether the tool led to the right diagnosis, how long it took, and what was confusing.

## Who may take part
* The **debugger** has not seen the case, its token, the preparer's key, the demo sources or `blind grade`. They have done the README demo and read `docs/USER_GUIDE.md` (record the preparation time separately).
* The **preparer** (may be the author) runs `tools/study-kit.sh blind-prepare` and keeps `DIR/key` private. The preparer must not hint.

## Give the debugger
Only `DIR/sessions/sN` (one session at a time), the user guide, `chronos help` and the compiled jar. Nothing from `DIR/key`.

## Say exactly this
"Somewhere in this recording a fault caused a failure. You may use any `chronos` command and the user guide, but not the source code of the demo
applications and not `blind grade`. Start with `chronos --data DIR/sessions/sN blind begin`. When you are ready, name the fault rule(s) you believe
caused it with `chronos --data DIR/sessions/sN blind answer --root RULE [--root RULE] --notes "what confused me"`. Say aloud what confuses you.
You have 30 minutes; if you reach the limit, answer with your best guess and say so."

## Success criteria
A session is CORRECT only if `blind grade` exits 0, i.e. the answer names exactly the planted set of fault rules (the overdetermined case has two). Time = from `blind begin` to `blind answer`, recorded by the kit.

## Record (facilitator)
For each session: debugger initials, prior CHRONOS experience (none / some), number of `chronos` commands used, the first command that gave the decisive clue, what was confusing, whether a different output would have helped. Then `tools/study-kit.sh blind-grade DIR` writes `DIR/blind-results.csv`; copy those columns and these notes into `docs/study-results/blind.csv` (header in `blind-template.csv`).

## Number of sessions
At least five (the kit prepares one of each case kind). Two different debuggers are better than one. State n next to every claim: five sessions can show the tool *can* lead to the right diagnosis, not a success rate.
