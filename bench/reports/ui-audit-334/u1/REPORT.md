# U1 Settings delivery

Base: main d140ece. Owned Kotlin, manifest, launcher color, settings theme and two Material Symbols vectors only.

## Implementation

G-01/16: activity-local NoActionBar-equivalent theme, 56 dp header, centered 640 dp maximum column; normal ScrollView.
G-02: Accent preset / Accent override.
G-03: token switch tracks drawn opaque (stock track alpha otherwise destroys contrast).
G-04: custom Checkable row with weighted label and right radio, plus preset swatches. Explicit MATCH_PARENT list layout fixes content-width measuring.
G-05: danger positive action, neutral Cancel (important: Crimson accent ink equals danger in dark mode).
G-06/07: keep gesture instructions in Playground; shorten learning/Arabizi/auto-correction details and move qualifications into a note.
G-08: live contrast ratio and warning below 3:1; text against keys, background against text, other roles against background. Invalid hex shows an error line and disables Apply. Low contrast never disables Apply or modifies a chosen color.
G-09: 48 dp swatches sourced from tokens/presets.
G-10: disabled Save uses neutral surface/muted ink and standard disabled alpha, plus distinct helper reasons (empty/full/duplicate).
G-11/12: concise home appearance subtitle; layout notes follow related controls.
G-13/14/15: external navigation icon; one account pick per tap; playground preview link.
D-01/Z-01/C-05: token radii, launcher background matches dark bg, trash icon for saved phrase deletion.

Y-01 is not fixed by U1: landscape keyboard height belongs to U2/U3. Integration/device verification remains required.

## Gates

`flock /tmp/crew-mboard/bench.lock nix develop -c bash -c 'set -euo pipefail; ktlint -F "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ktlint "app/src/main/java/**/*.kt" "bench/src/**/*.kt"; ./gradlew assembleDebug'`

Passed before and after merging main d140ece. Final gate rerun recorded by delivery session. No tests added. Bench and suggest probes not run: no engine or service behavior changed.

Light palette switch track/surface ratios from accepted token math: off 3.18:1; on violet 3.65, teal 3.42, amber 3.49, crimson 5.06. Device light pass remains with lead.

## Device evidence and remaining gate

PNG files in this directory are the interrupted first portrait pass on 2026-10-30. They are historical, not final acceptance. Some files were captured while another unit's APK was installed; filenames alone are not evidence of final code. The earlier dialog-accent image exposed radios not aligned; explicit MATCH_PARENT layout was added afterwards. Final verification is OPEN.

Phone disconnected on 2026-10-31. Lead requested code/build/commit without more device work. Required final pass: home; all pages; color (including invalid hex and live updates), accent and confirm dialogs; phrase helper states; account one-pick behavior; portrait, landscape, font_scale 1.3 (restore original), plus integrated playground keyboard visibility. Confirm header/column dimensions and switch tracks visually. Restore all changed settings. No font-scale or rotation changes were made by U1's interrupted pass.

The temporary common/decision files were missing after restart. Their complete earlier contents remain in this session; code review used that contract and main's new tokens. No edits to BoardUi/Tokens.
