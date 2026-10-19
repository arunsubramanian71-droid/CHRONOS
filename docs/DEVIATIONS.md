# Deviations, interpretations and open questions

This is the project's decision log for everything where the implementation **interprets**, **extends** or **departs from**
`CHRONOS System Specification v0.2.pdf`, plus the specification's own open questions. The specification says that after
confirmation a decision changes only by adding a new decision that supersedes it, with the consequences written down; the entries
below follow that rule. Nothing here changes the approved decisions D-01 to D-26: they are implemented, and where an
implementation choice was needed it is recorded here.

## Open questions (not answered, not assumed)

| Q | Question (verbatim idea) | Status |
|---|---|---|
| **Q1** | The deadline and the real weekly hours | **OPEN.** The specification estimates 35.5 joint weeks and says: default, reach Milestone S first. The user later set a *24-hour elapsed* target. That is recorded as the current implementation constraint of this conversation, **not** as an answer to Q1 (the weekly hours are still unknown). The work followed the specification's own default: the Milestone S spine (record, explain, branch, intervene, compare) was built first, and the depth items were implemented in bounded form. |
| **Q6** | What form of evaluation the institution expects | **OPEN.** The specification's default (demonstration, report and viva) was used: a runnable demonstration, a written report (`docs/`), and `docs/VIVA_GUIDE.md`. No paper. |

**Wording discrepancy in the specification.** Page 1 says decisions D-01 to D-26 were "reviewed and approved"; Appendix B marks
D-01 to D-22 "Approved" but D-23 to D-26 "New in v0.2". All four were implemented as requirements of v0.2.

## Interpretations of an underspecified rule

