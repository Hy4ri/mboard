# Phase 2: dialect suggestions, not aggressive autocorrect

## Kept changes

- Preserve mapped dialect spellings alongside normalized canonical dictionary entries, only when dialect completion is enabled. For example, بدي no longer disappears behind بدى. Frequencies/canonical correction maps are unchanged.
- Smooth the retained dialect follower distribution with a small lower-model share. Discarded dialect-table tail counts no longer give out-of-domain MSA followers an artificial advantage.
- Keep canonical-only completion lookup for the unfinished-word spelling guard, so surface aliases do not change automatic correction policy.

## Rejected experiments

- Broader folded-only Arabic context-prefix matching: increased choice competition and slightly reduced ordinary Arabic completion availability. Removed from final code.
- Allowing attached-word completions at two typed characters with only one stem letter: real JVM guesses were valid examples, but aggregate completion results got worse. Removed. The original three-character/two-stem-letter guard stays.

## Measured comparison with phase 1

| Metric | Phase 1 | Phase 2 |
|---|---:|---:|
| Jordanian next-word top-3 | 8.94% | 9.33% |
| Jordanian word found before finishing | 54.83% | 57.92% |
| Jordanian non-word typo target in strip | 54.09% | 57.01% |
| Jordanian after exactly one letter | 14.32% | 14.21% |
| Jordanian next-word top-3 with oracle learning | 9.98% | 10.23% |

The tradeoff is explicit: one-letter Jordanian availability is slightly lower, while next-word prediction, pre-finish word availability, and typo-target choice availability improve. Standard-Arabic and English headline quality metrics remain identical. Every corpus-word automatic-change count and every typo autofix/wrong-fix percentage remains identical to phase 1. This is an improvement to choices, not Arabic autocorrect recall.

## Real-code evidence

- Parent full `bash bench/run.sh phase2_final` through the repo Nix shell, ktlint, and `./gradlew assembleDebug --console=plain`: all exit 0. Logs in scratch: mboard-phase2-final-{bench,build}.log.
- Old/new inspection with identical assets/data: 156,733 dialect-off completion and lexical-correction rows byte-identical, SHA-256 `8dea064a0f96541ab56f704a2b5050a295a04a93ff5ea41cb289c19eb19fb25f`.
- Parent JVM probes: `بد` with dialect on offers بدي/بدك/بدكم/بدنا; off offers canonical بدون/بدى. `بدي ` predicts [اروح, اطلع, احكي]; `شو ` predicts [في, بدك, صار]. Deliberate بدي/بدى/شو/هسا/صاير/هههههه stay uncorrected. Bare و and article-only لل produce no clitic guesses.
- Data assets are byte-identical to phase 1; no held-out answers were copied into training tables.
- APK: `/home/m57/Projects/mboard/app/build/outputs/apk/debug/app-debug.apk`, 12,876,317 bytes; SHA-256 `3c7e60f0ca94a2d28d2fe4b7bbd1f185df912ec187a6fcb044f64bd46ec86e3d`.

## Remaining gate and measurement scope

Physical-device acceptance is pending, and the combined phase-1/phase-2 implementation has not been installed, committed or pushed. The earlier bench-only commit remains the published state. Dataset reuse, synthetic chat, oracle learning and desktop latency limitations still apply; see ../README.md. The JSON companion captures full old/new metrics, source hashes and parity evidence.
