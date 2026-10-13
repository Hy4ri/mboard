package com.m57.mboard

/** Special key codes; printable keys use their text directly. */
object Keys {
    const val SHIFT = "⇧"
    const val BACKSPACE = "⌫"

    /** Sent only by a long backspace hold; never shown as a key. */
    const val DELETE_WORD = "⌫word"
    const val ENTER = "⏎"
    const val GLOBE = "globe"
    const val SYMBOLS = "?123"
    const val MORE_SYMBOLS = "=\\<"
    const val LETTERS = "ABC"

    /** Symbols ⇄ number pad toggle. */
    const val PAD = "pad"
    const val SPACE = " "
}

/** Long-press alternates shared by every language: digits and punctuation. Letters live in each [Language]. */
val ALTERNATES: Map<String, List<String>> =
    mapOf(
        "1" to "١",
        "2" to "٢",
        "3" to "٣",
        "4" to "٤",
        "5" to "٥",
        "6" to "٦",
        "7" to "٧",
        "8" to "٨",
        "9" to "٩",
        "0" to "٠+",
        "." to "…·",
        "-" to "+−–—",
        "/" to "\\÷",
        "*" to "×",
    ).mapValues { (_, value) -> value.map(Char::toString) }

private val digits = "1234567890".map(Char::toString)

/**
 * A grid of keys. Letter layouts come from a [Language]; the rest are shared.
 * [letters] layouts carry a number row that the user can hide; [stretch] rows fill the width edge to edge.
 */
class Layout(
    val rows: List<List<String>>,
    val letters: Boolean = false,
    val stretch: Boolean = false,
    val grid: Boolean = false,
) {
    companion object {
        /** A language's letter rows below the shared number row. */
        fun letters(
            rows: List<List<String>>,
            stretch: Boolean = false,
        ) = Layout(listOf(digits) + rows, letters = true, stretch = stretch)

        val SYMBOLS =
            Layout(
                listOf(
                    digits,
                    "@#\$_%-+()/".map(Char::toString),
                    "*\"':;!?,".map(Char::toString) + Keys.BACKSPACE,
                    listOf(Keys.LETTERS, Keys.PAD, Keys.MORE_SYMBOLS, Keys.SPACE, ".", Keys.ENTER),
                ),
            )
        val SYMBOLS_MORE =
            Layout(
                listOf(
                    "[]{}<>^\\|~".map(Char::toString),
                    "€£¥₹¢©®™°•".map(Char::toString),
                    "=&…¿¡·`'".map(Char::toString) + Keys.BACKSPACE,
                    listOf(Keys.LETTERS, Keys.SYMBOLS, Keys.GLOBE, Keys.SPACE, ".", Keys.ENTER),
                ),
            )
        val NUMPAD =
            Layout(
                listOf(
                    listOf("+", "1", "2", "3", "/"),
                    listOf("-", "4", "5", "6", "%"),
                    listOf("*", "7", "8", "9", Keys.BACKSPACE),
                    listOf(Keys.SYMBOLS, ",", "0", ".", Keys.ENTER),
                ),
                grid = true,
            )
        val CALC =
            Layout(
                listOf(
                    listOf("7", "8", "9", "÷", Keys.BACKSPACE),
                    listOf("4", "5", "6", "×", "("),
                    listOf("1", "2", "3", "−", ")"),
                    listOf(".", "0", "%", "+", Keys.ENTER),
                ),
                grid = true,
            )
        val NUMBER =
            Layout(
                listOf(
                    listOf("1", "2", "3", Keys.BACKSPACE),
                    listOf("4", "5", "6", "-"),
                    listOf("7", "8", "9", "."),
                    listOf(Keys.LETTERS, "0", Keys.SPACE, Keys.ENTER),
                ),
                grid = true,
            )
    }
}
