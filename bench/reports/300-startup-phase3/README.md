# Word dictionary startup, phase 3

Base: `6959982`. Branch: `perf/300-fast-load`.
Behavior reference: `/home/m57/Projects/mboard/bench/results/main-6959982-bin`.
Phone: USB `AS7J6R4407003449`. English was active initially and was restored after Arabic measurement.

## Profile and decision

One instrumented English cold start confirmed the bottleneck:

| Stage | Time |
|---|---:|
| TSV parsing and normalization | 1048 ms |
| Supplemental entries | 83 ms |
| Merge | 55 ms |
| Sorting | 261 ms |
| Prefix/exact/original indices | 554 ms |

A normalized-entry binary alone still took ~1.9–2.0 seconds to first suggestion. Prefix index
construction remained ~0.7 seconds. The final format also stores precomputed short-prefix top-eight
indices and pre-sorts English source entries. Runtime merges canonical/supplemental entries, restores
prefix lists and inserts supplemental entries with the same frequency/normalized-key tie order.
For dialect or hidden-word configurations, the original prefix construction remains active.
Arabic source order remains unchanged, preserving spelling-group ties.

`compileWords` is a reproducible Gradle task; source TSVs remain authoritative and are omitted from
the APK after conversion. Both normalization implementations have change-together comments.
No generated blobs are committed. A forced second generation produced identical SHA-256 hashes.
The format has MBW2 magic, bounded length-prefixed UTF-8 strings, int64 frequencies and indexed
prefix lists. An empty normalized field reuses its original string. Runtime rejects invalid magic,
sizes, prefix indices, truncated data, unsorted Latin data and trailing bytes through IOException.

The ordinary source loader remains available for desktop comparison. `loadArabizi` also uses the
binary-aware reader. `jordanian.tsv` (~0.1 s) and `known_forms_ar.txt` (~0.07 s) stay unchanged;
converting them is not needed to meet either latency target.

## Final phone result

All adb sessions used `/tmp/crew-mboard/phone.lock`. The final clean APK was installed and pulled
back; SHA-256 matched before measurement. Both languages used that same APK. The keyboard's own
globe button switched English -> Arabic -> English. Screenshots confirmed restoration. Arabic logs
include `jordanian.tsv` and `jordanian_next.tsv`, confirming dialect tables were active.

| Language | Before | First suggestion median | Maximum | Engine median | PSS median | Java heap median |
|---|---:|---:|---:|---:|---:|---:|
| English | ~2500 ms | **1405 ms** | 2295 ms | 1291 ms | 170926 KB | 55100 KB |
| Arabic + dialect | phase-2 ~1802 ms | **1701 ms** | 1744 ms | 1584 ms | 185837 KB | 70116 KB |

English samples: 1400, 1404, 1405, 1417, 2295 ms.
Arabic samples: 1683, 1698, 1701, 1710, 1744 ms.
The English outlier includes service/editor scheduling before dictionary loading; engine duration
remained 1245–1296 ms. The target is a median, not a worst-case guarantee.
No screen unlock, uninstall, data clearing, system IME settings change or learning manipulation occurred.
An earlier development measurement encountered NotificationShade and stopped without dismissing it;
measurement resumed only after focus returned to the unlocked playground.

Clean APK: **19,246,937 bytes**, versus base **18,778,477 bytes**: **+468,460 bytes**.

## Behavioral verification

- Final full bench `crew300p3-final`, with generated continuation AND word binaries in the asset
  overlay: exit 0. All **24 TSVs byte-identical** to `main-6959982-bin`.
- All sets have zero quality deltas: next-word, completion, savings, found-before-end,
  false autocorrect, typo fix/in-strip/wrong-fix and mismatch proxy. No lost or gained target rows.
- Dictionary parity: old distribution reading TSV, new distribution reading binary words;
  **285,768 identical rows**, `cmp` exit 0. No JShell Error/Exception lines.
- Four malformed-word binary probes (magic, size, truncation, trailing bytes) all throw IOException.
- Binary probes: `شو ` -> `في | بدك | صار`; `I am goin` -> `goin | going | gonna`;
  `fixxit` -> `fix it | fixxit | fixing`.

## Exact gates

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew clean assembleDebug'
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew300p3-final --assets /tmp/crew300p3-assets'
python3 -I bench/scripts/summary.py bench/results/crew300p3-final /home/m57/Projects/mboard/bench/results/main-6959982-bin
flock /tmp/crew-mboard/bench.lock nix develop -c ./gradlew :app:compileWords --rerun-tasks
nix develop -c timeout 300 bash -c 'bench/suggest.sh --json --assets /tmp/crew300p3-assets --lang ar --dialect "شو "; bench/suggest.sh --json --assets /tmp/crew300p3-assets --lang en "I am goin"; bench/suggest.sh --json --assets /tmp/crew300p3-assets --lang en "fixxit"'
```

All final exits: 0. Parity used `nix develop -c timeout 300`, stdin `/dev/null`, identical
`-R-Dmboard.data`, and the new inspector's `-R-Dmboard.binary=true` only for the new jar.
Every phone session was wrapped in `flock /tmp/crew-mboard/phone.lock nix develop -c bash -c ...`;
measurement command: `bench/scripts/startup_measure.sh AS7J6R4407003449 5`.
No tests, dependencies, pushes or merges. No open requested gate.
