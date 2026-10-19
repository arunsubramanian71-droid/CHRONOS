# Benchmarks and soak results

Everything in this file was **measured by running the harness**; nothing is estimated. Numbers come from one machine and are
reproducible in method, not in absolute value on other hardware. Raw data: [`benchmark-results.csv`](benchmark-results.csv)
(harness) and [`jmh-results.txt`](jmh-results.txt) (JMH). This file is generated: `python3 chronos-bench/tools/benchmarks_md.py`.

## Environment and method

* processor Apple M2 Max (12 cores), max heap 8192 MiB, Mac OS X 27.2 aarch64, JDK 21.0.12.1 OpenJDK 64-Bit Server VM
* Commands (run with `JAVA_HOME` pointing at JDK 21):

```bash
java -jar chronos-bench/target/chronos-bench.jar all --csv docs/benchmark-results.csv     # B1-B14, XL, soak, NFR-01 (about 2 min)
java -jar chronos-bench/target/chronos-bench.jar jmh -rf text -rff docs/jmh-results.txt    # JMH micro-benchmarks (about 2 min)
java -jar chronos-bench/target/chronos-bench.jar nfr01 --seeds 1000                        # NFR-01 alone
java -jar chronos-bench/target/chronos-bench.jar eval --seeds 400                          # the unplanned-failure evaluation (docs/EVALUATION.md)
```

* Method (Section 26): each benchmark is a question; fixed seeds and workloads (the synthetic *gossip* scenario scales events and
  nodes freely); five repetitions with the **first discarded** for warm-up; the table shows **median / p95 / p99** (with four
  retained samples p95 and p99 equal the maximum). JMH is used for the small mechanisms (B3, B4).
* An earlier full run happened to use the wrong JDK (26); it was discarded and the harness rerun explicitly on JDK 21. The
  environment line above is printed by the harness itself.
* Two optimisations were made *because* of these measurements: the verifier compares whole events first and only builds a field-by-field
  diff on a mismatch, and a replay no longer hashes the events it regenerates; `world()` starts from the nearest checkpoint.
  Replay was 1.9-2.2x the recording time at 10,000 events before them.

## Results against the targets of Section 8

| NFR | Target | Measured | Verdict |
|---|---|---|---|
| NFR-01 | byte-identical logs, 100% over 1,000 seeded runs of each demo | **16 scenario/plan combinations x 1000 seeds = 16,000 pairs, all identical** (each seed run twice on different thread counts); also identical across fresh JVMs and on Linux | **met** |
| NFR-05 | >= 50,000 events/s in memory | 339,232 events/s (recorder off: 396,651) | **met** |
| NFR-05 | >= 20,000 events/s persisted | 302,918 events/s through the real asynchronous store, checkpoints included (synchronous disk: 195,056) | **met** |
| NFR-06 | seek < 1 s at 100,000 events | worst of 100 jumps including `world()`: 0.426 ms median / 17.8 ms p99 at 100,733 events; opening the session 42.3 ms | **met** |
| NFR-06 | seek < 5 s at 1,000,000 events | 1.275 ms at 1,018,235 events; session open 381.1 ms | **met** |
| NFR-07 | replay within 2x of the original | 1.22x at 10,085 events, 1.15x at 100,733 events, 1.07x at 1,018,235 events | **met at every size measured** |
| NFR-08 | branch cost independent of prefix length | branch creation 28-32 ms at prefixes of 10k, 50k and 90k events of a 100,733-event timeline; the branch stores ~80 KiB against the parent's 30,250 KiB | **met** |
| NFR-09 | 3-20 nodes, ~1,000,000 events | 1,018,235 events, 10 nodes recorded, graph-built, sought in and replay-verified | **met** (20 nodes: only the vector-clock cost B3 was measured) |
| NFR-10 | measure and report bytes per event | 275 bytes/event with state records (265 without); in memory ~912 bytes/event | measured and reported (the initial expectation, < 300 bytes without states, is met) |

## Harness results (JDK 21)

