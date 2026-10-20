# Evaluation

Q6 (the form of evaluation the institution expects) is **open**; the specification's default, *demonstration, report and viva*, was
assumed. This document is the evaluation part of the report: what was measured, how, and, just as important, **what it does not show**.

## What was evaluated, and what was not

| Claim | Evidence | Where |
|---|---|---|
| The engine is deterministic | 16,000 pairs of runs (8 demo scenarios x 1,000 seeds x with/without chaos plans) are byte-identical; the same hash across fresh JVMs and on Linux | `docs/BENCHMARKS.md`, `CliTest`, Linux build |
| Replay really verifies | mutated recordings are caught at exactly the mutated event; the bad citizen is caught; 1,350 chaos runs replay-verify | `LawsTest`, `AcceptanceTest`, `SoakTest` |
| The analysers find planted causes | KA-1 to KA-4 with exact expected answers | `KnownAnswerTest`, the demo |
| The analysers behave on failures **nobody planted** | the first experiment below | `chronos-bench ... eval` |
| The analysers find causes planted with **randomised** parameters | the second experiment below (500 cases) | `chronos-bench ... eval --tokens 500` |
| A developer finds bugs *faster* with CHRONOS | **not measured** | needs the blind protocol below |

## Experiment: unplanned failures found by a chaos campaign

The planted-bug library has a known weakness (Section 25, R15): a tool finds a bug it was designed around. To reduce that bias, a chaos
campaign runs RE (one backup) and RE with two backups under **randomly generated fault plans** drawn from the whole fault catalogue
(partitions, crash/restart, pauses, clock skew, slow links, reordering, probabilistic drop/delay/duplicate, and rules that fire right
after a named event), with no partition planted. For every run that ends in an invariant violation CHRONOS (1) names the first causal
*source* of the violation from `why` and (2) tests it with a **real counterfactual**: branch before that fault first fires, remove the
rule, run forward, and read the verdict.

Command: `java -jar chronos-bench/target/chronos-bench.jar eval --seeds 400` (raw output: [`evaluation-output.txt`](evaluation-output.txt)).

| Scenario | Chaos seeds | Runs that failed | Root fault named | Counterfactual: failure disappears | Failure persists |
|---|---|---|---|---|---|
| RE (client, p, b) | 400 | 30 | 28 | 22 | 6 |
| RE with two backups | 400 | 70 | 69 | 53 | 16 |
| **Total** | 800 | **100** | **97** | **75** | **22** |

Reading it:

* In **97 of 100** failures the first causal source of the violation was a *fault*. In the other 3 the first chain started from an input
  or a node start; those 3 were **not diagnosed individually**, so no claim is made about them. (Before the OMISSION scope of DEV-03 was
  closed the figure was 94: three failures were caused by a partition that began before the first contact and had no OMISSION edge.)
* In **75 of those 97** the named fault was a **but-for cause**: removing exactly that rule made the failure disappear. That is the
  strongest kind of agreement the tool can offer between its explanation and an independent experiment.
* In **22** the failure **persisted**. That is the *overdetermined* case of KA-4 (another fault in the same run also breaks the system),
  or a different path to the same invariant. The tool does not claim otherwise: `FAILURE_PERSISTS` is exactly its documented answer, and
  `chronos causes` finds the critical *combination*.
* The root kinds are diverse: partitions, crashes (two backups promote when the primary crashes), pauses (a GC-pause-like split brain),
  `after-event` rules and a slow link, i.e. not only the partition the example was designed around.

Samples from the output: seed 5 (RE) root `fault:c1-partition#1`, removal makes the failure disappear; seed 93 root `fault:c1-pause#1`
(a paused primary), disappears; re2 seed 8 root `fault:c3-crash#1`, disappears; re2 seed 7 root `fault:c2-pause-after#1`, persists.

## Experiment: randomised planted causes (the blind kit's cases), diagnosed from the recording alone

The kit behind the blind protocol (`docs/BLIND_PROTOCOL.md`) builds cases from a token: five kinds of planted cause with randomised
rule names, times and delays, under the neutral scenario name `mystery`. CHRONOS is given only the recording (not the plan) and asked
three questions: which fault rules do the certain causal chains of the violation start from (`why`); which fault rules does `why`
name once its *possible silence* section is added; and what is the minimal set of fault rules whose removal makes the failure
disappear, found by real counterfactual branches (`causes`)? Score: exact match with the planted set. 500 tokens, 100 of each kind
(`eval --tokens 500`, mean diagnosis time 4.6 ms; raw output in `evaluation-output.txt`).

| Kind of planted cause | `why`: chain sources | `why` + possible silence | `causes`: minimal critical set |
|---|---|---|---|
| split brain: a partition longer than a watchdog | 100 / 100 | 100 / 100 | 100 / 100 |
| lost update: a replication message delayed past its successor | 100 / 100 | 100 / 100 | 100 / 100 |
| blocked commit: a coordinator crash between prepare and decision | 100 / 100 | 100 / 100 | 100 / 100 |
| overdetermined: two independent partitions, each sufficient | **0 / 100** | 98 / 100 | 100 / 100 |
| a partition between a sender and a receiver (the tutorial) | **0 / 100** | 100 / 100 | 100 / 100 |
| **all** | **300 / 500** | **498 / 500** | **500 / 500** |

