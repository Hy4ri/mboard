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
Digit-free candidates require frequency >= 3,000 and neither an English word nor an English prefix.

### Deliberate guard exception to the examples

`shu` and `sho` do **not** get Arabic suggestions. They are shipped English words (`words_en.tsv`:
1,251 and 392 respectively). They retain their exact English strips. This follows the stronger
valid-English-word non-interference requirement, rather than silently creating a Franco allowlist.
`kifak`, `inshallah`, `yalla`, and the numeric examples do get Arabic readings when enabled.
`inshallah` and `yalla` are protected English chat spellings but not lexical entries; their literal
slot and automatic decision remain unchanged.

## Commands and exits

Final gate (exit **0**), from `/home/m57/Projects/mboard-302`:

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c \
 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; \
 ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug; \
 cd bench; bash run.sh arabizi-302; bash run.sh arabizi-302-on --arabizi'
```

Both ktlint commands, `assembleDebug`, and both complete benchmark runs returned 0. Full command log:
`/tmp/crew-mboard/302-final-gates.log`. Results: `bench/results/arabizi-302{,-on}` (gitignored).
Baseline: `/home/m57/Projects/mboard/bench/results/crew-baseline-dccc420`; both directories and JSON
files were checked before comparisons.

```sh
python3 -I bench/scripts/summary.py bench/results/arabizi-302 \
 /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302 --out /tmp/crew-mboard/302-compare
python3 -I bench/scripts/quality.py compare \
 --baseline /home/m57/Projects/mboard/bench/results/crew-baseline-dccc420 \
 --candidate bench/results/arabizi-302-on --out /tmp/crew-mboard/302-compare-on
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

Enabled: automatic fix rate, wrong-fix rate, false-autocorrect, clean early completion, savings, and
next-word metrics remain unchanged on every set. Arabic/Jordanian rows are unchanged. English typo
alternatives change: **76 intended English targets disappear, zero are gained**. This is why the
setting stays OFF by default. Do not describe enabled mode as zero-interference English typo recovery.

`metrics-table.txt` lists each set: clean found% / savings% / false-auto%, typo fixed% / wrong-fix%, and
baseline → enabled in-strip%. The default run equals every baseline entry in that table.

All 1,449 changed typo rows and 287 changed clean full-word fallback rows are in
`enabled-changed-rows.tsv`, with old/new choices and target-loss flags. Explanation codes:

- **A:** Enabled mode finds a frequent lexicon-attested Arabic skeleton for an unknown, non-prefix
  English token. Arabic replaces the last suggestion. The original English automatic decision and
  literal slot are unchanged. This also explains all 76 lost English typo targets.
- **B:** Enabled full-word fallback choices change for a non-dictionary token. No early-completion
  hit, automatic decision, or valid word changes. The file records the full row rather than hiding it
  because aggregate early-completion metrics stayed flat.

There are no gained intended target rows. See `enabled-summary.txt` for the 76 individual losses.

## Probes and actual manual pick

All requested one-shot probes ran under the lock inside `nix develop`:

```sh
bench/suggest.sh --lang en --arabizi --json WORD
```

| Input | Visible strip (enabled) | Auto |
|---|---|---|
| 7abibi | 7abibi · حبيبي · حبيب | none |
| shu | shu · shut · shit | none |
| kifak | kifak · kick · كيفك | none |
| 3arabi | 3arabi · عربي | none |
| ma3 | ma3 · مع | none |
| sho | sho · should · show | none |
| inshallah | inshallah · installation · انشالله | none |
| yalla | yalla · gallant · يلا | none |
| hello | hello · hell · yellow | none |
| the | the · there · they | none |
| good | good · god · goodbye | none |

`probes-and-latency.txt` contains the real JSON replies. An agent session set `7abibi`, picked slot 2,
and returned `text="حبيبي "`, `event="picked حبيبي"`. It then set `ma3` and typed space:
`text="ma3 "`, zero corrections. The production acceptance path uses the same literal Arabic choice
and existing checked whole-word commit; the desktop Editor mirrors the no-English-learning branch.
No device acceptance is claimed.

## Latency and remaining gates

A standalone JShell timing probe (500 warmups, 1,000 calls per string) measured pure skeleton expansion:
normal examples p95 0.002–0.025 ms; adversarial 14-character vowel input hit the 600-skeleton bound at
p95 0.2564 ms (max 1.0948 ms). This excludes lexicon lookup, strip assembly, IPC and Android drawing.
The full enabled desktop sentence p95 is retained in `enabled-summary.txt`; e.g. en_chat 413 → 444 µs,
en_subs 166 → 190 µs, en_tatoeba 183 → 188 µs. These single full-run timings are not device latency.

The physical keyboard/settings/pick check remains open. No phone port was supplied to this worker.
The `shu`/`sho` policy conflict and enabled English typo losses are explicit review decisions, not
claims of completed zero-interference acceptance. No push or merge was performed by this worker.
