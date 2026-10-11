package com.m57.mboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Read-only bundled word completion dictionaries. User input is never persisted. */
class WordSuggestions(
    context: Context,
) {
    enum class State { UNLOADED, LOADING, READY, FAILED, CLOSED }

    private val assets = context.applicationContext.assets
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var closed = false

    @Volatile private var english: WordDictionary? = null

    @Volatile private var arabic: WordDictionary? = null

    @Volatile private var englishNext: Map<String, List<String>> = emptyMap()

    @Volatile private var arabicNext: Map<String, List<String>> = emptyMap()

    @Volatile private var englishContext: Map<String, List<String>> = emptyMap()

    @Volatile private var arabicContext: Map<String, List<String>> = emptyMap()
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
            val loaded = if (language == Layout.ENGLISH) english else arabic
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
                    val dictionary =
                        assets
                            .open(if (language == Layout.ENGLISH) "words_en.tsv" else "words_ar.tsv")
                            .bufferedReader()
                            .use { WordDictionary.read(it, language) }
                    if (dictionary.size == 0) throw IOException("No usable bundled word entries")
                    // Next-word table is optional: a missing asset only loses predictions, not completions.
                    val next =
                        try {
                            assets
                                .open(if (language == Layout.ENGLISH) "next_en.tsv" else "next_ar.tsv")
                                .bufferedReader()
                                .use { reader ->
                                    reader
                                        .lineSequence()
                                        .mapNotNull { line ->
                                            val parts = line.split('\t')
                                            if (parts.size < 2) null else parts[0] to parts.drop(1)
                                        }.toMap()
                                }
                        } catch (_: IOException) {
                            emptyMap()
                        }
                    val contextTable =
                        try {
                            assets
                                .open(if (language == Layout.ENGLISH) "context_en.tsv" else "context_ar.tsv")
                                .bufferedReader()
                                .use { reader ->
                                    reader
                                        .lineSequence()
                                        .mapNotNull { line ->
                                            val parts = line.split('\t')
                                            if (parts.size < 2) null else parts[0] to parts.drop(1)
                                        }.toMap()
                                }
                        } catch (_: IOException) {
                            emptyMap()
                        }
                    val readyCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            if (language ==
                                Layout.ENGLISH
                            ) {
                                english = dictionary
                                englishContext = contextTable
                                englishNext = next
                                englishState = State.READY
                            } else {
                                arabic = dictionary
                                arabicContext = contextTable
                                arabicNext = next
                                arabicState =
                                    State.READY
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

    fun suggest(
        prefix: String,
        language: Layout,
    ): List<String> {
        if (closed) return emptyList()
        return (if (language == Layout.ENGLISH) english else arabic)?.suggest(prefix) ?: emptyList()
    }

    fun correction(
        word: String,
        language: Layout,
    ): String? {
        if (closed) return null
        return (if (language == Layout.ENGLISH) english else arabic)?.correction(word)
    }

    fun commonWords(language: Layout): List<String> {
        if (closed) return emptyList()
        return (if (language == Layout.ENGLISH) english else arabic)?.commonWords.orEmpty()
    }

    /** Likely words after [previous], or empty when the bundled table has nothing for it. */
    fun nextWords(
        previous: String,
        language: Layout,
    ): List<String> {
        if (closed || previous.isEmpty()) return emptyList()
        val key =
            if (language == Layout.ENGLISH) {
                previous.lowercase(java.util.Locale.ROOT)
            } else {
                previous.filterNot { it in '\u064B'..'\u065F' || it == '\u0670' || it == '\u0640' }
            }
        return (if (language == Layout.ENGLISH) englishNext else arabicNext)[key].orEmpty()
    }

    /** Two-word context wins; unseen contexts fall back to the existing one-word table. */
    fun predict(
        context: List<String>,
        language: Layout,
        jordanian: Boolean,
    ): List<String> {
        if (closed) return emptyList()
        val normalized =
            context.map {
                it.lowercase(java.util.Locale.ROOT).filterNot { c ->
                    c in '\u064B'..'\u065F' || c == '\u0670' ||
                        c == '\u0640'
                }
            }
        val pair = normalized.takeLast(2).joinToString(" ")
        val table = if (language == Layout.ENGLISH) englishContext else arabicContext
        val local = if (language == Layout.ARABIC && jordanian) JordanianArabic.next(normalized) else emptyList()
        val candidates =
            (
                local + table[pair].orEmpty() +
                    nextWords(
                        normalized.lastOrNull().orEmpty(),
                        language,
                    ) + commonWords(language)
            ).distinctBy {
                it.lowercase(java.util.Locale.ROOT)
            }
        // Reject only a repeated multi-word cycle, not every recently used word.
        return candidates.filter { candidate ->
            val tail = normalized + candidate.lowercase(java.util.Locale.ROOT)
            (2..4).none { size -> tail.size >= size * 2 && tail.takeLast(size) == tail.dropLast(size).takeLast(size) }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            english = null
            arabic = null
            englishNext = emptyMap()
            arabicNext = emptyMap()
            englishContext = emptyMap()
            arabicContext = emptyMap()
            englishState = State.CLOSED
            arabicState = State.CLOSED
            englishCallbacks.clear()
            arabicCallbacks.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
