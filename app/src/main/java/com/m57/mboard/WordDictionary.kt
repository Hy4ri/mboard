package com.m57.mboard

import java.io.Reader
import java.util.Locale

/** Pure Kotlin, read-only word completion dictionary. */
class WordDictionary private constructor(
    private val entries: List<Entry>,
    private val shortPrefixes: Map<String, List<String>>,
    private val language: Layout,
    val commonWords: List<String>,
) {
    private data class Entry(
        val normalized: String,
        val original: String,
        val frequency: Long,
    )

    val size: Int get() = entries.size

    fun suggest(prefix: String): List<String> {
        if (prefix.isEmpty() || prefix.length > 64) return emptyList()
        val normalizedPrefix = normalize(prefix, language) ?: return emptyList()
        if (normalizedPrefix.isEmpty()) return emptyList()
        shortPrefixes[normalizedPrefix]?.let { return it }

        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].normalized < normalizedPrefix) low = mid + 1 else high = mid
        }
        val best = ArrayList<Entry>(3)
        var index = low
        while (index < entries.size) {
            val entry = entries[index++]
            if (!entry.normalized.startsWith(normalizedPrefix)) break
            insertBest(best, entry)
        }
        return best.map { it.original }
    }

    companion object {
        @JvmStatic
        @Throws(java.io.IOException::class)
        fun read(
            reader: Reader,
            language: Layout,
        ): WordDictionary {
            val entries =
                reader
                    .buffered()
                    .useLines { lines ->
                        lines
                            .mapNotNull { line ->
                                val tab = line.indexOf('\t')
                                if (tab <= 0) return@mapNotNull null
                                val original = line.substring(0, tab)
                                val frequency = line.substring(tab + 1).trim().toLongOrNull() ?: return@mapNotNull null
                                val normalized = normalize(original, language) ?: return@mapNotNull null
                                if (normalized.isEmpty()) null else Entry(normalized, original, frequency)
                            }.toList()
                    }.sortedWith(compareBy<Entry> { it.normalized }.thenByDescending { it.frequency })
            val short = HashMap<String, MutableList<Entry>>()
            entries.forEach { entry ->
                for (length in 1..minOf(4, entry.normalized.length)) {
                    val key = entry.normalized.substring(0, length)
                    insertBest(short.getOrPut(key) { ArrayList() }, entry)
                }
            }
            val common = ArrayList<Entry>(3)
            entries.forEach { insertBest(common, it) }
            return WordDictionary(entries, short.mapValues { it.value.map(Entry::original) }, language, common.map { it.original })
        }

        private fun normalize(
            value: String,
            language: Layout,
        ): String? {
            if (language == Layout.ENGLISH) {
                val result = value.lowercase(Locale.ROOT)
                return if (result.firstOrNull() in 'a'..'z' && result.lastOrNull() in 'a'..'z' &&
                    result.all { it in 'a'..'z' || it == '\'' }
                ) {
                    result
                } else {
                    null
                }
            }
            val result =
                buildString(value.length) {
                    value.forEach { char ->
                        when (char) {
                            'أ', 'إ', 'آ', 'ٱ' -> {
                                append('ا')
                            }

                            'ى' -> {
                                append('ي')
                            }

                            'ـ' -> {
                                Unit
                            }

                            in '\u064B'..'\u065F', '\u0670', in '\u06D6'..'\u06ED' -> {
                                Unit
                            }

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

        private fun insertBest(
            best: MutableList<Entry>,
            entry: Entry,
        ) {
            val position = best.indexOfFirst { it.frequency < entry.frequency }
            if (position < 0) best.add(entry) else best.add(position, entry)
            if (best.size > 3) best.removeAt(3)
        }
    }
}
