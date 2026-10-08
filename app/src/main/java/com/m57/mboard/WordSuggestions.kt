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
                    val readyCallbacks =
                        synchronized(lock) {
                            if (closed) return@execute
                            if (language ==
                                Layout.ENGLISH
                            ) {
                                english = dictionary
                                englishState = State.READY
                            } else {
                                arabic = dictionary
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

    fun commonWords(language: Layout): List<String> {
        if (closed) return emptyList()
        return (if (language == Layout.ENGLISH) english else arabic)?.commonWords.orEmpty()
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            english = null
            arabic = null
            englishState = State.CLOSED
            arabicState = State.CLOSED
            englishCallbacks.clear()
            arabicCallbacks.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
