package com.m57.mboard

import java.io.Reader
import java.util.Locale
import kotlin.math.abs

/** Pure Kotlin, read-only word completion and correction dictionary. */
class WordDictionary private constructor(
    private val entries: List<Entry>,
    private val shortPrefixes: Map<String, List<Entry>>,
    private val language: Layout,
    val commonWords: List<String>,
    private val exact: Map<String, Entry>,
    private val loose: Map<String, Entry>,
    private val originals: Map<String, Long>,
    private val dialectSpellings: Set<String>,
) {
    data class Candidate(
        val word: String,
        val score: Double,
    )

    private data class Entry(
        val normalized: String,
        val original: String,
        val frequency: Long,
        val isDialectOnly: Boolean = false,
    )

    val size: Int get() = entries.size

    fun frequency(word: String): Long {
        val normalized = normalize(word, language) ?: return 0L
        return exact[normalized]?.frequency ?: 0L
    }

    /** Exact-prefix completions; score equals frequency. */
    fun completions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (prefix.isEmpty() || prefix.length > 64 || limit <= 0) return emptyList()
        val normalizedPrefix = normalize(prefix, language) ?: return emptyList()
        if (normalizedPrefix.isEmpty()) return emptyList()

        if (normalizedPrefix.length <= 3) {
            shortPrefixes[normalizedPrefix]?.let { cachedEntries ->
                val result = ArrayList<Candidate>(limit)
                for (entry in cachedEntries) {
                    if (!dialect && entry.isDialectOnly) continue
                    result.add(Candidate(entry.original, entry.frequency.toDouble()))
                    if (result.size == limit) break
                }
                return result
            }
        }

        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].normalized < normalizedPrefix) low = mid + 1 else high = mid
        }

        val best = ArrayList<Candidate>(limit)
        var index = low
        var scanned = 0
        while (index < entries.size && scanned < MAX_PREFIX_SCAN) {
            val entry = entries[index++]
            scanned++
            if (!entry.normalized.startsWith(normalizedPrefix)) break
            if (!dialect && entry.isDialectOnly) continue
            insertBestCandidate(best, Candidate(entry.original, entry.frequency.toDouble()), limit)
        }
        return best
    }

    /** Typo-tolerant prefix completions; score equals frequency * weight. */
    fun fuzzyCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (prefix.length < 3 || prefix.length > 64 || limit <= 0) return emptyList()
        val normalizedPrefix = normalize(prefix, language) ?: return emptyList()
        if (normalizedPrefix.length < 3) return emptyList()

        val variants = prefixVariants(normalizedPrefix)
        if (variants.isEmpty()) return emptyList()

        val candidateMap = HashMap<String, Double>()
        for ((variant, weight) in variants) {
            val topCompletions = completions(variant, limit = 2, dialect = dialect)
            for (comp in topCompletions) {
                val compNorm = normalize(comp.word, language) ?: continue
                if (compNorm.startsWith(normalizedPrefix)) continue
                val score = comp.score * weight
                val existing = candidateMap[comp.word]
                if (existing == null || score > existing) {
                    candidateMap[comp.word] = score
                }
            }
        }

        if (candidateMap.isEmpty()) return emptyList()
        val best = ArrayList<Candidate>(limit)
        for ((word, score) in candidateMap) {
            insertBestCandidate(best, Candidate(word, score), limit)
        }
        return best
    }

    private fun prefixVariants(prefix: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(prefix.length * 6)
        val n = prefix.length

        // Transposition: 0.95
        for (i in 0 until n - 1) {
            val trans = prefix.substring(0, i) + prefix[i + 1] + prefix[i] + prefix.substring(i + 2)
            if (trans != prefix) out += trans to 0.95
        }

        if (language == Layout.ENGLISH) {
            // Adjacent substitution: 0.8
            for (i in 0 until n) {
                val orig = prefix[i]
                for (c in ENGLISH_ALPHABET) {
                    if (c != orig && adjacentQwerty(orig, c)) {
                        out += (prefix.substring(0, i) + c + prefix.substring(i + 1)) to 0.8
                    }
                }
            }
            // Double a letter: 0.9
            for (i in 0 until n) {
                out += (prefix.substring(0, i) + prefix[i] + prefix.substring(i)) to 0.9
            }
            // Undouble a letter: 0.9
            for (i in 0 until n - 1) {
                if (prefix[i] == prefix[i + 1]) {
                    out += (prefix.substring(0, i) + prefix.substring(i + 1)) to 0.9
                }
            }
        } else {
            // Arabic: adjacent substitution: 0.8 (excluding dialect-sensitive pairs)
            for (i in 0 until n) {
                val orig = prefix[i]
                for (c in ARABIC_ALPHABET) {
                    if (c != orig && adjacentArabic(orig, c) && !isDialectSensitive(orig, c)) {
                        out += (prefix.substring(0, i) + c + prefix.substring(i + 1)) to 0.8
                    }
                }
            }
        }
        return out
    }

    /**
     * The dictionary spelling the typed word should become, or null when it should stay as typed.
     * English: contractions, 1-edit / 2-edit corrections.
     * Arabic: loose variants, clitic-decomp guards, tanween as noon, 1-edit adjacent/transposition.
     */
    fun correction(word: String): String? {
        if (word.isEmpty() || word.length > 32) return null
        synchronized(cache) { if (cache.containsKey(word)) return cache[word] }
        val result = if (language == Layout.ENGLISH) englishCorrection(word) else arabicCorrection(word)
        synchronized(cache) { cache[word] = result }
        return result
    }

    /** Suggest-only fix for KNOWN rare English words, else null. */
    fun likelyIntended(word: String): String? {
        if (language != Layout.ENGLISH) return null
        if (word.length < 4 || word.length > 32) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        val lower = word.lowercase(Locale.ROOT)
        val typedEntry = exact[lower] ?: return null
        val typedFreq = typedEntry.frequency
        if (typedFreq !in 1L..999L) return null

        var bestTarget: Entry? = null
        var bestFreq = 0L

        fun check(cand: String) {
            val entry = exact[cand] ?: return
            if (entry.frequency >= 300 * typedFreq && entry.frequency > bestFreq) {
                bestFreq = entry.frequency
                bestTarget = entry
            }
        }

        // Safe edit set: transposition, QWERTY-adjacent substitution, double, undouble
        val n = lower.length
        for (i in 0 until n - 1) {
            check(lower.substring(0, i) + lower[i + 1] + lower[i] + lower.substring(i + 2))
        }
        for (i in 0 until n) {
            val orig = lower[i]
            for (c in ENGLISH_ALPHABET) {
                if (c != orig && adjacentQwerty(orig, c)) {
                    check(lower.substring(0, i) + c + lower.substring(i + 1))
                }
            }
        }
        for (i in 0 until n) {
            check(lower.substring(0, i) + lower[i] + lower.substring(i))
        }
        for (i in 0 until n - 1) {
            if (lower[i] == lower[i + 1]) {
                check(lower.substring(0, i) + lower.substring(i + 1))
            }
        }

        return bestTarget?.original
    }

    private val cache =
        object : LinkedHashMap<String, String?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 128
        }

    private fun englishCorrection(word: String): String? {
        val lower = word.lowercase(Locale.ROOT)
        CONTRACTIONS[lower]?.let { return it }
        if (word.length < 3 || word.length > 32) return null
        // A typed apostrophe is deliberate (he's, i've); the list has no apostrophe forms, so edits would mangle them.
        if (word.any { !it.isLetter() }) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        if (lower in SLANG) return null
        val normalized = normalize(word, language) ?: return null
        if (exact.containsKey(normalized)) return null

        var bestEntry: Entry? = null
        var bestScore = 0.0

        fun consider(
            candidate: String,
            weight: Double,
        ) {
            if (candidate.length < 3) return
            if (lower.length == 3 && weight < 0.9) return
            val entry = exact[candidate] ?: return
            val score = entry.frequency * weight
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        // 1-edit
        val n = lower.length
        // Transposition
        for (i in 0 until n - 1) {
            consider(lower.substring(0, i) + lower[i + 1] + lower[i] + lower.substring(i + 2), 1.0)
        }
        // Substitution
        for (i in 0 until n) {
            val orig = lower[i]
            for (c in ENGLISH_ALPHABET) {
                if (c != orig) {
                    val weight =
                        when {
                            adjacentQwerty(orig, c) -> 0.9
                            orig in VOWELS && c in VOWELS -> 0.4
                            else -> 0.15
                        }
                    consider(lower.substring(0, i) + c + lower.substring(i + 1), weight)
                }
            }
        }
        // Deletion
        for (i in 0 until n) {
            val stray =
                (i > 0 && (lower[i - 1] == lower[i] || adjacentQwerty(lower[i - 1], lower[i]))) ||
                    (i + 1 < n && (lower[i + 1] == lower[i] || adjacentQwerty(lower[i + 1], lower[i])))
            if (stray) {
                consider(lower.substring(0, i) + lower.substring(i + 1), 0.7)
            }
        }
        // Insertion
        for (i in 0..n) {
            val prev = if (i > 0) lower[i - 1] else null
            for (d in ENGLISH_ALPHABET) {
                val weight =
                    when {
                        prev != null && d == prev -> 0.9
                        d in "aeiouh" -> 0.5
                        else -> 0.3
                    }
                consider(lower.substring(0, i) + d + lower.substring(i), weight)
            }
        }

        // 2-edit bounded
        if (bestEntry == null && lower.length >= 5) {
            val first = restrictedEdits(lower)
            for ((mid, w1) in first) {
                val second = restrictedEdits(mid)
                for ((cand, w2) in second) {
                    if (cand.length < 4) continue
                    val entry = exact[cand] ?: continue
                    val score = entry.frequency * w1 * w2 * 0.2
                    if (score > bestScore) {
                        bestScore = score
                        bestEntry = entry
                    }
                }
            }
        }

        val best = bestEntry ?: return null
        return if (bestScore >= 300.0 && !midWord(normalized, best)) best.original else null
    }

    /** A typed prefix of a more frequent other word is unfinished, not misspelt: "becau" must not become a guess. */
    private fun midWord(
        normalized: String,
        candidate: Entry,
    ): Boolean =
        completions(normalized, limit = 2, dialect = true).any {
            it.word != candidate.original && it.score >= candidate.frequency
        }

    private fun restrictedEdits(word: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val n = word.length
        // Transposition: 1.0
        for (i in 0 until n - 1) {
            out += (word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)) to 1.0
        }
        // Double a letter: 0.9
        for (i in 0 until n) {
            out += (word.substring(0, i) + word[i] + word.substring(i)) to 0.9
        }
        // Undouble a letter: 0.9
        for (i in 0 until n - 1) {
            if (word[i] == word[i + 1]) {
                out += (word.substring(0, i) + word.substring(i + 1)) to 0.9
            }
        }
        // Vowel->vowel substitution (aeiouy): 0.7
        for (i in 0 until n) {
            if (word[i] in VOWELS_Y) {
                for (v in VOWELS_Y) {
                    if (v != word[i]) {
                        out += (word.substring(0, i) + v + word.substring(i + 1)) to 0.7
                    }
                }
            }
        }
        // Insert a vowel (aeiou): 0.6
        for (i in 0..n) {
            for (v in VOWELS) {
                out += (word.substring(0, i) + v + word.substring(i)) to 0.6
            }
        }
        // Delete a vowel: 0.6
        for (i in 0 until n) {
            if (word[i] in VOWELS) {
                out += (word.substring(0, i) + word.substring(i + 1)) to 0.6
            }
        }
        return out
    }

    private fun arabicCorrection(word: String): String? {
        if (word.any { it in '\u064B'..'\u065F' || it == '\u0670' || it == 'ـ' }) return null
        if (word.length < 2 || word.length > 32) return null
        // Curated dialect spellings are deliberate: هلأ is "now", not a misspelt هلا.
        if (word in dialectSpellings) return null
        val normalized = normalize(word, language) ?: return null
        val typedFrequency = originals[word] ?: 0L

        // Loose-key spelling-variant rule
        val variant = loose[looseKey(normalized)]
        if (variant != null) {
            val yaaOnly = typedFrequency > 0 && word.dropLast(1) == variant.original.dropLast(1) && word.last() in "يى"
            if (!yaaOnly && variant.original != word && variant.frequency >= 3 * typedFrequency) {
                return variant.original
            }
        }

        // If known, no autocorrection
        if (exact.containsKey(normalized)) return null

        // Clitic decomposition
        if (isCliticDecomposable(normalized)) return null

        // Tanween typed as noon
        if (word.endsWith('ن') && word.length >= 4) {
            val tanweenCand = normalize(word.dropLast(1) + "ا", language)
            if (tanweenCand != null) {
                val entry = exact[tanweenCand]
                if (entry != null && entry.frequency >= 1000L) {
                    return entry.original
                }
            }
        }

        // Unknown word, length >= 3: single substitution or adjacent transposition
        if (normalized.length >= 3) {
            var bestEntry: Entry? = null
            var bestScore = 0.0
            val n = normalized.length

            fun considerArabic(
                cand: String,
                weight: Double,
            ) {
                val entry = exact[cand] ?: return
                val score = entry.frequency * weight
                if (score > bestScore) {
                    bestScore = score
                    bestEntry = entry
                }
            }

            // Adjacent transposition: 1.0
            for (i in 0 until n - 1) {
                val cand = normalized.substring(0, i) + normalized[i + 1] + normalized[i] + normalized.substring(i + 2)
                considerArabic(cand, 1.0)
            }

            // Arabic-adjacent substitution: 0.85
            for (i in 0 until n) {
                val orig = normalized[i]
                for (c in ARABIC_ALPHABET) {
                    if (c != orig && adjacentArabic(orig, c) && !isDialectSensitive(orig, c)) {
                        val cand = normalized.substring(0, i) + c + normalized.substring(i + 1)
                        considerArabic(cand, 0.85)
                    }
                }
            }

            val best = bestEntry
            if (best != null && best.frequency >= 50 * maxOf(1L, typedFrequency) && bestScore >= 500.0 &&
                !midWord(normalized, best)
            ) {
                return best.original
            }
        }

        return null
    }

    private fun isCliticDecomposable(normalizedWord: String): Boolean {
        // Enclitic only
        for (e in ARABIC_ENCLITICS) {
            if (normalizedWord.endsWith(e) && normalizedWord.length - e.length >= 2) {
                val stem = normalizedWord.substring(0, normalizedWord.length - e.length)
                if (exact.containsKey(stem)) return true
                if (stem.endsWith('ت') && exact.containsKey(stem.dropLast(1) + 'ة')) return true
            }
        }

        // Proclitic only
        for (p in ARABIC_PROCLITICS) {
            if (normalizedWord.startsWith(p) && normalizedWord.length - p.length >= 2) {
                val stem = normalizedWord.substring(p.length)
                if (exact.containsKey(stem)) return true
            }
        }

        // Proclitic + enclitic
        for (p in ARABIC_PROCLITICS) {
            if (normalizedWord.startsWith(p)) {
                val rem = normalizedWord.substring(p.length)
                for (e in ARABIC_ENCLITICS) {
                    if (rem.endsWith(e) && rem.length - e.length >= 2) {
                        val stem = rem.substring(0, rem.length - e.length)
                        if (exact.containsKey(stem)) return true
                        if (stem.endsWith('ت') && exact.containsKey(stem.dropLast(1) + 'ة')) return true
                    }
                }
            }
        }

        return false
    }

    companion object {
        private const val MAX_PREFIX_SCAN = 400
        private const val ENGLISH_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        private const val ARABIC_ALPHABET = "ضصثقفغعهخحجشسيبلاتنمكطذئءؤرىةوزظد"
        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
        private val VOWELS_Y = setOf('a', 'e', 'i', 'o', 'u', 'y')

        private val SLANG: Set<String> =
            setOf(
                "lol",
                "lmao",
                "lmfao",
                "rofl",
                "ngl",
                "tbh",
                "wtf",
                "idk",
                "idc",
                "lmk",
                "brb",
                "imo",
                "imho",
                "btw",
                "sus",
                "bruh",
                "tho",
                "rn",
                "smh",
                "ikr",
                "hbu",
                "wyd",
                "wbu",
                "ofc",
                "pls",
                "plz",
                "np",
                "gg",
                "thx",
                "tx",
                "bcz",
                "cuz",
                "cus",
                "ty",
                "yeet",
                "rizz",
                "okk",
                "okey",
                "wifi",
                "omg",
                "omw",
                "ttyl",
                "gtg",
                "nvm",
                "jk",
                "fyi",
                "asap",
                "dm",
                "dms",
                "ppl",
                "msg",
                "pic",
                "pics",
                "vid",
                "vids",
                "tmrw",
                "tmr",
                "tbf",
                "afaik",
                "iirc",
                "irl",
                "af",
                "ur",
                "kk",
                "yup",
                "yep",
                "nope",
                "nah",
                "ya",
                "yea",
                "yeah",
                "hmm",
                "hmmm",
                "ugh",
                "aww",
                "haha",
                "hahaha",
                "hehe",
                "xd",
                "xoxo",
                "yalla",
                "habibi",
                "habibti",
                "inshallah",
                "wallah",
                "mashallah",
                "khalas",
                "yani",
                "walla",
                "shu",
                "wen",
            )

        private val ARABIC_PROCLITICS =
            listOf(
                "وال",
                "بال",
                "فال",
                "كال",
                "لل",
                "ال",
                "و",
                "ف",
                "ب",
                "ل",
                "ك",
            )

        private val ARABIC_ENCLITICS =
            listOf(
                "هما",
                "كما",
                "كم",
                "كن",
                "هم",
                "هن",
                "نا",
                "ني",
                "ها",
                "ه",
                "ك",
                "ي",
                "و",
            )

        private val QWERTY = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val QWERTY_ROW_OFFSET = doubleArrayOf(0.0, 0.5, 1.5)

        private val ARABIC_ROWS =
            listOf(
                "ضصثقفغعهخحج",
                "شسيبلاتنمكط",
                "ذئءؤرىةوزظد",
            )

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

        private fun adjacentQwerty(
            a: Char,
            b: Char,
        ): Boolean {
            val pa = keyPositionQwerty(a) ?: return false
            val pb = keyPositionQwerty(b) ?: return false
            return abs(pa.first - pb.first) <= 1 && abs(pa.second - pb.second) <= 1.01
        }

        private fun keyPositionQwerty(c: Char): Pair<Int, Double>? {
            QWERTY.forEachIndexed { row, keys ->
                val col = keys.indexOf(c)
                if (col >= 0) return row to col + QWERTY_ROW_OFFSET[row]
            }
            return null
        }

        private fun adjacentArabic(
            a: Char,
            b: Char,
        ): Boolean {
            val pa = keyPositionArabic(a) ?: return false
            val pb = keyPositionArabic(b) ?: return false
            return abs(pa.first - pb.first) <= 1 && abs(pa.second - pb.second) <= 1
        }

        private fun keyPositionArabic(c: Char): Pair<Int, Int>? {
            ARABIC_ROWS.forEachIndexed { row, keys ->
                val col = keys.indexOf(c)
                if (col >= 0) return row to col
            }
            return null
        }

        private fun isDialectSensitive(
            a: Char,
            b: Char,
        ): Boolean =
            (a == 'ض' && b == 'ظ') || (a == 'ظ' && b == 'ض') ||
                (a == 'ذ' && b == 'ز') || (a == 'ز' && b == 'ذ') ||
                (a == 'ث' && b == 'س') || (a == 'س' && b == 'ث') ||
                (a == 'ت' && b == 'ط') || (a == 'ط' && b == 'ت') ||
                (a == 'ق' && b == 'ء') || (a == 'ء' && b == 'ق')

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
        @JvmOverloads
        @Throws(java.io.IOException::class)
        fun read(
            reader: Reader,
            language: Layout,
            dialect: Map<String, Long> = emptyMap(),
        ): WordDictionary {
            val fileEntries =
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
                                if (normalized.isEmpty()) null else Entry(normalized, original, frequency, isDialectOnly = false)
                            }.toList()
                    }

            val mergedMap = HashMap<String, Entry>(fileEntries.size + dialect.size)
            for (entry in fileEntries) {
                val existing = mergedMap[entry.normalized]
                if (existing == null || entry.frequency > existing.frequency) {
                    mergedMap[entry.normalized] = entry
                }
            }

            for ((orig, freq) in dialect) {
                val normalized = normalize(orig, language) ?: continue
                if (normalized.isEmpty()) continue
                val existing = mergedMap[normalized]
                if (existing != null) {
                    if (freq > existing.frequency) {
                        mergedMap[normalized] = Entry(normalized, existing.original, freq, isDialectOnly = false)
                    }
                } else {
                    mergedMap[normalized] = Entry(normalized, orig, freq, isDialectOnly = true)
                }
            }

            val entries = mergedMap.values.sortedWith(compareBy<Entry> { it.normalized }.thenByDescending { it.frequency })

            val short = HashMap<String, MutableList<Entry>>()
            val exact = HashMap<String, Entry>(entries.size * 2)
            val loose = HashMap<String, Entry>()
            val originals = HashMap<String, Long>(entries.size * 2)

            entries.forEach { entry ->
                for (length in 1..minOf(3, entry.normalized.length)) {
                    val key = entry.normalized.substring(0, length)
                    insertBestEntry(short.getOrPut(key) { ArrayList() }, entry, limit = 8)
                }
                exact.merge(entry.normalized, entry) { a, b -> if (b.frequency > a.frequency) b else a }
                originals.merge(entry.original, entry.frequency, ::maxOf)
                if (language == Layout.ARABIC) {
                    loose.merge(looseKey(entry.normalized), entry) { a, b -> if (b.frequency > a.frequency) b else a }
                }
            }

            val common = ArrayList<Entry>(3)
            entries.forEach { entry ->
                if (!entry.isDialectOnly) {
                    insertBestEntry(common, entry, limit = 3)
                }
            }

            return WordDictionary(
                entries = entries,
                shortPrefixes = short,
                language = language,
                commonWords = common.map { it.original },
                exact = exact,
                loose = loose,
                originals = originals,
                dialectSpellings = if (language == Layout.ARABIC) dialect.keys else emptySet(),
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

        private fun insertBestEntry(
            best: MutableList<Entry>,
            entry: Entry,
            limit: Int,
        ) {
            val position = best.indexOfFirst { it.frequency < entry.frequency }
            if (position < 0) best.add(entry) else best.add(position, entry)
            if (best.size > limit) best.removeAt(limit)
        }

        private fun insertBestCandidate(
            best: MutableList<Candidate>,
            candidate: Candidate,
            limit: Int,
        ) {
            val position = best.indexOfFirst { it.score < candidate.score }
            if (position < 0) best.add(candidate) else best.add(position, candidate)
            if (best.size > limit) best.removeAt(limit)
        }
    }
}
