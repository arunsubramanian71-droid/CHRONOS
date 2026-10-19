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
| RE (client, p, b) | 400 | 30 | 26 | 21 | 5 |
| RE with two backups | 400 | 70 | 68 | 52 | 16 |
| **Total** | 800 | **100** | **94** | **73** | **21** |

Reading it:

* In **94 of 100** failures the first causal source of the violation was a *fault*. In the other 6 the first chain started from an input
  or a node start; those 6 were **not diagnosed individually**, so no claim is made about them.
* In **73 of those 94** the named fault was a **but-for cause**: removing exactly that rule made the failure disappear. That is the
  strongest kind of agreement the tool can offer between its explanation and an independent experiment.
* In **21** the failure **persisted**. That is the *overdetermined* case of KA-4 (another fault in the same run also breaks the system),
  or a different path to the same invariant. The tool does not claim otherwise: `FAILURE_PERSISTS` is exactly its documented answer, and
  `chronos causes` finds the critical *combination*.
* The root kinds are diverse: partitions, crashes (two backups promote when the primary crashes), pauses (a GC-pause-like split brain),
  `after-event` rules and a slow link, i.e. not only the partition the example was designed around.

Samples from the output: seed 5 (RE) root `fault:c1-partition#1`, removal makes the failure disappear; seed 93 root `fault:c1-pause#1`
(a paused primary), disappears; re2 seed 8 root `fault:c3-crash#1`, disappears; re2 seed 7 root `fault:c2-pause-after#1`, persists.

## Experiment: randomised planted causes (the blind kit's cases), diagnosed from the recording alone

The kit behind the blind protocol (`docs/BLIND_PROTOCOL.md`) builds cases from a token: five kinds of planted cause with randomised
rule names, times and delays, under the neutral scenario name `mystery`. CHRONOS is given only the recording (not the plan) and asked
two questions: which fault rules do the causal chains of the violation start from (`why`), and what is the minimal set of fault rules
whose removal makes the failure disappear, found by real counterfactual branches (`causes`)? Score: exact match with the planted set.
500 tokens, 100 of each kind (`eval --tokens 500`, mean diagnosis time 4.7 ms).

| Kind of planted cause | `why`: chain sources = planted set | `causes`: minimal critical set = planted set |
|---|---|---|
| split brain: a partition longer than a watchdog | 100 / 100 | 100 / 100 |
| lost update: a replication message delayed past its successor | 100 / 100 | 100 / 100 |
| blocked commit: a coordinator crash between prepare and decision | 100 / 100 | 100 / 100 |
| overdetermined: two independent partitions, each sufficient | **0 / 100** | 100 / 100 |
| a partition between a sender and a receiver (the tutorial) | **0 / 100** | 100 / 100 |
| **all** | **300 / 500** | **500 / 500** |

What the failures of `why` mean (both are limits of the *method*, found by this experiment, and both are consistent with the
specification's own rules):

* **Silence with no timer leaves no trace.** In the tutorial case the receiver simply never gets two ticks, and nothing in the system
  waits for them with a timer, so no OMISSION edge exists (D-23 links a fault only to a *timer firing* that noticed the silence). The
  partition is not in the causal past of the violation at all, so `why` cannot name it; the counterfactual search finds it every time.
  This is why the tool has `branch`, `search` and `causes` as well as `why`, and the user guide says so.
* **An overdetermined failure has more than one cause, but a violation event has one story.** The *first* violation is already
  explained by one partition (its chain is exact for that event); that the failure would have happened anyway through the other
  partition is only visible by experiment (KA-4: `FAILURE_PERSISTS` for each removal alone).

So the right reading is: `why` is a precise explanation of *what happened*; `causes` is the test of *what was necessary*; neither
replaces the other, and the second is what reached 500 / 500 here.

## Limits of this evaluation (honest)

* **Not blind.** The same author wrote the scenarios, the fault generator, the case kit and the analysers (this applies to both experiments). The blind debugging protocol of Section 25
  (one author plants a bug without telling the other, who must find it using only CHRONOS, recording time and confusion) **was not run**:
  it needs a second person. This is the main missing piece of Phase 17.
* **Agreement with a counterfactual is not ground truth.** Both the explanation (`why`) and the experiment (the counterfactual) are
  computed by CHRONOS from the same model. They are independent *mechanisms* (a graph walk versus re-execution), so their agreement
  is real evidence of internal consistency, but a systematic modelling error would affect both.
* **Evidence about the model, not reality** (Section 19): the simulated world follows CHRONOS's rules.
* **Small scenarios.** RE has three or four nodes and a few dozen events per run; the million-event runs measure scale, not diagnosis.
* **The 21 persisting cases were not diagnosed individually.** They are reported as the tool's own verdict.

## What a fuller evaluation would add

1. The blind protocol with a second person on at least five bugs of increasing difficulty, recording time to diagnosis and wrong turns.
2. Applications that were *not* written by the tool's authors (against the Node API), so planted-bug bias disappears.
3. A manual diagnosis of a sample of the 21 `FAILURE_PERSISTS` cases, to check whether `causes` finds the right combinations.
