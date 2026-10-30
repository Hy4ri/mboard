# Startup phase 2

Baseline: main `3dbd5e4`, Arabic + Jordanian on USB phone `AS7J6R4407003449`.
Behavior reference: `bench/results/main-899a83e` in the lead checkout.

## Decision

A scanner and normalized-candidate cache lowered first-suggestion median from ~6150 ms to
4676 ms, still above the 2000 ms target. That experiment is **not** in the shipped source.
Early readiness would change initial suggestions. Instead, compile the five continuation TSVs
into indexed binary tables during `preBuild`. TSVs remain source of truth and the desktop
fallback. The APK omits those five text tables. Runtime restores all rows before READY;
there is no partial-result window, new runtime dependency, native code, or mmap complexity.
The existing dictionary loader remains unchanged.

The binary format is big-endian: MBT1 magic; UTF-8 length-prefixed string pool; row count;
then context-string ID, int64 total, follower count, and follower tuples
(surface-string ID, comparison-key-string ID, int64 count).
Build-time normalization matches the original runtime normalization.

## Evidence

- Full replay uses a temporary asset overlay with the generated binary files, not just TSV fallback.
- All 23 reference TSVs are byte-identical. No lost or gained targets. All quality deltas are zero.
- Binary decoder inspection compared all 262,497 rows, including all keys, surface words,
  comparison keys and counts, against the five source tables: exact equality.
- `bench/scripts/dictionary_parity.jsh`: old lead distribution and new distribution,
  same assets and `bench/data`, stdin `/dev/null`; `cmp` equal over 284,832 rows.
  Neither JShell log contains `Error:` or `Exception`.
- Probes with binary assets: `شو ` -> `في | بدك | صار`;
  `I am goin` -> `goin | going | gonna`.
- Device remained unlocked. No uninstall, clear-data command, or IME-settings change occurred.

## Phone measurements

| Variant | First-suggestion median | Maximum | Engine median | PSS median | Java heap median |
|---|---:|---:|---:|---:|---:|
| Lead baseline | ~6150 ms | — | — | ~190000 KB | ~70000 KB |
| Scanner experiment | 4676 ms | 4735 ms | 4552 ms | 190005 KB | 68788 KB |
| Binary, initial prototype | 1811 ms | 1824 ms | 1691 ms | 190219 KB | 70040 KB |
| Binary, text tables omitted | 1782 ms | 3154 ms | 1643 ms | 185038 KB | 69736 KB |
| **Final clean build** | **1802 ms** | **1848 ms** | **1685 ms** | **190017 KB** | **69884 KB** |

The incremental text-omitted variant's first round includes ~1.55 seconds between service creation and engine-load
start. Engine loading itself stays 1588–1667 ms. Do not claim every cold start is below two seconds.
The requested five-run median is below two seconds. Exact device logs are alongside this report.

## Commands

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug'
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew300-final --assets /tmp/crew300-assets'
python3 -I bench/scripts/summary.py bench/results/crew300-final /home/m57/Projects/mboard/bench/results/main-899a83e
nix develop -c timeout 300 bash -c 'bench/suggest.sh --json --assets /tmp/crew300-assets --lang ar --dialect "شو "; bench/suggest.sh --json --assets /tmp/crew300-assets --lang en "I am goin"'
adb -s AS7J6R4407003449 install -r app/build/outputs/apk/debug/app-debug.apk
bench/scripts/startup_measure.sh AS7J6R4407003449 5
```

Build and probe exits: 0. Earlier development builds caught buffer-overload, Gradle DSL,
and configuration-cache mistakes; those were corrected before the passing final gate.
No tests were added. No push or merge.

## Final clean-build receipt

`./gradlew clean assembleDebug` under the same lock and ktlint gate: exit 0.
This proves binary generation works without pre-existing outputs. The final clean APK was
installed, pulled back and hash-matched before the last five phone measurements.
APK: 18,766,797 bytes versus lead baseline 16,867,258 bytes (+1,899,539 bytes).
The earlier 27,970,843-byte incremental APK contained ZIP packaging slack; the clean size is authoritative.
Final artifact hash and per-round logs are in `device-clean-build.txt`.
Full final bench label `crew300-final`: exit 0, all 23 TSVs identical, all quality deltas zero.
