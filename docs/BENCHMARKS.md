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
| NFR-05 | >= 50,000 events/s in memory | 362,378 events/s (recorder off: 393,759) | **met** |
| NFR-05 | >= 20,000 events/s persisted | 342,868 events/s through the real asynchronous store, checkpoints included (synchronous disk: 197,678); 268336 events/s for a 1,017,092-event, 20-node timeline | **met** |
| NFR-06 | seek < 1 s at 100,000 events | worst of 100 jumps including `world()`: 0.574 ms median / 0.625 ms p99 at 100,733 events; opening the session 22.9 ms | **met** |
| NFR-06 | seek < 5 s at 1,000,000 events | worst of 100 jumps: 1.263 ms at 1,018,235 events / 10 nodes (session open 292.9 ms), 1.268 ms at 1,017,092 events / 20 nodes, 14.9 ms on a timeline read back from disk. Opening a persisted million-event timeline first parses the log: 3855 ms (not part of a jump) | **met** |
| NFR-07 | replay within 2x of the original | 1.45x at 10,085 events, 1.16x at 100,733 events, 1.02x at 1,018,235 events (10 nodes), 1.01x at 1,017,092 events (20 nodes), 1.17x against the persisted recording of the 20-node timeline | **met at every size measured** |
| NFR-08 | branch cost independent of prefix length | branch creation 23-27 ms at prefixes of 10k, 50k and 90k events of a 100,733-event timeline; the branch stores ~80 KiB against the parent's 30,250 KiB | **met** |
| NFR-09 | 3-20 nodes, ~1,000,000 events | 1,018,235 events at 10 nodes **and** 1,017,092 events at 20 nodes (the top of the envelope): each recorded in memory, graph-built, sought in and replay-verified; the 20-node timeline also persisted to disk by the real store, read back in a fresh store and replay-verified | **met** |
| Section 26 | conditional: implement delta compression of vectors if vector-clock cost dominates at 20 nodes | at 20 nodes a clock stamp is 2.979 % of the engine's time per event (89.8 ns per stamp against 3014 ns per event) and the vector field is 30.1 % of the encoded event line (103 of 342 bytes per event, state records included (on 24% of the events)); trigger (more than 50% of either) **not met** | **not needed** |
| NFR-10 | measure and report bytes per event | 275 bytes/event with state records (265 without) on the 5-node run; in memory ~912 bytes/event at 10 nodes, ~1166 at 20; on disk 401 bytes/event at 20 nodes (vectors grow with the node count); gzip compression (opt-in) 67 bytes/event on the 5-node run | measured and reported; the initial expectation (< 300 bytes without states) holds for the 5-node run and is exceeded at 20 nodes, which the specification allows (no pass-or-fail target) |

## Harness results (JDK 21)

