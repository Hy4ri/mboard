# mboard

An offline English/Arabic Android keyboard. No network permission, sound, or vibration.

## Everyday typing

- Number row; English and edge-to-edge Arabic letter layouts.
- Tap suggestions to complete a word. Suggestions follow the caret into earlier words and replace the whole word around it. At an empty whitespace boundary, the row predicts the next word using counted one- and two-word contexts with backoff, sentence-start counts, and local learned pairs. Learned words join the same ranking after at least two uses; private mode excludes personalization. English contractions retain their apostrophes; Arabic attached prefixes have fallback lookups, and a slip inside the stem after a prefix (e.g. وخياىر) still offers the intended word when no exact match exists. The optional Jordanian setting adds corpus-backed dialect vocabulary and continuations, preserves dialect surface spellings such as بدي alongside normalized canonical forms, and favors retained in-domain followers over unrelated general-Arabic table tails. A tiny on-device int8 neural reranker orders existing next-word candidates and one-letter completions; it never generates words or changes automatic correction. Its three models total 1.85 MB and train only on the licensed corpora listed in `bench/lm/SOURCES.md` (OpenSubtitles training slices, Shami training split and project-authored synthetic Jordanian text), never benchmark text. Everything runs on-device; no cloud calls.
- Auto-correction (Settings → Typing) applies on space/punctuation at a word's end. English single-edit choices use context and key-centre proximity, with a confidence margin; contractions (dont → don't, i → I) and bounded two-edit fallback remain. Arabic keeps its conservative spelling/typo policy (انا → أنا, مدرسه → مدرسة), with context-ranked alternatives offered for tapping rather than forced guesses. The automatic correction leads the strip in bold accent. Backspace immediately after it restores your word and stops correcting it in that field. Password fields never use input-dependent prediction or auto-correction.
- The row explains loading, disabled, unsupported-editor, selection, and no-match states instead of silently going blank. Common English choices respect Shift/caps lock. Suggestion replacement validates the word on both sides of the caret before a batched delete/commit and restores it if the editor rejects the commit; unsupported apps receive feedback.
- Left/right thumb modes narrow the actual keys and add Full/side-switch controls. Below 300 dp, keys use full width.
- Tap Shift for one uppercase letter; double-tap it for caps lock; tap again to unlock.
- Hold a letter for accents/Arabic variants. Slide across the strip and release to pick.
- Hold backspace to repeat deletion; lifting, hiding, or changing keyboards stops the repeat.
- Swipe space quickly to switch English/Arabic. Hold space first, then slide, to scrub the cursor instead.
- Numeric fields start with a keypad. ABC returns to letters; ?123 and =\< expose two symbol pages.
- Enter follows the app's Go, Send, Search, Next, Done, or custom action.

## Toolbar

- Clipboard: pinned and recent plain-text clips, deletion undo, explicit bulk-clear confirmation.
- Emoji: type English names or common Arabic words; browse categories; hold an emoji to favorite it.
- Text tools: arrows, line boundaries, select all, copy/cut/paste, host-app undo/redo, selected-text upper/lowercase, and Arabic mark removal.
- More tools/settings: text tools, offline calculator, saved phrases, Text Lab, settings, and system keyboard picker. In panels, Return replaces the text-tools slot instead of widening the toolbar.
- Private: pauses learning/history recording, not ordinary dictionary suggestions. Password fields offer only input-independent bundled choices and block personal phrases, history capture, and automatic correction. The user's suggestions setting controls the strip; apps' no-suggestions flags do not override it. App-requested no-learning fields prevent recording new learned words. Learned words, next-word pairs and rejected autocorrections stay on the device. Word and pair counts fade in 14-day steps when loaded; a new entry gets a full first window. Undoing a correction remembers that specific replacement across fields, unless recording is paused. Manually picking it clears that rejection; the learned-word protection is independent. Delete learned words clears all three stores.
- Hide: dismiss the keyboard.

Calculator supports +, -, *, /, decimal numbers (including Arabic digits), parentheses and postfix %. Percentage is mathematical division by 100: `50%` = `0.5`. It is not spreadsheet-style relative percentage. Tap an answer or Enter to insert it. Invalid/divide-by-zero expressions are not inserted. Results are rounded to eight decimal places; this is a convenience tool, not a high-precision financial calculator.