| ID | Specification text | What was done and why |
|---|---|---|
| DEV-01 | Event-kind rank in the tie-break key is not defined. | `FAULT 0 < CLIENT_INPUT 1 < DELIVERY 2 < TIMER 3` (`AgendaItem.Kind`). A message arriving at the same instant as a timer is delivered first. This matters for AD-4: with constant latency and a 300 ms watchdog, healing the partition 200 ms earlier makes the heartbeat arrive exactly when the watchdog is due, and delivery-before-timer is what lets it cancel the watchdog. Deterministic, documented, and tested. |
| DEV-02 | Content digest = "node, kind, canonical payload, **parent ids**, origin, state digest". | Parent ids in the digest **exclude PROGRAM_ORDER links** (`Event.contentDigest`). For node events they are implied by the per-node counter; for env events (identity-keyed, D-24) they would make every env event depend on which unrelated env events preceded it, which produces false `Changed` events and spurious causal roots. |
| DEV-03 | Phase 12a: a fault "touches" a node if the node sits on a side of an active partition that cuts it off from another node. | The OMISSION scope for partitions additionally requires that the node **has received a message from a node on the other side before** (the same test the specification prescribes for crash and pause scopes in 12b; `FaultInjector.touches`). Reason: with the literal rule, `p`'s own heartbeat timers (which wait for nothing from `b`) get OMISSION edges from the partition, which is misleading (`why` would blame the partition for a heartbeat) and creates spurious roots when comparing timelines. Open question for the authors: confirm or revert. |
| DEV-04 | "Partition ... every message between them is discarded" | Applied **at delivery time** (step-cycle step 3: the fault injector transforms the item when it is taken from the agenda), not at send time. |
| DEV-05 | Section 10 ping-pong table and the step cycle. | To reproduce the table exactly (seq 1 is `B#1`, before `A#2`), `boot()` records the `NODE_STARTED` event of every node first and only then the effects of the start handlers. `stateAfter` of `NODE_STARTED` is the state *after* `onStart` (the specification's "initial state digest"). |
| DEV-06 | FR-01 lists start, message and timer handlers. | The Node API also has `onClientInput` (the CLIENT_INPUT event needs a handler) and `onRestart` (Phase 12b recovery). |
| DEV-07 | Step cycle: events appended as one group (step 8), invariants checked (step 9). | The `INVARIANT_VIOLATED` event is appended **in the same atomic group** as the step that caused it, and is recorded when a violation *begins* (edge-triggered) so a persisting violation does not flood the log. |
| DEV-08 | `DIFFERENT_FAILURE`: "a different invariant was violated, or the violation has a different causal origin". | Simplified: a different invariant name, or a different **set of fields read** by the violated invariant. KA-4 requires `FAILURE_PERSISTS` when either of two independent partitions is removed; a causal-origin test based on the fault source would report `DIFFERENT_FAILURE` there. |
| DEV-09 | Section 20 step 8 path costs. | Ties between equally cheap paths prefer the path with **fewer PROGRAM_ORDER hops**, then the earlier parent (`CausalGraph.path`). Without this the explanation path could consist of a bare env program-order link instead of the OMISSION chain. Found by the anchor-rule test. |
| DEV-10 | D-16 anchor rule when no earlier Same event exists on the root's node. | Falls back to the earliest root of the failure timeline that reaches the failure, and says so in the report (`pathNote`). The flagship demo branches *at the partition's injection boundary* so the anchor (the partition event) exists, which is the reading of AD-5 that matches Section 20's own example. AD-4's wording "branch before the partition" and AD-5's "names the partition event" are not both achievable with an env-only intervention before the partition; this is noted, not hidden. |

## Extensions beyond the text

| ID | Extension | Reason |
|---|---|---|
| DEV-11 | **Heal-enabled FAULT edge.** A delivery of a message that was sent while a partition cut its link, and delivered only after the partition healed, gets a FAULT parent from the `FAULT_CLEARED` event. | Without it, the branch-side event "the heartbeat got through because the heal happened" has no causal link to the intervention that moved the heal, so law M10 ("every root lies in the intervention's causal future") could not hold for the flagship run. |
| DEV-12 | Env identity of repeated copies: `dup:<id>`, `dup:<id>~2`, ...; `drop:<id>~dup`, `drop:<id>~dup2`. | D-24 lists four env id forms; duplicating a message more than once (two rules, or a rule and an intervention) needs a unique identity per copy. Found by the 10,000-seed chaos soak (a real defect: duplicate ids). |
| DEV-13 | The verifier runs a replay to the same end as the original, including trailing steps that recorded nothing. | Found by the chaos soak: a restart rule for a node that was not crashed advances virtual time without an event, so comparing end states at the last event gave a false mismatch. Any event produced by a trailing step is a genuine mismatch. |
| DEV-14 | `CounterfactualEngine.findCriticalFaultSets` removes every single fault rule, then every pair, and lists the minimal critical sets. | Bounded form of Phase 14b "KA-4 reports FAILURE_PERSISTS and lists both causes". It searches over **fault rules**, not over every event of the causal past, so FR-38 (intervention search, Stretch) is still not implemented. |

## Simplifications (SIMPLIFIED in the traceability matrix)

| ID | What is simplified |
|---|---|
| DEV-15 | World images (checkpoints) are canonical JSON trees built by the runtime (`WorldImage` holds an opaque map), not typed record hierarchies. Completeness is enforced by law M5, tested at every checkpoint of every demo. |
| DEV-16 | Indexes by position, node, kind and virtual time (FR-18) are built in memory from the log when a timeline is opened (`TimelineIndex`); they are not persisted under `index/`. They are derived and rebuildable either way. |
| DEV-17 | Stepped replay with breakpoints (R3/FR-24) is available as `ReplayEngine.runUntil` and is tested, but is not exposed as a CLI command (the session offers `find`, `watch`, `diff`, `branch`). |
| DEV-18 | The HTML report draws at most the first 600 events in the SVG diagram and 3,000 table rows. |
| DEV-19 | `maxFirings` on fault rules is validated (allowed only on time-triggered rules) but time-triggered rules fire once, so it is not enforced as a counter. |
| DEV-20 | Persisted storage uses one writer thread per recording and fsync-free flushes per step group: a crashed *process* leaves a readable log (tested by killing a real process); durability against power loss is not claimed. |

## Not implemented (NOT IMPLEMENTED)

Pause/resume, clock-skew, reorder and slow-link faults; the "after named event" and "on node state" triggers; interventions that
reorder deliveries, add/remove client inputs, change configuration, reseed a stream or patch node state (the last is the only
*synthetic* one, so no synthetic timeline exists); the Pinned continuation policy (deferred by D-15); node-local replay R5
(Stretch); intervention search over the causal past FR-38 (Stretch); consistent cuts (Stretch); the live local viewer FR-50
(Stretch, D-18); the knowledge view (Could); log compression (cut line); the blind debugging protocol and the evaluation chapter
of Phase 17 (they need a second person); verification on Linux and Windows.

## Known limitations of what *is* implemented

* **Lengthening a partition.** `SHIFT_HEAL` with a positive shift keeps the partition active longer; the extra drops it causes
  are not causally linked to the intervention, so the comparator lists them as additional roots. Law M10 is therefore tested
  for shortening, dropping and delaying interventions (documented in `LawsTest`).
* **Roots from the baseline.** In a baseline-versus-branch comparison the baseline-side roots (for example the watchdog that fired
  only in the baseline) are baseline events the intervention replaced; M10's "every root lies in the intervention's causal future"
  is tested for the roots *in the branch*.
* **A baseline that stops at its first violation** (the default `stopOnViolation`) ends earlier than the branch, so the branch's
  tail appears as `Extra` roots ("one timeline ends earlier", Section 20). The demo records its baseline with `--no-stop`.
* **Wall-clock numbers** in `docs/BENCHMARKS.md` come from one machine (Apple M2 Max, JDK 21).
