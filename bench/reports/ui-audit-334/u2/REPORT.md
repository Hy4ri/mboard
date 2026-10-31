# U2 keyboard surfaces — #334 / #335

Base: `61a4c32`, then fast-forward `git merge main` to `d140ece` for the final landscape tokens.
Branch: `ui/334-u2-keys`.

## Implementation

- A-01: operator/page-switch labels use an LTR isolate; label fit reserves 8 dp rather than 4 dp. Key codes, layout rows and language engine fields are unchanged.
- K-01: `KeyboardView.enterIcon: Int` is a drawable resource id, default `ic_keyboard_return`. Zero draws `enterLabel`; nonzero draws the icon. `enterLabel` supplies the accessibility action word. Both setters invalidate. U3 selects the icon/action. No `enterDescription` property. Existing `ic_search` and `ic_send` vectors were already present on the base; no assets were added.
- K-02: alternates use `BoardUi.raised`, outlined boundaries, 40 dp desired cells, and view-width clamping. Preview also uses `raised` and an edge.
- K-03: key labels use KEY_LABEL / KEY_LABEL_LONG; popup and preview use KEY_POPUP (26 dp, as the accepted token table specifies). All key text intentionally stays fixed-dp, independent of font scale.
- K-04: shift/caps use `accentContainer` plus their distinct existing icons.
- K-05: retained the virtual-key accessibility ring; added a host focus boundary when the host is focused. Hardware keyboard navigation itself is not added or verified (optional audit item).
- O-01: thumb rail uses Full width / Move keyboard icon buttons, accessible descriptions and the caption token.
- O-02: Open all emoji target is 48 dp. Recent-emoji rows adapt to available body height; cells use the emoji token. Dial labels use fixed-dp KEY_LABEL.
- O-03: intentionally unchanged: no new gesture hint or extra control (audit says no change).
- L-01: public `keysHeight` remains pixels. Landscape target is 60% of configured window height minus fixed 48/40 dp toolbar/strip. The 34 dp row minimum wins, per final lead ruling. No row or toolbar is hidden. The configured portrait body stays stable across letter/symbol/pad pages.
- L-02: landscape key geometry is centered and capped at 640 dp.
- Decision 4: main keys, dial keys and function buttons use a 1 dp keyEdge outline when BoardUi.needsOutline(fill). User colors are not replaced.
- Manual release/preview animation checks `ValueAnimator.areAnimatorsEnabled()`. Disabled system animators skip the manual fade/grow and post-release preview delay. Typing/hold/repeat gesture timing remains unchanged.

## API for U3

```kotlin
keyboard.enterIcon = R.drawable.ic_check // 0 for visible text such as calculator '='
keyboard.enterLabel = "Done"            // spoken action; visible only when enterIcon == 0
val bodyHeightPx = keyboard.keysHeight
```

`enterIcon` is documented as a drawable resource id. No annotation dependency was added to this dependency-free app.

## Gates

All heavy commands used `/tmp/crew-mboard/bench.lock`.

Final build + full replay (exit 0):

```sh
flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug; cd bench; bash run.sh u2-334-final2 --assets /tmp/u2-binary-assets'
```

Asset overlay preparation (exit 0; preceding replay):

```sh
./gradlew :app:compileTables :app:compileWords
mkdir -p /tmp/u2-binary-assets
cp -a app/src/main/assets/. /tmp/u2-binary-assets/
cp -a app/build/generated/tableAssets/. /tmp/u2-binary-assets/
cp -a app/build/generated/wordAssets/. /tmp/u2-binary-assets/
```

Final full replay: `bench/results/u2-334-final2`.
Baseline: `/home/m57/Projects/mboard/bench/results/main-6959982-bin`.
Compared all baseline TSVs using Python byte equality: **24/24 byte-identical**; zero lost/gained target rows. All sentence and typo quality metrics are unchanged on every set (next-word, one-letter, found-before-end, savings, false autocorrect, typo fix/in-strip/wrong-fix and mismatch proxy). Timing is not byte-identical and is not claimed as a device latency measurement.

Summary command (exit 0):

```sh
python3 -I bench/scripts/summary.py bench/results/u2-334-final2 /home/m57/Projects/mboard/bench/results/main-6959982-bin
```

Binary-overlay probes (exit 0, under bench.lock):

```sh
bench/suggest.sh --json --assets /tmp/u2-binary-assets --lang en "I am goin"
# goin | going | gonna; auto=null
bench/suggest.sh --json --assets /tmp/u2-binary-assets --lang ar --dialect "شو "
# في | بدك | صار; auto=null
bench/suggest.sh --json --assets /tmp/u2-binary-assets --lang en "fixxit"
# fix it | fixxit | fixing; auto=null
```

## Contrast, code palette

| Pair | Dark | Light | Device custom palette |
|---|---:|---:|---:|
| text / key | 13.85 | 15.93 | 13.99 |
| text / function | 10.91 | 11.99 | 16.89 |
| key / background | 1.19 | 1.13 | 1.21 |
| function / background | 1.52 | 1.17 | 1.00 |

The runtime outline guard runs below 1.3:1. The mandated keyEdge outline is a boundary fallback, not a claim that custom fills meet 3:1.

## Device evidence and open gate

Device acceptance is **OPEN**, per lead's final instruction: phone disconnected; lead owns the remaining pass.

Earlier screenshots in this folder are partial evidence, not a completed matrix. Before screenshots are the audit's `screens/s02`–`s08`, `s30`–`s38`, `s46`. One whole device script held phone.lock through install, pull-back APK byte comparison (passed), navigation, screenshots and rotation restoration. EN letters, shift, caps, alternates, symbols, numpad, AR letters, AR symbols and AR more-symbols were inspected together with vision; those pages rendered without reported clipping. The first landscape/restored screenshots showed the keyboard dismissed, so they do NOT prove landscape acceptance. Later `landscape.png` / `preview.png` captures were taken but not inspected before the final device deferral; do not count them as accepted.

Open: final assembled U2+U3 Enter actions, landscape cap/minimum and width, preview, thumb modes, edge dial/recent emoji, custom-color outline visibility, hardware focus and final state-restoration confirmation. Earlier shots show the custom palette where function fill equals background. Light-device pass stays with the lead.

No unit/instrumented/screenshot tests, dependencies, pushes or merges to main were added/performed.
