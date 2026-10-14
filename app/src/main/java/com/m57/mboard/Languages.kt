package com.m57.mboard

import kotlin.math.abs
import kotlin.math.exp

/** Spelling rules a language's dictionary uses; languages sharing a script share them. */
enum class Script { LATIN, ARABIC }

/** Optional regional vocabulary layered over a language's dictionary, behind its own settings toggle. */
class Dialect(
    val settingKey: String,
    val title: String,
    val summary: String,
    val wordsAsset: String,
    val nextAsset: String,
)

/**
 * Everything the keyboard knows about one typing language. Adding a language is one entry in [Languages.ALL]
 * plus `words_<code>.tsv`, `next_<code>.tsv` and `context_<code>.tsv` in assets.
 */
class Language(
    /** Asset suffix and saved-preference value; never change it once shipped. */
    val code: String,
    /** English name, used in status messages. */
    val name: String,
    /** Native name shown on the space bar and in settings. */
    val label: String,
    val script: Script,
    /** Letter rows in visual left-to-right order, without the number row. */
    rows: List<List<String>>,
    /** Letters the typo model may substitute or insert; the keyboard's letters by default. */
    alphabet: String? = null,
    /** Stretch every row edge to edge instead of centering narrower rows. */
    stretch: Boolean = false,
    val alternates: Map<String, List<String>> = emptyMap(),
    /** Shared keys shown in this language's own form, e.g. ? → ؟. */
    val punctuation: Map<String, String> = emptyMap(),
    val lettersLabel: String = Keys.LETTERS,
    val symbolsLabel: String = Keys.SYMBOLS,
    val dialect: Dialect? = null,
    /** Typed forms always rewritten: im → I'm. */
    val contractions: Map<String, String> = emptyMap(),
    /** Unknown words that are deliberate and must never be corrected: lol, brb. */
    val keepAsTyped: Set<String> = emptySet(),
    /** Misspellings the word list contains as words: corrected, and never offered. */
    val misspellings: Map<String, String> = emptyMap(),
) {
    val layout = Layout.letters(rows, stretch)
    val alphabet = alphabet ?: rows.flatten().filter { it.length == 1 && it[0].isLetter() }.joinToString("")

    /** Upper/lower case exists: drives auto-capitalization and case-matched suggestions. */
    val cased get() = script == Script.LATIN

    val wordsAsset get() = "words_$code.tsv"
    val nextAsset get() = "next_$code.tsv"
    val contextAsset get() = "context_$code.tsv"

    /** Key centre (row, column in key widths) of each letter, as drawn, for the typo model's neighbour test. */
    private val positions: Map<Char, Pair<Int, Double>> by lazy {
        val letterRows = rows.filter { row -> row.any { it.length == 1 && it[0] in this.alphabet } }
        val widths = letterRows.map { row -> row.map { if (it.length == 1) 1.0 else 1.5 } }
        val maxUnits = widths.maxOfOrNull { it.sum() } ?: 0.0
        val out = HashMap<Char, Pair<Int, Double>>()
        letterRows.forEachIndexed { r, row ->
            val rowUnits = widths[r].sum()
            val scale = if (stretch) maxUnits / rowUnits else 1.0
            var x = if (stretch) 0.0 else (maxUnits - rowUnits) / 2
            row.forEachIndexed { i, key ->
                // Stretched Arabic rows have different key widths (backspace widens the last row).
                if (key.length == 1) out[key[0]] = r to (x + widths[r][i] * scale / 2)
                x += widths[r][i] * scale
            }
        }
        out
    }

    /** Width of a same-row neighbour in the widest stretched letter row, in [positions] units. */
    private val neighbourWidth: Double by lazy {
        if (!stretch) {
            1.0
        } else {
            val units =
                rows
                    .filter { row -> row.any { it.length == 1 && it[0] in this.alphabet } }
                    .map { row -> row.sumOf { if (it.length == 1) 1.0 else 1.5 } }
            units.max() / units.min()
        }
    }

    fun adjacent(
        a: Char,
        b: Char,
    ): Boolean {
        val pa = positions[a] ?: return false
        val pb = positions[b] ?: return false
        return abs(pa.first - pb.first) <= 1 && abs(pa.second - pb.second) <= neighbourWidth * 1.01
    }

    /** Relative likelihood of a neighbour-key slip, from actual key centres, not a flat yes/no score. */
    fun typoWeight(
        typed: Char,
        intended: Char,
    ): Double {
        if (typed == intended) return 1.0
        val a = positions[typed] ?: return 0.15
        val b = positions[intended] ?: return 0.15
        val dx = a.second - b.second
        val dy = (a.first - b.first).toDouble()
        return (0.9 * exp(-0.5 * (dx * dx + dy * dy - 1.0))).coerceIn(0.15, 0.9)
    }

    fun alternatesFor(key: String) = alternates[key] ?: ALTERNATES[key]
}

