# Card 299: accepted V2, bundled default ON

The lead accepted V2's three older-set prediction declines (at most 0.07 percentage
points) and requested bundling/default enablement. This is the final delivery stage;
`neural-reranker-299.md` retains the training and earlier experiments.

## Runtime and data

The app and desktop bench compile the same `NeuralReranker.enabled` implementation.
It now defaults ON. `-Dmboard.neural=false` is the diagnostic kill switch. No user
setting, runtime dependency, native library or network permission is added.

The model only reranks existing next words and one-letter completion pools. All
longer-prefix spelling scores and automatic correction remain unchanged. Each model
has 508,608 parameters; the three models have 1,525,824 parameters combined.
Weights are int8 with float accumulation; hidden/output biases remain float.

README and bundled NOTICE now explain the reranker and its licensed training-only
OpenSubtitles/Shami/synthetic sources. `bench/lm/SOURCES.md` documents the shared
ON default and OFF override. No new corpus or training input was introduced.

## Bundled model hashes and size

| File under app/src/main/assets | Bytes | SHA-256 |
|---|---:|---|
| rerank_en.bin | 592109 | e09972009238802e9fe0e26c6419e2e25a2a6b2dd3280488150b152d91c4d87a |
| rerank_ar.bin | 625224 | 8180b7f2f269992cad82b0d251fd2621319143261e3be41ce499676e34602dc9 |
| rerank_jo.bin | 628667 | 80a590233aa0bd68b043b407eac8ec1b678e2813527f772b5247ee81a0c7440c |

**Combined raw model assets: 1,846,000 bytes**, below 2,000,000 bytes. The same
hashes were verified after extracting their bytes from the built APK.

The debug APK grew from **14,462,522** to **15,440,701** bytes: **+978,179 bytes**.
The comparison is the disabled V2 APK immediately before bundling, not a separately
rebuilt dccc420 APK. Compressed model entries total 1,467,706 bytes. Whole-APK size
also includes changed ZIP/signing/alignment overhead, so the observed file delta is
not a stand-alone model compression estimate. `299/shipped-apk-size.json` records
both APK hashes, raw sizes, ZIP-entry sizes and embedded model hashes.

Final APK SHA-256: `b699d56295fb8f791b63cb87e9bc2f0299f7bc61ddbddbc298b063c08d9577ab`.

## Shipped-default quality

`crew299-shipped` runs with no neural properties and the app's actual asset directory.
All 14 dataset reports match V2 ON behavioral metrics exactly, excluding timing.
All **11 typo diagnostic TSVs** match the crew baseline **byte for byte**, covering
22,252 rows: zero manual targets lost/gained and zero changed autocorrect decisions.
Clean false-autocorrect counts are unchanged. Found-before-end and savings improve
on every clean set.

| Held-out set | Δ next top-1 | Δ found before end | Δ savings |
|---|---:|---:|---:|
| en_fresh | +0.31 pp | +0.22 pp | +0.31 pp |
| ar_fresh | +0.18 pp | +0.32 pp | +0.26 pp |
| jo_human_fresh | +0.38 pp | +0.40 pp | +0.20 pp |

The strict quality tool still exits 2 for en_chat top-1 -0.03 pp,
en_chat_learning top-1 -0.07 pp and jo_chat_learning next-word top-3 -0.06 pp.
These are the exact previously accepted tradeoffs, not new regressions.
`299/shipped-vs-baseline/` contains complete per-set metrics and aligned-row receipts.

## Final commands and exits

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  set -euo pipefail
  ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ./gradlew assembleDebug
  cd bench
  bash run.sh crew299-shipped
  cd ..
  java -Xmx512m -cp "bench/build/install/mboard-bench/lib/*" \
    com.m57.bench.RerankerLatencyKt app/src/main/assets
  bench/suggest.sh --json --no-autocap ""
  bench/suggest.sh --json --no-autocap "teh"
  bench/suggest.sh --lang ar --json --no-autocap "انهم"
  bench/suggest.sh --lang ar --dialect --json --no-autocap "بلا منعا"'
```

Every command exited **0**. Full output and explicit exit markers are retained in
`299/shipped-gates.log`. No property flag or alternate asset directory was needed.

```sh
python3 -I bench/scripts/quality.py compare \
  --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
  --candidate bench/results/crew299-shipped --out bench/reports/299/shipped-vs-baseline
```

Exit **2**: the accepted older-set prediction tradeoffs above; no lost typo targets,
new wrong replacements, correct-autocorrect losses or new valid-word corruption.

## Latency and probes

Warmed desktop p95 overhead, including two projections, 64 known candidates each,
map allocation, normalization and sorting:

| English | Arabic | Jordanian |
|---:|---:|---:|
| 0.082046 ms | 0.090076 ms | 0.091305 ms |

All are below the 2 ms budget. This is not Android main-thread latency evidence.

The shipped-default `suggest.sh` replies were:

| Text | Suggestions | Automatic correction |
|---|---|---|
| empty | I / you / the | null |
| teh | the / teh / there | the |
| انهم | انهم / إنهم / إنه | null |
| بلا منعا (dialect) | منعا / معنا / مناح | null |

Earlier OFF's empty strip was I / you / and; the shipped-default reply confirms
that the neural ranking path is active without diagnostic flags. Longer-prefix
spelling behavior remains the same as the baseline.

No tests were added. No install, push, merge or publication was performed by this
member. Device responsiveness and physical typing acceptance remain open.

## Integration inspection and requested final probes

Main inspected at `1803aceaee31518ad464d075ab7b19e8b865d1a6` (#301 docs + #302 Arabizi).
No rebase, checkout, real merge or main edit was done.

```sh
git merge-tree $(git merge-base HEAD main) HEAD main
git merge-tree --write-tree HEAD main
```

The write-tree dry run exits **1**, with **README.md as the only conflicted path**.
Both branches edit the long Everyday typing suggestion bullet; main also adds the
Arabizi bullet immediately after it. The lead should retain the neural explanation
and the Arabizi bullet when resolving that documentation conflict.

`WordSuggestions.kt` and `bench/build.gradle.kts` auto-merge without conflict.
`MboardService.kt` and `bench/.../Strip.kt` have no #299 edits and take main's #302
changes without conflict. This is merge-tree evidence, not a merged-source build;
the lead still owns the final integrated build and bench. Raw dry-run receipt:
`299/merge-main-1803ace.txt`.

Requested `suggest.sh` probes ran under the shared lock with the actual app assets.
The shipped mode uses no feature properties; the diagnostic comparison sets
`JAVA_TOOL_OPTIONS=-Dmboard.neural=false`. All commands exited **0**.

| Input | Shipped default ON | Diagnostic OFF | Auto |
|---|---|---|---|
| I am going to (trailing space) | be / do / get | be / do / get | null both |
| شو (trailing space, Arabic + dialect) | في / بدك / صار | في / صار / بدك | null both |
| kifak | kifak / kick / kicked | kifak / kick / kicked | null both |

`kifak` is unchanged by this reranker. This branch does not contain #302's optional
Arabizi implementation; the merge-tree inspection is against main that contains it.
Full JSON replies: `299/requested-probes.log`. No engine/asset source changed after
the shipped-default full gates in this report, so those results remain current.
