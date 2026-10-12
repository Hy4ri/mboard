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

val ALTERNATES: Map<String, List<String>> =
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
        "ا" to "أإآٱ",
        "ي" to "ئى",
        "و" to "ؤ",
        "ه" to "ة",
        "ء" to "َُِّْ",
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
    ).mapValues { (_, value) -> value.map(Char::toString) } +
        ("ل" to listOf("لا", "لأ", "لإ", "لآ"))

private val digits = "1234567890".map(Char::toString)

enum class Layout(
    val rows: List<List<String>>,
) {
    ENGLISH(
        listOf(
            digits,
            "qwertyuiop".map(Char::toString),
            "asdfghjkl".map(Char::toString),
            listOf(Keys.SHIFT) + "zxcvbnm".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.SYMBOLS, Keys.GLOBE, ",", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    ARABIC(
        listOf(
            digits,
            "ضصثقفغعهخحج".map(Char::toString),
            "شسيبلاتنمكط".map(Char::toString),
            "ذئءؤرىةوزظد".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.SYMBOLS, Keys.GLOBE, "،", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    SYMBOLS(
        listOf(
            digits,
            "@#\$_%-+()/".map(Char::toString),
            "*\"':;!?,".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.LETTERS, Keys.PAD, Keys.MORE_SYMBOLS, Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    SYMBOLS_MORE(
        listOf(
            "[]{}<>^\\|~".map(Char::toString),
            "€£¥₹¢©®™°•".map(Char::toString),
            "=&…¿¡·`'".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.LETTERS, Keys.SYMBOLS, Keys.GLOBE, Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    NUMPAD(
        listOf(
            listOf("+", "1", "2", "3", "/"),
            listOf("-", "4", "5", "6", "%"),
            listOf("*", "7", "8", "9", Keys.BACKSPACE),
            listOf(Keys.SYMBOLS, ",", "0", ".", Keys.ENTER),
        ),
    ),
    CALC(
        listOf(
            listOf("7", "8", "9", "÷", Keys.BACKSPACE),
            listOf("4", "5", "6", "×", "("),
            listOf("1", "2", "3", "−", ")"),
            listOf(".", "0", "%", "+", Keys.ENTER),
        ),
    ),
    NUMBER(
        listOf(
            listOf("1", "2", "3", Keys.BACKSPACE),
            listOf("4", "5", "6", "-"),
            listOf("7", "8", "9", "."),
            listOf(Keys.LETTERS, "0", Keys.SPACE, Keys.ENTER),
        ),
    ),
}
