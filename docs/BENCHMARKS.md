# Benchmarks and soak results

Everything in this file was **measured by running the harness**; nothing is estimated. Numbers come from one machine and are
reproducible in method, not in absolute value on other hardware. Raw data: [`benchmark-results.csv`](benchmark-results.csv)
(harness) and [`jmh-results.txt`](jmh-results.txt) (JMH). This file is generated: `python3 chronos-bench/tools/benchmarks_md.py`.

## Environment and method

* processor Apple M2 Max (12 cores), max heap 8192 MiB, Mac OS X 27.2 aarch64, JDK 21.0.12.1 OpenJDK 64-Bit Server VM
* Commands (run with `JAVA_HOME` pointing at JDK 21):

```bash
java -jar chronos-bench/target/chronos-bench.jar all --csv docs/benchmark-results.csv     # B1-B14, XL (10 and 20 nodes, memory and disk), soak, NFR-01 (about 4 min)
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
| NFR-05 | >= 50,000 events/s in memory | 355,471 events/s (recorder off: 397,251) | **met** |
| NFR-05 | >= 20,000 events/s persisted | 339,868 events/s through the real asynchronous store, checkpoints included (synchronous disk: 192,992); 289331 events/s for a 1,017,092-event, 20-node timeline | **met** |
| NFR-06 | seek < 1 s at 100,000 events | worst of 100 jumps including `world()`: 0.420 ms median / 0.482 ms p99 at 100,733 events; opening the session 25.6 ms | **met** |
| NFR-06 | seek < 5 s at 1,000,000 events | worst of 100 jumps: 1.276 ms at 1,018,235 events / 10 nodes (session open 280.2 ms), 1.409 ms at 1,017,092 events / 20 nodes, 15.2 ms on a timeline read back from disk. Opening a persisted million-event timeline first parses the log: 3686 ms (not part of a jump) | **met** |
| NFR-07 | replay within 2x of the original | 1.30x at 10,085 events, 1.15x at 100,733 events, 1.08x at 1,018,235 events (10 nodes), 1.05x at 1,017,092 events (20 nodes), 1.14x against the persisted recording of the 20-node timeline | **met at every size measured** |
| NFR-08 | branch cost independent of prefix length | branch creation 23-44 ms at prefixes of 10k, 50k and 90k events of a 100,733-event timeline; the branch stores ~80 KiB against the parent's 30,250 KiB | **met** |
| NFR-09 | 3-20 nodes, ~1,000,000 events | 1,018,235 events at 10 nodes **and** 1,017,092 events at 20 nodes (the top of the envelope): each recorded in memory, graph-built, sought in and replay-verified; the 20-node timeline also persisted to disk by the real store, read back in a fresh store and replay-verified | **met** |
| NFR-10 | measure and report bytes per event | 275 bytes/event with state records (265 without) on the 5-node run; in memory ~912 bytes/event at 10 nodes, ~1165 at 20; on disk 401 bytes/event at 20 nodes (vectors grow with the node count); gzip compression (opt-in) 67 bytes/event on the 5-node run | measured and reported; the initial expectation (< 300 bytes without states) holds for the 5-node run and is exceeded at 20 nodes, which the specification allows (no pass-or-fail target) |

## Harness results (JDK 21)

| ID | Variant | Metric | median / p95 / p99 | Note |
|---|---|---|---|---|
| B1 | gossip nodes=3 ~100000 events | steps/s | 121179 / 123538 / 123538 steps/s | baseline only |
| B1 | gossip nodes=5 ~100000 events | steps/s | 124556 / 125578 / 125578 steps/s | baseline only |
| B1 | gossip nodes=10 ~100000 events | steps/s | 121273 / 122239 / 122239 steps/s | baseline only |
| B2 | recorder off (null sink) | events/s | 397251 / 400365 / 400365 events/s | target NFR-05: >= 50,000 in memory |
| B2 | in memory | events/s | 355471 / 378140 / 378140 events/s | meets NFR-05 (>= 50,000/s); overhead x1.118 |
| B2 | synchronous disk | events/s | 192992 / 195647 / 195647 events/s | overhead x2.058 |
| B2 | asynchronous disk (the real store) | events/s | 339868 / 344219 / 344219 events/s | meets NFR-05 (>= 20,000/s persisted, incl. checkpoints); overhead x1.169 |
| B3 | nodes=3 | ns per receive | 71.9 / 85.1 / 85.1 ns/op | event line 125 bytes; informs compression |
| B3 | nodes=10 | ns per receive | 103.2 / 111.1 / 111.1 ns/op | event line 139 bytes; informs compression |
| B3 | nodes=20 | ns per receive | 96.5 / 101.1 / 101.1 ns/op | event line 158 bytes; informs compression |
| B3 | nodes=50 | ns per receive | 215.8 / 223.4 / 223.4 ns/op | event line 218 bytes; informs compression |
| B3 | nodes=100 | ns per receive | 428.5 / 436.3 / 436.3 ns/op | event line 318 bytes; informs compression |
| B4 | 100733 events, 23% carry a state record | microseconds/event | 1.282 / 1.791 / 1.791 us/event | bytes/event 275 |
| B12 | with full state records | bytes/event | 275 bytes | 275 MB per million events (NFR-10: measured and reported, initial expectation < 300 bytes without states) |
| B12 | digests only, no state records | bytes/event | 265 bytes |  |
| B5 | 10085 events | build | 1.448 / 1.699 / 1.699 ms | 20160 edges (1.999 per event) |
| B5 | 10085 events | happens-before | 296.2 / 374.0 / 374.0 ns/query | vector clocks, cost ~ node count |
| B5 | 10085 events | causal past (last) | 0.274 / 0.290 / 0.290 ms/query | walks parents backwards |
| B6 | 10085 events | worst of 100 jumps + world | 0.423 / 0.943 / 0.943 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 2.861 ms |
| B5 | 100733 events | build | 13.8 / 14.4 / 14.4 ms | 201456 edges (2.000 per event) |
| B5 | 100733 events | happens-before | 361.3 / 378.0 / 378.0 ns/query | vector clocks, cost ~ node count |
| B5 | 100733 events | causal past (last) | 1.904 / 1.953 / 1.953 ms/query | walks parents backwards |
| B6 | 100733 events | worst of 100 jumps + world | 0.420 / 0.482 / 0.482 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 25.6 ms |
| B7 | 10085 events | replay time | 34.2 / 44.6 / 44.6 ms | ratio to recording x1.301 meets NFR-07 (<= 2x); verified=true |
| B7 | 100733 events | replay time | 308.4 / 342.0 / 342.0 ms | ratio to recording x1.151 meets NFR-07 (<= 2x); verified=true |
| B6b | 100733 events | parse the log from disk | 263.3 / 270.6 / 270.6 ms | 305.0 bytes/event on disk |
| B6b | 100733 events | load persisted indexes | 1.838 / 2.200 / 2.200 ms | 205591 bytes (2.041 bytes/event) |
| B6b | 100733 events | rebuild indexes from the log | 4.823 / 5.921 / 5.921 ms | the fallback when an index file is missing or suspect |
| B12 | gzip-compressed segments (opt-in --compress) | bytes/event | 67 bytes | x4.492 smaller than the plain log; recording takes x3.685 of the plain time (296.4 ms plain, 1092 ms compressed) |
| B6b | 100733 events | parse the compressed log from disk | 335.7 / 344.6 / 344.6 ms | x1.275 of the plain parse time |
| B8 | prefix 10071 of 100733 events | branch creation | 39.5 / 41.9 / 41.9 ms | branch stores 79.0283203125 KiB vs parent 30459 KiB (a full copy would be >= 3045 KiB) |
| B8 | prefix 50362 of 100733 events | branch creation | 44.3 / 47.8 / 47.8 ms | branch stores 81.7021484375 KiB vs parent 30459 KiB (a full copy would be >= 15228 KiB) |
| B8 | prefix 90659 of 100733 events | branch creation | 22.7 / 25.4 / 25.4 ms | branch stores 85.6005859375 KiB vs parent 30459 KiB (a full copy would be >= 27413 KiB) |
| B10 | 100733 events, branch at 98 | compare | 638.7 / 723.3 / 723.3 ms | linear in events after indexing |
| B9 | 1 thread(s), 48 runs | runs/s | 313.6 / 315.2 / 315.2 runs/s | speedup x1.000 (reports are identical for every thread count, law M12) |
| B9 | 2 thread(s), 48 runs | runs/s | 535.3 / 544.6 / 544.6 runs/s | speedup x1.707 (reports are identical for every thread count, law M12) |
| B9 | 4 thread(s), 48 runs | runs/s | 908.5 / 927.2 / 927.2 runs/s | speedup x2.897 (reports are identical for every thread count, law M12) |
| B9 | 8 thread(s), 48 runs | runs/s | 1020 / 1023 / 1023 runs/s | speedup x3.252 (reports are identical for every thread count, law M12) |
| B11 | RE, 48 events, 1 thread(s) | search time | 44.8 / 61.3 / 61.3 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | RE, 48 events, 4 thread(s) | search time | 26.4 / 28.0 / 28.0 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | lost update, 19 events, 1 thread(s) | search time | 31.6 / 34.3 / 34.3 ms | 9 real counterfactual branches tried, 9 critical |
| B11 | lost update, 19 events, 4 thread(s) | search time | 20.4 / 21.6 / 21.6 ms | 9 real counterfactual branches tried, 9 critical |
| B13 | simulated 0 us per step group | events/s | 247913 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 100 us per step group | events/s | 18430 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 500 us per step group | events/s | 3800 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 2000 us per step group | events/s | 1036 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| XL | 1018235 events, 10 nodes | record time | 3412 ms | 298443 events/s |
| XL | 1018235 events, 10 nodes, held in memory | heap used | 886 MiB | 912 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1018235 events, 10 nodes | graph build | 197.8 ms | 2036450 edges |
| XL | 1018235 events, 10 nodes | worst of 100 jumps | 1.276 ms | meets NFR-06 (< 5000 ms); session open 280.2 ms |
| XL | 1018235 events, 10 nodes | replay time | 3683 ms | verified=true, ratio to recording x1.080 meets NFR-07 (<= 2x) |
| XL | 1017092 events, 20 nodes | record time | 3725 ms | 273014 events/s |
| XL | 1017092 events, 20 nodes, held in memory | heap used | 1130 MiB | 1165 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1017092 events, 20 nodes | graph build | 227.4 ms | 2034144 edges |
| XL | 1017092 events, 20 nodes | worst of 100 jumps | 1.409 ms | meets NFR-06 (< 5000 ms); session open 346.2 ms |
| XL | 1017092 events, 20 nodes | replay time | 3928 ms | verified=true, ratio to recording x1.054 meets NFR-07 (<= 2x) |
| XL | 1017092 events, 20 nodes, on disk | record + persist | 3515 ms | 289331 events/s meets NFR-05 (>= 20,000/s persisted) |
| XL | 1017092 events, 20 nodes, on disk | bytes per event | 401 bytes | log 389 MiB, whole timeline directory (checkpoints, indexes) 408 MiB |
| XL | 1017092 events, 20 nodes | parse the log | 3686 ms | indexes persisted in 16.7 ms |
| XL | 1017092 events, 20 nodes | worst of 100 jumps | 15.2 ms | meets NFR-06 (< 5000 ms); session open 321.4 ms (the log was already parsed) |
| XL | 1017092 events, 20 nodes | replay time | 3993 ms | verified=true, ratio to the persisted recording x1.136 meets NFR-07 (<= 2x) |
| B14 | AD-1 to AD-6 + KA scenarios | wall clock | 0.116 seconds | all checks passed; target < 600 s |
| SOAK | 10000 seeds, KA-0 control | violations | 0 runs | expected 0; law problems: 0; 100 runs also graph-checked and replay-verified |
| SOAK | 10000 seeds, 1559049 events | wall clock | 1.345 seconds | 7435 runs/s on 12 threads |
| NFR01 | pingpong default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.057 s |
| NFR01 | pingpong with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.056 s |
| NFR01 | causal default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.075 s |
| NFR01 | causal with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.074 s |
| NFR01 | re default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.132 s |
| NFR01 | re with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.125 s |
| NFR01 | re2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.133 s |
| NFR01 | re2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.149 s |
| NFR01 | ka2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.077 s |
| NFR01 | ka2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.084 s |
| NFR01 | ka3 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.095 s |
| NFR01 | ka3 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.095 s |
| NFR01 | control default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.075 s |
| NFR01 | control with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.073 s |
| NFR01 | gossip default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.696 s |
| NFR01 | gossip with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.477 s |

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
nodes, 428 ns at 100), so delta compression of vectors is **not** needed for the design envelope of 3-20 nodes
(96 ns at 20). The own random generator costs ~2 ns, an identity-keyed decision ~18 ns.

## B13: the asynchronous writer on a slow disk

A simulated per-step-group delay makes the writer the bottleneck. The bounded queue (256 groups) turns this into **back-pressure**: the
engine thread waits instead of memory growing, and every event is still persisted and readable afterwards:

| Simulated delay per step group | Engine throughput | Queue high-water |
|---|---|---|
| 0 us per step group | 247,913 events/s | 256 of 256 groups |
| 100 us per step group | 18,430 events/s | 256 of 256 groups |
| 500 us per step group | 3,800 events/s | 256 of 256 groups |
| 2000 us per step group | 1,036 events/s | 256 of 256 groups |

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
