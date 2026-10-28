# Research-gap execution receipts

Status: ENGINE WORK COMPLETE, PHONE ACCEPTANCE OPEN. Touch deferred and Arabic stem backoff closed by decision. No commits, pushes, installs or merges performed.

## Baseline

- Checkout: `/home/m57/Projects/mboard`, main, HEAD `fadbdb2`.
- Tracked tree was clean. Existing untracked .hermes, logs, old benchmark/research artifacts remain untouched.
- Host: hyari, Linux x86_64.
- `nix develop -c ./gradlew -p bench installDist --console=plain`: exit 0.
- Freeze: `engines/baseline`, manifest SHA-256 `034ed6fb2d3548109bea16509f6f1d6bf49ed832d6ba35e0c46d7f4f244550cc`.
- Immutable quick sample: `sample/`, 50 rows in 25 strata.
- `nix develop -c bash bench/run.sh research_gaps_baseline`: exit 0, all 14 sets completed.
- Headline metrics match `quality_manual_choice_candidate1` in every set.
- Retained results: `full-baseline/`.

## #296 oracle mismatch proxy

- This is not Gboard's observed later-edit WMR. It is an oracle post-separator exact-word mismatch proxy.
- Counts: wrong_autocorrect, typo_left, case_only; exclusive; typo denominator includes each valid row once, including real-word errors.
- Recompiled desktop: exit 0.
- Freeze: `engines/metric`, manifest SHA-256 `f5176c68e4f69d1474a4feb1dea10733a2a66bd7fffa49bf9cab3fb694bda2f3`.
- `nix develop -c bash bench/run.sh research_gaps_baseline_wmr`: exit 0, all 14 sets.
- Parent compared recursive JSON excluding only new words_modified* keys and nondeterministic load/timing fields: every existing value equal. Every detail TSV byte-identical.
- Parent checked denominators against words/rows, mutually exclusive count bounds, and percentage arithmetic on every JSON: PASS.
- Independent read-only spec/code review: no concrete defects reported.
- Retained corrected-harness baseline: `full-baseline-wmr/`.

## #293 implementation detail

The view computes candidate likelihood ratios directly from drawn Cell rectangles, instead of converting local offsets into the static Language key grid. This handles stretched Arabic rows and unequal key widths without a second geometry approximation. Ratios use picked-cell pitch and actual centres, sigmaX=0.45 key pitch, sigmaY=0.55 row height, log ratio clamped -8..0. They discount existing substitution weights only.

Touch data is ephemeral, current-word memory. Non-touch input falls back to the old model. The shared WordTouchBuffer aligns evidence with the full preceding-text snapshot and exact prefix; changes invalidate it. No touch measurements are persisted.

Parent found and fixed a child implementation error before gates: substitution weights initially used the typed letter rather than the candidate letter (typed ratio is always 1). Final code uses the candidate letter.

### Verification and rejected experiments

- No-touch dictionary public inspection: 284,832 completion/correction rows. Baseline and touch-v1 dumps are byte-identical (`cmp` exit 0). JShell logs have no errors/exceptions. Frozen parity input files are in `parity-data/`.
- Kotlin formatting and whole-project source lint: exit 0 (`touch-format.log`, `touch-lint.log`). Desktop compile: exit 0 (`touch-desktop-build.log`).
- Native Editor 11-command probe: real touch changes visible strip/auto decision; set/backspace clear evidence; unit weights restore baseline. All replies successful. Logs: `touch-editor*`.
- v1 APK build: exit 0, retained in `artifacts/touch-v1-debug.apk` with SHA-256 sidecar. NOT installed. This APK is the rejected v1 experiment, not the latest source.
- Touch generation: seed 570293, 5 attempts/sentence, first 800 input sentences. 1,752 English, 1,891 Arabic, 2,504 Jordanian rows. Synthetic Gaussian conditional letter taps, idealized zero drawing gap, no special-key/gesture errors. Outside-letter and special-key samples are discarded, not forced into letters.
- Three touch files reproduce byte-for-byte. Legacy generator produces byte-identical four-column output to HEAD for the same three inputs.
- Python/shell syntax passed; passing touch TSV to the LM builder exits nonzero with the expected training prohibition. `touch-training-guard.log`.
- Independent source reviews resolved: common picked-pitch covariance is an intentional locally homoscedastic model; private Arabic helper only receives prevalidated alignment; safe fallbacks and command-level logging are not defects. Parent fixed synthetic generator's initial special-key/outside-screen clamping before generating any retained dataset.

