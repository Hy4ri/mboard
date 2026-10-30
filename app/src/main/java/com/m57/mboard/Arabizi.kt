package com.m57.mboard

/**
 * Arabizi (Franco) to Arabic-script skeletons. Pure and bounded: a typed word expands into at most
 * [MAX_SKELETONS] spellings, and the caller keeps only the ones the Arabic lexicons contain.
 */
object Arabizi {
    const val MAX_SKELETONS = 600
    const val MAX_LENGTH = 14

    private val TRIGGER_DIGITS = "[2356-9]".toRegex()
    private val NOT_FRANCO = Regex("\\d+(st|nd|rd|th|am|pm|k|g|kg|mb|gb|hz|x)")

    private val digraphs =
        mapOf(
            "sh" to listOf("ش"),
            "kh" to listOf("خ"),
            "gh" to listOf("غ"),
            "th" to listOf("ث", "ذ"),
            "dh" to listOf("ذ", "ض"),
            "ch" to listOf("ش"),
            "3'" to listOf("غ"),
            "7'" to listOf("خ"),
            "9'" to listOf("ض"),
            "aa" to listOf("ا"),
            "ee" to listOf("ي"),
            "ii" to listOf("ي"),
            "oo" to listOf("و"),
            "ou" to listOf("و"),
        )

    private val letters =
        mapOf(
            'b' to listOf("ب"),
            'p' to listOf("ب"),
            't' to listOf("ت", "ط"),
            'j' to listOf("ج"),
            'h' to listOf("ه", "ح"),
            'd' to listOf("د", "ض"),
            'r' to listOf("ر"),
            'z' to listOf("ز", "ظ"),
            's' to listOf("س", "ص"),
            'f' to listOf("ف"),
            'v' to listOf("ف"),
            'q' to listOf("ق"),
            'k' to listOf("ك"),
            'l' to listOf("ل"),
            'm' to listOf("م"),
            'n' to listOf("ن"),
            'w' to listOf("و"),
            'y' to listOf("ي"),
            'g' to listOf("ج", "غ"),
            'c' to listOf("ك", "س"),
            'x' to listOf("كس"),
            '2' to listOf("ء", "ا"),
            '3' to listOf("ع"),
            '5' to listOf("خ"),
            '6' to listOf("ط"),
            '7' to listOf("ح"),
            '8' to listOf("ق"),
            '9' to listOf("ص"),
        )

    /** Vowels are optional in Arabic writing: nothing first, then the long vowel. */
    private val vowels =
        mapOf(
            'a' to listOf("", "ا", "ة"),
            'e' to listOf("", "ي", "ا"),
            'i' to listOf("", "ي"),
            'o' to listOf("", "و"),
            'u' to listOf("", "و"),
        )

    /** True when [word] is worth expanding: Franco letters, and a Franco digit unless [anyLetters] is set. */
    fun eligible(
        word: String,
        anyLetters: Boolean,
    ): Boolean {
        if (word.length !in 2..MAX_LENGTH) return false
        val lower = word.lowercase()
        if (!lower.all { it in 'a'..'z' || it in '0'..'9' || it == '\'' }) return false
        if (lower.none { it in 'a'..'z' }) return false
        if (NOT_FRANCO.matches(lower)) return false
        val digit = TRIGGER_DIGITS.containsMatchIn(lower)
        return if (anyLetters) !digit && lower.length >= 3 else digit
    }

    /** At most [MAX_SKELETONS] Arabic spellings, most direct reading first. */
    fun skeletons(word: String): List<String> {
        val lower = word.lowercase()
        val out = LinkedHashSet<String>()
        var steps = 0

        fun walk(
            index: Int,
            built: String,
        ) {
            if (out.size >= MAX_SKELETONS || ++steps > 4096) return
            if (index >= lower.length) {
                if (built.isNotEmpty()) out += built
                return
            }
            val digraph = lower.substring(index, minOf(index + 2, lower.length))
            digraphs[digraph]?.let { options ->
                for (option in options) walk(index + 2, built + option)
                return
            }
            val c = lower[index]
            if (c == '\'') {
                walk(index + 1, built)
                return
            }
            val vowel = vowels[c]
            if (vowel != null) {
                // A word-initial vowel is written with alef; elsewhere it is a long vowel or nothing.
                val options = if (index == 0) listOf("ا") else vowel
                for (option in options) walk(index + 1, built + option)
                return
            }
            val options = letters[c] ?: return
            // A doubled consonant (ll, bb) may be written once.
            val doubled = index + 1 < lower.length && lower[index + 1] == c && c in 'a'..'z'
            for (option in options) {
                walk(index + 1 + if (doubled) 1 else 0, built + option)
                if (doubled) walk(index + 1, built + option)
            }
        }
        walk(0, "")
        return out.toList()
    }

    /** Whether [word] is an Arabic-script reading offered on a Latin layout (not a word to learn). */
    fun isReading(word: String) = word.any { it in '\u0600'..'\u06FF' }

    /**
     * Put [readings] into the strip choices. A Franco word with a digit gets them right after the typed
     * word; otherwise a strong reading takes the last slot. Valid English words never reach this helper with readings.
     */
    fun place(
        choices: List<String>,
        typed: String,
        readings: List<String>,
    ): List<String> {
        if (readings.isEmpty()) return choices
        if (!eligible(typed, anyLetters = false)) return choices.take(2) + readings.take(1)
        val at = (choices.indexOf(typed) + 1).coerceAtLeast(0)
        return (choices.take(at) + readings.take(2) + choices.drop(at)).take(3)
    }
}
