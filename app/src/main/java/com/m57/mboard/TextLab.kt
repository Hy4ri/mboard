package com.m57.mboard

import java.util.Locale

/** Explicit, offline transforms. Neither selected text nor generated text is stored. */
object TextLab {
    enum class Transform(
        val label: String,
    ) {
        TITLE_CASE("Title case"),
        SENTENCE_CASE("Sentence case"),
        BULLETS("Bullets"),
        QUOTE("Quote"),
        INLINE_CODE("Inline code"),
        CODE_BLOCK("Code block"),
        MARKDOWN_BOLD("Markdown bold"),
        MARKDOWN_ITALIC("Markdown italic"),
        CLEAN_SPACES("Clean spaces"),
        ASCII_DIGITS("ASCII digits"),
        ARABIC_DIGITS("Arabic digits"),
        KEYBOARD_TO_ARABIC("Keyboard to Arabic"),
        KEYBOARD_TO_ENGLISH("Keyboard to English"),
        UPSIDE_DOWN("Upside down"),
        FULLWIDTH("Fullwidth"),
    }

    enum class Palette(
        val label: String,
    ) {
        KAOMOJI("Kaomoji"),
        SYMBOLS("Symbols"),
        MARKDOWN("Markdown"),
    }

    fun transform(
        text: String,
        kind: Transform,
    ): String =
        when (kind) {
            Transform.TITLE_CASE -> {
                Regex("\\p{L}[\\p{L}\\p{M}\\p{N}'’]*").replace(text) {
                    it.value.lowercase(Locale.ROOT).replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
                }
            }

            Transform.SENTENCE_CASE -> {
                sentenceCase(text)
            }

            Transform.BULLETS -> {
                lines(text) { line ->
                    if (line.isBlank() || Regex("^\\s*(?:[-*+•]|[0-9]+[.)])\\s+").containsMatchIn(line)) {
                        line
                    } else {
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        "$indent- ${line.drop(indent.length)}"
                    }
                }
            }

            Transform.QUOTE -> {
                lines(text) { if (it.isBlank() || it.trimStart().startsWith(">")) it else "> $it" }
            }

            Transform.INLINE_CODE -> {
                inlineCode(text)
            }

            Transform.CODE_BLOCK -> {
                fencedCode(text)
            }

            Transform.MARKDOWN_BOLD -> {
                "**$text**"
            }

            Transform.MARKDOWN_ITALIC -> {
                "*$text*"
            }

            Transform.CLEAN_SPACES -> {
                normalizeLines(text)
                    .replace(Regex("[\\t\\u000B\\f ]+"), " ")
                    .replace(Regex(" *\n *"), "\n")
                    .trim()
            }

            Transform.ASCII_DIGITS -> {
                digits(text, '0')
            }

            Transform.ARABIC_DIGITS -> {
                digits(text, '٠')
            }

            Transform.KEYBOARD_TO_ARABIC -> {
                mapKeyboard(text, true)
            }

            Transform.KEYBOARD_TO_ENGLISH -> {
                mapKeyboard(text, false)
            }

            Transform.UPSIDE_DOWN -> {
                Regex("\\X").findAll(text).map { it.value }.toList().asReversed().joinToString("") {
                    if (it.length == 1) upside[it[0].lowercaseChar()]?.toString() ?: it else it
                }
            }

            Transform.FULLWIDTH -> {
                text
                    .map {
                        when (it) {
                            in '!'..'~' -> (it.code + 0xFEE0).toChar()
                            ' ' -> '　'
                            else -> it
                        }
                    }.joinToString("")
            }
        }

    fun palette(kind: Palette): List<Pair<String, String>> =
        when (kind) {
            Palette.KAOMOJI -> {
                listOf(
                    "Happy" to "(＾▽＾)",
                    "Shrug" to "¯\\_(ツ)_/¯",
                    "Table flip" to "(╯°□°）╯︵ ┻━┻",
                    "Love" to "(｡♥‿♥｡)",
                    "Bear" to "ʕ•ᴥ•ʔ",
                    "Thanks" to "(人 •͈ᴗ•͈)",
                )
            }

            Palette.SYMBOLS -> {
                listOf(
                    "Arrow" to "→",
                    "Double arrow" to "⇒",
                    "Check" to "✓",
                    "Cross" to "✗",
                    "Bullet" to "•",
                    "Heart" to "♥",
                    "Star" to "★",
                    "Infinity" to "∞",
                    "Em dash" to "—",
                    "Ellipsis" to "…",
                )
            }

            Palette.MARKDOWN -> {
                listOf(
                    "Bold" to "**text**",
                    "Italic" to "*text*",
                    "Heading" to "# ",
                    "Bullet" to "- ",
                    "Numbered" to "1. ",
                    "Quote" to "> ",
                    "Inline code" to "`code`",
                    "Link" to "[label](url)",
                    "Divider" to "---",
                )
            }
        }

