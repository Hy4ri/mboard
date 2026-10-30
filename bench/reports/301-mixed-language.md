# Investigation Report: Board Card #301
## Mixed Arabic/English: Per-Word Language Guard

### Summary
Board Card #301 investigates whether mixed Arabic/English sentences in real use or benchmarks suffer from cross-script erroneous autocorrection (e.g. valid English words typed while Arabic layout is active, or Arabic words typed while English layout is active, getting wrongly autocorrected).

**Finding:** No erroneous cross-script autocorrection occurs. The dictionary normalization pipeline strictly gates inputs by Unicode script:
- `WordDictionary.normalize(value, language)` returns `null` for any token containing non-Latin characters when `language.script == Script.LATIN`.
- `WordDictionary.normalize(value, language)` returns `null` for any token containing non-Arabic characters when `language.script == Script.ARABIC`.
- `WordDictionary.latinCorrection(word)` and `WordDictionary.arabicCorrection(word)` both abort immediately when normalization returns `null`.
- Consequently, `autoCorrection(typed, ...)` returns `null` for any out-of-script token. Neither valid words nor typos in the other language get modified or autocorrected when typed under the active keyboard language.
- Furthermore, benchmark datasets (`bench/data/`) have 0 mixed-language sentences across clean sets (`ar_subs.txt`, `ar_fresh.txt`, `ar_tatoeba.txt`, `en_subs.txt`, etc.).

Per instruction step 4:
> "If no real problem exists, do not change engine code: write the evidence in a short note bench/reports/301-mixed-language.md and commit that only."

No engine code was modified. Existing behavior is inherently guarded at the script-normalization boundary.

---

### Pipeline Analysis

1. **Active Language Selection**:
   In `MboardService`, `language` is switched explicitly by user toggle (globe key / space-swipe). In `bench/suggest.sh`, `--lang auto` inspects whether the input string has Arabic letters (`input.any { it in '؀'..'ۿ' }`).

2. **Normalization Gates**:
   In `WordDictionary.kt`:
   ```kotlin
   private fun normalize(value: String, language: Language): String? {
       if (language.script == Script.LATIN) {
           val result = value.lowercase(Locale.ROOT)
           val letters = language.alphabet
           return if (result.isNotEmpty() && result.first() in letters && result.last() in letters &&
               result.all { it in letters || it == '\'' }
           ) {
               result
           } else {
               null
           }
       }
       // Arabic:
       val result = buildString(value.length) {
           value.forEach { char ->
               when (char) {
                   'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                   'ى' -> append('ي')
                   'ـ' -> Unit
                   in '\u064B'..'\u065F', '\u0670', in '\u06D6'..'\u06ED' -> Unit
                   else -> {
                       if (!char.isLetter() || Character.UnicodeScript.of(char.code) != Character.UnicodeScript.ARABIC) return null
                       append(char)
                   }
               }
           }
       }
       return if (result.isNotEmpty() &&
           result.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }
       ) {
           result
       } else {
           null
       }
   }
   ```

3. **Correction Entrypoints**:
   - `latinCorrection` executes:
     `val normalized = normalize(word, language) ?: return null`
     If an Arabic token like "ملف" or "بطيء" is passed when language is English, `normalize` returns `null` and correction is aborted.
   - `arabicCorrection` executes:
     `val normalized = normalize(word, language) ?: return null`
     If a Latin token like "meeting" or "laptop" is passed when language is Arabic, `normalize` returns `null` and correction is aborted.
   - Neither language attempts to autocorrect or alter out-of-script tokens on space.

---

### Empirical Probes (`bench/suggest.sh`)

Probes were conducted across requested sentences and language configurations:

| Input Text | Language Flag | Detected/Active Language | Word at Caret | Auto-correction (`auto`) | Suggestions Strip |
|---|---|---|---|---|---|
| `بدي اروح ع meeting بكرا` | `--lang auto` | `ar` | `بكرا` | `null` | `["بكرا", "بكرة", "براين"]` |
| `بدي اروح ع meeting ` | `--lang auto` | `ar` | `""` | `null` | `["لا", "من", "في"]` |
| `بدي اروح ع meeting` | `--lang auto` | `ar` | `meeting` | `null` | `["meeting"]` |
| `بدي اروح ع meeting` | `--lang ar` | `ar` | `meeting` | `null` | `["meeting"]` |
| `بدي اروح ع meeting` | `--lang en` | `en` | `meeting` | `null` | `["meeting", "meetings", "meetinghouse"]` |
| `شو رأيك بال laptop` | `--lang auto` | `ar` | `laptop` | `null` | `["laptop"]` |
| `شو رأيك بال laptop ` | `--lang auto` | `ar` | `""` | `null` | `["لا", "من", "في"]` |
| `شو رأيك بال laptop` | `--lang ar` | `ar` | `laptop` | `null` | `["laptop"]` |
| `شو رأيك بال laptop` | `--lang en` | `en` | `laptop` | `null` | `["laptop", "laptops"]` |
| `send me the ملف` | `--lang auto` | `ar` | `ملف` | `null` | `["ملف", "خلف", "ملفات"]` |
| `send me the ملف ` | `--lang auto` | `ar` | `""` | `null` | `["القضية", "ات", "قضية"]` |
| `send me the ملف` | `--lang ar` | `ar` | `ملف` | `null` | `["ملف", "خلف", "ملفات"]` |
| `send me the ملف` | `--lang en` | `en` | `ملف` | `null` | `["ملف"]` |
| `ok تمام` | `--lang auto` | `ar` | `تمام` | `null` | `["تمام", "تماما", "أمام"]` |
| `ok تمام ` | `--lang auto` | `ar` | `""` | `null` | `["الساعة", "يا", "الـ"]` |
| `ok تمام` | `--lang ar` | `ar` | `تمام` | `null` | `["تمام", "تماما", "أمام"]` |
| `ok تمام` | `--lang en` | `en` | `تمام` | `null` | `["تمام"]` |
| `the app بطيء` | `--lang auto` | `ar` | `بطيء` | `null` | `["بطيء", "بطيئة"]` |
| `the app بطيء ` | `--lang auto` | `ar` | `""` | `null` | `["جدا", "الفهم", "للغاية"]` |
| `the app بطيء` | `--lang ar` | `ar` | `بطيء` | `null` | `["بطيء", "بطيئة"]` |
| `the app بطيء` | `--lang en` | `en` | `بطيء` | `null` | `["بطيء"]` |

**Isolated Cross-Language Token Probes**:
- `meeting` under `ar`: `auto=null`, suggestions=`["meeting"]`
- `laptop` under `ar`: `auto=null`, suggestions=`["laptop"]`
- `ok` under `ar`: `auto=null`, suggestions=`["ok"]`
- `app` under `ar`: `auto=null`, suggestions=`["app"]`
- `send` under `ar`: `auto=null`, suggestions=`["send"]`
- `the` under `ar`: `auto=null`, suggestions=`["the"]`
- `ملف` under `en`: `auto=null`, suggestions=`["ملف"]`
- `تمام` under `en`: `auto=null`, suggestions=`["تمام"]`
- `بطيء` under `en`: `auto=null`, suggestions=`["بطيء"]`

Under both languages, out-of-script words are left untouched without autocorrection.

---

### Conclusion
Because the engine already enforces strict script isolation at the lexical normalization level, neither Arabic nor English autocorrects words of the other script. No code change or regression risk is introduced.
