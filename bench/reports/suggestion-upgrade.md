# Suggestion upgrade: measured first pass

Compared with baseline commit `4b88b6b`, rerun using the corrected benchmark. 29,600 unique source sentences (183,126 word events) and 17,952 typo rows; multiple setting variants replay the same source text. Measurements are desktop-engine evidence, not physical-device acceptance.

## Next-word and one-letter choice availability

| Domain | Next-word top-3 old → new | After one letter old → new | Corpus-word change rate old → new |
|---|---:|---:|---:|
| en_subs | 25.73% → 29.18% | 41.64% → 47.73% | 0.50% → 0.51% |
| en_tatoeba | 24.97% → 28.25% | 39.15% → 44.57% | 0.56% → 0.36% |
| en_chat | 21.24% → 22.40% | 38.80% → 41.24% | 1.30% → 1.17% |
| ar_subs | 18.21% → 20.41% | 28.62% → 33.82% | 0.32% → 0.32% |
| ar_tatoeba | 15.49% → 17.15% | 24.88% → 29.35% | 0.63% → 0.40% |
| jo_chat_dialect | 4.00% → 8.94% | 6.71% → 14.32% | 1.17% → 0.71% |
| en_chat_learning | 20.75% → 22.28% | 38.48% → 41.83% | 0.72% → 0.59% |
| jo_chat_learning | 5.97% → 9.98% | 15.89% → 18.47% | 1.08% → 0.69% |

## Typo outcomes (non-word rows)

| Domain | Scored / total | Automatically fixed old → new | Wrong replacement old → new | Intended in strip old → new |
|---|---:|---:|---:|---:|
| ar_real_typos | 510 / 771 | 19.22% → 16.86% | 3.53% → 2.94% | 47.45% → 49.41% |
| ar_subs | 2452 / 2919 | 27.12% → 26.26% | 4.98% → 4.89% | 71.17% → 70.19% |
| ar_tatoeba | 2408 / 2913 | 24.13% → 23.34% | 3.53% → 3.65% | 65.91% → 65.74% |
| en_chat | 342 / 400 | 86.84% → 83.04% | 7.60% → 6.14% | 92.11% → 91.52% |
| en_real_typos | 4000 / 4000 | 30.23% → 31.40% | 21.90% → 19.38% | 51.15% → 50.58% |
| en_subs | 2371 / 2790 | 84.44% → 82.88% | 6.50% → 4.34% | 92.41% → 92.03% |
| en_tatoeba | 2505 / 2960 | 82.36% → 80.08% | 7.66% → 5.43% | 90.82% → 89.74% |
| jo_chat_dialect | 1063 / 1199 | 13.36% → 17.31% | 4.52% → 4.42% | 42.99% → 54.09% |

## Decisions and limits

- Kept: counted tables with backoff; sentence-start predictions; frequency-ranked learned words; independently sourced Jordanian text; whole English contractions; distinct candidate apostrophes; key-centre-aware fuzzy weights; context-aware Latin single-edit corrections with a 3x confidence margin.
- Rejected: the aggressive context-aware Arabic auto-correction experiment (v2). It increased corpus-word changes on Arabic Tatoeba from 0.63% to 3.48%. v3 retains the conservative Arabic auto-correction policy and fixes stretched-row neighbour geometry.
- English now makes fewer wrong automatic replacements at the cost of leaving a few more slips uncorrected. Missing-letter English subtitle cases improve from 40.30% to 42.91% fixed and from 28.36% to 20.15% wrong replacements. Arabic generated-typo automatic recall remains slightly lower than baseline; do not advertise it as improved Arabic autocorrect.
- Chat datasets and Arabic hand-written error sets are synthetic. Subtitle text has errors; legitimate spelling/contraction normalizations can count as corpus-word changes. Metrics reuse the development evaluation set, not a new final unseen holdout.
- Learning runs use intended-word/oracle history, not a full editor replay of incorrect auto-corrections. All settings, punctuation-cost and desktop-latency caveats are documented in `../README.md`.

## Verification

- Parent full run: `nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; cd bench; bash run.sh ranking_typo_v3; cd ..; ./gradlew assembleDebug --console=plain'` — exit 0.
- Parent runtime probes: all same-row Arabic letter neighbours recognized; q→w weight 0.9 versus q→p 0.15; hello/lol/iOS/McDonald/friends/friend's kept; teh/becuase/beautiiful corrected; بدي/شو/مش/هسا/صاير/هههههه kept. Password choices remain input-independent dictionary common words. These are JVM engine checks, not Android UI checks.
- `git diff --check` — clean.
- Subagent independent code/measurement reviews and old/new benchmark/build runs were followed by parent inspection, a rejected v2 iteration, and parent v3 execution.
- APK: `/home/m57/Projects/mboard/app/build/outputs/apk/debug/app-debug.apk` (12,871,729 bytes). SHA-256 `e1be5c561e2e5631a046599d5f7f44c0e3bd7c368506778c9db2682f0596de02`.
- Physical-device gate remains open: `adb devices` showed no connected device. The new implementation has not been installed or pushed. Earlier bench-only commit `4b88b6b` is already on origin/main.

The JSON companion records complete old/new metrics, exact source/dataset hashes and the APK hash. The locally retained per-case results are in `bench/results/{baseline_corrected,ranking_typo_v3}/` (gitignored).