**v1** multiplied old key-centre substitution priors by touch ratios. Aligned row comparison returned exit 2: 46 targets lost, 27 gained, 52 new/changed wrong replacements, 145 removed, 43 correct autos lost (38 became manual). Rejected.

**v2** replaces the old spatial weight with the touch likelihood when supplied, falling back to the exact old weight for missing evidence. This avoids applying two spatial penalties to the same observation. Format/lint/desktop compile each exited 0. The same 6,147-row comparison still returned exit 2 (`TRADEOFF_APPROVAL_REQUIRED`):

| Non-word metric | English control → v2 | Arabic control → v2 | Jordanian control → v2 |
|---|---|---|---|
| Correct auto-fix % | 25.68 → 25.74 | 2.72 → 2.04 | 2.02 → 1.60 |
| Intended in strip % | 33.43 → 33.25 | 18.50 → 18.56 | 16.29 → 16.16 |
| Wrong fix % | 19.62 → 15.51 | 2.27 → 1.65 | 2.64 → 2.18 |

Row gate (including real-word rows): 39 targets lost vs 30 gained; 49 new/changed wrong replacements vs 134 removed; 34 correct automatic fixes lost, including 30 auto-to-manual transitions. Aggregate improvement in wrong-fix rate does not waive these losses.

Full per-row details and changed target replacements are in `touch-v2-comparison/comparison.json`; headline table is `touch-v2-comparison/summary.txt`. Command lines retained in `touch-v1-commands.log` and `touch-v2-commands.log`. Dataset hashes in `touch-data.json`.

**Limits:** These new touch sets are synthetic, noisy, and include multi-substitution words. Their overall percentages are not directly comparable to the original four-column typo sets or real phone typing. No clean sentence rows were run in the touch-only comparison; its zero clean-corruption count does NOT establish clean-text preservation. Null-touch whole-strip replay after final integration, final APK and real-device acceptance remain open. The complete earlier 14-set rerun validated the metric-only phase, not the integrated touch candidate.

**Stop condition:** Both bounded scoring attempts failed. No third tuning loop. Board #293 is in review, blocked on m57's decision; todo #6 stays in progress, not completed. Tasks #7–#15 have not been implemented. Recommended next choice: defer rejected touch scoring, preserve snapshots/evidence, and continue the remaining approved cards against the metric-only baseline. No runtime source restoration or scope change has been performed without that decision.


Latest frozen manifest SHA-256: `170565d7d409a7db5abd0b9319520e95c9a0c9de3e0e33fea3e8393e24b75f52`. Source/jar/asset working-tree check passed after the final edit.

## User decision: touch deferred

m57 explicitly selected **Defer touch; continue other cards**. Touch production/UI/desktop wiring was
restored to baseline; snapshots and synthetic evaluation tooling remain. No failed touch model is active.

## #294 original English misspelling rules

- Added52 original directional substring rules, validated TSV, pure Kotlin generator capped at two rewrites.
- New source is manual-only, gated on no exact/one-edit prefix candidates; correction policy unchanged.
- Curation did not use restricted spelling datasets; packaged provenance in assets/NOTICE.txt.
- Full14 sets and22,252 aligned typo rows:0 lost targets,0 new wrong replacements,0 correct-auto losses,
  4 new offered targets: lisence/license, avoued/avoid, aparant/apparent, steffen/stephen.
- Clean next-word/completion/KSR/false-AC scores unchanged. Lint/desktop compile and read-only review pass.
- Optional automatic rule replacement was measured ONLY in an isolated scratch copy:3 new wrong replacements
  and no extra offered targets. It was not adopted. Current source is the safe suggest-only variant.