| ID | Variant | Metric | median / p95 / p99 | Note |
|---|---|---|---|---|
| B1 | gossip nodes=3 ~100000 events | steps/s | 119250 / 122502 / 122502 steps/s | baseline only |
| B1 | gossip nodes=5 ~100000 events | steps/s | 120379 / 121222 / 121222 steps/s | baseline only |
| B1 | gossip nodes=10 ~100000 events | steps/s | 119826 / 122172 / 122172 steps/s | baseline only |
| B2 | recorder off (null sink) | events/s | 393759 / 398081 / 398081 events/s | target NFR-05: >= 50,000 in memory |
| B2 | in memory | events/s | 362378 / 366338 / 366338 events/s | meets NFR-05 (>= 50,000/s); overhead x1.087 |
| B2 | synchronous disk | events/s | 197678 / 198946 / 198946 events/s | overhead x1.992 |
| B2 | asynchronous disk (the real store) | events/s | 342868 / 347191 / 347191 events/s | meets NFR-05 (>= 20,000/s persisted, incl. checkpoints); overhead x1.148 |
| B3 | nodes=3 | ns per receive | 28.3 / 38.2 / 38.2 ns/op | event line 125 bytes; informs compression |
| B3 | nodes=10 | ns per receive | 52.0 / 52.7 / 52.7 ns/op | event line 139 bytes; informs compression |
| B3 | nodes=20 | ns per receive | 89.2 / 95.7 / 95.7 ns/op | event line 158 bytes; informs compression |
| B3 | nodes=50 | ns per receive | 210.9 / 214.5 / 214.5 ns/op | event line 218 bytes; informs compression |
| B3 | nodes=100 | ns per receive | 429.6 / 436.8 / 436.8 ns/op | event line 318 bytes; informs compression |
| B3b | nodes=10, 101720 events | clock stamp as share of the engine's time per event | 1.929 / 1.929 / 1.929 % | 52.8 ns per stamp against 2735 ns per event |
| B3b | nodes=10, 101720 events | vector field as share of the encoded event line | 19.3 / 19.3 / 19.3 % | 56 of 295 bytes per event, state records included (on 24% of the events) |
| B3b | nodes=10, 101720 events | trigger (more than 50% of time or bytes) | NOT MET  | delta compression of vectors is not needed; the conditional requirement is closed |
| B3b | nodes=20, 101497 events | clock stamp as share of the engine's time per event | 2.979 / 2.979 / 2.979 % | 89.8 ns per stamp against 3014 ns per event |
| B3b | nodes=20, 101497 events | vector field as share of the encoded event line | 30.1 / 30.1 / 30.1 % | 103 of 342 bytes per event, state records included (on 24% of the events) |
| B3b | nodes=20, 101497 events | trigger (more than 50% of time or bytes) | NOT MET  | delta compression of vectors is not needed; the conditional requirement is closed |
| B4 | 100733 events, 23% carry a state record | microseconds/event | 1.246 / 1.271 / 1.271 us/event | bytes/event 275 |
| B12 | with full state records | bytes/event | 275 bytes | 275 MB per million events (NFR-10: measured and reported, initial expectation < 300 bytes without states) |
| B12 | digests only, no state records | bytes/event | 265 bytes |  |
| B5 | 10085 events | build | 1.473 / 1.736 / 1.736 ms | 20160 edges (1.999 per event) |
| B5 | 10085 events | happens-before | 282.4 / 353.5 / 353.5 ns/query | vector clocks, cost ~ node count |
| B5 | 10085 events | causal past (last) | 0.191 / 0.296 / 0.296 ms/query | walks parents backwards |
| B6 | 10085 events | worst of 100 jumps + world | 0.303 / 0.857 / 0.857 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 7.030 ms |
| B5 | 100733 events | build | 15.9 / 16.9 / 16.9 ms | 201456 edges (2.000 per event) |
| B5 | 100733 events | happens-before | 410.3 / 434.8 / 434.8 ns/query | vector clocks, cost ~ node count |
| B5 | 100733 events | causal past (last) | 1.939 / 2.651 / 2.651 ms/query | walks parents backwards |
| B6 | 100733 events | worst of 100 jumps + world | 0.574 / 0.625 / 0.625 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 22.9 ms |
| B7 | 10085 events | replay time | 38.6 / 47.0 / 47.0 ms | ratio to recording x1.449 meets NFR-07 (<= 2x); verified=true |
| B7 | 100733 events | replay time | 307.4 / 326.1 / 326.1 ms | ratio to recording x1.164 meets NFR-07 (<= 2x); verified=true |
| B6b | 100733 events | parse the log from disk | 266.9 / 274.3 / 274.3 ms | 305.0 bytes/event on disk |
| B6b | 100733 events | load persisted indexes | 1.724 / 2.096 / 2.096 ms | 205591 bytes (2.041 bytes/event) |
| B6b | 100733 events | rebuild indexes from the log | 5.867 / 6.498 / 6.498 ms | the fallback when an index file is missing or suspect |
| B12 | gzip-compressed segments (opt-in --compress) | bytes/event | 67 bytes | x4.492 smaller than the plain log; recording takes x3.771 of the plain time (288.9 ms plain, 1089 ms compressed) |
| B6b | 100733 events | parse the compressed log from disk | 340.0 / 352.3 / 352.3 ms | x1.274 of the plain parse time |
| B15 | RE, 34 candidates, <= 2 faults | full exploration | 223.5 / 241.8 / 241.8 ms | 531 schedules executed: 2376 schedules/s, each a complete deterministic run with invariant checks |
| B16 | 100733 events | export | 624.4 / 661.6 / 661.6 ms | 5695 KiB (57.9 bytes/event compressed) |
| B16 | 100733 events | validated import | 874.3 / 906.7 / 906.7 ms | every file checksum, the log's commit checksums, count, log hash and causal evidence recomputed |
| B16 | 100733 events | reproduce (import, replay verify, fresh run) | 1500 / 1503 / 1503 ms |  |
| B8 | prefix 10071 of 100733 events | branch creation | 24.3 / 27.0 / 27.0 ms | branch stores 79.0283203125 KiB vs parent 30459 KiB (a full copy would be >= 3045 KiB) |
| B8 | prefix 50362 of 100733 events | branch creation | 27.4 / 35.6 / 35.6 ms | branch stores 81.7021484375 KiB vs parent 30459 KiB (a full copy would be >= 15228 KiB) |
| B8 | prefix 90659 of 100733 events | branch creation | 22.6 / 25.3 / 25.3 ms | branch stores 85.6005859375 KiB vs parent 30459 KiB (a full copy would be >= 27413 KiB) |
| B10 | 100733 events, branch at 98 | compare | 738.6 / 834.3 / 834.3 ms | linear in events after indexing |
| B9 | 1 thread(s), 48 runs | runs/s | 320.8 / 327.0 / 327.0 runs/s | speedup x1.000 (reports are identical for every thread count, law M12) |
| B9 | 2 thread(s), 48 runs | runs/s | 544.6 / 592.4 / 592.4 runs/s | speedup x1.698 (reports are identical for every thread count, law M12) |
| B9 | 4 thread(s), 48 runs | runs/s | 873.5 / 915.0 / 915.0 runs/s | speedup x2.723 (reports are identical for every thread count, law M12) |
| B9 | 8 thread(s), 48 runs | runs/s | 1091 / 1119 / 1119 runs/s | speedup x3.401 (reports are identical for every thread count, law M12) |
| B11 | RE, 48 events, 1 thread(s) | search time | 48.6 / 57.8 / 57.8 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | RE, 48 events, 4 thread(s) | search time | 27.7 / 30.3 / 30.3 ms | 12 real counterfactual branches tried, 1 critical |
| B11 | lost update, 19 events, 1 thread(s) | search time | 31.3 / 32.8 / 32.8 ms | 9 real counterfactual branches tried, 9 critical |
| B11 | lost update, 19 events, 4 thread(s) | search time | 19.7 / 21.5 / 21.5 ms | 9 real counterfactual branches tried, 9 critical |
| B13 | simulated 0 us per step group | events/s | 233138 events/s | queue high-water 193 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 100 us per step group | events/s | 21692 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 500 us per step group | events/s | 4464 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| B13 | simulated 2000 us per step group | events/s | 1225 events/s | queue high-water 256 of 256 groups (bounded: the engine waits instead of growing memory); all 20169 events persisted and readable: true |
| XL | 1018235 events, 10 nodes | record time | 3591 ms | 283535 events/s |
| XL | 1018235 events, 10 nodes, held in memory | heap used | 885 MiB | 912 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1018235 events, 10 nodes | graph build | 209.2 ms | 2036450 edges |
| XL | 1018235 events, 10 nodes | worst of 100 jumps | 1.263 ms | meets NFR-06 (< 5000 ms); session open 292.9 ms |
| XL | 1018235 events, 10 nodes | replay time | 3650 ms | verified=true, ratio to recording x1.016 meets NFR-07 (<= 2x) |
| XL | 1017092 events, 20 nodes | record time | 3955 ms | 257195 events/s |
| XL | 1017092 events, 20 nodes, held in memory | heap used | 1131 MiB | 1166 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1017092 events, 20 nodes | graph build | 252.7 ms | 2034144 edges |
| XL | 1017092 events, 20 nodes | worst of 100 jumps | 1.268 ms | meets NFR-06 (< 5000 ms); session open 335.8 ms |
| XL | 1017092 events, 20 nodes | replay time | 4001 ms | verified=true, ratio to recording x1.012 meets NFR-07 (<= 2x) |
| XL | 1017092 events, 20 nodes, on disk | record + persist | 3790 ms | 268336 events/s meets NFR-05 (>= 20,000/s persisted) |
| XL | 1017092 events, 20 nodes, on disk | bytes per event | 401 bytes | log 389 MiB, whole timeline directory (checkpoints, indexes) 408 MiB |
| XL | 1017092 events, 20 nodes | parse the log | 3855 ms | indexes persisted in 26.7 ms |
| XL | 1017092 events, 20 nodes | worst of 100 jumps | 14.9 ms | meets NFR-06 (< 5000 ms); session open 297.5 ms (the log was already parsed) |
| XL | 1017092 events, 20 nodes | replay time | 4447 ms | verified=true, ratio to the persisted recording x1.173 meets NFR-07 (<= 2x) |
| B14 | AD-1 to AD-6 + KA scenarios | wall clock | 0.126 seconds | all checks passed; target < 600 s |
| SOAK | 10000 seeds, KA-0 control | violations | 0 runs | expected 0; law problems: 0; 100 runs also graph-checked and replay-verified |
| SOAK | 10000 seeds, 1559049 events | wall clock | 0.980 seconds | 10204 runs/s on 12 threads |
| NFR01 | pingpong default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.053 s |
| NFR01 | pingpong with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.058 s |
| NFR01 | causal default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.074 s |
| NFR01 | causal with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.088 s |
| NFR01 | re default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.115 s |
| NFR01 | re with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.122 s |
| NFR01 | re2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.131 s |
| NFR01 | re2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.148 s |
| NFR01 | ka2 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.077 s |
| NFR01 | ka2 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.079 s |
| NFR01 | ka3 default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.106 s |
| NFR01 | ka3 with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.096 s |
| NFR01 | control default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.076 s |
| NFR01 | control with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 0.080 s |
| NFR01 | gossip default plan | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.690 s |
| NFR01 | gossip with chaos plans | identical | 1000/1000 runs | 100% identical (each seed run twice, on different thread counts); 1.427 s |

