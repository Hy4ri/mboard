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

    @Volatile private var englishModel: Model? = null

    @Volatile private var arabicModel: Model? = null

    private var englishState = State.UNLOADED
    private var arabicState = State.UNLOADED
    private val englishCallbacks = ArrayList<() -> Unit>()
    private val arabicCallbacks = ArrayList<() -> Unit>()

    fun state(language: Layout): State =
        synchronized(lock) {
            if (closed) {
                State.CLOSED
            } else if (language == Layout.ENGLISH) {
                englishState
            } else {
                arabicState
            }
        }

    fun load(
        language: Layout,
        onReady: () -> Unit,
    ) {
        synchronized(lock) {
            if (closed) return
            val loaded = if (language == Layout.ENGLISH) englishModel else arabicModel
            if (loaded != null) {
                mainHandler.post { if (!closed) onReady() }
                return
            }
            val callbacks = if (language == Layout.ENGLISH) englishCallbacks else arabicCallbacks
            callbacks.add(onReady)
            val loading = if (language == Layout.ENGLISH) englishState == State.LOADING else arabicState == State.LOADING
            if (loading) return
            if (language == Layout.ENGLISH) englishState = State.LOADING else arabicState = State.LOADING
            executor.execute {
                try {
                    val dialect =
                        if (language == Layout.ARABIC) {
                            readDialectVocabulary("jordanian.tsv")
                        } else {
                            emptyMap()
                        }
                    val dictionary =
                        assets
                            .open(if (language == Layout.ENGLISH) "words_en.tsv" else "words_ar.tsv")
                            .bufferedReader()
                            .use { WordDictionary.read(it, language, dialect) }
                    if (dictionary.size == 0) throw IOException("No usable bundled word entries")

                    val next = readTable(if (language == Layout.ENGLISH) "next_en.tsv" else "next_ar.tsv")
                    val contextTable = readTable(if (language == Layout.ENGLISH) "context_en.tsv" else "context_ar.tsv")
                    val dialectNext =
                        if (language == Layout.ARABIC) {
                            readTable("jordanian_next.tsv")
                        } else {
                            emptyMap()
                        }

                    val model = Model(dictionary, next, contextTable, dialectNext)

                    val readyCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            if (language == Layout.ENGLISH) {
                                englishModel = model
                                englishState = State.READY
                            } else {
                                arabicModel = model
                                arabicState = State.READY
                            }
                            callbacks.toList().also { callbacks.clear() }
                        }
                    mainHandler.post { if (!closed) readyCallbacks.forEach { it() } }
                } catch (_: IOException) {
                    val failedCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            if (language == Layout.ENGLISH) englishState = State.FAILED else arabicState = State.FAILED
                            callbacks.toList().also { callbacks.clear() }
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

    /** One comparison key for table keys, context words and candidates: lowercase, no harakat/tatweel. */
    private fun matchKey(word: String): String =
        word.lowercase(Locale.ROOT).filterNot { c ->
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640'
        }

    fun correction(
        word: String,
        language: Layout,
    ): String? {
        if (closed) return null
        return (if (language == Layout.ENGLISH) englishModel else arabicModel)?.dictionary?.correction(word)
    }

    fun likelyIntended(
        word: String,
        language: Layout,
    ): String? {
        if (closed) return null
        return (if (language == Layout.ENGLISH) englishModel else arabicModel)?.dictionary?.likelyIntended(word)
    }

    fun commonWords(language: Layout): List<String> {
        if (closed) return emptyList()
        return (if (language == Layout.ENGLISH) englishModel else arabicModel)?.dictionary?.commonWords.orEmpty()
    }

    /** Two-word context wins; unseen contexts fall back to the existing one-word table. */
    fun predict(
        context: List<String>,
        language: Layout,
        jordanian: Boolean,
    ): List<String> {
        if (closed) return emptyList()
        val model = (if (language == Layout.ENGLISH) englishModel else arabicModel) ?: return emptyList()

        val normalized = context.map(::matchKey)
        val pair = normalized.takeLast(2).joinToString(" ")
        val last = normalized.lastOrNull().orEmpty()

        val dialectPredictions =
            if (language == Layout.ARABIC && jordanian) {
                model.dialectNext[pair] ?: model.dialectNext[last].orEmpty()
            } else {
                emptyList()
            }

        val candidates =
            (
                dialectPredictions + model.context[pair].orEmpty() +
                    model.next[last].orEmpty() + model.dictionary.commonWords
            ).distinctBy {
                it.lowercase(Locale.ROOT)
            }
        // Reject only a repeated multi-word cycle, not every recently used word.
        return candidates.filter { candidate ->
            val tail = normalized + candidate.lowercase(Locale.ROOT)
            (2..4).none { size -> tail.size >= size * 2 && tail.takeLast(size) == tail.dropLast(size).takeLast(size) }
        }
    }

    /** Ranks up to 3 candidate words for the typed prefix taking context and typos into account. */
    fun rank(
        typed: String,
        context: List<String>,
        language: Layout,
        jordanian: Boolean,
    ): List<String> {
        if (closed || typed.isEmpty()) return emptyList()
        val model =
            synchronized(lock) {
                if (language == Layout.ENGLISH) {
                    if (englishState != State.READY) return emptyList()
                    englishModel
                } else {
                    if (arabicState != State.READY) return emptyList()
                    arabicModel
                }
            } ?: return emptyList()

        val normalized = context.map(::matchKey)
        val pair = normalized.takeLast(2).joinToString(" ")
        val last = normalized.lastOrNull().orEmpty()

        fun contextBonus(word: String): Double {
            val normWord = matchKey(word)
            if (language == Layout.ARABIC && jordanian) {
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
            if (language == Layout.ARABIC && jordanian) {
                val dialectLastList = model.dialectNext[last]
                if (dialectLastList != null) {
                    val idx = dialectLastList.indexOfFirst { matchKey(it) == normWord }
                    if (idx != -1) return 3.0 - 0.3 * idx
                }
            }
            val nextLastList = model.next[last]
            if (nextLastList != null) {
                val idx = nextLastList.indexOfFirst { matchKey(it) == normWord }
                if (idx != -1) return 2.5 - 0.3 * idx
            }
            return 0.0
        }

        val dialect = language == Layout.ARABIC && jordanian
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

        // 3. Context-only completions
        val contextSources = ArrayList<List<String>>()
        if (language == Layout.ARABIC && jordanian) {
            model.dialectNext[pair]?.let { contextSources.add(it) }
        }
        model.context[pair]?.let { contextSources.add(it) }
        if (language == Layout.ARABIC && jordanian) {
            model.dialectNext[last]?.let { contextSources.add(it) }
        }
        model.next[last]?.let { contextSources.add(it) }

        for (source in contextSources) {
            for (w in source) {
                val startsWithTyped =
                    if (language == Layout.ENGLISH) {
                        w.startsWith(typed, ignoreCase = true)
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

        val typedNorm = matchKey(typed)
        return scores.entries
            .filter { matchKey(it.key) != typedNorm }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            englishModel = null
            arabicModel = null
            englishState = State.CLOSED
            arabicState = State.CLOSED
            englishCallbacks.clear()
            arabicCallbacks.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
