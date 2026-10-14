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

## Single lookup and live typing

`suggest.sh` answers one question: what would the strip show for this text, caret at the end. A trailing space (or empty text) shows next words; otherwise it completes the word under the caret. Each run is its own process with fresh in-memory preferences, so nothing is learned or carried over, and it writes a markdown log to `bench/logs/suggest/` (gitignored).

    bench/suggest.sh "I am goin"
    bench/suggest.sh --lang ar --dialect "شو "

`--live` turns it into a simulated text field: every key redraws the strip. Tab, F1-F3 or Alt-1/2/3 tap suggestion 1/2/3, Backspace right after an auto-correction undoes it, Ctrl-D quits. The log gains one table row per key. Piped input is read as keys and prints one line per key. Learning is on in live mode as in the keyboard (`--no-learning` turns it off), so a word typed twice is trusted for the rest of that session.

For agents and scripts, `--json` makes a single lookup print one JSON object, and `--agent` is live mode over JSON lines: one command per stdin line (`{"type":"hi "}`, `{"pick":2}`, `{"key":"backspace","times":3}`, `{"set":"..."}`, `{"wait":800}`, `{"state":true}`, `{"quit":true}`), one reply per stdout line with the text, the 3 suggestions, the auto-correction and what happened. Time is virtual (100 ms per key), so a session replays identically. Every command, every key and every reply goes to `<stamp>-agent.md` (readable) and `<stamp>-agent.jsonl` (machine). `bench/suggest.sh --help-agent` prints the full protocol. Stdout carries only the result; the dev-shell banner goes to stderr.

    printf '%s\n' '{"type":"I am goin"}' '{"pick":2}' | bench/suggest.sh --agent

All modes use the keyboard's real engine through `Strip.kt`; live mode adds `Editor` in `Live.kt`, which mirrors MboardService's separator handling, double-space period, undo and suggestion tap. Keep both in step with `MboardService.kt`. Not simulated: saved phrases, emoji slot, password/address fields, selection, a caret inside a word.

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
