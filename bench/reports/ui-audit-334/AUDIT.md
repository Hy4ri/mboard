# UI/UX audit: boards #334 (unify design) and #335 (fix keyboard UX)

Phase A, audit only. No app source changed. Base: local `main` 90c88c4, branch `ui/334-335-unify`.
APK built from main (`ktlint` + `assembleDebug`, exit 0) and installed with `adb install -r` on the phone (ALI-NX1, 1200x2652, 520 dpi = 3.25x, Android, system dark).

Screens: `screens/sNN-*.png` (downscaled to max 1200 px; saved-phrase list and saved e-mail chips are blurred on purpose).

## 0. Method, limits, and device state

| Item | What was done |
|---|---|
| Surfaces shot | Settings (home, Appearance, Typing, Clipboard & privacy, Saved phrases, About, Playground, 4 dialogs), keyboard (EN letters/shift/caps, AR letters/symbols/pad/more symbols, number row, number/e-mail/password fields), strip, toolbar, shield, emoji search, emoji panel, clipboard, calculator, alternates popup, space-scrub, thumb mode + both edge panels. |
| Landscape | One pass: playground, keys (+number row), clipboard, emoji search, emoji panel, calculator (`s31`-`s35`). Rotation via `settings put system user_rotation 1`, **restored to 0** (verified). |
| Font scale 1.3 | `s40`-`s44`. `font_scale` set to 1.3, **restored to 1.0** (verified). |
| Light theme | **Not shot.** The phone has custom colors (below) that override the theme in every surface, and removing them would erase m57's configuration. Changing the system night mode is a system change. Light contrast is computed from the code palette instead (section 3). |
| Arabic | Arabic keyboard shot (`s02`-`s05`). The settings UI and keyboard chrome are **English-only**: all strings are hard-coded in Kotlin, `strings.xml` has 3 entries. A system locale change is a system change, so it was skipped. |
| Temporary app-setting changes | Number row on and Thumb mode "Left thumb", then restored. Verified afterwards in the UI: Thumb "Full width", Number row off, Key previews on, home subtitles identical to the start. Shield toggled on and off. Typed "hell"/"fir" in the playground. No data cleared, no uninstall, default IME unchanged. |
| Tooling note | `uiautomator dump` sometimes fails silently and leaves the previous XML. A few early taps used stale coordinates; no destructive dialog was confirmed. State was re-checked at the end. |

**The phone is not on the code defaults.** m57's device: Crimson accent (#990000), key height 44 dp (default 54), number row off (default on), custom colors: background #121212, letter keys #242424, function keys #121212, accent/text from theme. Function keys equal the background, so they have no visible fill (see G-08). Screens show that configuration.

## 1. Surface inventory

Letter keys EN (`s06`-`s08`) and AR (`s02`), number row (`s30`), symbols / more symbols / number pad EN+AR (`s03`-`s05`), number layout (`s19`), alternates popup (`s46`), space scrub (`s47`), suggestion strip and its messages, toolbar row, shield / password state (`s15`-`s18`), emoji search (`s09`, `s10`), emoji panel (`s11`), clipboard (`s12`), calculator (`s13`, `s14`), thumb mode + dial pad / recent-emoji edge panels (`s36`-`s38`), saved phrases and addresses (`s20`, `s27`), Settings: home (`s00`), Appearance (`s21`), color dialog (`s23`), choice dialogs (`s17`, `s22`), Typing (`s24`), Clipboard & privacy (`s25`, `s26`), Saved phrases (`s27`), About (`s28`), Playground (`s02`, `s18`-`s20`).

Strip status messages found in code (`MboardService.kt:541-565`, `SuggestionStrip.kt:29`): "Type a word for completions", "Word suggestions off · enable them in Settings", "Loading <lang> dictionary…", "Dictionary unavailable · reopen keyboard to retry", "Word suggestions unavailable", "Preparing word completions…", "Static suggestions · shield on", "Saved addresses", plus `context.status`. Aux row texts: "Type a calculation", "No result", "Emoji unavailable", "Loading emoji…", "No matches", "Type to search", "Clipboard · saved on this device", "Emoji · hold an emoji to favorite it".

## 2. Issues by surface

Severity: **H** breaks use or hides state on m57's own device; **M** clear defect or rule violation; **L** polish. Line numbers are on `main` 90c88c4.