    private fun normalizeLines(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun lines(
        text: String,
        change: (String) -> String,
    ) = normalizeLines(text).split('\n').joinToString("\n", transform = change)

    private fun sentenceCase(text: String): String =
        buildString(text.length) {
            var capital = true
            text.forEach { char ->
                when {
                    char.isLetter() && capital -> {
                        append(char.titlecase(Locale.ROOT))
                        capital = false
                    }

                    char.isLetter() -> {
                        append(char.lowercase(Locale.ROOT))
                    }

                    else -> {
                        append(char)
                        if (char in ".!?؟\n\r") capital = true
                    }
                }
            }
        }

    private fun inlineCode(text: String): String {
        if (text.isEmpty()) return ""
        val value = normalizeLines(text).replace('\n', ' ')
        val longest = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longest + 1)
        val padding = if (!value.isBlank() && (value.first() in "` " || value.last() in "` ")) " " else ""
        return "$fence$padding$value$padding$fence"
    }

    private fun fencedCode(text: String): String {
        val value = normalizeLines(text)
        val longest = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "$fence\n$value${if (value.endsWith('\n') || value.isEmpty()) "" else "\n"}$fence"
    }

    private fun digits(
        text: String,
        start: Char,
    ): String =
        text
            .map { char ->
                val value = char.digitToIntOrNull()
                if (value == null) char else (start.code + value).toChar()
            }.joinToString("")

    // Rescue is best-effort. Lam + alef cannot reveal whether b or g+h was originally typed.
    private fun mapKeyboard(
        text: String,
        toArabic: Boolean,
    ): String {
        val mapping = if (toArabic) arabic else english
        val keys = mapping.keys.sortedByDescending(String::length)
        val source = if (toArabic) text.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("") else text
        return buildString(source.length) {
            var index = 0
            while (index < source.length) {
                val key = keys.firstOrNull { source.startsWith(it, index) }
                if (key != null) {
                    append(mapping[key])
                    index += key.length
                } else {
                    val cp = source.codePointAt(index)
                    appendCodePoint(cp)
                    index += Character.charCount(cp)
                }
            }
        }
    }

    private val upside =
        mapOf(
            'a' to 'ɐ',
            'b' to 'q',
            'c' to 'ɔ',
            'd' to 'p',
            'e' to 'ǝ',
            'f' to 'ɟ',
            'g' to 'ƃ',
            'h' to 'ɥ',
            'i' to 'ᴉ',
            'j' to 'ɾ',
            'k' to 'ʞ',
            'l' to 'ן',
            'm' to 'ɯ',
            'n' to 'u',
            'o' to 'o',
            'p' to 'd',
            'q' to 'b',
            'r' to 'ɹ',
            's' to 's',
            't' to 'ʇ',
            'u' to 'n',
            'v' to 'ʌ',
            'w' to 'ʍ',
            'x' to 'x',
            'y' to 'ʎ',
            'z' to 'z',
            '!' to '¡',
            '?' to '¿',
        )
    private val arabic =
        mapOf(
            "q" to "ض",
            "w" to "ص",
            "e" to "ث",
            "r" to "ق",
            "t" to "ف",
            "y" to "غ",
            "u" to "ع",
            "i" to "ه",
            "o" to "خ",
            "p" to "ح",
            "[" to "ج",
            "]" to "د",
            "a" to "ش",
            "s" to "س",
            "d" to "ي",
            "f" to "ب",
            "g" to "ل",
            "h" to "ا",
            "j" to "ت",
            "k" to "ن",
            "l" to "م",
            ";" to "ك",
            "'" to "ط",
            "z" to "ئ",
            "x" to "ء",
            "c" to "ؤ",
            "v" to "ر",
            "b" to "لا",
            "n" to "ى",
            "m" to "ة",
            "," to "و",
            "." to "ز",
            "/" to "ظ",
            "`" to "ذ",
        )
    private val english = arabic.entries.associate { it.value to it.key }
}