| ID | Variant | Metric | median / p95 / p99 | Note |
|---|---|---|---|---|
| B1 | gossip nodes=3 ~100000 events | steps/s | 119770 / 123881 / 123881 steps/s | baseline only |
| B1 | gossip nodes=5 ~100000 events | steps/s | 124613 / 124989 / 124989 steps/s | baseline only |
| B1 | gossip nodes=10 ~100000 events | steps/s | 121289 / 122300 / 122300 steps/s | baseline only |
| B2 | recorder off (null sink) | events/s | 396651 / 398452 / 398452 events/s | target NFR-05: >= 50,000 in memory |
| B2 | in memory | events/s | 339232 / 374905 / 374905 events/s | meets NFR-05 (>= 50,000/s); overhead x1.169 |
| B2 | synchronous disk | events/s | 195056 / 197374 / 197374 events/s | overhead x2.034 |
| B2 | asynchronous disk (the real store) | events/s | 302918 / 320543 / 320543 events/s | meets NFR-05 (>= 20,000/s persisted, incl. checkpoints); overhead x1.309 |
| B3 | nodes=3 | ns per receive | 71.6 / 82.6 / 82.6 ns/op | event line 125 bytes; informs compression |
| B3 | nodes=10 | ns per receive | 98.9 / 99.4 / 99.4 ns/op | event line 139 bytes; informs compression |
| B3 | nodes=20 | ns per receive | 94.9 / 96.5 / 96.5 ns/op | event line 158 bytes; informs compression |
| B3 | nodes=50 | ns per receive | 215.9 / 222.1 / 222.1 ns/op | event line 218 bytes; informs compression |
| B3 | nodes=100 | ns per receive | 427.0 / 436.3 / 436.3 ns/op | event line 318 bytes; informs compression |
| B4 | 100733 events, 23% carry a state record | microseconds/event | 1.250 / 1.270 / 1.270 us/event | bytes/event 275 |
| B12 | with full state records | bytes/event | 275 bytes | 275 MB per million events (NFR-10: measured and reported, initial expectation < 300 bytes without states) |
| B12 | digests only, no state records | bytes/event | 265 bytes |  |
| B5 | 10085 events | build | 1.488 / 1.695 / 1.695 ms | 20160 edges (1.999 per event) |
| B5 | 10085 events | happens-before | 283.4 / 370.6 / 370.6 ns/query | vector clocks, cost ~ node count |
| B5 | 10085 events | causal past (last) | 0.189 / 0.292 / 0.292 ms/query | walks parents backwards |
| B6 | 10085 events | worst of 100 jumps + world | 0.333 / 0.962 / 0.962 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 7.649 ms |
| B5 | 100733 events | build | 14.0 / 14.3 / 14.3 ms | 201456 edges (2.000 per event) |
| B5 | 100733 events | happens-before | 358.8 / 378.1 / 378.1 ns/query | vector clocks, cost ~ node count |
| B5 | 100733 events | causal past (last) | 1.892 / 2.053 / 2.053 ms/query | walks parents backwards |
| B6 | 100733 events | worst of 100 jumps + world | 0.426 / 17.8 / 17.8 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 42.3 ms |
| B7 | 10085 events | replay time | 34.3 / 47.6 / 47.6 ms | ratio to recording x1.215 meets NFR-07 (<= 2x); verified=true |
| B7 | 100733 events | replay time | 302.4 / 318.9 / 318.9 ms | ratio to recording x1.152 meets NFR-07 (<= 2x); verified=true |
| B8 | prefix 10071 of 100733 events | branch creation | 27.6 / 31.5 / 31.5 ms | branch stores 78.44140625 KiB vs parent 30258 KiB (a full copy would be >= 3025 KiB) |
| B8 | prefix 50362 of 100733 events | branch creation | 31.5 / 34.9 / 34.9 ms | branch stores 81.1025390625 KiB vs parent 30258 KiB (a full copy would be >= 15128 KiB) |
| B8 | prefix 90659 of 100733 events | branch creation | 30.5 / 33.8 / 33.8 ms | branch stores 84.9775390625 KiB vs parent 30258 KiB (a full copy would be >= 27233 KiB) |
| B10 | 100733 events, branch at 98 | compare | 623.3 / 719.0 / 719.0 ms | linear in events after indexing |
| B9 | 1 thread(s), 48 runs | runs/s | 336.7 / 347.9 / 347.9 runs/s | speedup x1.000 (reports are identical for every thread count, law M12) |
| B9 | 2 thread(s), 48 runs | runs/s | 565.5 / 567.0 / 567.0 runs/s | speedup x1.679 (reports are identical for every thread count, law M12) |
| B9 | 4 thread(s), 48 runs | runs/s | 931.9 / 933.3 / 933.3 runs/s | speedup x2.767 (reports are identical for every thread count, law M12) |
| B9 | 8 thread(s), 48 runs | runs/s | 1060 / 1104 / 1104 runs/s | speedup x3.147 (reports are identical for every thread count, law M12) |
| B11 | RE, 48 events, 1 thread(s) | search time | 42.2 / 44.1 / 44.1 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | RE, 48 events, 4 thread(s) | search time | 25.4 / 32.0 / 32.0 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | lost update, 19 events, 1 thread(s) | search time | 26.3 / 28.4 / 28.4 ms | 9 real counterfactual branches tried, 9 critical |
| B11 | lost update, 19 events, 4 thread(s) | search time | 16.8 / 18.7 / 18.7 ms | 9 real counterfactual branches tried, 9 critical |
| B13 | simulated 0 us per step group | events/s | 329011 events/s | queue high-water 50 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 100 us per step group | events/s | 21972 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 500 us per step group | events/s | 4549 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 2000 us per step group | events/s | 1226 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| XL | 1018235 events, 10 nodes | record time | 3343 ms | 304621 events/s |
| XL | 1018235 events held in memory | heap used | 886 MiB | 912 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1018235 events | graph build | 196.5 ms | 2036450 edges |
| XL | 1018235 events | worst of 100 jumps | 1.275 ms | meets NFR-06 (< 5000 ms); session open 381.1 ms |
| XL | 1018235 events | replay time | 3594 ms | verified=true, ratio to recording x1.075 meets NFR-07 (<= 2x) |
| B14 | AD-1 to AD-6 + KA scenarios | wall clock | 0.112 seconds | all checks passed; target < 600 s |
| SOAK | 10000 seeds, KA-0 control | violations | 0 runs | expected 0; law problems: 0; 100 runs also graph-checked and replay-verified |
| SOAK | 10000 seeds, 1559049 events | wall clock | 1.096 seconds | 9124 runs/s on 12 threads |
| NFR01 | pingpong default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.058 s |
| NFR01 | pingpong with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.059 s |
| NFR01 | causal default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.079 s |
| NFR01 | causal with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.077 s |
| NFR01 | re default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.127 s |
| NFR01 | re with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.120 s |
| NFR01 | re2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.140 s |
| NFR01 | re2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.146 s |
| NFR01 | ka2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.078 s |
| NFR01 | ka2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.081 s |
| NFR01 | ka3 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.095 s |
| NFR01 | ka3 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.095 s |
| NFR01 | control default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.071 s |
| NFR01 | control with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.075 s |
| NFR01 | gossip default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.726 s |
| NFR01 | gossip with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.443 s |

