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

enum class Layout(
    val rows: List<List<String>>,
) {
    ENGLISH(
        listOf(
            "qwertyuiop".map(Char::toString),
            "asdfghjkl".map(Char::toString),
            listOf(Keys.SHIFT) + "zxcvbnm".map(Char::toString) + Keys.BACKSPACE,
            listOf(Keys.SYMBOLS, Keys.GLOBE, ",", Keys.SPACE, ".", Keys.ENTER),
        ),
    ),
    ARABIC(
        listOf(
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
