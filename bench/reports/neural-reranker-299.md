# Card 299: tiny neural candidate reranker

**Current candidate: v2 (one-letter completions + next words), still OFF pending review.**
The v1 results below are retained as rejected experimental evidence. See the final
v2 section for the current source and recommendation.

## Scope and architecture

Experimental, OFF by default. No app asset is added unless the acceptance contract passes.
The runtime only reorders the engine's existing candidates. It never creates a word.
The correction scorer, edit model, autocorrect thresholds and strip/editor glue are unchanged.
No runtime dependency, native library, TFLite or network operation is added.

Each model has 10,000 vocabulary IDs, including UNK/BOS/PAD, a 48-dimensional tied
input/output embedding, a 128-unit ReLU hidden layer over the last two words, and a
48-dimensional output projection. It has **508,608 parameters**. Three models total
1,525,824 parameters. The scorer uses int8 weights and float accumulation; hidden/output
biases remain float. A lookup scores only the supplied candidates, not all 10,000 words.

`new_score = existing_log_score + 0.1 * (neural_logit - candidate_pool_mean)`.
Unrepresented candidates get zero neural adjustment. Existing prediction source priority,
completion edit costs and literal/manual-choice guards remain. The initial weight 0.1 is
an exploratory setting, not a claim of a calibrated optimum. Default-OFF calls retain the
original prediction sorting path and do not load model assets.

The desktop opt-in is `JAVA_OPTS='-Dmboard.neural=true -Dmboard.neural.weight=0.1'`.
The property flag defaults to false. There is no new user setting or production rollout.

## Training

Python has neither NumPy nor Torch, inside or outside the dev shell. Nothing was installed.
`train_reranker.py` launches the dependency-free Java trainer in the existing JDK 21.
Training uses the existing **training-only count intermediates**, not evaluation sentences:
English/Arabic `unigram_`, `next_`, and `context_` tables from `build_ngrams.py`; Jordanian
uses `out_jo_split`, never full-Shami `out_jo`. The subtitle windows and exact-line
exclusions remain those documented in `bench/lm/SOURCES.md` and `build_ngrams.py`.
The retained follower distributions are truncated training estimates, not full softmax
corpus counts. No held-out text is used to learn embeddings or vocabulary.

Sources: licensed OpenSubtitles-derived counts (existing project CC BY-SA 4.0 data
terms), Apache-2.0 Shami split-training text and project-authored synthetic augmentation.
No new corpus or license is introduced. If assets are ever shipped, their derivation must
retain the attribution/license terms in `SOURCES.md` and the app's existing notices.

Training uses fixed seed 57485, four deterministic workers, four epochs, batch 256,
AdaGrad learning rate 0.05, and context-row weighting proportional to square-root count.
Vocabulary ties use lexical order; worker gradients merge in fixed order. Output hashes
and every input-table hash are recorded in `bench/lm/reranker_manifest.json`.

Executed command (shared input intermediates were read-only):

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c python3 -I bench/lm/train_reranker.py \
  --en /home/m57/Projects/mboard/bench/lm/out_en \
  --ar /home/m57/Projects/mboard/bench/lm/out_ar \
  --jo /home/m57/Projects/mboard/bench/lm/out_jo_split