Saved phrases are explicitly added/deleted in the settings app, then inserted from the keyboard panel. Up to 40 phrases, 2,000 characters each. Nothing typed in another app is automatically turned into a phrase.

## Text Lab

Open More → Text Lab. Select text in the host app, choose a transform, check its preview, then explicitly replace the selection. Replacement checks the current editor and exact selection again; text is never added to keyboard storage. Maximum selected source: 20,000 characters. Leaving/hiding the panel clears preview views.

Transforms: title/sentence case, bullets, quote, inline/fenced code, Markdown bold/italic, whitespace cleanup, ASCII/Arabic digits, Latin↔Arabic keyboard-layout rescue, upside-down text, and fullwidth text. Keyboard rescue is approximate, especially Arabic lam–alef; check before replacing. Reversal operates on grapheme clusters, not individual emoji code points. Insertable palettes: kaomoji, symbols, Markdown snippets. Password/app no-learning fields block this panel.

## Settings

A compact home opens focused pages instead of displaying every control, playground and phrase editor in one long form:

- Appearance: theme, accent swatch/picker (including exact Crimson #990000), key height, thumb mode, number row and previews.
- Typing: suggestions, capitalization and double-space period.
- Clipboard & privacy: history, retention and confirmed local-data deletion.
- Saved phrases: dedicated draft editor, actual saved count, explicit Save/Delete.
- Try your keyboard: separate normal/numeric/email/password playground.
- About: gestures, offline tools, credits and system keyboard management.

Native single-choice dialogs replace inline spinners. The page header stays outside scrolling content, and Back returns to the actual parent page. Setup actions appear only when mboard needs enabling/selecting. Password samples clear on leaving the playground, changing password type or rebuilding appearance and never enter saved drafts. Unsaved phrase text remains only in the activity's memory; both editor fields opt out of normal autofill/view autosave.

## Privacy and limits

- No INTERNET or VIBRATE permission, so typed text cannot leave the device through the keyboard (cloud keyboards have repeatedly been found leaking keystrokes: Citizen Lab, 2023 and 2024).
- Clipboard capture runs only while mboard is visible, skips sensitive-marked clips/password/private fields, and reads text only (never resolves content URIs). Android's clipboard access rules still apply.
- Up to 30 unpinned and 30 pinned clips. Clips larger than 20,000 characters are skipped, never silently truncated.
- Expired clips are purged from storage when the store is opened/read or its retention changes. While the app process is stopped, expiry is enforced on the next access; this is not a background deletion scheduler.
- Pinned clips do not expire; deleting a pin is explicit. Individual deletion has undo while the panel remains open; undo cannot revive an expired clip or overwrite a newer copy of the same text.
- Recent emoji: 40; favorites: 60. All is the default browsing category; an empty Recent tab stays empty rather than showing the whole catalogue. Private mode does not delete existing history.
- App data is excluded from Android cloud backup and device transfer.
- Text actions are routed through the active app's InputConnection. Some apps do not implement undo/redo or cursor key events; mboard reports unsupported context-menu actions instead of pretending success. Text-tool panels are disabled in password/app no-learning fields before reading selected text. Selection transforms recheck editor/observed selection versions and selected content; the host API does not offer an atomic compare-and-replace guarantee.

## Build

    nix develop -c ./gradlew assembleDebug
    nix develop -c ktlint 'app/src/main/java/**/*.kt'
    nix develop -c ./gradlew lintDebug
    nix develop -c ./gradlew assembleRelease

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
The release task produces an unsigned local artifact; no release is published by building it.

AGENTS.md forbids unit/instrumented/screenshot test sources and test dependencies. The standalone `bench/` desktop replay tool is allowed and is not part of the app build or CI. Verification is benchmark replay plus builds/lint and physical-device acceptance. See `bench/README.md` for measurement scope and limitations.

## Bundled data

Word lists: Hermit Dave/FrequencyWords, selected entries from the 2018 50k English/Arabic lists, CC BY-SA 4.0 content (the upstream code's MIT license does not cover these lists).
Unicode emoji data: Unicode License V3.
Material Symbols: Google, Apache 2.0. The launcher mark is original mboard artwork.
Full notices and license texts are bundled in `app/src/main/assets/NOTICE.txt` and `assets/licenses/`.
