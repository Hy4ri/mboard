package com.m57.mboard

import java.io.Reader
import java.util.Locale

/** Pure Kotlin, read-only word completion and correction dictionary. */
class WordDictionary private constructor(
    private val entries: List<Entry>,
    private val shortPrefixes: Map<String, List<Entry>>,
    private val language: Language,
    val commonWords: List<String>,
    private val exact: Map<String, Entry>,
    private val spellings: Map<String, List<Spelling>>,
    private val originals: Map<String, Long>,
    private val dialectSpellings: Set<String>,
    private val dialectAliases: Map<String, List<Spelling>> = emptyMap(),
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

    /** Sum of all listed frequencies, the denominator of a word's share. */
    val totalFrequency: Long = entries.sumOf { it.frequency }.coerceAtLeast(1L)

    fun frequency(word: String): Long {
        val normalized = normalize(word, language) ?: return 0L
        return exact[normalized]?.frequency ?: 0L
    }

    /** Exact-prefix completions; score equals frequency. */
    fun completions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> = complete(prefix, limit, dialect, includeAliases = true)

    /** Canonical-only lookup remains available to the spelling guards: aliases are display choices. */
    private fun complete(
        prefix: String,
        limit: Int,
        dialect: Boolean,
        includeAliases: Boolean,
    ): List<Candidate> {
        if (prefix.isEmpty() || prefix.length > 64 || limit <= 0) return emptyList()
        val normalizedPrefix = normalize(prefix, language) ?: return emptyList()
        if (normalizedPrefix.isEmpty()) return emptyList()

        if (normalizedPrefix.length <= 3) {
            shortPrefixes[normalizedPrefix]?.let { cachedEntries ->
                if (!includeAliases || !dialect || dialectAliases.isEmpty()) {
                    val result = ArrayList<Candidate>(limit)
                    for (entry in cachedEntries) {
                        if (!dialect && entry.isDialectOnly) continue
                        result.add(Candidate(entry.original, entry.frequency.toDouble()))
                        if (result.size == limit) break
                    }
                    return result
                }
                val best = ArrayList<Candidate>(limit)
                val seenWords = HashSet<String>()
                for (entry in cachedEntries) {
                    val aliases = dialectAliases[entry.normalized]
                    if (aliases != null) {
                        for (alias in aliases) {
                            if (seenWords.add(alias.original)) {
                                insertBestCandidate(
                                    best,
                                    Candidate(alias.original, alias.frequency.toDouble()),
                                    limit,
                                    preferNewOnTie = true,
                                )
                            }
                        }
                    }
                    if (seenWords.add(entry.original)) {
                        insertBestCandidate(
                            best,
                            Candidate(entry.original, entry.frequency.toDouble()),
                            limit,
                            preferNewOnTie = false,
                        )
                    }
                }
                return best
            }
        }

        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].normalized < normalizedPrefix) low = mid + 1 else high = mid
        }

        val best = ArrayList<Candidate>(limit)
        val seenWords = if (includeAliases && dialect && dialectAliases.isNotEmpty()) HashSet<String>() else null
        var index = low
        var scanned = 0
        while (index < entries.size && scanned < MAX_PREFIX_SCAN) {
            val entry = entries[index++]
            scanned++
            if (!entry.normalized.startsWith(normalizedPrefix)) break
            if ((!dialect && entry.isDialectOnly) || entry.hidden) continue

            if (seenWords != null) {
                val aliases = dialectAliases[entry.normalized]
                if (aliases != null) {
                    for (alias in aliases) {
                        if (seenWords.add(alias.original)) {
                            insertBestCandidate(
                                best,
                                Candidate(alias.original, alias.frequency.toDouble()),
                                limit,
                                preferNewOnTie = true,
                            )
                        }
                    }
                }
                if (seenWords.add(entry.original)) {
                    insertBestCandidate(
                        best,
                        Candidate(entry.original, entry.frequency.toDouble()),
                        limit,
                        preferNewOnTie = false,
                    )
                }
            } else {
                insertBestCandidate(best, Candidate(entry.original, entry.frequency.toDouble()), limit)
            }
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
        if (language.script != Script.ARABIC || prefix.length < 3 || prefix.length > 64) return emptyList()
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

    /**
     * Typo-tolerant Arabic completions behind an attached conjunction or preposition (e.g. وخياىر → وخيار).
     * Suggestion-only seam; never called by correction/autocorrect.
     * Evaluates at most the two longest eligible clitic matches, requiring at least 3 substantive typed stem letters.
     */
    fun cliticFuzzyCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (language.script != Script.ARABIC || prefix.length < 4 || prefix.length > 32 || limit <= 0) {
            return emptyList()
        }

        val effectiveLimit = minOf(limit, 4)
        val candidateMap = HashMap<String, Double>()
        var branchesEvaluated = 0

        for ((clitic, stemStart) in ARABIC_CLITIC_PREFIXES) {
            if (!prefix.startsWith(clitic)) continue
            val typedStemAfterClitic = prefix.substring(clitic.length)
            if (typedStemAfterClitic.length < 3) continue

            val stem = stemStart + typedStemAfterClitic
            val stemCandidates = fuzzyCompletions(stem, effectiveLimit, dialect)

            for (c in stemCandidates) {
                if (stemStart.isNotEmpty() && !c.word.startsWith(stemStart)) continue
                val word = clitic + c.word.substring(stemStart.length)
                if (word == prefix || word.startsWith(prefix)) continue

                val existing = candidateMap[word]
                if (existing == null || c.score > existing) {
                    candidateMap[word] = c.score
                }
            }

            branchesEvaluated++
            if (branchesEvaluated >= 2) break
        }

        if (candidateMap.isEmpty()) return emptyList()

        return candidateMap.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(effectiveLimit)
            .map { Candidate(it.key, it.value) }
    }

    private data class FuzzyKey(
        val prefix: String,
        val limit: Int,
        val dialect: Boolean,
    )

    private val fuzzyCache =
        object : LinkedHashMap<FuzzyKey, List<Candidate>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FuzzyKey, List<Candidate>>?) = size > 128
        }

    /** Typo-tolerant prefix completions; score equals frequency * weight. */
    fun fuzzyCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (prefix.length < 3 || prefix.length > 64 || limit <= 0) return emptyList()
        val key = FuzzyKey(prefix, limit, dialect)
        synchronized(fuzzyCache) {
            fuzzyCache[key]?.let { return it }
        }
        val computed = computeFuzzyCompletions(prefix, limit, dialect)
        val result = java.util.Collections.unmodifiableList(ArrayList(computed))
        synchronized(fuzzyCache) {
            fuzzyCache[key] = result
        }
        return result
    }

    private fun computeFuzzyCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
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

    /**
     * Last resort for a long word with two slips (definatly → definitely): words sharing the first letter
     * whose start is within two edits of [prefix]. Closer matches always beat more frequent ones.
     */
    fun distantCompletions(
        prefix: String,
        limit: Int,
        dialect: Boolean,
    ): List<Candidate> {
        if (prefix.length < 5 || prefix.length > 32 || limit <= 0) return emptyList()
        val typed = normalize(prefix, language) ?: return emptyList()
        if (typed.length < 5) return emptyList()
        val first = typed.substring(0, 1)
        var index = entries.binarySearchBy(first) { it.normalized }.let { if (it < 0) -it - 1 else it }
        val byDistance = Array(MAX_DISTANCE) { ArrayList<Candidate>(limit) }
        while (index < entries.size) {
            val entry = entries[index++]
            if (!entry.normalized.startsWith(first)) break
            if ((!dialect && entry.isDialectOnly) || entry.hidden) continue
            if (entry.normalized.length < typed.length - MAX_DISTANCE) continue
            val distance = prefixDistance(typed, entry.normalized)
            if (distance in 1..MAX_DISTANCE) {
                insertBestCandidate(byDistance[distance - 1], Candidate(entry.original, entry.frequency.toDouble()), limit)
            }
        }
        return byDistance.flatMap { it }.take(limit)
    }

    /** Fewest edits (with adjacent swaps) turning [typed] into some start of [word]; above the cap is cap + 1. */
    private fun prefixDistance(
        typed: String,
        word: String,
    ): Int {
        val m = minOf(word.length, typed.length + MAX_DISTANCE)
        var before = IntArray(m + 1)
        var previous = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        for (i in 1..typed.length) {
            current[0] = i
            var rowBest = i
            for (j in 1..m) {
                val cost = if (typed[i - 1] == word[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && typed[i - 1] == word[j - 2] && typed[i - 2] == word[j - 1]) {
                    value = minOf(value, before[j - 2] + 1)
                }
                current[j] = value
                rowBest = minOf(rowBest, value)
            }
            if (rowBest > MAX_DISTANCE) return MAX_DISTANCE + 1
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        return previous.minOrNull() ?: (MAX_DISTANCE + 1)
    }

    private fun prefixVariants(prefix: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(prefix.length * 6)
        val n = prefix.length

        // Transposition: 0.95
        for (i in 0 until n - 1) {
            val trans = prefix.substring(0, i) + prefix[i + 1] + prefix[i] + prefix.substring(i + 2)
            if (trans != prefix) out += trans to 0.95
        }

        if (language.script == Script.LATIN) {
            // Adjacent substitution: 0.8
            for (i in 0 until n) {
                val orig = prefix[i]
                for (c in language.alphabet) {
                    if (c != orig && language.adjacent(orig, c)) {
                        out += (prefix.substring(0, i) + c + prefix.substring(i + 1)) to language.typoWeight(orig, c)
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
            missingAndExtraLetters(prefix, out)
        } else {
            // Arabic: adjacent substitution: 0.8 (excluding dialect-sensitive pairs)
            for (i in 0 until n) {
                val orig = prefix[i]
                for (c in language.alphabet) {
                    if (c != orig && language.adjacent(orig, c) && !isDialectSensitive(orig, c)) {
                        out += (prefix.substring(0, i) + c + prefix.substring(i + 1)) to language.typoWeight(orig, c)
                    }
                }
            }
            missingAndExtraLetters(prefix, out)
        }
        return out
    }

    /**
     * A skipped or stray letter is the commonest slip in a long word, and every later keystroke keeps the
     * exact prefix dead: "unfortunatl" needs the missing e, "beautiiful" the extra i dropped.
     */
    private fun missingAndExtraLetters(
        prefix: String,
        out: MutableList<Pair<String, Double>>,
    ) {
        if (prefix.length < 4) return
        // The first letter is rarely the slip, and keeping it bounds the candidate set.
        for (i in 1 until prefix.length) {
            out += (prefix.substring(0, i) + prefix.substring(i + 1)) to 0.7
        }
        for (i in 1..prefix.length) {
            for (c in language.alphabet) {
                out += (prefix.substring(0, i) + c + prefix.substring(i)) to 0.7
            }
        }
    }

    /** Full Latin dictionary words one plausible keyboard edit away; unfinished completions are excluded. */
    fun correctionOptions(word: String): List<Candidate> {
        if (language.script != Script.LATIN || word.length !in 3..32) return emptyList()
        val normalized = normalize(word, language) ?: return emptyList()
        if (word.any { !it.isLetter() } || word.drop(1).any { it.isUpperCase() }) return emptyList()
        if (normalized in language.keepAsTyped || normalized in language.contractions || exact.containsKey(normalized)) return emptyList()
        val scores = HashMap<String, Candidate>()
        for ((variant, weight) in prefixVariants(normalized)) {
            val entry = exact[variant] ?: continue
            if (entry.hidden || entry.normalized == normalized) continue
            val score = entry.frequency * weight
            if (score < 300.0 || midWord(normalized, entry)) continue
            val old = scores[entry.original]
            if (old == null || score > old.score) scores[entry.original] = Candidate(entry.original, score)
        }
        return scores.values.sortedByDescending { it.score }.take(12)
    }

    /**
     * The dictionary spelling the typed word should become, or null when it should stay as typed.
     * English: contractions, 1-edit / 2-edit corrections.
     * Arabic: loose variants, clitic-decomp guards, tanween as noon, 1-edit adjacent/transposition.
     */
    fun correction(word: String): String? {
        if (word.isEmpty() || word.length > 32) return null
        synchronized(cache) { if (cache.containsKey(word)) return cache[word] }
        val result = if (language.script == Script.LATIN) latinCorrection(word) else arabicCorrection(word)
        synchronized(cache) { cache[word] = result }
        return result
    }

    /** Suggest-only fix for KNOWN rare words, else null. */
    fun likelyIntended(word: String): String? {
        if (language.script == Script.ARABIC) return arabicIntended(word)
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
            for (c in language.alphabet) {
                if (c != orig && language.adjacent(orig, c)) {
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

    private fun latinCorrection(word: String): String? {
        val lower = word.lowercase(Locale.ROOT)
        language.contractions[lower]?.let { return it }
        if (word.length < 3 || word.length > 32) return null
        // A typed apostrophe is deliberate (he's, i've); the list has no apostrophe forms, so edits would mangle them.
        if (word.any { !it.isLetter() }) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        if (lower in language.keepAsTyped) return null
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
            for (c in language.alphabet) {
                if (c != orig) {
                    val weight =
                        when {
                            language.adjacent(orig, c) -> 0.9
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
                (i > 0 && (lower[i - 1] == lower[i] || language.adjacent(lower[i - 1], lower[i]))) ||
                    (i + 1 < n && (lower[i + 1] == lower[i] || language.adjacent(lower[i + 1], lower[i])))
            if (stray) {
                consider(lower.substring(0, i) + lower.substring(i + 1), 0.7)
            }
        }
        // Insertion
        for (i in 0..n) {
            val prev = if (i > 0) lower[i - 1] else null
            for (d in language.alphabet) {
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
        complete(normalized, limit = 2, dialect = true, includeAliases = false).any {
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
        language.misspellings[word]?.let { return it }
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
            for (c in language.alphabet) {
                if (c != orig && language.adjacent(orig, c) && !isDialectSensitive(orig, c) && !(last && orig in "ةت" && c in "ةت")) {
                    consider(normalized.substring(0, i) + c + normalized.substring(i + 1), 0.85)
                }
            }
            // A stray neighbouring key pressed with the intended one.
            val stray = (i > 0 && language.adjacent(normalized[i - 1], orig)) || (i + 1 < n && language.adjacent(normalized[i + 1], orig))
            if (stray && n >= 4) consider(normalized.removeRange(i, i + 1), 0.6)
        }
        // A missed letter inside a longer word: حبيي → حبيبي.
        if (n >= 4) {
            for (i in 1 until n) {
                for (c in language.alphabet) consider(normalized.substring(0, i) + c + normalized.substring(i), 0.5)
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
        private const val MAX_DISTANCE = 2
        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
        private val VOWELS_Y = setOf('a', 'e', 'i', 'o', 'u', 'y')

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
            language: Language,
            shifted: Boolean,
            capsLocked: Boolean,
        ): String =
            when {
                !language.cased -> word
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
            language: Language,
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
                                    Entry(normalized, original, frequency, hidden = original in language.misspellings)
                                }
                            }.toList()
                    }

            // Every listed surface spelling, since normalizing merges انا into أنا and علي into على.
            val originals = HashMap<String, Long>(fileEntries.size * 2)
            val spellings = HashMap<String, MutableList<Spelling>>()
            if (language.script == Script.ARABIC) {
                for (entry in fileEntries) {
                    if ('ـ' in entry.original || entry.original in language.misspellings) continue
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

            val dialectAliases = HashMap<String, MutableList<Spelling>>()
            if (dialect.isNotEmpty()) {
                for ((orig, freq) in dialect) {
                    if (freq <= 0L || orig in language.misspellings) continue
                    val normalized = normalize(orig, language) ?: continue
                    if (normalized.isEmpty()) continue
                    val canonical = mergedMap[normalized] ?: continue
                    if (canonical.hidden) continue
                    if (orig != canonical.original) {
                        dialectAliases.getOrPut(normalized) { ArrayList() } += Spelling(orig, freq)
                    }
                }
                dialectAliases.values.forEach { group -> group.sortByDescending { it.frequency } }
            }

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
                if (language.script == Script.LATIN) originals.merge(entry.original, entry.frequency, ::maxOf)
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
                dialectSpellings = if (language.script == Script.ARABIC) dialect.keys else emptySet(),
                dialectAliases = dialectAliases,
            )
        }

        private fun normalize(
            value: String,
            language: Language,
        ): String? {
            if (language.script == Script.LATIN) {
                val result = value.lowercase(Locale.ROOT)
                val letters = language.alphabet
                return if (result.isNotEmpty() && result.first() in letters && result.last() in letters &&
                    result.all { it in letters || it == '\'' }
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
            preferNewOnTie: Boolean = false,
        ) {
            val position =
                if (preferNewOnTie) {
                    best.indexOfFirst { it.score <= candidate.score }
                } else {
                    best.indexOfFirst { it.score < candidate.score }
                }
            if (position < 0) best.add(candidate) else best.add(position, candidate)
            if (best.size > limit) best.removeAt(limit)
        }
    }
}
