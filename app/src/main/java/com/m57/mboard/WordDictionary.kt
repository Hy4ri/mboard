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
    private val spellings: Map<String, List<Spelling>>,
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
        /** A listed misspelling (ذالك): known, so never "corrected" into, but never offered either. */
        val hidden: Boolean = false,
    )

    private class Spelling(
        val original: String,
        val frequency: Long,
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
            if ((!dialect && entry.isDialectOnly) || entry.hidden) continue
            insertBestCandidate(best, Candidate(entry.original, entry.frequency.toDouble()), limit)
        }
        return best
    }

    /**
     * Arabic completions behind an attached conjunction or preposition: وبالبي → وبالبيت, للمدر → للمدرسة.
     * Score is the bare word's frequency; the clitic form is rarer, so callers should discount it.
     */
    fun cliticCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (language != Layout.ARABIC || prefix.length < 3 || prefix.length > 64) return emptyList()
        val best = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        for ((clitic, stemStart) in ARABIC_CLITIC_PREFIXES) {
            if (!prefix.startsWith(clitic)) continue
            // ل + ال is written لل: complete the ال-word and write it back with one alef dropped.
            val stem = stemStart + prefix.substring(clitic.length)
            if (stem.length < 2 + stemStart.length) continue
            for (c in completions(stem, limit, dialect)) {
                val word = clitic + c.word.substring(stemStart.length)
                if (seen.add(word)) insertBestCandidate(best, Candidate(word, c.score), limit)
            }
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
        if (language == Layout.ARABIC) return arabicIntended(word)
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
        ARABIC_MISSPELLINGS[word]?.let { return it }
        val normalized = normalize(word, language) ?: return null
        val typedFrequency = originals[word] ?: 0L

        lazySpelling(word, normalized, typedFrequency)?.let { return it }
        // Final hamza on a seat: شيئ → شيء, بطئ → بطء.
        if (word.length >= 3 && word.last() in "ئؤ") {
            val bare = word.dropLast(1) + 'ء'
            val bareFrequency = originals[bare] ?: 0L
            if (bareFrequency >= 1000L && bareFrequency >= 3 * typedFrequency) return bare
        }
        if (exact.containsKey(normalized)) return null
        // Laughter and held single letters (هههههه, ممممم) are deliberate.
        if (ELONGATION.replace(normalized, "$1").length < 3) return null
        if (looksInflected(normalized)) return null

        // Tanween typed as noon: شكرن → شكرا, جدن → جدا.
        if (word.endsWith('ن') && word.length >= 3) {
            exact[normalized.dropLast(1) + 'ا']?.let { if (it.frequency >= 1000L) return it.original }
        }

        val candidates = arabicEdits(normalized)
        val best = candidates.firstOrNull() ?: return null
        // Two plausible different words means a guess, so leave the choice to the suggestion strip.
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && runnerUp.second * 3 > best.second) return null
        val elongated = ELONGATION.containsMatchIn(normalized)
        if (!elongated && (normalized.length < 4 || best.second < 4_000.0)) return null
        return if (midWord(normalized, best.first)) null else best.first.original
    }

    /**
     * The common spelling of the same word when [word] only differs by a lazily typed letter:
     * bare alef for hamza (انا → أنا), final ه for ة (مدرسه → مدرسة), ى for ي (فى → في), ئ for ء (شيئ → شيء).
     * Letters the user typed deliberately (أ إ آ, or a known word ending in ي/ه) are kept.
     */
    private fun lazySpelling(
        word: String,
        normalized: String,
        typedFrequency: Long,
        strict: Boolean = true,
    ): String? {
        val group = spellings[looseKey(normalized)] ?: return null
        val top = group.first()
        if (top.original == word || top.original.length != word.length) return null
        val rival = group.firstOrNull { it !== top && it.original != word }?.frequency ?: 0L
        var ratio = 3L
        for (i in word.indices) {
            val typed = word[i]
            val wanted = top.original[i]
            if (typed == wanted) continue
            val last = i == word.lastIndex
            when {
                typed == 'ا' && wanted in "أإآ" -> {
                    // انه could be أنه or إنه: only fill a hamza that is clearly the usual one.
                    if (top.frequency < 2 * rival) return null
                    ratio = maxOf(ratio, 2L)
                }

                last && typed == 'ى' && wanted == 'ي' -> {
                    Unit
                }

                // علي and الي are real words, not misspelt على and إلى.
                last && typed == 'ي' && wanted == 'ى' -> {
                    if (typedFrequency > 0) return null
                }

                // كتابه is "his book", not a misspelt كتابة; an ال- word cannot take that pronoun.
                last && typed == 'ه' && wanted == 'ة' -> {
                    val stem = normalized.dropLast(1)
                    if (strict && typedFrequency > 0 && !word.startsWith("ال") && exact.containsKey(stem)) ratio = maxOf(ratio, 20L)
                }

                last && typed == 'ة' && wanted == 'ه' -> {
                    Unit
                }

                last && typed in "ئؤ" && wanted == 'ء' -> {
                    Unit
                }

                else -> {
                    return null
                }
            }
        }
        return if (top.frequency >= ratio * typedFrequency) top.original else null
    }

    /** Suggest-only Arabic fix for an unknown word whose correction was not certain enough to apply. */
    private fun arabicIntended(word: String): String? {
        if (word.length < 3 || word.length > 32 || word in dialectSpellings) return null
        if (word.any { it in '\u064B'..'\u065F' || it == '\u0670' || it == 'ـ' }) return null
        val normalized = normalize(word, language) ?: return null
        // مدرسه stays as typed (it can mean "his teacher") but مدرسة is offered first.
        lazySpelling(word, normalized, originals[word] ?: 0L, strict = false)?.let { return it }
        if (exact.containsKey(normalized) || looksInflected(normalized)) return null
        val best = arabicEdits(normalized).firstOrNull() ?: return null
        return if (best.second >= 500.0) best.first.original else null
    }

    /** Known words one keyboard slip away from [normalized], best first, scored by frequency * edit weight. */
    private fun arabicEdits(normalized: String): List<Pair<Entry, Double>> {
        if (normalized.length < 3) return emptyList()
        val scores = HashMap<String, Pair<Entry, Double>>()

        fun consider(
            cand: String,
            weight: Double,
        ) {
            if (cand.length < 2 || cand == normalized) return
            val entry = exact[cand] ?: return
            if (entry.hidden) return
            val score = entry.frequency * weight
            val old = scores[entry.normalized]
            if (old == null || score > old.second) scores[entry.normalized] = entry to score
        }

        val n = normalized.length
        // Held keys: حلوووو → حلو, تماام → تمام.
        if (ELONGATION.containsMatchIn(normalized)) {
            consider(ELONGATION.replace(normalized, "$1"), 1.0)
            consider(ELONGATION.replace(normalized, "$1$1"), 0.8)
        }
        for (i in 0 until n - 1) {
            consider(normalized.substring(0, i) + normalized[i + 1] + normalized[i] + normalized.substring(i + 2), 1.0)
            if (normalized[i] == normalized[i + 1]) consider(normalized.removeRange(i, i + 1), 0.9)
        }
        for (i in 0 until n) {
            val orig = normalized[i]
            // A final ة/ت swap is usually a different word (noun vs verb), not a slip.
            val last = i == n - 1
            for (c in ARABIC_ALPHABET) {
                if (c != orig && adjacentArabic(orig, c) && !isDialectSensitive(orig, c) && !(last && orig in "ةت" && c in "ةت")) {
                    consider(normalized.substring(0, i) + c + normalized.substring(i + 1), 0.85)
                }
            }
            // A stray neighbouring key pressed with the intended one.
            val stray = (i > 0 && adjacentArabic(normalized[i - 1], orig)) || (i + 1 < n && adjacentArabic(normalized[i + 1], orig))
            if (stray && n >= 4) consider(normalized.removeRange(i, i + 1), 0.6)
        }
        // A missed letter inside a longer word: حبيي → حبيبي.
        if (n >= 4) {
            for (i in 1 until n) {
                for (c in ARABIC_ALPHABET) consider(normalized.substring(0, i) + c + normalized.substring(i), 0.5)
            }
        }
        return scores.values.sortedByDescending { it.second }
    }

    /**
     * A valid but unlisted form of a known word: clitics (وبسيارته), conjugations (تقبض beside يقبض)
     * or inflection suffixes (ضروريا beside ضروري). Most Arabic words are such forms, so they stay as typed.
     */
    private fun looksInflected(normalized: String): Boolean {
        if (isCliticDecomposable(normalized)) return true
        val verb = if (normalized.startsWith('س') && normalized.length >= 4) normalized.drop(1) else normalized
        if (verb.length >= 3 && verb[0] in VERB_PREFIXES) {
            val rest = verb.substring(1)
            if (VERB_PREFIXES.any { it != verb[0] && exact.containsKey(it + rest) }) return true
        }
        for (suffix in ARABIC_SUFFIXES) {
            if (normalized.length - suffix.length >= 2 && normalized.endsWith(suffix)) {
                val stem = normalized.dropLast(suffix.length)
                if (exact.containsKey(stem) || exact.containsKey(stem + 'ة')) return true
                if (stem.endsWith('ت') && exact.containsKey(stem.dropLast(1) + 'ة')) return true
            }
        }
        return false
    }

    private fun isCliticDecomposable(normalizedWord: String): Boolean {
        // Enclitic only; a doubled final ي/و (حبيي) is a slip, not a pronoun.
        for (e in ARABIC_ENCLITICS) {
            if (normalizedWord.endsWith(e) && normalizedWord.length - e.length >= 2) {
                val stem = normalizedWord.substring(0, normalizedWord.length - e.length)
                if (e.length == 1 && e[0] in "يو" && stem.last() == e[0]) continue
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

        /** Frequent misspellings that the subtitle corpus lists as words, so edit rules cannot catch them. */
        private val ARABIC_MISSPELLINGS: Map<String, String> =
            mapOf(
                "ذالك" to "ذلك",
                "كذالك" to "كذلك",
                "بذالك" to "بذلك",
                "لذالك" to "لذلك",
                "هاذه" to "هذه",
                "هاكذا" to "هكذا",
                "لاكن" to "لكن",
                "ولاكن" to "ولكن",
                "اللة" to "الله",
                "مسؤل" to "مسؤول",
                "مسئول" to "مسؤول",
                "لاكنه" to "لكنه",
                "لاكني" to "لكني",
            )

        /** Clitic prefix as typed, and what the bare word starts with in its place. */
        private val ARABIC_CLITIC_PREFIXES =
            listOf(
                "ولل" to "ال",
                "فلل" to "ال",
                "لل" to "ال",
                "وب" to "",
                "ول" to "",
                "فب" to "",
                "فل" to "",
                "و" to "",
                "ف" to "",
                "ب" to "",
                "ل" to "",
                "ك" to "",
            )

        /** The same letter held three or more times. */
        private val ELONGATION = Regex("(.)\\1{2,}")

        /** Imperfect-verb person prefixes: يقبض / تقبض / نقبض / اقبض are one verb. */
        private const val VERB_PREFIXES = "يتنا"

        private val ARABIC_SUFFIXES = listOf("ات", "ون", "ين", "ان", "تان", "تين", "ا", "ة")

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
                                if (normalized.isEmpty()) {
                                    null
                                } else {
                                    Entry(normalized, original, frequency, hidden = original in ARABIC_MISSPELLINGS)
                                }
                            }.toList()
                    }

            // Every listed surface spelling, since normalizing merges انا into أنا and علي into على.
            val originals = HashMap<String, Long>(fileEntries.size * 2)
            val spellings = HashMap<String, MutableList<Spelling>>()
            if (language == Layout.ARABIC) {
                for (entry in fileEntries) {
                    if ('ـ' in entry.original || entry.original in ARABIC_MISSPELLINGS) continue
                    originals.merge(entry.original, entry.frequency, ::maxOf)
                    spellings.getOrPut(looseKey(entry.normalized)) { ArrayList() } += Spelling(entry.original, entry.frequency)
                }
                spellings.values.forEach { group -> group.sortByDescending { it.frequency } }
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

            entries.forEach { entry ->
                if (!entry.hidden) {
                    for (length in 1..minOf(3, entry.normalized.length)) {
                        val key = entry.normalized.substring(0, length)
                        insertBestEntry(short.getOrPut(key) { ArrayList() }, entry, limit = 8)
                    }
                }
                exact.merge(entry.normalized, entry) { a, b -> if (b.frequency > a.frequency) b else a }
                if (language == Layout.ENGLISH) originals.merge(entry.original, entry.frequency, ::maxOf)
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
                spellings = spellings,
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
