package com.m57.mboard

import java.io.Reader
import java.util.Locale
import kotlin.math.abs

/** Pure Kotlin, read-only word completion and correction dictionary. */
class WordDictionary private constructor(
    private val entries: List<Entry>,
    private val shortPrefixes: Map<String, List<String>>,
    private val language: Layout,
    val commonWords: List<String>,
    private val exact: Map<String, Entry>,
    private val loose: Map<String, Entry>,
    private val originals: Map<String, Long>,
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

    /**
     * The dictionary spelling the typed word should become, or null when it should stay as typed.
     * English: unambiguous contractions, then unknown words within one (or two) edits.
     * Arabic: hamza/taa-marbuta/alef-maqsura spelling variants, then unknown words within one edit.
     */
    fun correction(word: String): String? {
        if (word.isEmpty() || word.length > 32) return null
        synchronized(cache) { if (cache.containsKey(word)) return cache[word] }
        val result = if (language == Layout.ENGLISH) englishCorrection(word) else arabicCorrection(word)
        synchronized(cache) { cache[word] = result }
        return result
    }

    // The strip and the separator key both ask about the same word; edit search is not free.
    private val cache =
        object : LinkedHashMap<String, String?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 128
        }

    private fun englishCorrection(word: String): String? {
        val lower = word.lowercase(Locale.ROOT)
        CONTRACTIONS[lower]?.let { return it }
        // Acronyms and mixed-case identifiers are deliberate.
        if (word.length < 2 || word.drop(1).any { it.isUpperCase() }) return null
        val normalized = normalize(word, language) ?: return null
        if (exact.containsKey(normalized)) return null
        return editCandidate(normalized, ENGLISH_ALPHABET, twoEdits = false)
    }

    private fun arabicCorrection(word: String): String? {
        // Harakat/tatweel mean the spelling is intentional.
        if (word.any { it in '\u064B'..'\u065F' || it == '\u0670' || it == 'ـ' }) return null
        val normalized = normalize(word, language) ?: return null
        val typedFrequency = originals[word] ?: 0L
        val variant = loose[looseKey(normalized)]
        if (variant != null) {
            // Final yaa vs alef maqsura are both real words (علي/على); keep a known typed spelling.
            val yaaOnly = typedFrequency > 0 && word.dropLast(1) == variant.original.dropLast(1) && word.last() in "يى"
            return if (!yaaOnly && variant.original != word && variant.frequency >= 3 * typedFrequency) variant.original else null
        }
        // Unknown Arabic words are usually valid clitic forms (كيفك, بيتها) missing from a 25k list:
        // only spelling-variant fixes are safe enough to apply automatically.
        return null
    }

    private fun editCandidate(
        word: String,
        alphabet: String,
        twoEdits: Boolean,
    ): String? {
        var best: Entry? = null
        var bestScore = 0.0

        fun consider(
            candidate: String,
            weight: Double,
        ) {
            val entry = exact[candidate] ?: return
            val score = entry.frequency * weight
            if (score > bestScore) {
                best = entry
                bestScore = score
            }
        }
        val first = edits(word, alphabet)
        first.forEach { (candidate, weight) -> consider(candidate, weight) }
        if (best == null && twoEdits) {
            first.forEach { (middle, weight) ->
                edits(middle, alphabet).forEach { (candidate, second) -> consider(candidate, weight * second * 0.05) }
            }
        }
        return best?.original
    }

    private fun edits(
        word: String,
        alphabet: String,
    ): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(word.length * (alphabet.length * 2 + 2) + alphabet.length)
        // Arabic clitics/suffixes (كيفك, بيتها) make insertions/deletions unsafe: substitute/swap only.
        val lengthEdits = language == Layout.ENGLISH
        for (i in word.indices) {
            // Only drop an accidental extra key: a doubled letter or one touching its neighbour key.
            val stray =
                (i > 0 && (word[i - 1] == word[i] || adjacent(word[i - 1], word[i]))) ||
                    (i + 1 < word.length && (word[i + 1] == word[i] || adjacent(word[i + 1], word[i])))
            if (lengthEdits && stray) out += word.removeRange(i, i + 1) to 0.5
            if (i + 1 < word.length) {
                out += (word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)) to 1.0
            }
            for (c in alphabet) {
                if (c != word[i]) {
                    val weight = if (language == Layout.ENGLISH && adjacent(word[i], c)) 1.0 else 0.3
                    out += (word.substring(0, i) + c + word.substring(i + 1)) to weight
                }
            }
        }
        if (lengthEdits) for (i in 0..word.length) for (c in alphabet) out += (word.substring(0, i) + c + word.substring(i)) to 0.5
        return out
    }

    companion object {
        private const val ENGLISH_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        private const val ARABIC_ALPHABET = "ضصثقفغعهخحجشسيبلاتنمكطذئءؤرةوزظد"
        private val QWERTY = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val ROW_OFFSET = doubleArrayOf(0.0, 0.5, 1.5)

        /** Apostrophe-less spellings that are unambiguous contractions; "its", "ill", "were", "id" stay as typed. */
        private val CONTRACTIONS =
            mapOf(
                "i" to "I",
                "im" to "I'm",
                "ive" to "I've",
                "dont" to "don't",
                "cant" to "can't",
                "wont" to "won't",
                "didnt" to "didn't",
                "doesnt" to "doesn't",
                "isnt" to "isn't",
                "wasnt" to "wasn't",
                "arent" to "aren't",
                "werent" to "weren't",
                "couldnt" to "couldn't",
                "wouldnt" to "wouldn't",
                "shouldnt" to "shouldn't",
                "havent" to "haven't",
                "hasnt" to "hasn't",
                "youre" to "you're",
                "theyre" to "they're",
                "thats" to "that's",
                "whats" to "what's",
                "theres" to "there's",
                "youve" to "you've",
                "youll" to "you'll",
                "theyve" to "they've",
                "wouldve" to "would've",
                "couldve" to "could've",
                "shouldve" to "should've",
            )

        private fun adjacent(
            a: Char,
            b: Char,
        ): Boolean {
            val pa = keyPosition(a) ?: return false
            val pb = keyPosition(b) ?: return false
            return abs(pa.first - pb.first) <= 1 && abs(pa.second - pb.second) <= 1.01
        }

        private fun keyPosition(c: Char): Pair<Int, Double>? {
            QWERTY.forEachIndexed { row, keys ->
                val col = keys.indexOf(c)
                if (col >= 0) return row to col + ROW_OFFSET[row]
            }
            return null
        }

        /** Arabic spelling-variant key: alef/yaa are merged by normalize; also fold final taa marbuta. */
        private fun looseKey(normalized: String) = if (normalized.endsWith('ة')) normalized.dropLast(1) + 'ه' else normalized

        fun displayWord(
            word: String,
            prefix: String,
            language: Layout,
            shifted: Boolean,
            capsLocked: Boolean,
        ): String =
            when {
                language != Layout.ENGLISH -> word
                prefix.isEmpty() && capsLocked -> word.uppercase(Locale.ROOT)
                prefix.isEmpty() && shifted -> word.replaceFirstChar { it.uppercaseChar() }
                capsLocked && prefix.isNotEmpty() && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
                prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
                prefix.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }

        /** Validate absolute UTF-16 prefix bounds without altering an editor. */
        fun prefixRange(
            text: String,
            start: Int,
            end: Int,
            offset: Int,
            prefix: String,
        ): Pair<Int, Int>? {
            if (prefix.isEmpty() || offset < 0 || start != end || start !in 0..text.length || prefix.length > start) return null
            if (!text.regionMatches(start - prefix.length, prefix, 0, prefix.length)) return null
            val cursor = offset.toLong() + start
            if (cursor > Int.MAX_VALUE) return null
            return (cursor.toInt() - prefix.length) to cursor.toInt()
        }

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
            val exact = HashMap<String, Entry>(entries.size * 2)
            val loose = HashMap<String, Entry>()
            val originals = HashMap<String, Long>(entries.size * 2)
            entries.forEach { entry ->
                for (length in 1..minOf(4, entry.normalized.length)) {
                    val key = entry.normalized.substring(0, length)
                    insertBest(short.getOrPut(key) { ArrayList() }, entry)
                }
                exact.merge(entry.normalized, entry) { a, b -> if (b.frequency > a.frequency) b else a }
                originals.merge(entry.original, entry.frequency, ::maxOf)
                if (language == Layout.ARABIC) {
                    loose.merge(looseKey(entry.normalized), entry) { a, b -> if (b.frequency > a.frequency) b else a }
                }
            }
            val common = ArrayList<Entry>(3)
            entries.forEach { insertBest(common, it) }
            return WordDictionary(
                entries,
                short.mapValues { it.value.map(Entry::original) },
                language,
                common.map { it.original },
                exact,
                loose,
                originals,
            )
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
