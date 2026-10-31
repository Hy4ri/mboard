# Card 298: Levantine continuation expansion

Base engine/assets: `be17dc98692fbf8f1fafc277b9734c0b925cf799`.
Selected replay: `crew298-half`; control: `crew298-base`.
Both use the same engine, harness, existing split-trained continuation baseline,
neural models, lexicons and generated-binary overlay path. No Kotlin engine edits.
The control Tatoeba set was rerun after four normalized overlaps with existing
synthetic training were removed (309 -> 305); all comparisons use the final 305 rows.
One initial baseline and one initial weight-1 run were stopped during setup;
only successful completed runs and the corrected control are used below.

## Inputs and splitting

- Shami Syrian, Palestinian and Lebanese: Apache-2.0; approval supplied by lead.
- Tatoeba ajp/apc: CC BY 2.0 FR; evaluation only. 305 rows after removing Latin-script rows,
  normalized duplicates and existing evaluation/training overlaps. Original spelling is retained.
- Sister split: 46,535 training lines; 12,409 clean held-out lines, sampled deterministically to 1,500.
- Assign **raw** contiguous 50-line blocks before cleaning, separately per source;
  SHA-256 of `mboard_shami_sister_v1:<filename>:<block-id>`, modulo 5 (0 held out).
- Deduplicate held-out first; exclude all held-out messages, including unsampled ones, from sister training.
- `fetch_shami.py` supplies the existing cleaning policy, unchanged.
- Raw inspection disproved the claim that every source row starts with a speaker name:
  Syrian begins `قامت على طاولتها...`; Palestinian `هه اصلا حاليا...`; Lebanese `بقا بالوسايط...`.
  Blanket first-token stripping would damage real sentences. Remove recognized Syrian name prefixes only;
  ambiguous names such as شهد require a dialogue opener (`شهد اها` -> `اها`).
  This is a bounded heuristic, not complete speaker/narrative parsing. Some names can remain;
  names used as narrative subjects can also be removed. No speaker-ID metadata exists in these files.
- Raw URLs, raw SHA-256, output SHA-256, block assignments and Tatoeba sentence links:
  `bench/lm/split_298_manifest.json`, plus both SOURCES.md files.
- Output generation repeated byte-identically, including its manifest.
- Both eval sets run with `--lang ar --dialect`; the builder excludes their lines and rejects direct training-file use.

## Selection and attribution

Selected weights: **Jordanian human 6 : sister 1 : Jordanian synthetic 2**.
Minimum next/context totals: 6. This preserves the old Jordanian:synthetic 3:1 ratio.
Existing Jordanian inputs contribute 665,426 weighted whitespace tokens versus 629,367 sister
(51.4% Jordanian). This is an input-weight measure, not a claim that all sister language is Jordanian.
Curated seed counts remain unchanged, so this experiment also changes their relative scale.
Only `jordanian_next.tsv` was generated, through `make_assets.py --only-jordanian-next --dialect-source bench/lm/out_298_half`.
No vocabulary changes were needed. Stem tables and all three neural rerankers are unchanged.
The reranker was not retrained: the measured gains do not require it. These are continuation gains,
not fresh neural-model training results.

Rejected alternatives:
- `crew298-w1-final`: original human3/sister1/synthetic1. Fresh next3/found/savings improved,
  but sister tokens dominated, and jo_chat next1 fell 0.01 pp. Manual targets: 6 lost / 3 gained.
- `crew298-w2`: human3/sister2/synthetic1. jo_chat next3 fell 0.36 pp and savings fell 0.06 pp.
  Manual targets: 11 lost / 5 gained. Not selected.

## Selected per-set deltas

All figures below are percentage points. Positive savings are idealized entry-action savings,
not measured typing speed. All automatic correction and wrong-automatic counts/rates remain identical.
JSON timing/load numbers naturally vary and are not byte-equality claims.

