# Card 302: Arabizi suggestions — narrowed trigger (v3)

## Scope

Opt-in, default OFF. English-layout Arabic candidates only when input has a Franco digit mixed with
letters (2,3,5,6,7,8,9), OR is explicitly listed in `app/src/main/assets/arabizi_allowlist.txt`.
There is **no broad unknown-English-word/strong-Arabic-match fallback**. The rejected v1/v2 fallback
and its 76 unrelated English target losses are removed.

The editable asset has one spelling per line, with comments/blanks allowed: shu, sho, yalla, inshallah,
wallah, habibi, habibti, khalas, mashallah, ya3ni, yani, salam, walla, kifak. Its readings must still
exist in the shipped Arabic/Jordanian lexicons. Non-digit readings require loaded frequency >=3,000.
Allowlisted readings take only the last occupied slot: typed word and best English suggestion remain.
No automatic correction into Arabic. Arabic picks do not enter the English learning stores.

Expansion is bounded to 600 distinct skeletons, 4,096 recursive steps and 14 input characters. Existing
Arabic normalization and lexical frequency rank the retained words (Jordanian tiers use the existing
60,000 / 15,000 / 4,000 scores). No vocabulary, tests or dependencies were added.

## Final commands and exits

From `/home/m57/Projects/mboard-302`, final execution exit **0**:

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c \
 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; \
 ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug; \
 cd bench; bash run.sh arabizi-302-v3; bash run.sh arabizi-302-v3-on --arabizi'
```

Both ktlint commands, `assembleDebug`, both complete benchmark runs: exit **0**.
Log: `/tmp/crew-mboard/302-v3-final-gates.log`.

Both candidate directories and the baseline JSON files were checked before comparison:

```sh
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302-v3 --out /tmp/crew-mboard/302-v3-compare
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302-v3-on --out /tmp/crew-mboard/302-v3-compare-on
```

Both exit **0**. OFF reports `ROW_GATE_PASS`; ON reports `TRADEOFF_APPROVAL_REQUIRED`. Comparator
exit 0 means the comparison ran, not that the behavior met every acceptance requirement.

Earlier failed attempts were superseded: outer Java 17 caused UnsupportedClassVersionError, a lock
wait timed out, and an orchestration script edited while waiting exited 127. Final commands above
were immutable. Full final results are under `bench/results/arabizi-302-v3{,-on}` (gitignored).

## Default OFF: unchanged

Every behavioral metric on all 14 sets is unchanged. All 22,252 typo rows and all clean `.misses.tsv`
files are byte-identical to the crew baseline. Zero lost/gained targets, changed automatic decisions,
or new valid-word corruption. `default-summary.txt` contains per-set results and desktop latency.

## Enabled: 2 residual overlap losses, not zero

Removing broad matching restores all 76 unrelated English typo targets lost by v2. Only **three typo
rows change** now:

| Set and TSV line | Input → intended | Before | After | Target lost |
|---|---|---|---|---|
| en_subs:1003 | sho → show | sho / should / show | sho / should / شو | yes |
| en_subs:2535 | Salam → Salma | Salam / Salami / Salad | Salam / Salami / سلام | no |
| en_tatoeba:2033 | sho → show | sho / should / show | sho / should / شو | yes |

Zero gained targets. All automatic fixes, wrong fixes, false autocorrections, next-word metrics and
headline non-word typo in-strip rates remain unchanged. `sho` is a valid English word, so its two lost
intended targets are real-word typo rows, not visible in the non-word headline rate.

**The initial zero-lost-English-row target is not met.** It conflicts with simultaneously offering
Arabic for `sho`, preserving literal `sho`, and keeping best English suggestion `should` in three slots.
The lead reviewed and accepted exactly these two opt-in sho/show losses. No other target loss is
accepted. No context-specific exception or vocabulary/ranking change was invented.

The same overlap adds 47 clean miss rows, all for `show`/`Show`: typing prefix `sho` now displaces its
third-slot English completion. Enabled clean deltas (percentage points):

| Set | Found-before-end | Savings |
|---|---:|---:|
| en_chat | -0.10 | -0.01 |
| en_chat_learning | -0.10 | -0.02 |
| en_fresh | -0.01 | -0.01 |
| en_subs | -0.05 | -0.01 |
| en_tatoeba | -0.03 | -0.01 |

Arabic/Jordanian sets are unchanged. `metrics-table.txt` lists every set's baseline → enabled clean
found% / savings% / false-auto%, typo fixed% / wrong-fix%, and in-strip%. `enabled-changed-rows.tsv`
contains all three typo changes and 47 newly recorded clean miss rows. `<no miss row>` means the
baseline had an early completion. `enabled-summary.txt` retains full comparator output.

## Probes and actual picks

Final probes ran without bench.lock, inside `nix develop`, exit **0**:

```sh
bench/suggest.sh --lang en --arabizi --json WORD
```

| Input | Visible strip | Auto |
|---|---|---|
| 7abibi | 7abibi / حبيبي / حبيب | none |
| shu | shu / shut / شو | none |
| kifak | kifak / kick / كيفك | none |
| 3arabi | 3arabi / عربي | none |
| ma3 | ma3 / مع | none |
| sho | sho / should / شو | none |
| inshallah | inshallah / installation / انشالله | none |
| yalla | yalla / gallant / يلا | none |
| hwad | had / hwad / head | had |
| rown | rown / rowan / down | none |
| lsot | lsot / lot / lost | none |
| hello | hello / hell / yellow | none |
| the | the / there / they | none |
| good | good / god / goodbye | none |

The restored typo probes retain their English alternatives. An agent set `shu`, picked slot 3 and
returned `text="شو "`. Setting `sho` and typing space returned `text="sho "`, zero corrections. The
production checked whole-word commit path and desktop Editor use the same candidate text; both skip
English learning for Arabic picks. `probes-and-latency.txt` contains the real JSON output.

## Timing and remaining gates

Skeleton generation did not change during the narrowing. Its earlier standalone JShell measurement
(500 warmups, 1,000 calls/string) gave normal-example p95 0.002–0.025 ms, adversarial 14-character vowel
input p95 0.2564 ms at the 600-skeleton bound. This excludes lexicon lookup, editor IPC and UI drawing.
Final enabled full-run desktop p95 is retained: e.g. en_chat 413 →466 µs, en_subs 166 →210 µs,
en_tatoeba 183 →233 µs. These single full-run timings are not Android performance acceptance.

Lead review accepted the two opt-in sho/show rows. Physical-device settings/pick acceptance remains
open. No phone port was supplied to this worker. No push or merge by this worker.