## JMH micro-benchmarks (JDK 21, 1 fork, 2 warm-up and 3 measurement iterations of 1 s)

```
Benchmark                              (nodes)  Mode  Cnt     Score     Error  Units
JmhBenchmarks.decodeEvent                  N/A  avgt    3  4189.075 ± 237.353  ns/op
JmhBenchmarks.encodeEventWithState         N/A  avgt    3  1372.754 ±  39.658  ns/op
JmhBenchmarks.encodeEventWithoutState      N/A  avgt    3   985.935 ±   3.473  ns/op
JmhBenchmarks.identityKeyedDecision        N/A  avgt    3    17.530 ±   0.768  ns/op
JmhBenchmarks.ownRandomGenerator           N/A  avgt    3     1.950 ±   0.238  ns/op
JmhBenchmarks.stateDigest                  N/A  avgt    3   481.216 ±   0.767  ns/op
JmhBenchmarks.vectorLocalEvent               3  avgt    3    49.132 ±   4.029  ns/op
JmhBenchmarks.vectorLocalEvent              10  avgt    3    66.474 ±   7.610  ns/op
JmhBenchmarks.vectorLocalEvent              20  avgt    3    78.573 ±   1.685  ns/op
JmhBenchmarks.vectorLocalEvent              50  avgt    3   172.166 ±  13.176  ns/op
JmhBenchmarks.vectorLocalEvent             100  avgt    3   343.682 ±  12.239  ns/op
JmhBenchmarks.vectorReceive                  3  avgt    3    63.599 ± 193.156  ns/op
JmhBenchmarks.vectorReceive                 10  avgt    3   133.831 ±  74.223  ns/op
JmhBenchmarks.vectorReceive                 20  avgt    3    94.149 ±   1.735  ns/op
JmhBenchmarks.vectorReceive                 50  avgt    3   206.022 ±   5.981  ns/op
JmhBenchmarks.vectorReceive                100  avgt    3   422.234 ±  32.394  ns/op
```