```

The input tables were also copied into this worktree's ignored
`bench/lm/out_training_{en,ar,jo_split}` directories for a stable local reproduction.
Models stay in ignored `bench/lm/out_reranker/` until acceptance. The initial one-epoch
English feasibility run is not an evaluated/shipped model. An earlier full run was
stopped before writing an app asset; the final run above writes only experimental outputs.

## Verification

Full replay, row comparisons, probes, model hashes, size and latency receipts follow below.
Desktop timing does not establish Android main-thread timing or physical-device acceptance.

## Result: experimental branch only; do not enable automatically

The three fresh-set headline gates pass at weight 0.1. However, the stricter row gate
returns `TRADEOFF_APPROVAL_REQUIRED`: 41 formerly pickable typo targets disappear,
while 107 become pickable. There are also small old-set metric declines. Keep the
feature OFF and the binary models OUT of the APK pending the lead's review. A fresh-set
headline win is not a claim of universal improvement. No device verification was done.

All fresh deltas below are percentage points:

| Set | Next top-1 before → ON | Found before end before → ON | Savings before → ON |
|---|---:|---:|---:|
| en_fresh | 16.55 → 16.86 (+0.31) | 84.71 → 85.71 (+1.00) | 40.59 → 41.42 (+0.83) |
| ar_fresh | 10.35 → 10.53 (+0.18) | 60.63 → 61.73 (+1.10) | 24.51 → 25.07 (+0.56) |
| jo_human_fresh | 7.10 → 7.48 (+0.38) | 51.11 → 52.64 (+1.53) | 18.26 → 18.99 (+0.73) |

Autocorrect fix and wrong-fix percentages are unchanged in **every** typo set. Clean
false-autocorrect counts and new-valid-corruption checks are unchanged in every set.
Across 22,252 aligned typo rows: zero new/changed wrong fixes, zero correct autos lost.
OFF matches all 23 baseline diagnostic TSVs **byte for byte** and all quality metrics;
only timing/load metadata changes. OFF's row gate is `ROW_GATE_PASS` (exit 0).

| Set | Δ next top-1 | Δ found | Δ savings | Manual targets lost / gained |
|---|---:|---:|---:|---:|
| en_subs | +0.12 | +0.50 | +0.56 | 0 / 8 |
| en_tatoeba | +0.16 | +1.09 | +0.85 | 0 / 13 |
| en_chat | -0.03 | +0.45 | +0.48 | 0 / 2 |
| en_fresh | +0.31 | +1.00 | +0.83 | 0 / 10 |
| en_real_typos | — | — | — | 5 / 2 |
| ar_subs | +0.28 | +0.51 | +0.46 | 5 / 19 |
| ar_tatoeba | +0.14 | +1.27 | +0.56 | 7 / 21 |
| ar_fresh | +0.18 | +1.10 | +0.56 | 4 / 7 |
| ar_real_typos | — | — | — | 3 / 5 |
| jo_chat_dialect | +0.13 | -0.14 | +0.05 | 8 / 6 |
| jo_chat_msa | +0.78 | -0.38 | -0.07 | — |
| jo_human_fresh | +0.38 | +1.53 | +0.73 | 9 / 14 |
| en_chat_learning | -0.07 | +0.59 | +0.47 | — |
| jo_chat_learning | +0.08 | +0.74 | +0.18 | — |

See `299/on-vs-baseline/comparison.json` for every lost/gained typo row and before/after
shown lists. Clean `.misses.tsv` diagnostics are retained in the local result directories.
The old-set MSA Jordanian savings drop is -0.07 pp; it is not hidden behind fresh gains.

## Size and latency

All three language models **combined** total **1,846,000 bytes**, below 2,000,000 bytes:

| Asset | Bytes | SHA-256 |
|---|---:|---|
| rerank_en.bin | 592109 | e09972009238802e9fe0e26c6419e2e25a2a6b2dd3280488150b152d91c4d87a |
| rerank_ar.bin | 625224 | 8180b7f2f269992cad82b0d251fd2621319143261e3be41ce499676e34602dc9 |
| rerank_jo.bin | 628667 | 80a590233aa0bd68b043b407eac8ec1b678e2813527f772b5247ee81a0c7440c |

The rejected/default-OFF APK has **zero model asset growth**: none of these files is in
`app/src/main/assets`. Enabling later requires intentionally copying the models and
changing the flag. Current experiment assets are `/tmp/rr299/assets` (existing assets
plus models) and persistent ignored `bench/lm/out_reranker/*.bin` (models only).

`RerankerLatencyKt` warms 10,000 lookups, then measures 20,000 lookups per model.
Each lookup includes **two** context projections, 64 known candidates each, normalization,
map creation and sorting. This bounds the normal no-learning prediction's two scoring
pools; completion uses one projection. It does not bound arbitrary unbounded user-word
maps. Results in milliseconds:

| Model | p50 | p95 | p99 |
|---|---:|---:|---:|
| English | 0.066351 | 0.086182 | 0.113828 |
| Arabic | 0.067624 | 0.080721 | 0.092679 |
| Jordanian | 0.067843 | 0.081449 | 0.093010 |

The isolated scoring overhead is below 2 ms p95. Full replay wall times mix cold/warm
calls and different early-hit populations, so they are not a controlled latency A/B.
Their fresh-set p95 values, baseline → ON, were 264→248 µs (en), 695→551 µs (ar),
and 459→386 µs (jo). Do not interpret those decreases as a neural speedup.

## Exact gates and exits

All builds, replays, probes, training and latency runs used the shared bench lock.

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  set -euo pipefail
  ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ./gradlew assembleDebug
  ./gradlew -p bench installDist'
# All four commands: exit 0. See 299/build.log.

flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  cd bench
  JAVA_OPTS="-Dmboard.neural=true -Dmboard.neural.weight=0.1" \
    bash run.sh crew299-on --assets /tmp/rr299/assets'
# exit 0
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  cd bench; bash run.sh crew299-off'
# exit 0

flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  java -Xmx512m -cp "bench/build/install/mboard-bench/lib/*" \
    com.m57.bench.RerankerLatencyKt /tmp/rr299/assets'
# exit 0

python3 -I bench/scripts/quality.py compare \
  --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
  --candidate bench/results/crew299-on --out bench/reports/299/on-vs-baseline
# exit 2: expected tradeoff receipt, not a compile/runtime failure
python3 -I bench/scripts/quality.py compare \
  --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
  --candidate bench/results/crew299-off --out bench/reports/299/off-vs-baseline
# exit 0
```

The training command above exited 0. Gate logs and comparison receipts are committed
under `bench/reports/299/`; raw replay results remain under `bench/results/crew299-{on,off}`.
There are no new unit/instrumented/screenshot tests or test dependencies.

## Clean diagnostic row inspection

`299/clean-miss-row-diffs.json` records new and removed `(context, target)` miss
occurrences, with multiplicity, excluding false-autocorrect diagnostic rows. New /
removed misses were en_fresh 22/162, ar_fresh 58/156, jo_human_fresh 247/522.
The old jo_chat_dialect set has 248/234 and jo_chat_msa 92/53, consistent with their
small found-before-end declines. A diagnostic miss difference is not a full trace
of every intermediate prefix; the replay JSON supplies aggregate early-hit metrics.

## Probe receipts (v1)

Both probe batches exited 0. They use `flock ... nix develop -c bash -c '...'` and
`bench/suggest.sh --json --no-autocap --assets /tmp/rr299/assets TEXT`. ON sets
`JAVA_TOOL_OPTIONS='-Dmboard.neural=true -Dmboard.neural.weight=0.1'` because
`suggest.sh` invokes java directly. Full raw replies are in `299/*probes.log`.

| Input | OFF choices | ON choices |
|---|---|---|
| he broke down everg | everg / everglades / everything | everg / everglades / every |
| قد لا يجب | يجب / يجري / جيبي | يجب / يجري / يحب |
| انهم | انهم / إنهم / إنه | انهم / إنه / إنها |
| بلا منعا (dialect) | منعا / معنا / مناح | منعا / معنا / منها |

`teh` stays `the / teh / there`, auto=`the`, in both modes. The `انهم` loss is
reported deliberately; the demo does not show only successful cases.

# V2: current candidate — preserve spelling evidence after one letter

V1 is committed at `380194c99a4b31c6d566e0ab4fde4e6473dfcd8c` for reproduction.
Its manual-target losses are not accepted. V2 makes one conservative policy change:
**rerank next words and one-letter completion pools only**. Once the user supplies two
or more characters, the existing literal/fuzzy/spelling scores remain authoritative.
This keeps the neural model from displacing typo recovery with fluent but wrong words.
It is a general prefix-length policy, not a vocabulary or benchmark exception list.

The same training outputs and weight 0.1 are used. No retraining, evaluation-text
training or model-size increase occurs. Feature remains OFF; binary models stay outside
the app, for the lead to review the small older-set prediction tradeoffs before rollout.

| Fresh set | Next top-1 baseline → v2 | Found baseline → v2 | Savings baseline → v2 |
|---|---:|---:|---:|
| en_fresh | 16.55 → 16.86 (+0.31) | 84.71 → 84.93 (+0.22) | 40.59 → 40.90 (+0.31) |
| ar_fresh | 10.35 → 10.53 (+0.18) | 60.63 → 60.95 (+0.32) | 24.51 → 24.77 (+0.26) |
| jo_human_fresh | 7.10 → 7.48 (+0.38) | 51.11 → 51.51 (+0.40) | 18.26 → 18.46 (+0.20) |

Every clean set improves found-before-end and savings. Every typo set has **zero
manual targets lost or gained** and zero autocorrect changes over 22,252 aligned rows.
The strict quality tool still returns `TRADEOFF_APPROVAL_REQUIRED` (exit 2), solely
because en_chat top-1 falls 0.03 pp, en_chat_learning top-1 falls 0.07 pp, and
jo_chat_learning next-word top-3 falls 0.06 pp. Those declines are not fresh-set declines.
The model meets the task's stated fresh-set, unchanged-autocorrect, size and desktop
latency gates; the lead must review the older-set tradeoff rather than assuming universal
improvement. Current recommendation: review v2, not v1; do not silently enable it.

## Final gate commands (v2)

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  set -euo pipefail
  ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"
  ./gradlew assembleDebug
  cd bench
  JAVA_OPTS="-Dmboard.neural=true -Dmboard.neural.weight=0.1" \
    bash run.sh crew299-short-on --assets /tmp/rr299/assets'
# Formatting, lint, assembleDebug and full ON replay: exit 0 each.
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c '
  set -e; cd bench; bash run.sh crew299-short-off'
# Full OFF replay: see final receipts.
python3 -I bench/scripts/quality.py compare \
  --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
  --candidate bench/results/crew299-short-on --out bench/reports/299/short-on-vs-baseline
# exit 2 (three tiny older-set prediction declines; no lost typo rows).
```

The final source does not change the projection implementation. The measured
64-candidate, two-projection microbenchmark remains an upper-workload probe for both
v1 and v2 ordinary lookup paths; v2 computes fewer completion projections.

Final OFF replay and both final probe batches exited 0. OFF again matches **23/23
TSV files byte for byte**. V2 ON matches **11/11 typo TSV files byte for byte**, not
just their aggregate metrics. See `short-off-vs-baseline/`, `short-off-exit.log`,
`short-probes.log`, `short-prediction-probes.log` and `final-clean-deltas.json`.
`short-clean-miss-row-diffs.json` preserves the final clean miss-row differences.

Final `suggest.sh --json --no-autocap` examples (same explicit experimental asset directory):

| Text | OFF | V2 ON | Auto |
|---|---|---|---|
| empty | I / you / and | I / you / the | null both |
| teh | the / teh / there | the / teh / there | the both |
| انهم | انهم / إنهم / إنه | انهم / إنهم / إنه | null both |
| he broke down everg | everg / everglades / everything | everg / everglades / everything | null both |
| بلا منعا (dialect) | منعا / معنا / مناح | منعا / معنا / مناح | null both |

This demonstrates next-word reranking and the restoration of v1's longer-prefix
spelling behavior. It does not claim that the current engine fixes every example.

Open items: lead review of three small old-set prediction declines; production flag
and asset inclusion decision; device responsiveness/typing verification. No install,
push, merge or publication was performed by this member. No second full training
reproduction run was executed; fixed-seed deterministic code and recorded input/output
hashes support reproduction, but cross-JDK bitwise identity is not claimed.