| Set | Next3 | Next1 | Found before end | Savings | False AC | Typo fix | Typo wrong fix |
|---|---:|---:|---:|---:|---:|---:|---:|
| ar_fresh | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| ar_real_typos | — | — | — | — | — | +0.00 | +0.00 |
| ar_subs | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| ar_tatoeba | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| en_chat | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| en_chat_learning | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | — | — |
| en_fresh | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| en_real_typos | — | — | — | — | — | +0.00 | +0.00 |
| en_space_typos | — | — | — | — | — | +0.00 | +0.00 |
| en_subs | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| en_tatoeba | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 |
| jo_chat_dialect | +1.26 | +0.30 | +1.18 | +1.16 | +0.00 | +0.00 | +0.00 |
| jo_chat_learning | +1.07 | +0.38 | +1.05 | +1.07 | +0.00 | — | — |
| jo_chat_msa | +0.00 | +0.00 | +0.00 | +0.00 | +0.00 | — | — |
| jo_human_fresh | +0.66 | +0.26 | +0.50 | +0.31 | +0.00 | +0.00 | +0.00 |
| jo_tatoeba_fresh | +1.08 | +0.00 | +0.19 | +0.32 | +0.00 | — | — |
| levantine_sister_heldout | +4.68 | +2.49 | +3.94 | +3.06 | +0.00 | — | — |

EN/AR and dialect-off jo_chat TSVs are byte-identical, both misses and typo rows.
All 12 typo datasets preserve every automatic outcome; aggregate assertions also compare
raw words_modified counts and denominators. See `half-comparison.json` and `compare.py`.

### Manual-target tradeoff (not automatic corrections)

Six typo targets are lost and six gained. This is **not manual suggestion parity**.
No engine tuning was added to hide these losses. Lead/user review must consider them.
The comparison JSON retains full old/new rows, including cases whose outcome label stays wrong_fix.

| Set | TSV line | Change | Typed | Target | Before shown | After shown |
|---|---:|---|---|---|---|---|
| jo_chat_dialect.typos | 25 | lost | يهم | يهمك | يهم / يهمك / يهمني | يهم / يهمني / يعمل |
| jo_chat_dialect.typos | 640 | gained | لدرس | ادرس | لدرس / بدرس / لدرجه | لدرس / ادرس / بدرس |
| jo_chat_dialect.typos | 649 | lost | انظل | انزل | انظر / انظل / انزل | انظر / انظل / انظري |
| jo_chat_dialect.typos | 722 | gained | ويم | وين | ويم / يومها / يوم | ويم / وين / يوم |
| jo_chat_dialect.typos | 943 | gained | شوق | شوب | شوي / شوق / شوية | شوي / شوق / شوب |
| jo_chat_dialect.typos | 960 | lost | للنفس | النفس | للنفس / النفس / لنفسك | للنفس / لنفسك / لنفسي |
| jo_human_fresh.typos | 417 | gained | ان | انا | ان / أن / أنا | ان / انا / أن |
| jo_human_fresh.typos | 504 | gained | اننا | انا | أننا / اننا / أنا | أننا / اننا / انا |
| jo_human_fresh.typos | 939 | lost | باب | بابا | باب / بابا / بالطبع | باب / بالطبع / بالتأكيد |
| jo_human_fresh.typos | 950 | gained | اهعلي | اهلي | أهلي / اهعلي / اهلين | أهلي / اهعلي / اهلي |
| jo_human_fresh.typos | 1004 | lost | الهم | المهم | الهم / المهم / العمل | الهم / العمل / المكان |
| jo_human_fresh.typos | 1311 | lost | عن | عنا | عن / عني / عنا | عن / عني / عندما |

## Hashes and size

Full per-asset before/after hashes are in `assets-before.sha256` and `assets-after.sha256`.
Only continuation TSV and NOTICE changed. All lexical/spelling assets, stem tables and neural
models are byte-identical. The NOTICE change documents the newly used Apache-2.0 source files.
README distinguishes continuation sources from unchanged neural-model training sources.