### Toolbar (T), shield / password (P)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| T-01 | H | The toolbar never shows which panel is open (clipboard, emoji, calculator icons look the same open or closed). Only the shield has a selected state. | s09 s11 s12 s13 | MboardService.kt:262-306 | Selected state (accent fill, onAccent glyph) on the icon of the open panel; the same icon closes the panel. |
| T-02 | H | When a panel opens, a "Return to keyboard" icon is inserted at slot 5, so the shield, hide and settings icons jump right. With 8 icons x 48 dp = 384 dp the row scrolls on 360 dp phones and "Hide keyboard" leaves the screen. | s09 s11 | MboardService.kt:278 | Fixed slots (paste, clipboard, emoji, calc, shield, hide) with no insertion. Remove the extra icon: the selected icon is the return path. |
| T-03 | M | "Return to keyboard" (ic_keyboard) and "Hide keyboard" (ic_keyboard_hide) are nearly identical glyphs side by side. | s09 s12 | MboardService.kt:278,306 | Falls away with T-02. If a return button stays: `ic_arrow_back`. |
| T-04 | M | Emoji icon (ic_mood) opens emoji **search**. Inside the full emoji panel, tapping it goes to search instead of closing. Inconsistent toggle. | s11 | MboardService.kt:276,311 | `togglePanel` closes whenever the tapped icon's panel family is open (EMOJI or EMOJI_SEARCH). |
| P-01 | H | Shield state is only a filled toolbar icon. No text names it. The strip message "Static suggestions · shield on" is dead: the strip hides its status whenever words exist, and the shield path always passes words. | s15 s16 | MboardService.kt:565, SuggestionStrip.kt:96 | In the free toolbar space (between slot 6 and Settings, empty in `s06`) show a 12 sp label while active: "Private · history paused" or "Password field · protected". Keep chips for state, labels for the reason. |
| P-02 | M | In password fields 4 toolbar icons and the shield go to alpha .35/.4 with no reason shown (tooltip only). The disabled shield also looks "off" though it is on. | s18 | MboardService.kt:268,302 | Disabled = same icon, `DISABLED_ALPHA`, plus the P-01 label stating why. Shield keeps accent fill, no dimming. |
| P-03 | L | Shield `isEnabled=false` in password fields but `isSelected=true`: three overlapping states on one control. | s18 | MboardService.kt:295-302 | One state enum: off, on, locked(password). Locked draws accent fill at full alpha and ignores taps. |

### Suggestion strip (S)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| S-01 | M | Chips are 34 dp high (target < 48 dp). | s06 | SuggestionStrip.kt:72 | 40 dp chips inside the 40 dp row with the 2 dp bottom padding removed, or 48 dp row. Decide in tokens (strip height). |
| S-02 | M | Number / phone / date fields show common words ("I you the") above a number pad. | s19 | MboardService.kt:573-586, SuggestionPolicy.kt:32 | For `fieldPolicy.numeric` render the status "Number field" (empty strip). Change `renderSuggestions` and bench `Strip.show` together. |
| S-03 | M | Saved e-mail chips end-ellipsize ("hiari45@gmail.…"), hiding the domain. | s20 | SuggestionStrip.kt:61 | `ellipsize=MIDDLE` for address chips, and tooltip = full text. |
| S-04 | L | Chip text auto-sizes 11-15 sp (3 sizes in one row). Words of different length render at different sizes. | s06 s20 | SuggestionStrip.kt:67 | One fixed 15 sp for words, ellipsize in the middle for long ones. |
| S-05 | L | "Loading English dictionary…" replaces the strip after each language switch (honest, but a 13 sp muted line in a row built for chips). | s06 | MboardService.kt:556 | Keep text, use token `text.caption`. |
| S-06 | L | Correction chip is bold + accent ink on a 16 % accent mix; the highlight is easy to miss on Violet/Teal. | s16 | SuggestionStrip.kt:98-104 | Keep, but use token `accent.container` (see section 4). |

