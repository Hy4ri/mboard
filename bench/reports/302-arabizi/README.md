# Card 302: Arabizi suggestions

## Scope and result

Opt-in (default OFF) Arabic-script suggestions on the English layout. Only shipped Arabic/Jordanian
lexicon entries can appear; no transliterated guess is committed automatically. Arabic readings do not
enter English learning stores. Settings, production strip and desktop Editor/Strip are wired together.
No tests, dependencies, asset vocabulary, or automatic-correction decisions changed.

The feature is not a general transliterator. Vowels have bounded optional readings; words over 14
characters are skipped. Expansion stops at 600 distinct skeletons or 4,096 recursive steps. Digraphs
and numeric conventions are in `Arabizi.kt`. Matching uses the existing Arabic normalization; candidates
rank by loaded lexical frequency (Jordanian tiers use the existing 60,000 / 15,000 / 4,000 mapping).
Digit-free candidates require frequency >= 3,000; non-allowlisted input must be neither an English word nor an English prefix.

### Explicit Franco allowlist

Lead review requested an editable allowlist for English-overlapping Franco words. The bundled
`app/src/main/assets/arabizi_allowlist.txt` has one spelling per line: shu, sho, yalla, inshallah,
wallah, habibi, habibti, khalas, mashallah, ya3ni, yani, salam, walla. Blank lines/comments are allowed.
Only when the toggle is ON may an allowlisted input bypass the English word/prefix guard. It must
still expand to an existing frequent Arabic word. Its Arabic reading takes only the last available
slot, preserving the typed word and best English suggestion, including for digit-bearing `ya3ni`.
No exceptions are hardcoded into ranking logic. Non-allowlisted valid English words remain protected.

## Commands and exits

Final gate (exit **0**), from `/home/m57/Projects/mboard-302`:

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c \
 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; \
 ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug; \
 cd bench; bash run.sh arabizi-302-v2; bash run.sh arabizi-302-v2-on --arabizi'
```

Both ktlint commands, `assembleDebug`, and both complete benchmark runs returned 0. Full command log:
`/tmp/crew-mboard/302-v2-final-gates.log`. Results: `bench/results/arabizi-302-v2{,-on}` (gitignored).
Baseline: `/home/m57/Projects/mboard/bench/results/crew-baseline-dccc420`; both directories and JSON
files were checked before comparisons.

```sh
python3 -I bench/scripts/summary.py bench/results/arabizi-302-v2 \
 /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302-v2 --out /tmp/crew-mboard/302-v2-compare
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302-v2-on --out /tmp/crew-mboard/302-v2-compare-on
```

All three commands exited 0; the enabled comparison explicitly reports `TRADEOFF_APPROVAL_REQUIRED`.
The comparator's exit code is not a behavioral acceptance decision.

Earlier attempts are not acceptance evidence: running the probe outside Nix selected Java 17 and failed
with `UnsupportedClassVersionError`; one lock-waiting probe timed out. A first orchestration script
was edited while waiting and exited 127 after its successful build/probes. The final gate above was
rerun from an immutable command and exited 0.

## Full benchmark results

Default OFF: every behavioral metric is unchanged on all 14 sets. All 22,252 typo rows and all clean
`.misses.tsv` files are byte-identical. Zero lost/gained targets, changed automatic decisions, or new
valid-word corruption. `default-summary.txt` has the per-set results and latency.

Enabled: automatic fix rate, wrong-fix rate, false-autocorrect and next-word metrics remain unchanged
on every set. Arabic/Jordanian rows are unchanged. The allowlist causes small English clean changes:
found-before-end drops 0.01–0.10 percentage points; savings drops 0.01–0.02 points. English typo
alternatives change: **78 intended English targets disappear, zero are gained**. This is why the
setting stays OFF by default. Do not describe enabled mode as zero-interference English typo recovery.

`metrics-table.txt` lists each set: clean found% / savings% / false-auto%, typo fixed% / wrong-fix%, and
baseline → enabled values. The default run equals every baseline entry in that table.

All 1,452 changed typo rows and 334 changed clean miss/fallback rows are in
`enabled-changed-rows.tsv`, with old/new choices and target-loss flags. Explanation codes:

- **A:** Enabled mode finds a frequent lexicon-attested Arabic skeleton for an unknown, non-prefix
  English token, or for an explicitly allowlisted Franco input. Arabic replaces the last suggestion. The original English automatic decision and
  literal slot are unchanged. This also explains all 78 lost English typo targets.
- **B:** The same clean miss row remains but its full-word fallback choices change. The original
  automatic decision is unchanged. Repeated rows are aligned by context/target/correction and matching
  rows are removed before remaining changes are paired.
- **C:** An allowlisted Franco token is also an English prefix. Its last-slot Arabic reading can
  displace a later English completion. There are 47 newly recorded clean miss rows; `<no miss row>`
  denotes that the baseline had an earlier completion (or a different diagnostic-row status). The
  small aggregate early-completion/savings declines above remain visible, not silently accepted.

There are no gained intended target rows. See `enabled-summary.txt` for the 78 individual losses.

## Probes and actual manual pick

All requested one-shot probes ran inside `nix develop` without the heavy-job lock, as the lead requested:

```sh
bench/suggest.sh --lang en --arabizi --json WORD
```

| Input | Visible strip (enabled) | Auto |
|---|---|---|
| 7abibi | 7abibi · حبيبي · حبيب | none |
| shu | shu · shut · شو | none |
| kifak | kifak · kick · كيفك | none |
| 3arabi | 3arabi · عربي | none |
| ma3 | ma3 · مع | none |
| sho | sho · should · شو | none |
| inshallah | inshallah · installation · انشالله | none |
| yalla | yalla · gallant · يلا | none |
| hello | hello · hell · yellow | none |
| the | the · there · they | none |
| good | good · god · goodbye | none |

`probes-and-latency.txt` contains the real JSON replies, including all allowlist examples. An agent
session set `shu`, picked slot 3, and returned `text="شو "`, `event="picked شو"`. It then set `sho`
and typed space: `text="sho "`, zero corrections. Picking slot 2 for `7abibi` returned `text="حبيبي "`. The production acceptance path uses the same literal Arabic choice
and existing checked whole-word commit; the desktop Editor mirrors the no-English-learning branch.
No device acceptance is claimed.

## Latency and remaining gates

The unchanged skeleton implementation was measured with a standalone JShell timing probe
(500 warmups, 1,000 calls per string), measuring pure expansion:
normal examples p95 0.002–0.025 ms; adversarial 14-character vowel input hit the 600-skeleton bound at
p95 0.2564 ms (max 1.0948 ms). This excludes lexicon lookup, strip assembly, IPC and Android drawing.
The full enabled desktop sentence p95 is retained in `enabled-summary.txt`; e.g. en_chat 413 → 490 µs,
en_subs 166 → 226 µs, en_tatoeba 183 → 229 µs. These single full-run timings are not device latency.

The physical keyboard/settings/pick check remains open. No phone port was supplied to this worker.
The lead resolved the `shu`/`sho` policy with the editable allowlist. Enabled English losses and the
small clean-completion declines remain explicit review evidence, not zero-interference acceptance. No push or merge was performed by this worker.
