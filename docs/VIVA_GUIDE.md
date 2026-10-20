# Explaining CHRONOS: viva and demonstration guide

The specification (Section 4) asks both authors to be able to explain, without notes: Lamport and vector clocks, happens-before,
consistent cuts, event sourcing, deterministic replay, why identical code can behave differently, and what a counterfactual can
and cannot prove. This guide maps each to the code and to a command that shows it. (Q6: the official rubric is unconfirmed; the default evaluation form of
the specification, demonstration + report + viva, is the working assumption.)

## A 10-minute demonstration script

Run `java -jar chronos-cli/target/chronos.jar --data demo-data demo` once beforehand and keep the output. Then, live:

| Minute | Say | Command |
|---|---|---|
| 0-1 | "Distributed bugs are hard because nothing is repeatable. CHRONOS owns time, network, scheduling and randomness, so every run is data." | `chronos scenarios` |
| 1-3 | "Here is RE failing: a 400 ms partition makes the backup promote itself, so two primaries exist. CHRONOS names the chain from the partition to the violation." (AD-1) | `chronos run re --seed 42 --no-stop` |
| 3-4 | "The recording is a log; replay re-executes the application and checks every event. A node that reads the system clock is caught at its first mismatching event." (AD-2) | `chronos verify T0` then `chronos run bad` and `chronos verify T1` |
| 4-5 | "We can look at any past moment without running code, and step backwards." (AD-3) | `chronos inspect T0 --goto violation --world` then `--back 3` |
| 5-7 | "What if the partition had healed 200 ms earlier? CHRONOS branches, changes one thing, runs forward." (AD-4) | `chronos branch T0 --heal-earlier 200` |
| 7-9 | "Where did the two executions first part ways, and why does that matter?" (AD-5) | `chronos compare T0 T0.1` |
| 9-10 | "Everything in one self-contained page." (AD-6) | `chronos report T0 --against T0.1` then `open chronos-data/reports/T0-vs-T0.1.html` |

## The concepts, and where to point

**Virtual time, logical time and log position are three different things.** Virtual time (ms) says when something is scheduled in
the simulation; Lamport and vector clocks say what could have influenced what; the sequence number is where the recorder put the
event. `Event` stores all three. *Show:* `chronos show T0` (columns t, Lamport, vector, seq).

**Lamport clock.** One counter per node; local event and send: +1; receive: max(own, message) + 1. Guarantee: if X could influence Y
then L(X) < L(Y); not the reverse. *Code:* `ClockService.stamp`. *Test:* `ClockServiceTest.lamportMisleadsWithoutMessages` (a smaller
number does not prove influence).