## JMH micro-benchmarks (JDK 21, 1 fork, 2 warm-up and 3 measurement iterations of 1 s)

```
Benchmark                              (nodes)  Mode  Cnt     Score      Error  Units
JmhBenchmarks.decodeEvent                  N/A  avgt    3  4327.179 ± 1477.688  ns/op
JmhBenchmarks.encodeEventWithState         N/A  avgt    3  1411.304 ±   65.890  ns/op
JmhBenchmarks.encodeEventWithoutState      N/A  avgt    3   991.371 ±   26.698  ns/op
JmhBenchmarks.identityKeyedDecision        N/A  avgt    3    17.484 ±    2.930  ns/op
JmhBenchmarks.ownRandomGenerator           N/A  avgt    3     1.949 ±    0.152  ns/op
JmhBenchmarks.stateDigest                  N/A  avgt    3   475.918 ±    9.772  ns/op
JmhBenchmarks.vectorLocalEvent               3  avgt    3    49.899 ±   16.784  ns/op
JmhBenchmarks.vectorLocalEvent              10  avgt    3    68.419 ±   30.256  ns/op
JmhBenchmarks.vectorLocalEvent              20  avgt    3    79.697 ±    3.182  ns/op
JmhBenchmarks.vectorLocalEvent              50  avgt    3   173.577 ±    8.426  ns/op
JmhBenchmarks.vectorLocalEvent             100  avgt    3   344.397 ±   28.582  ns/op
JmhBenchmarks.vectorReceive                  3  avgt    3    77.005 ±  318.548  ns/op
JmhBenchmarks.vectorReceive                 10  avgt    3   148.458 ±   57.344  ns/op
JmhBenchmarks.vectorReceive                 20  avgt    3    97.845 ±    6.127  ns/op
JmhBenchmarks.vectorReceive                 50  avgt    3   205.763 ±    8.182  ns/op
JmhBenchmarks.vectorReceive                100  avgt    3   436.364 ±  149.132  ns/op
```

Reading the numbers: the vector-clock cost grows roughly linearly with the node count (B3, harness: 28 ns per receive at 3
nodes, 430 ns at 100), so delta compression of vectors is **not** needed for the design envelope of 3-20 nodes
(89 ns at 20; B3b measures the Section 26 trigger directly: 2.979 % of the time and 30.1 % of the bytes of an event at 20 nodes, trigger not met). The own random generator costs ~2 ns, an identity-keyed decision ~18 ns.

## B13: the asynchronous writer on a slow disk

A simulated per-step-group delay makes the writer the bottleneck. The bounded queue (256 groups) turns this into **back-pressure**: the
engine thread waits instead of memory growing, and every event is still persisted and readable afterwards:

| Simulated delay per step group | Engine throughput | Queue high-water |
|---|---|---|
| 0 us per step group | 233,138 events/s | 193 of 256 groups |
| 100 us per step group | 21,692 events/s | 256 of 256 groups |
| 500 us per step group | 4,464 events/s | 256 of 256 groups |
| 2000 us per step group | 1,225 events/s | 256 of 256 groups |

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
