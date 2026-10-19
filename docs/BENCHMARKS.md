# Benchmarks and soak results

Everything in this file was **measured by running the harness**; nothing is estimated. Numbers come from one machine and are
reproducible in method, not in absolute value on other hardware. Raw data: [`benchmark-results.csv`](benchmark-results.csv)
(harness) and [`jmh-results.txt`](jmh-results.txt) (JMH).

## Environment and method

* processor Apple M2 Max (12 cores), max heap 8192 MiB, Mac OS X 27.2 aarch64, JDK 21.0.12.1 OpenJDK 64-Bit Server VM
* Commands (run with `JAVA_HOME` pointing at JDK 21):

```bash
java -jar chronos-bench/target/chronos-bench.jar all --csv docs/benchmark-results.csv     # B1-B14, XL, soak (about 90 s)
java -jar chronos-bench/target/chronos-bench.jar jmh -rf text -rff docs/jmh-results.txt    # JMH micro-benchmarks (about 2 min)
```

* Method (Section 26): each benchmark is a question; fixed seeds and workloads (the synthetic *gossip* scenario scales events and
  nodes freely); five repetitions with the **first discarded** for warm-up; the table shows **median / p95 / p99** (with four
  retained samples p95 and p99 equal the maximum). JMH is used for the small mechanisms (B3, B4).
* The first full run happened to use the wrong JDK (26); it was discarded and the whole harness was rerun explicitly on JDK 21.
  The environment line above is printed by the harness itself.

## Results against the targets of Section 8

| NFR | Target | Measured | Verdict |
|---|---|---|---|
| NFR-05 | >= 50,000 events/s in memory | ~359,000 events/s (recorder off: ~402,000) | **met** |
| NFR-05 | >= 20,000 events/s persisted | ~330,000 events/s through the real asynchronous store (includes checkpoints; synchronous disk ~196,000) | **met** |
| NFR-06 | seek < 1 s at 100,000 events | worst of 100 jumps including `world()`: ~21 ms (p99) at 100,733 events; session open 41 ms | **met** |
| NFR-06 | seek < 5 s at 1,000,000 events | ~200 ms at 1,018,235 events; session open ~398 ms | **met** |
| NFR-07 | replay within 2x of the original | 1.88x at 100,733 events, 1.76x at 1,018,235 events, **but 2.23x at 10,085 events** | **met at 100k and 1M; MISSED at 10k** (small runs are dominated by warm-up and per-step overhead; not tuned) |
| NFR-08 | branch cost independent of prefix length | branch creation 26-42 ms at prefix 10k, 50k and 90k events of a 100,733-event timeline; the branch stores ~80 KiB against the parent's 30,250 KiB | **met** |
| NFR-09 | 3-20 nodes, ~1,000,000 events | 1,018,235 events, 10 nodes recorded, graph-built, sought in and replay-verified | **met** (20 nodes: only the vector-clock cost B3 was measured) |
| NFR-10 | measure and report bytes per event | 275 bytes/event with state records (265 without); in memory ~911 bytes/event (events, checkpoints and indexes) | measured and reported (the initial expectation, < 300 bytes without states, is met) |

## Harness results (JDK 21)