### Letter and symbol keys (K), Arabic (A)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| A-01 | M | On the Arabic layout the symbol key "=\<" is drawn as "><\=" (bidi reordering of neutral characters). The "؟123" key is clipped at its right edge. | s03 s05 | KeyboardView.kt:396-409, Languages.kt:197, Layouts.kt:13 | Draw symbol labels with LTR text direction (`\u200E` prefix, or `TextPaint` bidi flag). Shrink-to-fit uses `available = width - 4dp`; use 8 dp. |
| A-02 | L | AR "123" key and EN "?123" differ in glyph order only; Arabic digits exist only as long-press alternates; the symbol page uses Latin digits. Product choice, not a bug; noted so the token pass keeps both. | s03 | Languages.kt:196-197, Layouts.kt:59-66 | None now. |
| A-03 | M | The settings UI and keyboard chrome have no Arabic strings. An Arabic-locale user gets English settings, English panel texts ("Clipboard", "Pick"). Hard-coded in ~15 Kotlin files. | all | SetupActivity.kt, MboardService.kt, panels | Separate card: move strings to `strings.xml` (+ `values-ar`). Out of scope for #334/#335 unless m57 asks; keep strings in one place when touching them. |
| K-01 | M | Enter key mixes an icon (return) with words ("Done", "Go", "Send", "Pick", "Prev"). "Pick" is unclear; word length shrinks the font. | s09 s18 s19 | MboardService.kt:331,438-452, KeyboardView.kt:325 | Icons for all actions: Done → `ic_check`, Send → `ic_send`, Search → `ic_search`, Go/Next → `ic_arrow_forward`, Pick → `ic_check`, `=` stays text. All drawables already exist. |
| K-02 | M | Alternates popup uses `modifier` fill. With m57's palette it equals the background; the popup edge vanishes, and the last glyph overlaps the next key. Cells are min 32 dp wide. | s46 | KeyboardView.kt:356,487-490 | Popup fill = `surface.raised` (token), 40 dp cells, clamp to view width, edge drawn like a key. |
| K-03 | L | Key text sizes: 22 sp letters, 14 for labels longer than 2 characters, 23 in the popup, 26 in the preview bubble. They use `density`, not `scaledDensity`, so they ignore font scale while the strip and toolbar scale. | s40 | KeyboardView.kt:404,368,466 | Decide: keys fixed-dp (document it) and one `key.label` token (22/14). Preview 26 → 28. |
| K-04 | L | Shift state: icon changes only (outline → filled → filled+bar). Caps lock is recognisable but the key fill stays the same as when shift is off. | s07 s08 | KeyboardView.kt:305-314 | Fill `accent.container` while shifted/locked. |
| K-05 | L | Keys have the edge-line depth effect but no visible focus ring for hardware keyboards; only TalkBack bounds draw a 2 dp ring. | – | KeyboardView.kt:344-350 | Optional: reuse the 2 dp ring for `isFocused`. |

### One-hand rail and edge panels (O)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| O-01 | M | Rail mixes a 12 sp muted label ("Keyboard left"), a text button ("Full") and an arrow icon. The text button is 48 dp tall but only ~60 dp wide. | s36 | OneHandSurface.kt:409-426 | Two icon buttons (move, full width) with tooltips, and one status label token. |
| O-02 | M | Recent-emoji panel: "open all" button is 32 dp; emoji tiles ~35 dp; dial keys use 20 sp text next to 22 sp key text. | s37 s38 | OneHandSurface.kt:446,154,189 | Open-all 48 dp; tiles min 44 dp (fewer rows if needed); dial text = `key.label`. |
| O-03 | L | Edge panels are only reachable by gesture and a settings toggle. No hint on the keyboard that they exist. | s37 | OneHandSurface.kt:231-236 | None; the settings note explains it. Keep wording honest. |

### Emoji panel and search (E)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| E-01 | M | Category tabs, search and clear-recent are 36 dp targets with 20 dp icons. | s11 | EmojiPanel.kt:151-152 | 48 dp targets, 24 dp icons. The 12 category tabs scroll already (fixed search, fixed clear, scrolling tabs). |
| E-02 | M | Smiley icon (ic_mood) is used for toolbar "Search emoji", for the strip button "Browse all emoji" and for the "Smileys" category. Same glyph, three meanings. | s09 s11 | MboardService.kt:276,845, EmojiPanel.kt:138 | Strip button → `ic_apps` (grid). Toolbar keeps `ic_mood`. Category keeps `ic_mood`. |
| E-03 | M | Clear-recent uses `ic_delete_sweep` as a bare icon at the far right, with a two-tap confirm that is only visible as an accent fill. No label states what it clears. | s11 | EmojiPanel.kt:37-47,86-94 | Keep the two-tap confirm, but show text on arm: replace the title area with "Tap again to clear recent" for 3 s (real state, not color only). |
| E-04 | M | Search field is fixed 150 dp x 32 dp; at font scale 1.3 the hint reads "…ch emoji". The clear (X) is 34 dp. | s44 s09 | MboardService.kt:796,950 | Width weight with min 140 dp / max 45 %, height 40 dp, clear target 48 dp (icon 20 dp). |
| E-05 | L | First search result is highlighted only when the query is non-blank (honest), but the highlight is `modifier` fill, nearly invisible on m57's palette. | s10 | MboardService.kt:817-818 | Use `accent.container`. |
| E-06 | L | Emoji panel has no title or empty "what is this" row; clipboard has both (see C-04). | s11 s12 | EmojiPanel.kt:53-64 | Part of the shared panel header (X-03). |

