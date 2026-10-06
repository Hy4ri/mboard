package com.m57.mboard

/** Special key codes; printable keys use their text directly. */
object Keys {
    const val SHIFT = "⇧"
    const val BACKSPACE = "⌫"
    const val ENTER = "⏎"
    const val GLOBE = "🌐"
    const val SYMBOLS = "?123"
    const val LETTERS = "ABC"
    const val SPACE = " "
}

/** Long-press alternates; the base key is shown first. */
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
        "0" to "٠",
    ).mapValues { (_, v) -> v.map(Char::toString) } + ("ل" to listOf("لا", "لأ", "لإ", "لآ"))

private val NUMBERS = "1234567890".map(Char::toString)

enum class Layout(
    val rows: List<List<String>>,
) {
    ENGLISH(
        listOf(
            NUMBERS,
            "qwertyuiop".map(Char::toString),
            "asdfghjkl".map(Char::toString),
            listOf(Keys.SHIFT) + "zxcvbnm".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.SYMBOLS, Keys.GLOBE, ",", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    ARABIC(
        listOf(
            NUMBERS,
            "ضصثقفغعهخحج".map(Char::toString),
            "شسيبلاتنمكط".map(Char::toString),
            "ذئءؤرىةوزظد".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.SYMBOLS, Keys.GLOBE, "،", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    SYMBOLS(
        listOf(
            "1234567890".map(Char::toString),
            "@#\$_&-+()/".map(Char::toString),
            "*\"':;!?".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.LETTERS, Keys.GLOBE, ",", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
}
