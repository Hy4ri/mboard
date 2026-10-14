package com.m57.mboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ln

/** Read-only bundled word completion dictionaries. User input is never persisted. */
class WordSuggestions(
    context: Context,
) {
    enum class State { UNLOADED, LOADING, READY, FAILED, CLOSED }

    private class Model(
        val dictionary: WordDictionary,
        val next: Map<String, Row>,
        val context: Map<String, Row>,
        val dialectNext: Map<String, Row>,
    )

    /** One table row: how often [total] the context was followed by anything, and its best followers. */
    private class Row(
        val total: Long,
        val words: Array<String>,
        val keys: Array<String>,
        val counts: LongArray,
    ) {
        val listed = counts.sum()

        fun count(key: String): Long {
            for (i in keys.indices) if (keys[i] == key) return counts[i]
            return 0L
        }
    }

    private val assets = context.applicationContext.assets
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var closed = false

    private val models = java.util.concurrent.ConcurrentHashMap<String, Model>()
    private val states = HashMap<String, State>()
    private val callbacks = HashMap<String, ArrayList<() -> Unit>>()

    private fun model(language: Language): Model? = models[language.code]

    fun state(language: Language): State =
        synchronized(lock) {
            if (closed) State.CLOSED else states[language.code] ?: State.UNLOADED
        }

    fun load(
        language: Language,
        onReady: () -> Unit,
    ) {
        synchronized(lock) {
            if (closed) return
            if (model(language) != null) {
                mainHandler.post { if (!closed) onReady() }
                return
            }
            val waiting = callbacks.getOrPut(language.code) { ArrayList() }
            waiting.add(onReady)
            if (states[language.code] == State.LOADING) return
            states[language.code] = State.LOADING
            executor.execute {
                try {
                    val dialect = language.dialect?.let { readDialectVocabulary(it.wordsAsset) }.orEmpty()
                    val dictionary =
                        assets
                            .open(language.wordsAsset)
                            .bufferedReader()
                            .use { WordDictionary.read(it, language, dialect) }
                    if (dictionary.size == 0) throw IOException("No usable bundled word entries")

                    val next = readTable(language.nextAsset)
                    val contextTable = readTable(language.contextAsset)
                    val dialectNext = language.dialect?.let { readTable(it.nextAsset) }.orEmpty()

                    val model = Model(dictionary, next, contextTable, dialectNext)

                    val readyCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            models[language.code] = model
                            states[language.code] = State.READY
                            waiting.toList().also { waiting.clear() }
                        }
                    mainHandler.post { if (!closed) readyCallbacks.forEach { it() } }
                } catch (_: IOException) {
                    val failedCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            states[language.code] = State.FAILED
                            waiting.toList().also { waiting.clear() }
                        }
                    mainHandler.post { if (!closed) failedCallbacks.forEach { it() } }
                }
            }
        }
    }

    private fun readDialectVocabulary(name: String): Map<String, Long> =
        try {
            assets
                .open(name)
                .bufferedReader()
                .use { reader ->
                    reader
                        .lineSequence()
                        .mapNotNull { line ->
                            val parts = line.split('\t')
                            if (parts.size != 2) return@mapNotNull null
                            val word = parts[0].trim()
                            val tier = parts[1].trim()
                            val freq =
                                when (tier) {
                                    "1" -> 60_000L
                                    "2" -> 15_000L
                                    "3" -> 4_000L
                                    else -> return@mapNotNull null
                                }
                            if (word.isEmpty()) null else word to freq
                        }.toMap()
                }
        } catch (_: IOException) {
            emptyMap()
        }

    /**
     * `context TAB total TAB word:count ...`; a row without counts (`context TAB word ...`) counts its
     * words by rank. Shared strings keep the tables' memory down.
     */
    private fun readTable(name: String): Map<String, Row> {
        val strings = HashMap<String, String>()

        fun shared(value: String) = strings.getOrPut(value) { value }
        return try {
            assets
                .open(name)
                .bufferedReader()
                .use { reader ->
                    val rows = HashMap<String, Row>()
                    reader.lineSequence().forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size < 2) return@forEach
                        val key = matchKey(parts[0].trim())
                        val total = parts[1].trim().toLongOrNull()
                        val items = (if (total == null) parts.drop(1) else parts.drop(2)).map { it.trim() }.filter { it.isNotEmpty() }
                        if (key.isEmpty() || items.isEmpty()) return@forEach
                        val words = ArrayList<String>(items.size)
                        val counts = LongArray(items.size)
                        items.forEachIndexed { i, item ->
                            val colon = item.lastIndexOf(':')
                            val count = if (colon > 0) item.substring(colon + 1).toLongOrNull() else null
                            words += shared(if (count != null) item.substring(0, colon) else item)
                            counts[i] = count ?: (items.size - i).toLong()
                        }
                        val array = words.toTypedArray()
                        rows[shared(key)] = Row(total ?: counts.sum(), array, Array(array.size) { shared(candidateKey(array[it])) }, counts)
                    }
                    rows
                }
        } catch (_: IOException) {
            emptyMap()
        }
    }

    /**
     * One comparison key for table keys, context words and candidates: lowercase, no harakat/tatweel and
     * no apostrophes. The corpus tables were built without apostrophes, so "don't" must find "dont".
     */
    private fun matchKey(word: String): String =
        word.lowercase(Locale.ROOT).filterNot { c ->
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' || c == '\'' || c == '\u2019'
        }

    /** Written candidates keep their apostrophes: friends and friend's are different words. */
    private fun candidateKey(word: String): String =
        word.lowercase(Locale.ROOT).replace('\u2019', '\'').filterNot { c ->
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640'
        }

    /** Row for the last word; Arabic falls back to the word behind attached و/ف/ب/ل/ك/ع (والله → الله). */
    private fun row(
        table: Map<String, Row>,
        last: String,
        language: Language,
    ): Row? {
        table[last]?.let { return it }
        if (language.script != Script.ARABIC) return null
        var word = last
        repeat(2) {
            if (word.length < 4 || word[0] !in ARABIC_PROCLITICS) return null
            // لل and عال keep the article: للبيت → البيت, عالبيت → البيت.
            word = if (word.startsWith("لل")) "ا" + word.substring(1) else word.substring(1)
            table[word]?.let { return it }
        }
        return null
    }

    /** Table words in their written form: dont → don't, i → I. */
    private fun written(
        word: String,
        language: Language,
    ): String = language.contractions[word] ?: word

    fun correction(
        word: String,
        language: Language,
        context: List<String> = emptyList(),
        dialectOn: Boolean = false,
        learned: Map<String, Int> = emptyMap(),
    ): String? {
        if (closed) return null
        val model = model(language) ?: return null
        val legacy = model.dictionary.correction(word)
        // Arabic unknown forms are often valid inflections, not typos. Keep its conservative policy;
        // context-ranked suggestions remain available without forcing a new spelling on space.
        if (language.script == Script.ARABIC) return legacy
        val options = model.dictionary.correctionOptions(word)
        // Keep spelling normalization, contractions and the deliberate-word guards in the dictionary.
        if (options.isEmpty()) return legacy
        val scorer = Scorer(model, context, language, dialectOn && language.dialect != null, learned, emptyMap())
        val ranked =
            options
                .map { c ->
                    val frequency = model.dictionary.frequency(c.word)
                    val editWeight = (c.score / maxOf(frequency, 1L)).coerceIn(1e-6, 1.0)
                    c to scorer.probability(c.word) * editWeight
                }.sortedByDescending { it.second }
        val best = ranked.first()
        val runner = ranked.getOrNull(1)
        // Ambiguous typos stay suggestions, not automatic replacements. Context can break the tie.
        if (runner != null && best.second < runner.second * 3.0) return null
        return best.first.word
    }

    fun likelyIntended(
        word: String,
        language: Language,
    ): String? {
        if (closed) return null
        return model(language)?.dictionary?.likelyIntended(word)
    }

    fun commonWords(language: Language): List<String> {
        if (closed) return emptyList()
        return model(language)?.dictionary?.commonWords.orEmpty()
    }

    /**
     * The tables around the caret: one- and two-word contexts, the Jordanian ones when on, and the user's
     * own pairs. A sentence's first word is predicted from the `<s>` row.
     */
    private inner class Scorer(
        private val model: Model,
        context: List<String>,
        language: Language,
        dialect: Boolean,
        /** Words the user typed after the last context word, with how often. */
        private val learnedNext: Map<String, Int>,
        /** Learned words for the current prefix, with how often they were typed. */
        private val userCounts: Map<String, Int>,
    ) {
        private val normalized = context.map(::matchKey)
        private val last = normalized.lastOrNull() ?: START
        private val pair = if (normalized.size >= 2) normalized.takeLast(2).joinToString(" ") else null
        val nextRow = row(model.next, last, language)
        val contextRow = pair?.let { model.context[it] }
        val dialectNextRow = if (dialect) row(model.dialectNext, last, language) else null
        val dialectContextRow = if (dialect && pair != null) model.dialectNext[pair] else null
        private val learnedKeys = learnedNext.mapKeys { candidateKey(it.key) }
        private val learnedTotal = learnedNext.values.sum().toLong()
        private val userKeys = userCounts.mapKeys { candidateKey(it.key) }
        private val total = model.dictionary.totalFrequency.toDouble()

        /** Context words, best known first, for prediction and context-only completions. */
        val sources: List<List<String>> =
            listOfNotNull(
                learnedNext.keys.toList(),
                dialectContextRow?.words?.toList(),
                contextRow?.words?.toList(),
                dialectNextRow?.words?.toList(),
                nextRow?.words?.toList(),
            )

        /** P(word | context): each more specific table refines the one below; [baseFrequency] backs unknown forms. */
        fun probability(
            word: String,
            baseFrequency: Long = 0L,
        ): Double {
            val key = candidateKey(word)
            val frequency = maxOf(model.dictionary.frequency(word), baseFrequency)
            var p = (frequency + 1.0) / total + USER_WORD * minOf(userKeys[key] ?: 0, 10)
            p = refine(nextRow, key, p, BETA)
            p = refine(contextRow, key, p, BETA)
            p = refineDialect(dialectNextRow, key, p)
            p = refineDialect(dialectContextRow, key, p)
            if (learnedTotal > 0) {
                val c = learnedKeys[key]?.toDouble() ?: 0.0
                p = if (c > 0) (c + LEARNED_BETA * p) / (learnedTotal + LEARNED_BETA) else LEARNED_BETA * p / (learnedTotal + LEARNED_BETA)
            }
            return p
        }

        /**
         * A pruned dialect table represents the retained in-domain followers. Smooth that distribution,
         * not its discarded corpus tail: otherwise missing words inherit most of an unrelated MSA row.
         * Keep a small lower-model share so unknown prefixes still have dictionary/context fallbacks.
         */
        private fun refineDialect(
            row: Row?,
            key: String,
            lower: Double,
        ): Double {
            if (row == null) return lower
            return (row.count(key) + DIALECT_BETA * lower) / (row.listed + DIALECT_BETA)
        }

        /** Listed followers take their share; the rest keep the unlisted share of the lower estimate. */
        private fun refine(
            row: Row?,
            key: String,
            lower: Double,
            beta: Double,
        ): Double {
            if (row == null) return lower
            val c = row.count(key)
            return if (c > 0) (c + beta * lower) / (row.total + beta) else (row.total - row.listed + beta) * lower / (row.total + beta)
        }
    }

    /**
     * Next words for an empty caret, most probable first.
     * [learned] are words the user typed after the last context word, with how often.
     */
    fun predict(
        context: List<String>,
        language: Language,
        dialectOn: Boolean,
        learned: Map<String, Int> = emptyMap(),
    ): List<String> {
        if (closed) return emptyList()
        val model = model(language) ?: return emptyList()
        val scorer = Scorer(model, context, language, dialectOn && language.dialect != null, learned, emptyMap())
        val normalized = context.map(::matchKey)
        val candidates =
            (scorer.sources.flatten() + model.dictionary.commonWords)
                .map { written(it, language) }
                .distinctBy(::candidateKey)
                .sortedByDescending { scorer.probability(it) }
        // Reject only a repeated multi-word cycle, not every recently used word.
        return candidates.filter { candidate ->
            val tail = normalized + matchKey(candidate)
            (2..4).none { size -> tail.size >= size * 2 && tail.takeLast(size) == tail.dropLast(size).takeLast(size) }
        }
    }

    /**
     * Ranks up to 3 words for the typed prefix by their probability in context; typo and clitic readings
     * pay a fixed cost. [learned] are the user's next words after the last context word and [userWords]
     * their learned words starting with [typed], each with how often they were typed.
     */
    fun rank(
        typed: String,
        context: List<String>,
        language: Language,
        dialectOn: Boolean,
        learned: Map<String, Int> = emptyMap(),
        userWords: Map<String, Int> = emptyMap(),
    ): List<String> {
        if (closed || typed.isEmpty()) return emptyList()
        val model =
            synchronized(lock) {
                if (states[language.code] != State.READY) return emptyList()
                model(language)
            } ?: return emptyList()

        val dialect = dialectOn && language.dialect != null
        val scorer = Scorer(model, context, language, dialect, learned, userWords)
        val scores = LinkedHashMap<String, Double>()

        fun consider(
            word: String,
            score: Double,
        ) {
            scores[word] = maxOf(scores[word] ?: Double.NEGATIVE_INFINITY, score)
        }

        // 1. Exact prefix completions
        val exactCandidates = model.dictionary.completions(typed, 8, dialect)
        val exactWords = HashSet<String>(exactCandidates.size)
        for (c in exactCandidates) {
            consider(c.word, ln(scorer.probability(c.word)))
            exactWords.add(candidateKey(c.word))
        }

        // 2. Fuzzy completions
        if (typed.length >= 3) {
            for (c in model.dictionary.fuzzyCompletions(typed, 6, dialect)) {
                val frequency = model.dictionary.frequency(c.word)
                val weight = if (frequency > 0) c.score / frequency else 1.0
                consider(c.word, ln(scorer.probability(c.word)) + ln(weight) - FUZZY_COST)
            }
        }

        // 2b. Arabic words behind an attached و/ف/ب/ل/ك/لل, which the word list rarely contains.
        var cliticExact = 0
        if (language.script == Script.ARABIC && typed.length >= 3) {
            for (c in model.dictionary.cliticCompletions(typed, 6, dialect)) {
                if (candidateKey(c.word) in exactWords) continue
                cliticExact++
                consider(c.word, ln(scorer.probability(c.word, c.score.toLong())) - CLITIC_COST)
            }
        }

        // 2c. A slip inside an attached Arabic stem: exact clitic lookup cannot recover it.
        // Offer choices only; the lexical auto-correction path is deliberately unchanged.
        // Only when nothing exact matched, so a correct partial word never loses its slots to guesses.
        if (language.script == Script.ARABIC && typed.length >= 4 && exactWords.isEmpty() && cliticExact == 0) {
            for (c in model.dictionary.cliticFuzzyCompletions(typed, 4, dialect)) {
                if (candidateKey(c.word) in exactWords) continue
                consider(c.word, ln(scorer.probability(c.word, c.score.toLong())) - CLITIC_COST - FUZZY_COST)
            }
        }

        // 3. Context and learned words that start with the prefix
        val typedKey = matchKey(typed)
        for (word in scorer.sources.flatten() + userWords.keys) {
            val w = written(word, language)
            val startsWithTyped = if (language.cased) matchKey(w).startsWith(typedKey) else w.startsWith(typed)
            if (startsWithTyped && !exactWords.contains(candidateKey(w))) consider(w, ln(scorer.probability(w)))
        }

        // 4. Nothing matched at all: a long word with two slips still gets its likely target.
        if (scores.keys.none { matchKey(it) != matchKey(typed) }) {
            // Already ordered closest first, then most frequent.
            model.dictionary.distantCompletions(typed, 3, dialect).forEachIndexed { index, c ->
                scores[c.word] = -index.toDouble()
            }
        }

        val typedNorm = matchKey(typed)
        return scores.entries
            .filter { matchKey(it.key) != typedNorm }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }

    private companion object {
        const val ARABIC_PROCLITICS = "وفبلكع"

        /** Sentence-start context in the tables. */
        const val START = "<s>"

        /** Pseudo-counts each table level gives to the level below it. */
        const val BETA = 3.0
        const val DIALECT_BETA = 3.0
        const val LEARNED_BETA = 3.0

        /** Unigram share per use of a learned word, capped at ten uses (about a top-500 word at two uses). */
        const val USER_WORD = 2e-4

        /** Log-probability cost of reading the prefix as a typo or behind a clitic. */
        const val FUZZY_COST = 1.5
        const val CLITIC_COST = 2.5
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            models.clear()
            states.clear()
            callbacks.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