### Clipboard (C)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| C-01 | H | Pin and delete are 23 dp targets (15 dp icons) in the corners of a bubble that pastes on tap. A missed tap pastes text or deletes a clip. | s12 | ClipboardPanel.kt:182-183,128-134 | Bubble gets an action row: min height 72 dp, pin and delete 48 dp targets with 20 dp icons, aligned bottom; text area above. Or long-press menu. |
| C-02 | M | Title says "1 clips · 1 pinned". | s12 | ClipboardPanel.kt:73 | Plural-correct text ("1 clip · 1 pinned"). |
| C-03 | M | "Clear unpinned" is 34 dp high, and when disabled (alpha .4) gives no reason. | s12 | ClipboardPanel.kt:61,76 | 48 dp; when disabled the title says "Nothing to clear" or tooltip with reason. |
| C-04 | M | Header holds title, a paste icon (same glyph as toolbar slot 1) and the clear button; the emoji panel has none of this. | s12 s11 | ClipboardPanel.kt:49-62 | Shared panel header (X-03): title or tabs left, actions right, fixed 48 dp. |
| C-05 | L | Delete uses a close (X) icon labelled "Delete clip". X means dismiss in the rest of the UI. Same in saved phrases. Undo row pushes the list down. | s12 s27 | ClipboardPanel.kt:171, SetupActivity.kt:610 | Add `ic_delete` for delete. Reserve the undo row height (48 dp) or show undo inside the title row. |
| C-06 | L | Preview text 13 sp, 3 lines, bubble 50 % width: Latin text with ">" and "…" wraps awkwardly. | s12 | ClipboardPanel.kt:147-150 | 14 sp (`text.body`), keep 3 lines. |

### Calculator (X)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| X-01 | H | With m57's custom palette the operator keys (÷ × − + ( ) %) have the same fill as the background: only the glyph is visible, no key shape. The "=" key is fine. | s13 s14 s35 | Layouts.kt:87-97, BoardSettings custom colors | See G-08: warn about low contrast in the color dialog; also draw operator keys with `key.function` token which never equals `background` (min 1.3:1 mix). |
| X-02 | M | Expression is drawn in muted ink (14 sp) like a hint; the result is a primary chip. The "=" key and the result chip are both accent: two controls for one action. | s14 | MboardService.kt:772-790 | Expression `text.body` in primary ink. Result chip → neutral `surface` fill with accent ink; the `=` key stays the single accent action. |
| X-03 | M | Panels differ: calculator and emoji search use the 40 dp aux row, clipboard and emoji browse hide it and add their own header, all with different heights (40, 36, 48). | s12 s13 s11 | MboardService.kt:243,336-342 | One panel header row of 48 dp for every non-key panel (toolbar stays 48): title/tabs left, actions right. Body height stays fixed. |
| X-04 | L | "No result" chip at alpha .5 and disabled: honest but its text is 14 sp autosized 10-14. | s13 | MboardService.kt:781-790 | Fixed 14 sp, disabled token. |

