# U3 service chrome

Base: d140ece. Owns MboardService.kt, SuggestionStrip.kt, bench Strip.kt.

Implemented: fixed six toolbar slots with selected state; emoji family toggle closes either mode;
password-disabled icons at alpha .5 with accessible reasons; active shield stays full alpha;
shield/password status suffix when there are no chips; numeric fields show no word chips;
saved-address chips middle-ellipsize; fixed body type, full 40 dp strip chips;
Enter drawable mapping via KeyboardView.enterIcon (0 = text) and enterLabel (accessible word);
calculator uses shared 48 dp panelHeader and neutral result action; search uses search glyph,
weighted/minimum 144 dp field, 48 dp-wide clear target, first-result accent container;
clipboard/emoji bodies use keysHeight without the old extra strip-height addition.

No text-editor operation changed. Live.kt is unchanged. Strip.show mirrors the numeric guard with
an optional numeric=false argument; existing text replays retain identical outputs.

## Verification

- `flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"'`: exit 0.
- `flock /tmp/crew-mboard/bench.lock nix develop -c ./gradlew assembleDebug`: exit 0 with a temporary
  enterIcon property stub in KeyboardView. Stub reverted immediately afterwards, NOT committed.
  Final integrated build requires U2's real enterIcon property. Do not claim this branch builds standalone.
- Binary asset overlay: copy app/src/main/assets into /tmp/crew-mboard/u3-assets and overlay generated
  app/build/generated/{tableAssets,wordAssets}/*.bin.
- `flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'cd bench; bash run.sh ui334-u3 --assets /tmp/crew-mboard/u3-assets'`: exit 0.
- Compared with /home/m57/Projects/mboard/bench/results/main-6959982-bin: **24/24 TSV files byte-identical**.
  All per-set quality deltas zero. No gained/lost targets. No numeric field replay rows exist in the
  reference, so decision 10 changes no existing rows. Latency is nondeterministic; see bench-summary.txt.

## Open device/integration gates

Phone disconnected: final screenshots deferred to lead. a-f PNGs are earlier development shots
(before the final header/body changes), NOT final acceptance evidence. g/h were stale-navigation
captures of settings, NOT search evidence. Retained only as historical captures; do not use for acceptance.
The prior audit shots are the before references.

Needs U2: `var enterIcon: Int` (drawable id, 0 = text), existing enterLabel, landscape-capped keysHeight.
Mapping: Done/check, Go/forward, Search/search, Send/send, Next/last_page, Previous/first_page,
plain/keyboard_return, Pick/check, calculator/0 with '='. Custom actionLabel uses keyboard_return icon
with the full label for accessibility. No new icons needed.

X-01 key outlines belong to U2 (not changed here). E-04 clear target height stays 40 dp, as the accepted
strip height is 40; its width is 48. Full 48 dp height would require changing the strip contract.
S-01 chips fill 40 dp (accepted token), not Android's ideal 48 dp. P-01 follows the decision to show
status only without chips, not an additional row. L-03 root already paints navigation insets with background.