| Asset | Before SHA-256 | After SHA-256 |
|---|---|---|
| jordanian_next.tsv | `bd634d87d2ba72bfa5de1e674115d938d56270831b926de27557396536422a1d` | `9e663c29fbb966995b9dfd58f25dc1c85bdec06eaf6dc40b3b97e5bbd6cb33bc` |
| NOTICE.txt | `cfb3e8fe0d4793ece7ca0eedbfaa4a30994c701b2fecab7d1226adfbf632c436` | `c6efddbf1368940b1c730f183140601c870d21ccf2d2a0c64a781f6f1eea8399` |

Final APK: **21,043,747 bytes**; baseline **19,261,469 bytes**; delta **+1,782,278 bytes**.
APK SHA-256: `e8c3b39ce19379a27c5adecf72c415f0412ba8d991e00782a9a2de8009dcb806`.

Final probes (all `auto=null`):
- `شو ` -> `في | صار | بدك` (baseline `في | بدك | صار`).
- `بدي ` -> `اروح | احكي | اعمل` (baseline `اروح | اكل | اطلع`).
- English `fixxit` -> `fix it | fixxit | fixing`.

## Exact gates and retained outputs

Every full replay/build held `/tmp/crew-mboard/bench.lock`. The overlay construction is:

```sh
./gradlew :app:compileTables :app:compileWords assembleDebug
mkdir -p bench/reports/298-levantine/<name>-assets
cp -a app/src/main/assets/. bench/reports/298-levantine/<name>-assets/
cp -a app/build/generated/tableAssets/. bench/reports/298-levantine/<name>-assets/
cp -a app/build/generated/wordAssets/. bench/reports/298-levantine/<name>-assets/
```

Successful commands (exit 0 unless explicitly noted):

```sh
python3 -I bench/lm/fetch_298.py
# Training regeneration: exact command in bench/lm/SOURCES.md.
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew298-base --assets "$PWD/reports/298-levantine/base-assets"'
# Tatoeba-only correction after decontaminating the evaluation set:
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; build/install/mboard-bench/bin/mboard-bench --lang ar --dialect --sentences data/jo_tatoeba_fresh.txt --label jo_tatoeba_fresh --out results/crew298-base --assets "$PWD/reports/298-levantine/base-assets"'
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew298-w1-final --assets "$PWD/reports/298-levantine/w1-final-assets"'
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew298-w2 --assets "$PWD/reports/298-levantine/w2-assets"'
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh crew298-half --assets "$PWD/reports/298-levantine/half-assets"'
python3 -I bench/scripts/summary.py bench/results/crew298-half bench/results/crew298-base
python3 -I bench/reports/298-levantine/compare.py bench/results/crew298-base bench/results/crew298-half
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug'
nix develop -c timeout 300 bash -c 'bench/suggest.sh --json --assets "$PWD/bench/reports/298-levantine/half-assets" --lang ar --dialect "شو "; bench/suggest.sh --json --assets "$PWD/bench/reports/298-levantine/half-assets" --lang ar --dialect "بدي "; bench/suggest.sh --json --assets "$PWD/bench/reports/298-levantine/half-assets" --lang en "fixxit"'
```

Intentional rejection probe: `python3 -I bench/lm/build_ngrams.py --lang jo --out-dir /tmp/298-forbidden --extra-text bench/data/jo_tatoeba_fresh.txt` exits **1**, reporting evaluation-only input.

Full replay results and binary overlays remain in this worktree (gitignored); compact summaries,
full target-change receipts and hashes are committed here. Desktop latency excludes Android editor/UI.
No physical-device installation/acceptance, tests, dependencies, pushes or merges were performed.
Open gates: lead review of the manual-target tradeoff and physical Android acceptance.