### Settings (G), playground (Y), dialogs (D)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| G-01 | M | Duplicate title: the system action bar shows "mboard" above the in-page header (which shows "mboard" again on Home). Costs ~56 dp, more in landscape. | s00 s31 | AndroidManifest.xml:10, SetupActivity.kt:215-229 | `Theme.DeviceDefault.DayNight.NoActionBar` (or `actionBar?.hide()`), keep the in-page header. |
| G-02 | M | Two controls called "Accent": preset (Color) and override (Custom colors). | s21-1 | SetupActivity.kt:329,1065 | Rename: "Accent preset" and "Accent override". |
| G-03 | M | Switch contrast: off track (modifier) vs row surface 1.27:1, on track 2.9:1 (dark) / 2.1-2.7:1 (light), thumb-on vs track-on 3.0:1. On/off reads by thumb position only. | s24 s21-3 | SettingsUi.kt:87-89 | Tokens `switch.trackOff` = mix(surface, text, .5), `switch.trackOn` = mix(surface, accentInk, .7). Gives >= 3.2:1 in dark and light (section 3). |
| G-04 | M | Choice dialogs (`Theme`, `Accent`, `Field type`, key height, thumb, edges, retention): the radio dot sits right after the label text, not aligned; the accent dialog shows no color swatch. | s17 s22 | SetupActivity.kt:787-805 | Custom row: label left, radio right, 56 dp row. For Accent add the swatch. |
| G-05 | M | Delete confirm: "Delete" has the same accent ink as "Cancel". Danger color exists (`ui.danger`) but is not used. | s26 | SetupActivity.kt:966,983-986 | Positive button in `danger` ink for destructive confirms. |
| G-06 | M | Gesture help appears three times (Typing "Quick gestures", Playground "What to try", About "Keyboard tools") as non-interactive notes. | s24-3 s28 | SetupActivity.kt:497-504,697-701 | Keep one list in Playground; Typing links to it (the row already exists). |
| G-07 | M | Toggle details run 6-8 lines ("Learn my words", "Arabizi suggestions"). Rows become paragraphs. | s24-1 s24-2 | SetupActivity.kt:452-456,476-480 | 2 lines max (title + one clause). Move the rest to the section note. |
| G-08 | H | Color dialog has no contrast check. m57's current palette (function keys = background) already hides key shapes (X-01). Hex field gives no error on bad input; Apply silently does nothing for invalid hex. | s23 s35 | SetupActivity.kt:833-935 | Show live "Contrast with background 1.0:1 · low" text (and for text vs key). Do not block Apply. Red hint on invalid hex. |
| G-09 | L | Swatches are 40 dp high, labelled only by hex; 24 literal swatch colors duplicate the palette (drift risk). | s23 | SetupActivity.kt:909,1071-1096 | 48 dp swatches. Build the swatch list from the token table plus accent presets. |
| G-10 | M | Disabled "Save phrase" uses the primary fill at alpha .4: text 1.7:1 on a dark maroon bar, no reason shown. One error string covers three causes. | s27-1 | SetupActivity.kt:621,659,631 | Disabled primary = surface fill + muted text, helper text under the field: "Type a phrase" / "40 of 40 saved" / "Already saved". |
| G-11 | L | Home "Appearance" subtitle packs 4 facts ("Follow system · Crimson · #990000 · 44 dp") and wraps. | s00 | SetupActivity.kt:285 | "Follow system · Crimson". Key height has its own row. |
| G-12 | L | Notes sit between rows ("Thumb mode moves real keys…") and split related controls. | s21-2 | SetupActivity.kt:377,403 | Put notes at the end of their section. |
| G-13 | L | "Manage keyboards" and "Switch keyboard" leave the app but use the same chevron as inner navigation. | s28-2 | SetupActivity.kt:739,745 | Use an "open in new" glyph (new `ic_open_in_new`) for external rows. |
| G-14 | L | "Add emails from device accounts" reopens the system picker after every pick until Back; the toast tells the user. | s27-1 | SetupActivity.kt:646 | Add one per tap; second row action "Add another" optional. |
| G-15 | L | Appearance changes show no preview; the page says "changes apply immediately" but the keyboard is only visible in the playground. | s21 | SetupActivity.kt:315 | Link row "Preview in playground" is enough. No inline mock keyboard (speculative). |
| G-16 | M | Landscape: header 64 dp + action bar leave ~1.5 rows visible; content lines are 2400 px wide, no max width. | s31 | SetupActivity.kt:211-232 | G-01 plus content column max 640 dp, centered. |
| Y-01 | M | Landscape: the keyboard covers ~80 % of the window, so the playground field is hidden while typing. See L-01. | s32 | – | L-01. |
| D-01 | L | Dialogs use window radius 20, buttons 12, rows 16. | s26 | SetupActivity.kt:583 | Radius tokens (section 4). |

### Landscape (L), font scale (F), global (Z)

