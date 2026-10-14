# Suggestion replay bench

This standalone JVM project measures the keyboard's real `WordDictionary`, `WordSuggestions`, `UserWords`, language definitions and layouts. Small desktop Android stand-ins supply asset files and in-memory preferences. `Strip.kt` mirrors the ordinary-text-field glue from `MboardService`: keep both in step when that glue changes.

It is not an app module, unit/instrumented test suite, CI gate, Android emulator, or measure of real touch feel.

## Run

From the repository root:

    nix develop -c bash -c 'cd bench; bash run.sh candidate'
    python3 -I bench/scripts/summary.py bench/results/candidate bench/results/baseline_corrected

The runner compiles the JVM bench and bounds execution to two simultaneous JVMs. Results live under `bench/results/<label>/` (gitignored): one JSON report per dataset, `.misses.tsv` sentence details, and `.typos.tsv` per-typo outcomes. `lm/seeds/words_{en,ar}.tsv` fixes typo classification to the original dictionary, so changing the app's vocabulary cannot silently change the benchmark denominator.

Single dataset, after `installDist`:

    cd bench
    build/install/mboard-bench/bin/mboard-bench --lang ar --dialect --sentences data/jo_chat.txt --typos data/jo_chat_typos.tsv --label jordanian --out results/probe

Use `--learning` for an ordered online-learning run. Preferences are isolated per process. The replay feeds the intended words into learning, not an actually auto-corrected/accepted editor transcript; this controlled oracle history separates personalization from cascaded typing errors. The previous-word bug in the initial bench was fixed: use the rerun `baseline_corrected`, not the original `baseline`, when comparing learning runs.

## Metrics and limitations

- `next_word_top3_pct`: intended word appears among the three displayed choices before typing, with preceding context. Sentence starts are reported separately.
- `one_letter_top3_pct`: choice availability after exactly one letter, even when it was already predicted at zero letters. Includes one-letter words; it is not strictly a measure of unfinished-word completion.
- `found_before_end_pct`: intended word appears before all letters are typed.
- `keystroke_savings_pct`: idealized word-entry actions (letters plus space versus letters plus a suggestion tap). Punctuation, navigation, correction backspaces, and the trailing-space interaction with punctuation are not simulated. Do not call this measured typing-speed improvement.
- `false_autocorrect_pct`: change to an exactly typed corpus word, excluding case and limited Arabic spelling normalization. Source corpora also contain mistakes, and legitimate English contractions can be counted as changes. Inspect the failure TSV, not just the percentage.
- Typo headline percentages use **non-word rows only**, with `scored_rows` as denominator. Real-word errors are reported separately as `~real_word_typo`; distance buckets beginning with `~` are additional views and do not contribute twice to totals. `rows` is the full input count.
- Latency is desktop JVM wall time (warm and cold calls mixed), not Android main-thread latency. Loading, editor IPC, UI drawing, scheduling and memory must also be checked on a phone.
- Default setting scenarios: ordinary field, no saved phrases, no emoji suggestions, no active selection, caret at word end, Shift/caps off. Password/address behavior and mid-word/editor compatibility need separate device acceptance.
- The fixed dataset is reused across development iterations: measured deltas are comparative engineering evidence, not a never-seen final validation set or proof of superiority over other keyboards. Jordanian and English chat sets and hand-written Arabic errors are synthetic.

## Dictionary compatibility inspection

`scripts/dictionary_parity.jsh` is a standalone desktop JShell inspection, not a unit/CI test source. Compile the old and new bench distributions, provide each jar classpath, and use identical assets/data:

    jshell --class-path "$CP" -R-Dmboard.assets="$ASSETS" -R-Dmboard.data="$DATA" -R-Dmboard.output="$DUMP" scripts/dictionary_parity.jsh

It emits ordered dialect-OFF completion choices and lexical corrections over dictionary prefixes and typo inputs. Compare the dumps with `cmp`; also inspect JShell logs for snippet errors because JShell can exit zero on compilation errors. Phase 2's final old/new dumps matched over 156,733 rows. This does not prove UI/device behavior or dialect-ON ranking quality; those require the replay and phone gates separately.

## Fresh validation sets

`en_fresh`, `ar_fresh` (Tatoeba, seed 57485, 2,000 lines each) and `jo_human_fresh` (held-out Shami blocks) are disjoint from every older evaluation line (`data/fresh_validation_manifest.json`). `jo_human_fresh` is held out only from the continuation counts: the older installed model and `jordanian.tsv` vocabulary saw that corpus, so compare engines only when both are built from `lm/jo_shami_train.txt`. Phase 3 result (split-trained counts on both sides, desktop replay): the clitic-stem fuzzy suggestions add +0.25 to +2.44 points of typo-in-strip on Arabic sets, with identical autocorrect, false-autocorrect and English numbers, and identical dictionary parity over 160,546 rows.

## Data and rebuilding

`data/SOURCES.md` documents benchmark inputs and licensing. Public misspelling datasets are benchmark-only: they are not used to build shipped dictionaries or models. Generated touch errors are synthetic proxies, not collected finger traces.

`lm/SOURCES.md` documents training inputs, excluded subtitle windows, Shami attribution, synthetic chat, weighting, and asset generation. Run from `bench/lm/`:

    bash rebuild.sh
    python3 -I make_assets.py

Rebuilding downloads and decompresses large subtitle streams; English/Arabic jobs run serially to avoid RAM exhaustion. Defaults retain 30,000 one-word rows, 60,000 two-word rows, eight followers per row. Original curated seeds are immutable inputs in `lm/seeds/`; asset generation is idempotent. The output tables preserve observed totals and follower counts for runtime backoff scoring. Jordanian rows also incorporate original curated continuations with explicitly synthetic counts.

Training totals are approximate counts after singleton-context pruning, and compressed-byte window boundaries can split a line. They should not be described as unpruned complete-corpus maximum-likelihood counts.
