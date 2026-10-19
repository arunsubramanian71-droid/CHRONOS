# The blind debugging protocol (Section 25) and the kit that supports it

**Status: the kit is built and tested; no blind session with a second person has been run.** The protocol needs two people, so the
evidence it is meant to produce does not exist yet. `EVALUATION.md` evaluates the analysers on 800 unplanned chaos runs instead and says
what that does not show. Until real sessions are recorded below, the "blind" evaluation is **NOT DONE**.

## Why

Planted bugs bias tests: they are found because they were planted (risk R15). The protocol lets one author plant a cause the other does
not know, and the other must find it using only CHRONOS. We record whether the tool led to the right diagnosis, how long it took, and what
was confusing.

## Roles

* **Preparer**: runs `blind prepare`, hands over the data directory, runs `blind grade` at the end. Must not tell the debugger anything.
* **Debugger**: uses only the `chronos` command line (inspect, session, branch, compare, search, causes, report, serve) on the data
  directory. Must not read the demo sources or run `blind grade` before answering (honour system: the token in the header determines the
  case, so the grader can rebuild it).

## Procedure

```bash
# Preparer (choose any hexadecimal token; 5 kinds of case exist and the token's remainder picks one):
chronos --data cases/session1 blind prepare --token 9f3a21

# Debugger (starts the timer, diagnoses, answers):
chronos --data cases/session1 blind begin
chronos --data cases/session1 show T0                                   # ... any CHRONOS commands ...
chronos --data cases/session1 blind answer --root RULEID [--root RULEID] --notes "what was confusing"

# Preparer, afterwards (exit code 0 = correct):
chronos --data cases/session1 blind grade
```

An answer names the fault rule(s) that cause the failure: a rule id (as printed in `fault coverage` and in event ids such as
`fault:f91c-cut#1`). The grade is CORRECT only if the answer names exactly the planted set (an overdetermined case needs both causes).
`blind.json` in the data directory keeps the answer, the notes, the time and the grade.

## What the cases are

Five kinds, built from the known-answer scenarios but called `mystery` with randomised rule names, times and delays so that a remembered
answer does not help: a partition longer than a watchdog (split brain), a delayed replication message past its successor (lost update), a
coordinator crash between prepare and decision (blocked participant), two independent partitions (overdetermined), and a partition between a
sender and a receiver. `BlindKitTest` checks, for 60 tokens, that every case fails, replays, and that removing the planted cause by a real
counterfactual makes the failure disappear (and removing one of two causes does not).

## What the kit does not do

It cannot supply the second person, and it plants only causes in the fault plan, not bugs in application code (a bug seeded in code, as
the specification describes, still needs one author to write it). The honour system applies. Results from sessions should be added here:

| Date | Debugger | Token (kind revealed after) | Correct | Time | What was confusing |
|---|---|---|---|---|---|
| *(no sessions recorded yet)* | | | | | |
