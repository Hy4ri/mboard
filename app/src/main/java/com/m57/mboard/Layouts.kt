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

/** Visual and interaction role of a key in a [Layout]. */
enum class KeyKind {
    /** Printable character key (normal key surface, shows press preview popup). */
    CHAR,

    /** Modifier or action key (function key surface, no press preview popup). */
    FUNCTION,

    /** Space bar (normal key surface, no press preview popup, supports cursor scrub). */
    SPACE,
}

/** One key declared in a [Layout]: emitted [code], relative width [weight], and [kind]. */
data class KeySpec(
    val code: String,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
) {
    companion object {
        fun chars(text: String): List<KeySpec> = text.map { KeySpec(it.toString()) }

        fun char(code: String): KeySpec = KeySpec(code, 1f, KeyKind.CHAR)

        fun fn(
            code: String,
            weight: Float = 1.5f,
        ): KeySpec = KeySpec(code, weight, KeyKind.FUNCTION)

        fun space(weight: Float = 4f): KeySpec = KeySpec(Keys.SPACE, weight, KeyKind.SPACE)
    }
}

private val digits = KeySpec.chars("1234567890")

/**
 * A grid of keys. Letter layouts come from a [Language]; the rest are shared.
 * [letters] layouts carry a number row that the user can hide; [stretch] rows fill the width edge to edge.
 */
class Layout(
    val rows: List<List<KeySpec>>,
    val letters: Boolean = false,
    val stretch: Boolean = false,
    val grid: Boolean = false,
) {
    companion object {
        /** A language's letter rows below the shared number row. */
        fun letters(
            rows: List<List<KeySpec>>,
            stretch: Boolean = false,
        ) = Layout(listOf(digits) + rows, letters = true, stretch = stretch)

        val SYMBOLS =
            Layout(
                listOf(
                    digits,
                    KeySpec.chars("@#\$_%-+()/"),
                    listOf(KeySpec.fn(Keys.MORE_SYMBOLS)) + KeySpec.chars("*\"':;!?") + KeySpec.fn(Keys.BACKSPACE),
                    listOf(
                        KeySpec.fn(Keys.LETTERS),
                        KeySpec.fn(Keys.PAD, 1f),
                        KeySpec.char(","),
                        KeySpec.space(),
                        KeySpec.char("."),
                        KeySpec.fn(Keys.ENTER),
                    ),
                ),
            )
        val SYMBOLS_MORE =
            Layout(
                listOf(
                    KeySpec.chars("[]{}<>^\\|~"),
                    KeySpec.chars("€£¥₹¢©®™°•"),
                    listOf(KeySpec.fn(Keys.SYMBOLS)) + KeySpec.chars("=&…¿¡·`") + KeySpec.fn(Keys.BACKSPACE),
                    listOf(
                        KeySpec.fn(Keys.LETTERS),
                        KeySpec.fn(Keys.GLOBE, 1f),
                        KeySpec.char(","),
                        KeySpec.space(),
                        KeySpec.char("."),
                        KeySpec.fn(Keys.ENTER),
                    ),
                ),
            )
        val NUMPAD =
            Layout(
                listOf(
                    KeySpec.chars("+123/"),
                    KeySpec.chars("-456%"),
                    KeySpec.chars("*789") + KeySpec.fn(Keys.BACKSPACE, 1f),
                    listOf(
                        KeySpec.fn(Keys.SYMBOLS, 1f),
                        KeySpec.char(","),
                        KeySpec.char("0"),
                        KeySpec.char("."),
                        KeySpec.fn(Keys.ENTER, 1f),
                    ),
                ),
                grid = true,
            )
        val CALC =
            Layout(
                listOf(
                    KeySpec.chars("+123÷"),
                    KeySpec.chars("−456×"),
                    KeySpec.chars("(789") + KeySpec.fn(Keys.BACKSPACE, 1f),
                    KeySpec.chars(")%0.") + KeySpec.fn(Keys.ENTER, 1f),
                ),
                grid = true,
            )
        val NUMBER =
            Layout(
                listOf(
                    KeySpec.chars("123") + KeySpec.fn(Keys.BACKSPACE, 1f),
                    KeySpec.chars("456-"),
                    KeySpec.chars("789."),
                    listOf(KeySpec.fn(Keys.LETTERS, 1f), KeySpec.char("0"), KeySpec.space(1f), KeySpec.fn(Keys.ENTER, 1f)),
                ),
                grid = true,
            )
    }
}