**Vector clock.** One counter per node plus one for `env`, kept as a list; receive = slot-wise maximum, then increment own slot;
`V1 < V2` iff every slot is <= and they differ; otherwise concurrent. Exact for happens-before. *Code:* `VectorClock.compare`.
*Test:* `ClockServiceTest.worldExampleFromSection11` (the specification's worked example, verbatim).

**Happens-before and the causal graph.** X happened before Y if information could travel from X to Y through a node's memory
(program order) or through a message; the environment adds FAULT, WITNESS and OMISSION links. The graph is acyclic by construction
because every parent has a smaller log position. *Code:* `CausalGraph`; *self-check:* vector order equals graph reachability
(`CausalGraph.selfCheck`, law M3/M4 on every test run).

**OMISSION: when the cause is a message that never came.** A dropped message creates no MESSAGE edge, so without help nothing would
link the partition to b's watchdog. The OMISSION edge from the fault to the timer firing does. *Show:* `chronos inspect T0 --goto
violation --why`.

**Consistent cuts.** A cut is consistent if every receive in it has its send in it. Every prefix of the CHRONOS log is a consistent
cut because parents precede children, and the engine owns the only thread, so it can snapshot the whole world between steps without
Chandy-Lamport markers. *Code:* `Engine.captureImage`; *test:* law M5, `EngineTest.checkpointRestoreThenRunEquals...`.

**Event sourcing.** The log is the source of truth; node states, graphs and timelines are derived. *Show:* `TimeTravelSession`
reconstructs state, in-flight messages and timers from the log only (law M2, `LawsTest.m2_...`).

**Deterministic replay.** An execution is a function of code, configuration, inputs and the environment's decisions (delays, drops,
random values, time). CHRONOS records the decisions inside the events they influence and replays them through the *same* engine
(D-19), comparing each regenerated event. *Code:* `ReplayEngine`, `VerifyingSink`. *Show:* AD-2.

**Why identical code can behave differently.** Because of uncontrolled nondeterminism: the clock, the network, the scheduler, random
numbers, hash order, threads. The determinism contract (Section 9) bans each; the build scans for it (`BuildRulesTest`), and the bad
citizen proves the verifier really catches a violation (`chronos run bad`, `chronos verify T1`).

**Identity-keyed randomness and butterfly noise.** The latency of message `p#17` is a hash of (seed, `p#17`), not the next value of a
shared stream, so an alternative timeline that differs earlier does not shift every later value. This is what makes the *locality
law* M8 true: nodes with no causal link to an intervention behave identically in the branch (`LawsTest.m8_...`).

**What a counterfactual can and cannot prove.** It runs the changed world forward under CHRONOS's rules, so it is evidence about
the *model*, not proof about reality; the closest alternative world is defined by "same seed, identity-keyed randomness"; and a
but-for test misses overdetermined failures (KA-4: removing either of two independent partitions leaves the failure; only removing
both removes it, which `chronos causes` finds). *Show:* `chronos run re2` then `chronos causes T0`.

**First causal divergence.** Comparing two timelines by log position is arbitrary for concurrent events. CHRONOS aligns by event
identity, computes *taint* separately inside each timeline (an event is a root if it differs and none of its parents is tainted),
orders roots by virtual time, node order (env last) and event id, and never compares Lamport or vector values across timelines.
*Code:* `TimelineComparator`; *test:* `ComparatorTest` (two concurrent roots; the anchor rule).

## Likely questions

* *Why a simulation instead of tracing real Java programs?* Decision D-01: replaying arbitrary Java needs control over threads, clocks,
  I/O and native code; applications are written against the small Node API instead.
* *What is not implemented?* See `docs/COMPLETION_MATRIX.md` (generated) and `docs/DEVIATIONS.md`: the blind evaluation and the new-user run need real
  participants (BLOCKED BY EXTERNAL INPUT); per-vector delta compression is conditional and its trigger is not met (B3b). (The
  Stretch items are done, and persisted indexes, opt-in compression and the opt-in Pinned policy too: on-node-state trigger, intervention search, consistent cuts, knowledge view, node-local replay, live viewer.)
* *What does intervention search do, and what can it miss?* `chronos search T0` takes the causal past of the first violation, turns each
  changeable event in it (a message, a scripted input, a fault rule) into a real counterfactual branch (drop, remove input, remove fault)
  and ranks the ones that make the failure disappear by causal distance. It tries one change at a time, so a failure that needs two
  changes (KA-4) shows up under `chronos causes` instead (DEV-30).
* *What is node-local replay good for?* `chronos local-replay T0 b` re-runs one node alone from its recorded inputs and checks every send,
  timer, random draw and post-step state digest. It localises a bug to one node's logic in milliseconds, and it also catches hidden
  nondeterminism (the bad citizen fails it). It cannot check what the world did around the node (DEV-32).
* *What is the knowledge view?* `chronos inspect T0 --knows b#12`: for each other node, what b had last heard and how stale that is, with the
  state b would believe next to the actual state. It makes "b promoted itself because it had not heard from p" visible (DEV-31).
* *Did the tests find real bugs?* Yes, four: the chaos soak found a duplicate-identity collision for repeated duplicates and a verifier
  false mismatch on trailing silent steps (`docs/DEVIATIONS.md` DEV-12, DEV-13); testing replay from every checkpoint found that a
  checkpoint did not remember its run had stopped (DEV-21); the comparator tests led to the path tie-break rule (DEV-09).
* *How is the first causal divergence kept honest when node ids are per-node counters?* A missing event on a node's lane renumbers everything
  after it, which used to show up as extra "roots". The comparator now reports such events as **displaced**, with the reason: renumbered (same kind and
  payload elsewhere on the lane), absence (the event it replaces depended on a message or fault this timeline does not reproduce) or silence
  (a divergent drop to that node). Absence and silence are labelled potential. The engine, for its part, links what an intervention changes to the
  intervention itself: ghost rules for removed faults, lane marks for removed inputs, moved fault windows, patched configuration, reseeded streams
  (DEV-36, DEV-43). Law M10 then holds in every case of a 120-intervention random sweep; a 30,000-seed run (66,427 comparisons) leaves none (DEV-47).
* *How do you know the stored data is intact?* Every step group has a commit line with a SHA-256 of its events; a torn tail is dropped, anything else
  refuses to load. `chronos check` goes further (clocks against the graph, lineage, summary, index, checkpoints, a full replay) and tells the user the
  action for each problem. It cannot detect a rewrite that fixes up all the checksums and the summary as well; replay against the code catches that,
  and the fuzzers (`CorruptionFuzzTest`) show that random damage is detected or leaves a lossless prefix.
* *What does `minimize` guarantee?* The plan it returns violates the same invariant, and re-running it without any single kept rule does not (the report
  prints those runs). It is delta debugging (ddmin) plus a binary search on `duration`/`by`, so it is 1-minimal, not the minimum-size plan, and a
  parameter found by binary search is the smallest value it *tried* that failed. It needs no instrumentation because every re-run is deterministic.
* *Is the explorer a model checker?* No. `chronos explore` runs every subset of at most K declared faults for one seed, so "no violation" means "none
  in this bound"; it varies fault schedules, not message interleavings. It beats random sampling at the same budget in coverage of minimal causes
  (RE: 7 of 7 against 2 of 7) and can say "this whole space is clean" (KA-0), which random testing cannot (`docs/EVALUATION.md`).
* *What does an incident bundle protect against?* Damage, tampering and false claims: every file has a SHA-256, the log has its own commit checksums,
  and the causal evidence is recomputed from the events, so a bundle whose story does not match its log is rejected before anything is written.
* *Does `why` always name the fault?* No, and the evaluation shows it: a fault is linked only to timers that noticed its silence, so a failure caused by a message that never arrived and that nothing was waiting for has no fault in its causal past (the tutorial case: chains 0/100, `causes` 100/100 on randomised cases). `why` therefore also prints a labelled "possible silence" hint (98-100/100 with it). Explanation says what happened; counterfactuals say what was necessary (`docs/EVALUATION.md`).
* *How do you know the explanations are right on bugs you did not plant?* `docs/EVALUATION.md`: 800 chaos runs, 100 failures, a root fault
  named in 97, and for 75 of them removing exactly that fault by a real counterfactual made the failure disappear; and what that does
  not show (it is not blind).
* *Are the benchmark numbers trustworthy?* They were measured on one machine with the method of Section 26 (`docs/BENCHMARKS.md`).
