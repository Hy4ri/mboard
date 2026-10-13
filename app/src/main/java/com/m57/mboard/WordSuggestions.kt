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
        val next: Map<String, List<String>>,
        val context: Map<String, List<String>>,
        val dialectNext: Map<String, List<String>>,
    )

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

    private fun readTable(name: String): Map<String, List<String>> =
        try {
            assets
                .open(name)
                .bufferedReader()
                .use { reader ->
                    reader
                        .lineSequence()
                        .mapNotNull { line ->
                            val parts = line.split('\t')
                            if (parts.size < 2) {
                                null
                            } else {
                                val key = matchKey(parts[0].trim())
                                val values = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                                if (key.isEmpty() || values.isEmpty()) null else key to values
                            }
                        }.toMap()
                }
        } catch (_: IOException) {
            emptyMap()
        }

    /**
     * One comparison key for table keys, context words and candidates: lowercase, no harakat/tatweel and
     * no apostrophes. The corpus tables were built without apostrophes, so "don't" must find "dont".
     */
    private fun matchKey(word: String): String =
        word.lowercase(Locale.ROOT).filterNot { c ->
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' || c == '\'' || c == '\u2019'
        }

    /** Row for the last word; Arabic falls back to the word behind attached و/ف/ب/ل/ك/ع (والله → الله). */
    private fun row(
        table: Map<String, List<String>>,
        last: String,
        language: Language,
    ): List<String>? {
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
    ): String? {
        if (closed) return null
        return model(language)?.dictionary?.correction(word)
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
     * The user's own next words lead, then the two-word context, then the one-word table.
     * [learned] are words the user typed after the last context word, best first.
     */
    fun predict(
        context: List<String>,
        language: Language,
        dialectOn: Boolean,
        learned: List<String> = emptyList(),
    ): List<String> {
        if (closed) return emptyList()
        val model = model(language) ?: return emptyList()

        val dialect = dialectOn && language.dialect != null
        val normalized = context.map(::matchKey)
        val pair = normalized.takeLast(2).joinToString(" ")
        val last = normalized.lastOrNull().orEmpty()

        val dialectPredictions =
            if (dialect) {
                model.dialectNext[pair] ?: row(model.dialectNext, last, language).orEmpty()
            } else {
                emptyList()
            }

        val candidates =
            (
                learned + dialectPredictions + model.context[pair].orEmpty() +
                    row(model.next, last, language).orEmpty() + model.dictionary.commonWords
            ).map { written(it, language) }
                .distinctBy(::matchKey)
        // Reject only a repeated multi-word cycle, not every recently used word.
        return candidates.filter { candidate ->
            val tail = normalized + matchKey(candidate)
            (2..4).none { size -> tail.size >= size * 2 && tail.takeLast(size) == tail.dropLast(size).takeLast(size) }
        }
    }

    /** Ranks up to 3 candidate words for the typed prefix taking context and typos into account. */
    fun rank(
        typed: String,
        context: List<String>,
        language: Language,
        dialectOn: Boolean,
        learned: List<String> = emptyList(),
    ): List<String> {
        if (closed || typed.isEmpty()) return emptyList()
        val model =
            synchronized(lock) {
                if (states[language.code] != State.READY) return emptyList()
                model(language)
            } ?: return emptyList()

        val dialect = dialectOn && language.dialect != null
        val normalized = context.map(::matchKey)
        val pair = normalized.takeLast(2).joinToString(" ")
        val last = normalized.lastOrNull().orEmpty()

        fun contextBonus(word: String): Double {
            val normWord = matchKey(word)
            val learnedIdx = learned.indexOfFirst { matchKey(it) == normWord }
            if (learnedIdx != -1) return 4.5 - 0.4 * learnedIdx
            if (dialect) {
                val dialectPairList = model.dialectNext[pair]
                if (dialectPairList != null) {
                    val idx = dialectPairList.indexOfFirst { matchKey(it) == normWord }
                    if (idx != -1) return 4.0 - 0.4 * idx
                }
            }
            val contextPairList = model.context[pair]
            if (contextPairList != null) {
                val idx = contextPairList.indexOfFirst { matchKey(it) == normWord }
                if (idx != -1) return 4.0 - 0.4 * idx
            }
            if (dialect) {
                val dialectLastList = row(model.dialectNext, last, language)
                if (dialectLastList != null) {
                    val idx = dialectLastList.indexOfFirst { matchKey(it) == normWord }
                    if (idx != -1) return 3.0 - 0.3 * idx
                }
            }
            val nextLastList = row(model.next, last, language)
            if (nextLastList != null) {
                val idx = nextLastList.indexOfFirst { matchKey(it) == normWord }
                if (idx != -1) return 2.5 - 0.3 * idx
            }
            return 0.0
        }

        val scores = LinkedHashMap<String, Double>()

        // 1. Exact prefix completions
        val exactCandidates = model.dictionary.completions(typed, 8, dialect)
        val exactWords = HashSet<String>(exactCandidates.size)
        for (c in exactCandidates) {
            val score = ln(c.score + 1.0) + contextBonus(c.word)
            scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, score)
            exactWords.add(matchKey(c.word))
        }

        // 2. Fuzzy completions
        if (typed.length >= 3) {
            val fuzzyCandidates = model.dictionary.fuzzyCompletions(typed, 6, dialect)
            for (c in fuzzyCandidates) {
                val score = ln(c.score + 1.0) - 1.5 + contextBonus(c.word)
                scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, score)
            }
        }

        // 2b. Arabic words behind an attached و/ف/ب/ل/ك/لل, which the word list rarely contains.
        if (language.script == Script.ARABIC && typed.length >= 3) {
            for (c in model.dictionary.cliticCompletions(typed, 6, dialect)) {
                if (matchKey(c.word) in exactWords) continue
                val score = ln(c.score + 1.0) - 2.5 + contextBonus(c.word)
                scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, score)
            }
        }

        // 3. Context-only completions
        val contextSources = ArrayList<List<String>>()
        if (learned.isNotEmpty()) contextSources.add(learned)
        if (dialect) {
            model.dialectNext[pair]?.let { contextSources.add(it) }
        }
        model.context[pair]?.let { contextSources.add(it) }
        if (dialect) {
            row(model.dialectNext, last, language)?.let { contextSources.add(it) }
        }
        row(model.next, last, language)?.let { contextSources.add(it) }

        val typedKey = matchKey(typed)
        for (source in contextSources) {
            for (tableWord in source) {
                val w = written(tableWord, language)
                val startsWithTyped =
                    if (language.cased) {
                        matchKey(w).startsWith(typedKey)
                    } else {
                        w.startsWith(typed)
                    }
                if (startsWithTyped && !exactWords.contains(matchKey(w))) {
                    val freq = model.dictionary.frequency(w)
                    val score = ln(freq.toDouble() + 1.0) + contextBonus(w)
                    scores[w] = maxOf(scores[w] ?: Double.NEGATIVE_INFINITY, score)
                }
            }
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