object Languages {
    val ENGLISH =
        Language(
            code = "en",
            name = "English",
            label = "English",
            script = Script.LATIN,
            rows =
                listOf(
                    "qwertyuiop".map(Char::toString),
                    "asdfghjkl".map(Char::toString),
                    listOf(Keys.SHIFT) + "zxcvbnm".map(Char::toString) + Keys.BACKSPACE,
                    listOf(Keys.SYMBOLS, Keys.GLOBE, ",", Keys.SPACE, ".", Keys.ENTER),
                ),
            alphabet = "abcdefghijklmnopqrstuvwxyz",
            alternates =
                mapOf(
                    "a" to "àáâäãåæ",
                    "e" to "èéêëē",
                    "i" to "ìíîï",
                    "o" to "òóôöõøœ",
                    "u" to "ùúûü",
                    "c" to "ç",
                    "n" to "ñ",
                    "s" to "ß",
                    "y" to "ýÿ",
                ).mapValues { (_, value) -> value.map(Char::toString) },
            contractions = EnglishWords.CONTRACTIONS,
            keepAsTyped = EnglishWords.SLANG,
        )

    val ARABIC =
        Language(
            code = "ar",
            name = "Arabic",
            label = "العربية",
            script = Script.ARABIC,
            rows =
                listOf(
                    "ضصثقفغعهخحج".map(Char::toString),
                    "شسيبلاتنمكط".map(Char::toString),
                    "ذئءؤرىةوزظد".map(Char::toString) + Keys.BACKSPACE,
                    listOf(Keys.SYMBOLS, Keys.GLOBE, "،", Keys.SPACE, ".", Keys.ENTER),
                ),
            stretch = true,
            alternates =
                mapOf(
                    "ا" to "أإآٱ",
                    "ي" to "ئى",
                    "و" to "ؤ",
                    "ه" to "ة",
                    "ء" to "َُِّْ",
                ).mapValues { (_, value) -> value.map(Char::toString) } +
                    ("ل" to listOf("لا", "لأ", "لإ", "لآ")),
            punctuation = mapOf("?" to "؟", "," to "،", ";" to "؛"),
            lettersLabel = "أبج",
            symbolsLabel = "؟123",
            dialect =
                Dialect(
                    settingKey = "jordanian_arabic",
                    title = "Jordanian Arabic",
                    summary = "Add local chat words like كيفك، بدي، هسا",
                    wordsAsset = "jordanian.tsv",
                    nextAsset = "jordanian_next.tsv",
                ),
            misspellings = ArabicWords.MISSPELLINGS,
        )

    /** Globe/space-swipe order; the first entry is the default. */
    val ALL = listOf(ENGLISH, ARABIC)

    fun byCode(code: String?) = ALL.firstOrNull { it.code == code } ?: ALL.first()

    fun next(current: Language) = ALL[(ALL.indexOf(current) + 1) % ALL.size]

    /** Settings copy: "English + العربية". */
    val labels get() = ALL.joinToString(" + ") { it.label }
}