What the 0 / 100 cells of the first column meant, and what was done about it (both are limits of the *method*, found by running this
experiment, and consistent with the specification's own rules):

* **Silence with no timer leaves no trace.** In the tutorial case the receiver simply never gets two ticks, and nothing in the system
  waits for them with a timer, so no OMISSION edge exists (D-23 links a fault only to a *timer firing* that noticed the silence). The
  partition is not in the causal past of the violation at all, so the certain chains cannot name it. After seeing this the `why`
  output gained a clearly labelled **possible silence** section (`TimeTravelSession.silence`): for each node the invariant read, the
  messages dropped on their way to it after the last write the violation depends on, with the fault that dropped each. It adds no edge and
  claims nothing certain. The second column is the result with it (the hint was designed after the first run of the experiment, so
  this column is not an independent test of it).
* **An overdetermined failure has more than one cause, but a violation event has one story.** The *first* violation is already
  explained by one partition (its chain is exact for that event); that the failure would have happened anyway through the other
  partition is only visible by experiment (KA-4: `FAILURE_PERSISTS` for each removal alone). The hint recovers 98 of 100; in the other two
  the second partition dropped nothing addressed to a node the invariant read before the violation.

So the right reading is: `why` is a precise explanation of *what happened*, the silence hint is a lead, and `causes` is the test of
*what was necessary*, which reached 500 / 500 here without any hint.

## Experiment: systematic exploration against random sampling at the same budget

The three experiments above are random (chaos plans, randomised planted causes). This one separates *systematic* from random.
`chronos explore` (DEV-44) enumerates every subset of at most two faults from a generated candidate space (a crash and a pause of every node,
a partition of every communicating pair, a drop of the first two messages of every observed link, at 30, 100 and 300 ms), runs each
deterministically and reports the **minimal** violating schedules; every one of them was then re-verified independently (it violates, and
no proper subset does). A random baseline draws the same number of schedules from the same space (fixed seed) and counts how many of the
minimal sets it happened to run.

| Scenario (candidates) | schedules in the bound | executed | duplicate traces | pruned supersets | minimal violating sets (re-verified) | random sampling, same budget, hit |
|---|---|---|---|---|---|---|
| RE, no planted partition (34) | 596 | 531 | 39 | 65 | **7 of 7 minimal** | 2 of 7 |
| RE with two backups (47) | 1,129 | 828 | 39 | 301 | **17 of 17 minimal** | 9 of 17 |
| KA-2 lost update, no planted delay (30) | 466 | 409 | 26 | 57 | **3 of 3 minimal** | 3 of 3 |
| KA-3 blocked commit, no planted crash (40) | 821 | 476 | 27 | 345 | **12 of 12 minimal** | 10 of 12 |
| KA-0 control (17) | 154 | 154 | 19 | 0 | **0 violations: the whole bound is clean** | 0 of 0 |

What it shows: exhaustiveness *within the bound* (the explorer ran the whole space; random sampling at the same cost missed 5 of 7 minimal
causes in RE) and a statement random testing cannot make (KA-0: none of 154 schedules violates). What it does not show: anything outside the
bound (other fault kinds, larger subsets, other times, other seeds, message interleavings the simulator does not vary), or that the
candidate space was the right one. The explorer is deterministic: the same configuration gives a byte-identical report (`SystematicExplorerTest`).
Regenerate everything with `tools/regenerate-results.sh`.

## NFR-15 dry run by the author (not the specified evidence)

The specification asks that "a new team member can run the flagship demo from the README: under 10 minutes, three commands". The only valid
evidence is a new person (`docs/HUMAN_STUDIES.md`, study A; **not run**). As a measurement of the *machine* part of that requirement the author
timed the three README commands (`export JAVA_HOME=...`, `mvn -ntp clean package`, `java -jar chronos-cli/target/chronos.jar --data chronos-data demo`) on
a clean `git archive` export of commit `9e89ed5` with an **empty Maven repository** (`-Dmaven.repo.local=/tmp/m2-empty`, so every dependency and plugin, 35 MB, was
downloaded from Maven Central): build and 235 tests **79 s**, demonstration **1 s**, 80 s in total, exit codes 0, "24 of 24 checks passed". This is
the floor: a person also has to find the README, install JDK 21 and Maven, and read.

## Acceptance demonstrations: the time budget (benchmark B14, Section 26)

AD-1 to AD-6 and the known-answer scenarios, 24 self-checks, run in **0.48 s** from a cold JVM (`chronos demo`, macOS) and in 0.50 s in the Linux container;
the guided tour of `tools/professor-demo.sh` (11 steps including a full-store replay) takes 3.2 s. The target is under 10 minutes in total.

## Limits of this evaluation (honest)

* **No human participant has been involved in any experiment here** (NFR-15 and the blind protocol are not run: `docs/HUMAN_STUDIES.md`).
* **Not blind.** The same author wrote the scenarios, the fault generator, the case kit and the analysers (this applies to both experiments). The blind debugging protocol of Section 25
  (one author plants a bug without telling the other, who must find it using only CHRONOS, recording time and confusion) **was not run**:
  it needs a second person. This is the main missing piece of Phase 17.
* **Agreement with a counterfactual is not ground truth.** Both the explanation (`why`) and the experiment (the counterfactual) are
  computed by CHRONOS from the same model. They are independent *mechanisms* (a graph walk versus re-execution), so their agreement
  is real evidence of internal consistency, but a systematic modelling error would affect both.
* **Evidence about the model, not reality** (Section 19): the simulated world follows CHRONOS's rules.
* **Small scenarios.** RE has three or four nodes and a few dozen events per run; the million-event runs measure scale, not diagnosis.
* **The 22 persisting cases were not diagnosed individually.** They are reported as the tool's own verdict.

## What a fuller evaluation would add

1. The blind protocol with a second person on at least five bugs of increasing difficulty, recording time to diagnosis and wrong turns.
2. Applications that were *not* written by the tool's authors (against the Node API), so planted-bug bias disappears.
3. A manual diagnosis of a sample of the 22 `FAILURE_PERSISTS` cases, to check whether `causes` finds the right combinations.
