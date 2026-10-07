# Luna's mboard morning read

Status: no-phone implementation pass complete; builds/lint passed. Physical-device acceptance is still pending.
Base: bd8d2b0 on main. Verified source: 8f2a80086ab1b3b2af2907f0eb50513b65cb61ac.
Everything is local. No push, PR, merge, phone install/control, emulator, test sources, or test dependencies.

## Everything changed

1. Shared flat visual system: paired light/dark colors, Violet/Teal/Amber accents, consistent readable text, surfaces, ripple feedback, and labelled controls.
2. Original adaptive launcher mark with monochrome support, plus locally bundled tinted vector icons instead of structural emoji icons.
3. Replaced the bare setup activity with a scrollable settings screen and real enabled/active-keyboard status.
4. Added compact/balanced/roomy keyboard sizing, optional number row, and optional key previews.
5. Added offline English/Arabic tap-to-complete word suggestions, backed by the existing 25,000-entry assets for each language. Dictionaries load/index off-main-thread.
6. Added Arabic alef/yaa/harakat normalization and English case matching. Completions re-check the current prefix/selection and avoid insertion in the middle of a word. No automatic correction or learning of typed words.
7. Added double-tap Shift caps lock, explicit locked-state icon, and app-guided automatic capitalization.
8. Added optional double-space period insertion in ordinary text fields, not password/email/URL/no-suggestion contexts.
9. Added a numeric keypad and second symbol page with brackets, currencies, mathematical symbols, percentages, and explicit paths back to letters/first symbols.
10. Added punctuation alternates (ellipsis, dashes, plus, multiplication/division) alongside the existing English accents, Arabic variants, and Arabic digits.
11. Added hold-space cursor scrubbing using relative motion steps; it does not also commit a space or switch language.
12. Preserved quick space-swipe language switching and now remembers the chosen English/Arabic language locally.
13. Improved touch ownership, two-thumb rollover, sliding to adjacent keys, gap hit targets within the correct row, and outside-release cancellation.
14. Added press highlighting for every key and robust cancellation of preview, long-press and held-backspace callbacks on hide, detach, layout/editor changes, or cancelled touches.
15. Cached keyboard geometry and visible row lists; shorter symbol/keypad layouts fill the stable keyboard body instead of leaving a dead bottom band.
16. Enter labels/actions follow the active app's Go/Search/Send/Next/Done/custom action; internal calculator/search modes instead show Insert/Pick. Disabled fullscreen extraction mode.
17. Added emoji category tabs with real selected state and a recycled auto-fit grid, independent of the typing keys.
18. Added recent emoji, favorites via long press, explicit recent-clear confirmation, and visible/accessibility feedback. Successful editor commits own recent-history recording.
19. Added English synonyms and common Arabic emoji search aliases; Arabic searches tolerate harakat/tatweel/alef/yaa variations.
20. Moved emoji asset parsing, glyph checks and indexing off the UI thread; added loading/unavailable states and destruction-safe worker/callback cleanup.
21. Redesigned clipboard history with sticky count/clear controls, independent paste/pin/delete targets, readable RTL text, and an explicit paste-current-system-clipboard action.
22. Added individual clipboard deletion undo while the panel remains open; undo preserves the original timestamp and cannot revive an expired clip.
23. Clipboard expiry now removes stored entries on access/retention changes. Purging before a retention extension prevents resurrection; unsupported retention values are rejected/fall back safely.
24. Added defensive JSON recovery and pin-preserving duplicate repair, plus bounded clipboard/history storage. Oversized clipboard text is skipped intact, never silently truncated.
25. Added manual private-session mode and automatic password/app-requested no-learning protection. Pauses suggestions and clipboard capture; private mode also pauses emoji recent/favorite writes. Existing saved content remains local.
26. Added saved phrases: explicitly save/delete up to 40 replies, addresses or signatures in settings, then insert them from the keyboard. No automatic capture of typed text.
27. Added text-tools panel: cursor arrows, line start/end, select all, copy/cut/paste, host-app undo/redo, selected-text upper/lowercase, and Arabic mark removal. Unsupported context-menu actions report failure.
28. Added an offline expression calculator with parentheses, unary signs, decimals, Arabic digits, + - * / and postfix %. Results insert only on an explicit tap/Enter. Invalid/divide-by-zero input does not produce a result; long expressions no longer hide the insertion control.
29. Added normal/numeric/email/password playground field types. Password playground drafts are excluded from Activity saved-state bundles.
30. Added live preference observation so visible keyboards apply theme/size/number-row/preview changes without waiting for a new editor. Rebuilds preserve transient mode/layout/query/shift state; listeners are removed on destruction.
31. Added native virtual accessibility key buttons, focus/hover support, labelled special keys, and alternate-character menus. TalkBack behavior still needs physical-device acceptance.
32. Kept zero requested permissions, no network/sound/vibration, and added explicit Android cloud-backup/device-transfer exclusions for local app data.
33. Added in-app third-party notices and full local license texts. Corrected FrequencyWords content attribution to CC BY-SA 4.0 (MIT covers its generator code). Verified every entry in both existing word-list assets against the upstream 2018 50k lists; assets were preserved unchanged.
34. Added README.md with controls/privacy/limits/build commands and this durable log with commit receipts, real verification and the remaining phone checklist.

## Local commit receipts