| ID | Sev | Issue | Shot | file:line | Fix |
|---|---|---|---|---|---|
| L-01 | H | Landscape: toolbar 48 + strip 40 + 4 key rows (4 x 44 + 4 = 180 dp) = 268 dp (312 dp with number row) on a 369 dp tall window: 73-85 % of the screen. The field being edited is hidden (`s31` → `s32`). Row height ignores orientation. | s31 s32 s35 | KeyboardView.kt:27,77, MboardService.kt:243,336-342 | `keysHeight` = min(setting, (0.5 x windowHeightDp - 88 dp) / rows) when height < 480 dp; hide the toolbar row in landscape if still above 55 %. |
| L-02 | M | Landscape keys are 250 px (77 dp) wide each; no max keyboard width, thumb reach is poor. | s32 | KeyboardView.kt:253-256 | Cap keyboard width at 640 dp in landscape, centered; extra area is the background color. |
| L-03 | L | In landscape a dark block at the left shows the system cut-out inset beside the IME. | s32 | MboardService.kt:228-232 | Paint insets with `background` (already set on root); verify. |
| F-01 | M | At font scale 1.3 the strip stays readable (autosize), but the search hint truncates (E-04), Settings rows wrap to 5-6 lines (`s41`), and keys do not scale (K-03). | s40-s44 | – | E-04, G-07, K-03. |
| Z-01 | L | `ic_launcher_background` #191A21 differs from the keyboard background #14141A. | – | res/values/colors.xml:2 | Use the token. |
| Z-02 | L | No missing content descriptions found: every `ui.icon(...)` call carries one, key labels come from `accessibleLabel`, decorative glyphs are hidden. | – | BoardUi.kt:134-147 | None. Listed to show it was checked. |
| Z-03 | L | Typeface: system default for body, `sans-serif-medium` in 3 places, bold spans and `DEFAULT_BOLD` for titles/chips. No token. | – | BoardUi.kt:161, SettingsUi.kt:26, SetupActivity.kt:222,274, SuggestionStrip.kt:103 | Two weights only: regular and medium; no bold. |

### Counts

| Severity | Count |
|---|---|
| High | 7 (T-01, T-02, P-01, C-01, X-01, G-08, L-01) |
| Medium | 33 |
| Low | 25 |
| Total | 65 |

### Top 10 (fix order)

1. L-01 landscape keyboard covers the field.
2. C-01 clipboard pin/delete at 23 dp.
3. T-01/T-02 toolbar state and icon reflow.
4. P-01/P-02 shield and password state shown by text.
5. G-08/X-01 custom colors can erase key shapes; no contrast feedback.
6. G-03 switch contrast.
7. G-01/G-16 duplicate title bar and landscape settings.
8. X-03/C-04 one panel header height and pattern.
9. K-01/E-02 icon consistency (enter key, smiley reuse).
10. A-01/S-02/S-03 Arabic symbol bidi, number-field strip, e-mail chip truncation.

## 3. Contrast (computed from `BoardUi.kt:30-60`)

WCAG ratios, code palette (no custom colors). Text needs 4.5:1, icons and control boundaries 3:1.

| Pair | Dark | Light | Note |
|---|---|---|---|
| text / surface, bg, modifier | 13.9 / 16.5 / 10.9 | 15.9 / 14.1 / 12.0 | pass |
| muted / bg, surface, modifier | 9.7 / 8.1 / 6.4 | 5.7 / 6.4 **4.85** | pass |
| onAccent / accent (violet, teal, amber, crimson) | 9.1, 9.7, 10.9, 8.9 | 7.4, 6.4, 6.9, 8.9 | pass |
| accent ink on surface (section titles, text buttons) | 8.6-10.4 | 6.4-7.4 | pass |
| accent fill vs bg (shield, selected tab) | crimson **2.06**, others 10-12 | 5.7-7.9 | crimson dark fill is below 3:1 against bg; glyph on it is 8.9:1, so pass for the glyph, note for the boundary |
| letter key fill vs bg | **1.19** | **1.13** | boundary relies on glyph and edge line; by design, not a text failure |
| function key fill vs bg | **1.52** | **1.17** | same |
| switch track off vs surface | **1.27** | **1.33** | fail (G-03) |
| switch track on vs surface | **2.9-3.2** | **2.1-2.7** | fail in light and violet/crimson dark |
| disabled text/icon (alpha .35/.4) vs bg | 3.0 / 3.5 | 2.1 / 2.4 | disabled controls are exempt from WCAG; "Save phrase" disabled is 1.7:1 and unreadable |

Proposed track mixes: off = mix(surface, text, .5): dark 4.56, light 3.18. On = mix(surface, accentInk, .7): dark 4.95-5.8, light 3.4-5.1 (violet 3.65, teal 3.42, amber 3.49, crimson 5.06).

## 4. Proposed unified token set

One Kotlin object `Tokens` (new file `Tokens.kt`) read by `BoardUi`. Values below keep today's colors where they pass.

### Color (semantic; dark / light)

