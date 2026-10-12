package com.m57.mboard

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * On-device list of words the user types, per language. Never leaves the app (backup is excluded).
 * Unknown words need two uses before they are suggested so one-off typos don't stick.
 */
class UserWords(
    context: Context,
) {
    private class Word(
        var display: String,
        var count: Int,
        var used: Long,
    )

    private val prefs = context.getSharedPreferences("user_words", Context.MODE_PRIVATE)
    private val loaded = HashMap<Layout, LinkedHashMap<String, Word>>()
    private var dirty = false
    private var clock = 0L

    private fun key(word: String) = word.lowercase(Locale.ROOT)

    private fun pref(language: Layout) = if (language == Layout.ARABIC) "ar" else "en"

    private fun words(language: Layout): LinkedHashMap<String, Word> =
        loaded.getOrPut(language) {
            val map = LinkedHashMap<String, Word>()
            try {
                val json = JSONObject(prefs.getString(pref(language), "{}") ?: "{}")
                json.keys().forEach { k ->
                    val entry = json.optJSONArray(k) ?: return@forEach
                    val display = entry.optString(0)
                    if (display.isNotEmpty()) {
                        val used = entry.optLong(2)
                        clock = maxOf(clock, used)
                        map[k] = Word(display, entry.optInt(1).coerceAtLeast(1), used)
                    }
                }
            } catch (_: JSONException) {
                // A damaged list starts over rather than breaking typing.
            }
            map
        }

    fun record(
        word: String,
        language: Layout,
    ) {
        if (word.length < 2 || word.length > 32 || word.any(Char::isDigit)) return
        val map = words(language)
        val entry = map.getOrPut(key(word)) { Word(word, 0, 0) }
        entry.display = word
        entry.count = (entry.count + 1).coerceAtMost(1_000)
        entry.used = ++clock
        if (map.size > LIMIT) {
            // Drop the least-used, oldest words first.
            map.entries
                .sortedWith(compareBy({ it.value.count }, { it.value.used }))
                .take(map.size - LIMIT)
                .forEach { map.remove(it.key) }
        }
        dirty = true
    }

    /** A word typed often enough that auto-correction should leave it alone. */
    fun knows(
        word: String,
        language: Layout,
    ) = (words(language)[key(word)]?.count ?: 0) >= TRUSTED

    fun completions(
        typed: String,
        language: Layout,
    ): List<String> {
        if (typed.isEmpty()) return emptyList()
        val prefix = key(typed)
        return words(language)
            .entries
            .filter { it.value.count >= TRUSTED && it.key.length > prefix.length && it.key.startsWith(prefix) }
            .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.used })
            .take(3)
            .map { it.value.display }
    }

    fun size() = Layout.entries.filter { it == Layout.ENGLISH || it == Layout.ARABIC }.sumOf { words(it).size }

    fun save() {
        if (!dirty) return
        val edit = prefs.edit()
        loaded.forEach { (language, map) ->
            val json = JSONObject()
            map.forEach { (k, w) ->
                json.put(
                    k,
                    org.json
                        .JSONArray()
                        .put(w.display)
                        .put(w.count)
                        .put(w.used),
                )
            }
            edit.putString(pref(language), json.toString())
        }
        edit.apply()
        dirty = false
    }

    fun clear() {
        loaded.clear()
        dirty = false
        prefs.edit().clear().apply()
    }

    private companion object {
        const val LIMIT = 3_000
        const val TRUSTED = 2
    }
}
