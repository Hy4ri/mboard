package com.m57.mboard

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * On-device list of words the user types, and which word they type after which, per language.
 * Never leaves the app (backup is excluded). Words and pairs need two uses before they are suggested
 * so one-off typos don't stick.
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
    private val loaded = HashMap<Language, LinkedHashMap<String, Word>>()
    private val loadedPairs = HashMap<Language, LinkedHashMap<String, Word>>()
    private var dirty = false
    private var clock = 0L

    private fun key(word: String) = word.lowercase(Locale.ROOT)

    private fun pref(language: Language) = language.code

    private fun pairPref(language: Language) = "${language.code}_next"

    private fun words(language: Language) = loaded.getOrPut(language) { read(pref(language)) }

    /** Keyed "previous next" in lowercase; the display is the next word as last typed. */
    private fun pairs(language: Language) = loadedPairs.getOrPut(language) { read(pairPref(language)) }

    private fun read(name: String): LinkedHashMap<String, Word> {
        val map = LinkedHashMap<String, Word>()
        try {
            val json = JSONObject(prefs.getString(name, "{}") ?: "{}")
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
        return map
    }

    /** Records [word], and that it followed [previous] when the two sit in one sentence. */
    fun record(
        word: String,
        previous: String?,
        language: Language,
    ) {
        if (!pairable(word)) return
        if (word.length >= 2) bump(words(language), key(word), word, LIMIT)
        // Single letters still pair: "I" → "am", "a" → "lot".
        if (previous != null && pairable(previous)) bump(pairs(language), key(previous) + " " + key(word), word, PAIR_LIMIT)
        dirty = true
    }

    private fun pairable(word: String) = word.length in 1..32 && word.none(Char::isDigit)

    private fun bump(
        map: LinkedHashMap<String, Word>,
        k: String,
        display: String,
        limit: Int,
    ) {
        val entry = map.getOrPut(k) { Word(display, 0, 0) }
        entry.display = display
        entry.count = (entry.count + 1).coerceAtMost(1_000)
        entry.used = ++clock
        if (map.size > limit) {
            // Drop the least-used, oldest entries first.
            map.entries
                .sortedWith(compareBy({ it.value.count }, { it.value.used }))
                .take(map.size - limit)
                .forEach { map.remove(it.key) }
        }
    }

    /** Words the user has typed after [previous] at least twice, most used and most recent first. */
    fun next(
        previous: String,
        language: Language,
    ): List<String> {
        if (previous.isEmpty()) return emptyList()
        val start = key(previous) + " "
        return pairs(language)
            .entries
            .filter { it.value.count >= TRUSTED && it.key.startsWith(start) }
            .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.used })
            .take(3)
            .map { it.value.display }
    }

    /** A word typed often enough that auto-correction should leave it alone. */
    fun knows(
        word: String,
        language: Language,
    ) = (words(language)[key(word)]?.count ?: 0) >= TRUSTED

    fun completions(
        typed: String,
        language: Language,
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

    fun size() = Languages.ALL.sumOf { words(it).size }

    fun save() {
        if (!dirty) return
        val edit = prefs.edit()
        loaded.forEach { (language, map) -> edit.putString(pref(language), json(map)) }
        loadedPairs.forEach { (language, map) -> edit.putString(pairPref(language), json(map)) }
        edit.apply()
        dirty = false
    }

    private fun json(map: Map<String, Word>): String {
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
        return json.toString()
    }

    fun clear() {
        loaded.clear()
        loadedPairs.clear()
        dirty = false
        prefs.edit().clear().apply()
    }

    private companion object {
        const val LIMIT = 3_000
        const val PAIR_LIMIT = 5_000
        const val TRUSTED = 2
    }
}