| Token | Dark | Light | Replaces (today) |
|---|---|---|---|
| `bg` | #14141A | #F1F0F7 | `BoardUi.background` and the same literal in `colors.xml` (#191A21 launcher) |
| `surface` (key, card, chip) | #24242E | #FFFFFF | `BoardUi.surface` |
| `surface.function` (function keys, rail buttons, selected cell) | #353540 | #E0DEEA | `BoardUi.modifier`. Guard: if a custom value is within 1.3:1 of `bg`, draw an outline (G-08, X-01) |
| `surface.raised` (alternates popup, preview bubble, dialogs) | mix(surface, text, .06) | #FFFFFF + edge | `modifier` used as popup fill (KeyboardView.kt:356,463), dialog `shape(surface,20)` |
| `text` | #F4F2FA | #21212C | `BoardUi.text` |
| `text.muted` | #BDBACB | #605C70 | `BoardUi.muted` |
| `accent` | per preset | per preset | `BoardUi.accent` (violet #C9B8FF/#6241A6, teal #80D8CE/#006B61, amber #F6CF80/#785300, crimson #990000) |
| `accent.on` | #20202A (crimson #FFFFFF) | #FFFFFF | `onAccent` |
| `accent.ink` | = accent (crimson dark #FFB4AB) | = accent | `controlAccent` |
| `accent.container` (selected chip, correction, first emoji result, shift on) | mix(surface, accent, .16) | same | literal `.16f` at SuggestionStrip.kt:104, `.12f` at SetupActivity.kt:276 |
| `danger` | #FFB4AB | #A32626 | `BoardUi.danger` |
| `edge` (key lower edge) | mix(bg, black, .35) | mix(bg, black, .12) | `BoardUi.keyEdge` |
| `switch.trackOff` | mix(surface, text, .5) | same | `modifier` (SettingsUi.kt:89) |
| `switch.trackOn` | mix(surface, accent.ink, .7) | same | mix(.45) (SettingsUi.kt:89) |
| `state.pressed` | mix(base, accent, .32) | .22 | `BoardUi.pressed` (keep) |
| `state.ripple` | accent @ 19 % (0x30) | same | `BoardUi.ripple` (keep) |
| `state.disabled` | alpha .5 | alpha .5 | alpha .35 (MboardService.kt:268), .4 (MboardService.kt:302, ClipboardPanel.kt:76, EmojiPanel.kt:88, SetupActivity.kt:621,659), .5 (MboardService.kt:783), .35 (SuggestionStrip.kt:107, animation start only) |

### Type scale (sp; keyboard keys stay dp)

| Token | Size | Weight | Replaces |
|---|---|---|---|
| `text.title` | 28 | medium | Home title (SetupActivity.kt:221) |
| `text.header` | 22 | medium | page title (SetupActivity.kt:221), 19 (BoardUi.heading, unused) |
| `text.row` | 16 | medium (title) | row/toggle/editor/dialog 16, `RelativeSizeSpan` 13/16 |
| `text.body` | 14 | regular | 13, 14, 15 labels (note 13, status 13, calc 14, clip 13, empty 15, phrase 15, search 15, strip words 11-15 auto) |
| `text.caption` | 12 | regular/medium | section title 12, modeLabel 12, count 12 |
| `text.emoji` | 24 | – | 22 (OneHand), 24 (results), 26 (grid) |
| `key.label` | 22 dp / 14 dp (>2 chars) | regular | KeyboardView.kt:404 |
| `key.popup` | 26 dp | regular | 23 (popup) and 26 (preview) |

Fourteen sizes today (10,11,12,13,14,15,16,19,20,22,23,24,26,28) become six plus key text.

### Spacing (dp, 4-based)

`space.1`=4, `space.2`=8, `space.3`=12, `space.4`=16, `space.5`=24. Replaces 2, 3, 6, 9, 10, 14, 20 used at SuggestionStrip.kt:65,72, OneHandSurface.kt:134,141,201, ClipboardPanel.kt:19,153,354, MboardService.kt:776,934,948, SetupActivity.kt:211,232. Key gap stays 4 (`space.1`).

### Radius

| Token | dp | Replaces |
|---|---|---|
| `radius.key` | 8 | key, chip, popup, preview (KeyboardView.kt:276, BoardUi.kt:keyChip) |
| `radius.control` | 12 | `BoardUi.shape` default, editors, phrase rows, color preview |
| `radius.card` | 16 | settings rows `CARD_RADII`, status card |
| `radius.sheet` | 20 | dialog window (SetupActivity.kt:583), swatches use circle |
| `radius.pill` | full | search field 19 (MboardService.kt:907) |

### Sizes

| Token | dp | Replaces |
|---|---|---|
| `icon` | 24 | toolbar, header, keys (`min(24,…)` KeyboardView.kt:417) |
| `icon.small` | 20 | emoji tab 20, row chevron 20, clipboard 15, search glyph 18, clear X 16/18 |
| `target.min` | 48 | all tappable controls (replaces 23, 32, 34, 36, 40, 44) |
| `toolbar.height` | 48 | MboardService.kt:234,241 (keep) |
| `strip.height` | 40 (chips 40 inside, 4 dp gap) | `AUX_DP`=40 (MboardService.kt:1379) with 34 dp chips (SuggestionStrip.kt:72) |
| `panel.header` | 48 | clipboard 48+34, emoji tab 36, calc aux 40 |
| `key.row` | 44 / 54 / 64 (setting), landscape cap per L-01 | `BoardSettings.rowHeightDp` |
| `key.gap` | 4 | KeyboardView.kt:28 |
| `header.settings` | 56 | SetupActivity.kt:211 (64) |
| `content.maxWidth` | 640 | none |

### Motion

| Token | ms | Replaces |
|---|---|---|
| `motion.instant` | 70 | preview pop (KeyboardView.kt:810) |
| `motion.fast` | 120 | key release fade 140, strip chip 110, popup hide 100 |
| `motion.base` | 200 | side panel 220 (OneHandSurface.kt:83), settings page 160 (SetupActivity.kt:166) |
| `motion.hold` | 250 | long-press (KeyboardView.kt:748, keep) |

Easing: decelerate for enter, linear for fades. Respect `animator_duration_scale` (already honored by `ValueAnimator`/`ViewPropertyAnimator`; the manual key-release/preview timers in `KeyboardView` are not: skip them when scale = 0).

### Editable config (house rule 8)

Already user-editable: colors, accent, key height, thumb mode, edges. Constants that should become settings only if m57 asks (not proposed now): strip chip count (3), edge width (40 dp), long-press delay (250 ms).

## 5. Proposed split for implementation (Phase B)

Step 0 (lead, small, first): land `Tokens.kt` plus `BoardUi.kt` reading it (color tokens, radii, sizes, motion, `DISABLED_ALPHA`, danger/contrast helper). No visual change except dead-code removal. Everything else rebases on it.

| Unit | Owns (exclusive) | Issues |
|---|---|---|
| **U1 Settings** | `SetupActivity.kt`, `SettingsUi.kt`, `AndroidManifest.xml` (theme), `res/values/colors.xml`, new drawables `ic_open_in_new`, `ic_delete` | G-01..G-16, Y-01, D-01, Z-01, C-05 (phrase delete icon) |
| **U2 Keyboard surface** | `KeyboardView.kt`, `KeyboardAccessibility.kt`, `OneHandSurface.kt`, `Layouts.kt`, `Languages.kt` (labels only) | A-01, K-01 (key icon drawing), K-02..K-05, O-01..O-03, L-01 (`keysHeight`), L-02 |
| **U3 Service chrome** | `MboardService.kt` (toolbar, aux row, calc/search UI, mode glue), `SuggestionStrip.kt`, bench `Strip.kt` / `Live.kt` (same-edit rule) | T-01..T-04, P-01..P-03, S-01..S-06, K-01 (enter labels, `enterLabel`), X-01..X-04, E-02, E-04, E-05, L-03, X-03 (aux row side) |
| **U4 Panels** | `EmojiPanel.kt`, `ClipboardPanel.kt` | E-01, E-03, E-06, C-01..C-06, X-03 (panel header component) |

Disjoint by file. Cross-unit contracts: (1) U3 owns `body` height math; U2 exposes `keysHeight`, U4 fills the body. (2) The shared 48 dp panel header lives in `BoardUi.kt` (Step 0) so U3 and U4 call one helper. (3) `Strip.kt` and `Live.kt` edits stay with U3 together with their `MboardService` counterparts. (4) No unit edits tests (none exist). Each unit verifies by `ktlint`, `assembleDebug`, and device screenshots with the same scripts as this audit.

Order: Step 0, then U1 ‖ U2 ‖ U3 ‖ U4. U3 merges before U4 if the panel header helper needs a tweak.

## 6. Not covered

Light theme on device (custom colors, section 0). Arabic settings UI (no Arabic strings). Real TalkBack run. Hardware keyboard focus. Split-screen and tablet widths. Dark-theme shots of other accents (computed only).