| ID | Variant | Metric | median / p95 / p99 | Note |
|---|---|---|---|---|
| B1 | gossip nodes=3 ~100000 events | steps/s | 121879 / 123224 / 123224 steps/s | baseline only |
| B1 | gossip nodes=5 ~100000 events | steps/s | 125511 / 127011 / 127011 steps/s | baseline only |
| B1 | gossip nodes=10 ~100000 events | steps/s | 123307 / 123766 / 123766 steps/s | baseline only |
| B2 | recorder off (null sink) | events/s | 401767 / 404915 / 404915 events/s | target NFR-05: >= 50,000 in memory |
| B2 | in memory | events/s | 358639 / 382868 / 382868 events/s | meets NFR-05 (>= 50,000/s); overhead x1.120 |
| B2 | synchronous disk | events/s | 196060 / 198698 / 198698 events/s | overhead x2.049 |
| B2 | asynchronous disk (the real store) | events/s | 330158 / 338999 / 338999 events/s | meets NFR-05 (>= 20,000/s persisted, incl. checkpoints); overhead x1.217 |
| B3 | nodes=3 | ns per receive | 70.5 / 80.1 / 80.1 ns/op | event line 125 bytes; informs compression |
| B3 | nodes=10 | ns per receive | 101.1 / 108.3 / 108.3 ns/op | event line 139 bytes; informs compression |
| B3 | nodes=20 | ns per receive | 95.3 / 98.7 / 98.7 ns/op | event line 158 bytes; informs compression |
| B3 | nodes=50 | ns per receive | 215.7 / 219.5 / 219.5 ns/op | event line 218 bytes; informs compression |
| B3 | nodes=100 | ns per receive | 428.9 / 433.4 / 433.4 ns/op | event line 318 bytes; informs compression |
| B4 | 100733 events, 23% carry a state record | microseconds/event | 1.248 / 1.334 / 1.334 us/event | bytes/event 275 |
| B12 | with full state records | bytes/event | 275 bytes | 275 MB per million events (NFR-10: measured and reported, initial expectation < 300 bytes without states) |
| B12 | digests only, no state records | bytes/event | 265 bytes |  |
| B5 | 10085 events | build | 1.413 / 1.663 / 1.663 ms | 20160 edges (1.999 per event) |
| B5 | 10085 events | happens-before | 286.1 / 359.3 / 359.3 ns/query | vector clocks, cost ~ node count |
| B5 | 10085 events | causal past (last) | 0.185 / 0.348 / 0.348 ms/query | walks parents backwards |
| B6 | 10085 events | worst of 100 jumps + world | 0.384 / 1.156 / 1.156 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 7.551 ms |
| B5 | 100733 events | build | 13.3 / 13.6 / 13.6 ms | 201456 edges (2.000 per event) |
| B5 | 100733 events | happens-before | 359.3 / 367.7 / 367.7 ns/query | vector clocks, cost ~ node count |
| B5 | 100733 events | causal past (last) | 1.873 / 1.984 / 1.984 ms/query | walks parents backwards |
| B6 | 100733 events | worst of 100 jumps + world | 6.637 / 20.5 / 20.5 ms | meets NFR-06 (<1000 ms); opening the session (graph + indexes): 41.0 ms |
| B7 | 10085 events | replay time | 57.3 / 62.7 / 62.7 ms | ratio to recording x2.232 MISSES NFR-07 (<= 2x); verified=true |
| B7 | 100733 events | replay time | 513.1 / 519.4 / 519.4 ms | ratio to recording x1.881 meets NFR-07 (<= 2x); verified=true |
| B8 | prefix 10071 of 100733 events | branch creation | 41.7 / 42.7 / 42.7 ms | branch stores 77.9296875 KiB vs parent 30250 KiB (a full copy would be >= 3024 KiB) |
| B8 | prefix 50362 of 100733 events | branch creation | 31.0 / 60.6 / 60.6 ms | branch stores 80.5908203125 KiB vs parent 30250 KiB (a full copy would be >= 15124 KiB) |
| B8 | prefix 90659 of 100733 events | branch creation | 26.0 / 27.1 / 27.1 ms | branch stores 84.4658203125 KiB vs parent 30250 KiB (a full copy would be >= 27225 KiB) |
| B10 | 100733 events, branch at 98 | compare | 613.8 / 707.3 / 707.3 ms | linear in events after indexing |
| B9 | 1 thread(s), 48 runs | runs/s | 321.2 / 332.8 / 332.8 runs/s | speedup x1.000 (reports are identical for every thread count, law M12) |
| B9 | 2 thread(s), 48 runs | runs/s | 558.3 / 575.7 / 575.7 runs/s | speedup x1.738 (reports are identical for every thread count, law M12) |
| B9 | 4 thread(s), 48 runs | runs/s | 934.8 / 945.4 / 945.4 runs/s | speedup x2.911 (reports are identical for every thread count, law M12) |
| B9 | 8 thread(s), 48 runs | runs/s | 1090 / 1165 / 1165 runs/s | speedup x3.394 (reports are identical for every thread count, law M12) |
| XL | 1018235 events, 10 nodes | record time | 3279 ms | 310524 events/s |
| XL | 1018235 events held in memory | heap used | 885 MiB | 911 bytes per event (events + checkpoints + indexes of the log) |
| XL | 1018235 events | graph build | 212.3 ms | 2036450 edges |
| XL | 1018235 events | worst of 100 jumps | 200.0 ms | meets NFR-06 (< 5000 ms); session open 397.8 ms |
| XL | 1018235 events | replay time | 5761 ms | verified=true, ratio to recording x1.757 meets NFR-07 (<= 2x) |
| B14 | AD-1 to AD-6 + KA scenarios | wall clock | 0.102 seconds | all checks passed; target < 600 s |
| SOAK | 10000 seeds, KA-0 control | violations | 0 runs | expected 0; law problems: 0; 100 runs also graph-checked and replay-verified |
| SOAK | 10000 seeds, 1669749 events | wall clock | 1.289 seconds | 7756 runs/s on 12 threads |

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

Reading the numbers: the vector-clock cost grows about linearly with the node count (B3, harness: 70 ns per receive at 3 nodes, 429 ns at
100); R7 of the risk register (cost at many nodes) is therefore low, and delta compression of vectors is **not** needed for the
design envelope of 3-20 nodes (95 ns at 20 nodes). The own random generator costs ~2 ns, an identity-keyed decision ~18 ns.

## What the soak proves and does not prove

The soak runs the KA-0 control (a correct protocol) under **10,000 seeds of random fault plans** (partitions, crash and restart
pairs, probabilistic drops, delays and duplicates) in parallel: **0 violations**, 1,669,749 events, with graph self-checks and replay
verification on every 100th run. The same chaos generator on every demo scenario is part of the test suite
(`SoakTest`: 150 seeds per scenario, every run graph-checked and replay-verified, 1,350 runs). The soak found two real defects
during development (duplicate env identities for repeated duplication; a verifier false mismatch on trailing silent steps), which
were fixed; see `docs/DEVIATIONS.md` DEV-12 and DEV-13.

## Honest limits

* **Not run:** B11 (intervention search; the feature is not implemented) and B13 (asynchronous writer under a slow disk).
* **Seek cost grows with the position.** `TimeTravelSession.world()` rebuilds in-flight messages and timers by scanning the log
  prefix; the specification suggests starting from the nearest checkpoint. It is far inside NFR-06, but it is linear in the position (median / p99 of the worst-of-100 measurement: 0.384 / 1.156 ms at 10k events, 6.637 / 20.5 ms at 100k, about 200 ms at 1M).
* **Replay at small sizes misses NFR-07** (2.23x at 10k events), as shown above.
* Wall-clock numbers vary run to run (the same 3-node vector-clock measurement read 28 ns, 29 ns and 70 ns in three full runs of the harness; the JMH figure is the steadier one); the ratios and orders of magnitude are the reliable part.
* B14 (the acceptance demonstrations take about 0.1 s in a warm JVM; the cold `java -jar ... demo` run takes a few seconds including
  JVM start and two fresh-JVM determinism checks).