Reading the numbers: the vector-clock cost grows roughly linearly with the node count (B3, harness: 72 ns per receive at 3
nodes, 427 ns at 100), so delta compression of vectors is **not** needed for the design envelope of 3-20 nodes
(95 ns at 20). The own random generator costs ~2 ns, an identity-keyed decision ~18 ns.

## B13: the asynchronous writer on a slow disk

A simulated per-step-group delay makes the writer the bottleneck. The bounded queue (256 groups) turns this into **back-pressure**: the
engine thread waits instead of memory growing, and every event is still persisted and readable afterwards:

| Simulated delay per step group | Engine throughput | Queue high-water |
|---|---|---|
| 0 us per step group | 329,011 events/s | 50 of 256 groups |
| 100 us per step group | 21,972 events/s | 256 of 256 groups |
| 500 us per step group | 4,549 events/s | 256 of 256 groups |
| 2000 us per step group | 1,226 events/s | 256 of 256 groups |

## What the soak proves and does not prove

The soak runs the KA-0 control (a correct protocol) under **10,000 seeds of random fault plans** drawn from the whole fault catalogue
(partitions, crash and restart pairs, pauses, clock skew, slow links, reordering, probabilistic drops, delays and duplicates, rules
after a named event), in parallel: **0 violations**, 1559049 events, with graph self-checks and replay
verification on every 100th run. `SoakTest` runs the same chaos generator on every demo scenario (150 seeds each, every run
graph-checked and replay-verified). The soak found two real defects during development (duplicate env identities for repeated
duplication; a verifier false mismatch on trailing silent steps); testing replay from every checkpoint found a third (a checkpoint taken
after a run stopped did not remember the stop). All were fixed; see `docs/DEVIATIONS.md` DEV-12, DEV-13, DEV-21.

## Honest limits

* B11 (intervention search) is measured on the two small failing demos only; the cost is one branch per suspect, so it grows linearly with the number of suspects (capped at 64 by default).
* Wall-clock numbers vary run to run (the same 3-node vector-clock measurement read 28 ns, 29 ns, 70 ns and 71 ns in four full runs of
  the harness; the JMH figure is the steadier one), and the replay ratios above sit well under 2x but are not guaranteed on other hardware.
* `world()` starts from the nearest checkpoint and folds only the events after it; the equivalence with a full fold from event 0 is
  tested at every sampled position of every demo, also under duplicates, delays, pauses and crashes (`LawsTest.m2_inflightAndTimers...`).
* B14 (the acceptance demonstrations take about 0.1 s in a warm JVM; the cold `java -jar ... demo` run takes a few seconds including JVM
  start and two fresh-JVM determinism checks).
