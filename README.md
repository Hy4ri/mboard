# mboard

An offline English/Arabic Android keyboard. No network permission, sound, or vibration.

## Everyday typing

- Number row; English and edge-to-edge Arabic letter layouts.
- Tap suggestions to complete a word. English and Arabic dictionaries are bundled locally; no automatic correction, cloud calls, or typing history.
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
- More tools/settings: offline calculator, saved phrases, settings, and system keyboard picker.
- Private: pauses clipboard recording, word suggestions, emoji recents and favorite changes. Existing saved items remain available locally. Password and app-requested no-learning fields automatically enable stronger privacy safeguards.
- Hide: dismiss the keyboard.

Calculator supports +, -, *, /, decimal numbers (including Arabic digits), parentheses and postfix %. Percentage is mathematical division by 100: `50%` = `0.5`. It is not spreadsheet-style relative percentage. Tap an answer or Enter to insert it. Invalid/divide-by-zero expressions are not inserted. Results are rounded to eight decimal places; this is a convenience tool, not a high-precision financial calculator.

Saved phrases are explicitly added/deleted in the settings app, then inserted from the keyboard panel. Up to 40 phrases, 2,000 characters each. Nothing typed in another app is automatically turned into a phrase.

## Settings

Follow-system, dark or light appearance; Violet, Teal or Amber accent; compact/balanced/roomy key height; optional number row and previews; word suggestions; app-guided capitalization; double-space period; clipboard history and retention. A typing playground offers normal, numeric, email and password fields.

## Privacy and limits

- No INTERNET or VIBRATE permission.
- Clipboard capture runs only while mboard is visible, skips sensitive-marked clips/password/private fields, and reads text only (never resolves content URIs). Android's clipboard access rules still apply.
- Up to 30 unpinned and 30 pinned clips. Clips larger than 20,000 characters are skipped, never silently truncated.
- Expired clips are purged from storage when the store is opened/read or its retention changes. While the app process is stopped, expiry is enforced on the next access; this is not a background deletion scheduler.
- Pinned clips do not expire; deleting a pin is explicit. Individual deletion has undo while the panel remains open; undo cannot revive an expired clip.
- Recent emoji: 40; favorites: 60. Private mode does not delete existing history.
- App data is excluded from Android cloud backup and device transfer.
- Text actions are routed through the active app's InputConnection. Some apps do not implement undo/redo or cursor key events; mboard reports unsupported context-menu actions instead of pretending success.

## Build

    nix develop -c ./gradlew assembleDebug
    nix develop -c ktlint 'app/src/main/java/**/*.kt'
    nix develop -c ./gradlew lintDebug
    nix develop -c ./gradlew assembleRelease

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
The release task produces an unsigned local artifact; no release is published by building it.

AGENTS.md forbids test sources and dependencies. Verification is builds/lint plus physical-device acceptance. The autonomous no-phone pass deliberately does not install the APK or control a device. See LUNA_LOG.md for the verified gates and the phone checklist.

## Bundled data

Word lists: Hermit Dave/FrequencyWords, selected entries from the 2018 50k English/Arabic lists, CC BY-SA 4.0 content (the upstream code's MIT license does not cover these lists).
Unicode emoji data: Unicode License V3.
Material Symbols: Google, Apache 2.0. The launcher mark is original mboard artwork.
Full notices and license texts are bundled in `app/src/main/assets/NOTICE.txt` and `assets/licenses/`.