- Evidence: rules-v1-comparison/, rules-auto-comparison/, engines/rules-{v1,auto}, rules-auto.patch.

## #295 learning storage

- Persisted bounded500 typed->replacement pairs per language. Undo saves, exact manual acceptance removes,
  and clear-data erases them. All writes use the same learning/private/field/layout/mode guard as words.
- Counts halve per14-day epoch on load; words/pairs have separate per-entry epochs, keeping new words' first
  window. Legacy three-field JSON migrates safely; rejection storage does not decay.
- Parent fixed child migration not being marked dirty and removed per-use resetting of the decay epoch.
- Real-store/Editor inspection passed migration persistence, window boundaries, fresh entry preservation,
  pair decay, repeated-load safety, zero eviction, rejection reload/specificity/LRU, clear, new-field undo,
  manual acceptance, and learning-off. This uses desktop in-memory prefs, not Android disk/editor proof.
- Initial ktlint formatter returned1 for a line it subsequently wrapped. Second formatter and full lint
  exited0; desktop build and inspection exited0. Full14-set comparison against rules-only: every behavioral
  score and22,252 typo rows unchanged. Evidence: learning-*; final APK/device gates still open.

## #297 retained training stem backoff

- Avoided repeating the18-minute,5.3GB full OPUS build. Aggregated retained train-only counts, preserving
  source row totals and surface followers. Selected intermediates reproduce bundled next_ar and split-trained
  jordanian_next byte-for-byte. No fresh evaluation context/follower counts were ingested.
- Lexical guards use unchanged words_ar/jordanian vocabulary, including pre-existing full-Shami spelling
  coverage. This is tuning evidence, not a blind unseen-language result or full morphological segmenter.
- Built7,873 MSA rows (852,662 bytes),2,421 dialect rows (187,022 bytes). Repeated output byte-identical;
  every pre-existing asset remained unchanged at generation time. Full metadata: stem-data.json.
- Parent fixed raw-vs-normalized Python frequency lookup; JVM/Python frequency/key parity passed on74,580
  source context forms. Lint/desktop builds and source review pass.
- v1 added a lower model before exact rows:4 manual targets gained, none lost, no auto/corruption changes;
  but13 prediction checks declined. Strict gate exit2.
- v2 restricted stem use to contexts with no observed or learned row: all22,252 typo rows unchanged, no
  new wrong fixes/auto losses. Some old sets gain0.01–0.02 points; jo_chat_msa next3 declines3.57->3.56.
  Fresh Arabic next3 remains17.61; fresh Jordanian remains11.51. Strict gate still exits2.
- Stop after two variants. Recommend deferring runtime integration rather than shipping1.04MB of tables
  for marginal benefits. Board#297 and todo#13 await m57's decision. Nothing installed or published.
- Evidence: engines/stem-{v1,v2}, stem-v*-comparison/{comparison.json,summary.txt}, stem-v*-full.log.

## #297 closed; final integration

m57 chose a redesign; the coverage diagnostic (validated against full-bench word counts) showed lookup-only gains under 0.3 pt (alef/ى folding and prefix peeling recover 0.2-1.2% of events; Jordanian two-word tables are the real bottleneck). Closed without runtime change; stem tables/key class removed from app, tooling and evidence kept.

## Final gates (engines/final, manifest c9f0e7c3...ae92, working tree matches)
- ktlint whole project, assembleDebug, desktop installDist: exit 0.
- Full 14-set replay vs corrected baseline (full-baseline-wmr): ROW_GATE_PASS over 22,252 rows: 4 targets gained, 0 lost, 0 new wrong, 0 correct-auto lost, 0 clean corruption; all clean metrics identical. Desktop p95 lower than baseline on every set.
- Sentinels (quality_sentinels.jsonl): 0 failed checks on baseline and final.
- APK: artifacts/final-debug.apk sha256 b4a250241fbb0255bd4522414a31d409a9658edca342308043dcfaecdf31f7fc (built, NOT installed).
- Open: physical-device acceptance (m57 gives adb port), including undo->new-field retype, persistence across restart, and real-device latency.