- 9e4e85c — shared keyboard themes and private launcher branding
- 90eb4c0 — clipboard expiry hardening and safe deletion undo
- a4b4bb0 — emoji categories, favorites and normalized Arabic search
- eefba1d — keypad, cursor scrub and accessible keyboard gestures
- d155b17 — bundled offline English/Arabic word completions and licenses
- 627582a — local calculator and saved phrase storage
- da85b32 — integration, private typing, text tools and live settings
- 8f2a800 — fill compact layouts without dead touch bands

A following documentation-only commit contains README.md and this log; its hash is shown by `git log`, not recursively embedded in this file.

## What actually ran

All gates below returned exit 0 against the finished source:

- `nix develop -c ./gradlew assembleDebug`
- `nix develop -c ktlint 'app/src/main/java/**/*.kt'`
- `nix develop -c ./gradlew lintDebug`
- `nix develop -c ./gradlew assembleRelease` (R8 optimization/resource shrinking included)
- `git diff --check`
- Also rebuilt after `clean` with configuration-cache disabled, then reran the finished APK tasks after commits.
- Android `aapt dump permissions`: package com.m57.mboard, no requested permissions.
- Android `apksigner verify`: debug APK signature valid.
- ZIP/hash inspection: English/Arabic word assets inside both APKs match source assets; all three full license texts are bundled.
- Color calculations: base text/secondary/accent text pairs exceed 4.5:1 in both themes (smallest checked ratio 5.69:1).

Android lint has zero errors and 39 non-fatal warnings, including unused preserved vector resources, programmatic custom-view constructor warnings, and localization/dependency-update suggestions. This is a passing gate, not a claim that lint is warning-free.

Manual execution of the actual compiled Calculator class in JShell returned:

- `12*(3+4)` -> `84`
- `٥٠%` -> `0.5`
- `-4+6/2` -> `-1`
- `1/0`, `2*(3`, and `garbage` -> `null`
- `0.1+0.2` -> `0.3`

Manual execution of the actual compiled emoji tokenizer returned:

- `قَلْب` -> `[قلب]`
- `حُبّ` -> `[حب]`
- `قـهـوة` -> `[قهوة]`
- `COFFEE` -> `[coffee]`
- `أَنا` -> `[انا]`

These were direct compiled-code probes, not added test files, and they do not prove on-device UI behavior.

## Artifacts

Installable debug-signed APK:
`/home/m57/Projects/mboard/app/build/outputs/apk/debug/app-debug.apk`
Bytes: 3306028
SHA-256: fc7855627c213e3b1fc694a2930c1303fa9066aef56acd383c3aae74e3e89174

Optimized, unsigned release APK (not published, not directly installable):
`/home/m57/Projects/mboard/app/build/outputs/apk/release/app-release-unsigned.apk`
Bytes: 471368
SHA-256: adc928a7b2748533e7292af200a9b4a9a4da5130004acdfeccca5a30b9cffb3e

Lint report:
`/home/m57/Projects/mboard/app/build/reports/lint-results-debug.html`

## Review disposition and honest limitations

- Review-found password saved-state issue and stale live visual settings: fixed.
- Review-found Arabic combining-mark tokenization and main-thread emoji loading: fixed.
- Defensive findings for clipboard duplicate pins, invalid retention and emoji write bounds: hardened.
- Did not remove clipboard text from accessibility descriptions: it is intentionally readable for choosing clips with TalkBack, and a generic description would not make the displayed text secret. Clipboard history is not a secure vault; unflagged sensitive text can still be saved when history is enabled.
- No test sources/dependencies were added, per AGENTS.md. Android lint's automatically named test-model tasks are not test execution.
- Existing unused untracked vector assets were left untouched/uncommitted rather than deleting or absorbing unrelated files.
- No SOUL or Luna skill was changed. Reusable IME lessons were added to the Android development skill.
- Clipboard expiry is enforced on next access while the process is stopped, not by a background deletion scheduler. Pins remain until explicit deletion.
- Calculator uses bounded floating-point arithmetic and rounds to eight decimal places; postfix % means division by 100, not a relative spreadsheet percentage.
- Cursor events, undo/redo, and editor actions depend on the host app's InputConnection support.

## Phone checklist — NOT DONE

1. Install/open the debug APK and confirm setup status/keyboard picker.
2. Type fast with two thumbs; slide between adjacent keys; test key gaps/edges and cancelled touches.
3. Hold backspace, hide/switch apps mid-hold, and verify deletion stops without affecting the next editor.
4. Hold accented/Arabic letters at both screen edges; select each alternate; check preview timing.
5. Quick-swipe space to change language; hold-then-scrub in English and Arabic; verify no stray spaces or repeated absolute motion.
6. Check all layouts/panels/heights in portrait, landscape and foldable states, including gesture/three-button navigation insets.
7. Try numeric/email/password/no-learning fields and multiple apps' Send/Search/Next/custom Enter actions.
8. Check suggestions after cursor moves, selections, mid-word edits, capitalization, and language switches; verify private/password fields do not offer them.
9. Exercise clipboard expiry, pin limits, delete/undo, clear confirmations, history-off/private modes, and sensitive-marked copied values.
10. Search emoji in English and Arabic with/without harakat; switch categories; favorite/unfavorite; clear recents; verify private-mode suppression.
11. Change preferences while the sandbox keyboard remains visible and ensure they apply without losing the current internal query/shift state.
12. Insert phrases, select/transform text, move cursor, and try host-app undo/redo.
13. Type calculator expressions including Arabic digits, parentheses, percentages and errors; check explicit result insertion and long expressions.
14. Exercise large fonts, both themes/all accents, TalkBack virtual keys/alternates, and activity recreation with password playground content.

No on-device success is claimed until this checklist is exercised.
