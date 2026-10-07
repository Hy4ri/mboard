package com.m57.mboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Read-only bundled word completion dictionaries. User input is never persisted. */
class WordSuggestions(
    context: Context,
) {
    private data class Entry(
        val normalized: String,
        val original: String,
        val frequency: Long,
    )

    private data class Dictionary(
        val entries: List<Entry>,
        val shortPrefixes: Map<String, List<String>>,
    )

    private val assets = context.applicationContext.assets
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var closed = false

    @Volatile private var english: Dictionary? = null

    @Volatile private var arabic: Dictionary? = null
    private var englishLoading = false
    private var arabicLoading = false
    private val englishCallbacks = ArrayList<() -> Unit>()
    private val arabicCallbacks = ArrayList<() -> Unit>()

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
            if (language == Layout.ENGLISH) {
                englishCallbacks.add(onReady)
                if (englishLoading) return
                englishLoading = true
            } else {
                arabicCallbacks.add(onReady)
                if (arabicLoading) return
                arabicLoading = true
            }
            executor.execute {
                val dictionary = readDictionary(if (language == Layout.ENGLISH) "words_en.tsv" else "words_ar.tsv", language)
                val callbacks =
                    synchronized(lock) {
                        if (closed) return@execute
                        if (language == Layout.ENGLISH) {
                            english = dictionary
                            englishLoading = false
                            englishCallbacks.toList().also { englishCallbacks.clear() }
                        } else {
                            arabic = dictionary
                            arabicLoading = false
                            arabicCallbacks.toList().also { arabicCallbacks.clear() }
                        }
                    }
                mainHandler.post { if (!closed) callbacks.forEach { it() } }
            }
        }
    }

    fun suggest(
        prefix: String,
        language: Layout,
    ): List<String> {
        if (closed || prefix.isEmpty() || prefix.length > 64) return emptyList()
        val dictionary = (if (language == Layout.ENGLISH) english else arabic) ?: return emptyList()
        val normalizedPrefix = normalize(prefix, language) ?: return emptyList()
        if (normalizedPrefix.isEmpty()) return emptyList()
        dictionary.shortPrefixes[normalizedPrefix]?.let { return it }

        val entries = dictionary.entries
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

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            english = null
            arabic = null
            englishCallbacks.clear()
            arabicCallbacks.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun readDictionary(
        name: String,
        language: Layout,
    ): Dictionary? {
        return try {
            val entries =
                assets
                    .open(name)
                    .bufferedReader()
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
                    val list = short.getOrPut(key) { ArrayList() }
                    insertBest(list, entry)
                }
            }
            Dictionary(entries, short.mapValues { it.value.map(Entry::original) })
        } catch (_: IOException) {
            Log.w(TAG, "Unable to load bundled word dictionary")
            Dictionary(emptyList(), emptyMap())
        }
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

    private companion object {
        const val TAG = "WordSuggestions"
    }
}
