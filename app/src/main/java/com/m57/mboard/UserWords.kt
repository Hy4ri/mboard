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
class UserWords
    @JvmOverloads
    constructor(
        context: Context,
        private val nowMillis: () -> Long = System::currentTimeMillis,
    ) {
        private class Word(
            var display: String,
            var count: Int,
            var used: Long,
            var decayEpoch: Long,
        )

        private class Rejection(
            val typed: String,
            val corrected: String,
            var used: Long,
        )

        private val prefs = context.getSharedPreferences("user_words", Context.MODE_PRIVATE)
        private val loaded = HashMap<Language, LinkedHashMap<String, Word>>()
        private val loadedPairs = HashMap<Language, LinkedHashMap<String, Word>>()
        private val loadedRejections = HashMap<Language, LinkedHashMap<String, Rejection>>()
        private var dirty = false
        private var clock = 0L

        private fun key(word: String) = word.lowercase(Locale.ROOT)

        private fun pref(language: Language) = language.code

        private fun pairPref(language: Language) = "${language.code}_next"

        private fun rejectionPref(language: Language) = "${language.code}_rejections"

        private fun words(language: Language) = loaded.getOrPut(language) { read(pref(language)) }

        /** Keyed "previous next" in lowercase; the display is the next word as last typed. */
        private fun pairs(language: Language) = loadedPairs.getOrPut(language) { read(pairPref(language)) }

        private fun rejections(language: Language) = loadedRejections.getOrPut(language) { readRejections(rejectionPref(language)) }

        private fun read(name: String): LinkedHashMap<String, Word> {
            val map = LinkedHashMap<String, Word>()
            val now = nowMillis().coerceAtLeast(0L)
            try {
                val json = JSONObject(prefs.getString(name, "{}") ?: "{}")
                json.keys().forEach { k ->
                    val entry = json.optJSONArray(k) ?: return@forEach
                    val display = entry.optString(0)
                    if (display.isNotEmpty()) {
                        var count = entry.optInt(1).coerceIn(1, 1_000)
                        val used = entry.optLong(2)
                        clock = maxOf(clock, used)
                        var epoch = if (entry.length() > 3) entry.optLong(3, now) else now
                        if (entry.length() <= 3) dirty = true
                        if (epoch < 0L) {
                            epoch = now
                            dirty = true
                        }

                        if (now > epoch) {
                            val periods = (now - epoch) / DECAY_PERIOD_MILLIS
                            if (periods > 0L) {
                                val shift = periods.coerceAtMost(31L).toInt()
                                count = count shr shift
                                epoch += periods * DECAY_PERIOD_MILLIS
                                dirty = true
                            }
                        }

                        if (count > 0) {
                            map[k] = Word(display, count.coerceAtMost(1_000), used, epoch)
                        } else {
                            dirty = true
                        }
                    }
                }
            } catch (_: JSONException) {
                // A damaged list starts over rather than breaking typing.
            }
            return map
        }

        private fun readRejections(name: String): LinkedHashMap<String, Rejection> {
            val map = LinkedHashMap<String, Rejection>()
            try {
                val json = JSONObject(prefs.getString(name, "{}") ?: "{}")
                json.keys().forEach { k ->
                    val entry = json.optJSONArray(k) ?: return@forEach
                    val typed = entry.optString(0)
                    val corrected = entry.optString(1)
                    val used = entry.optLong(2)
                    clock = maxOf(clock, used)
                    if (validRejectionString(typed) && validRejectionString(corrected) && key(typed) != key(corrected)) {
                        map[k] = Rejection(typed, corrected, used)
                    }
                }
            } catch (_: JSONException) {
                // A damaged list starts over rather than breaking typing.
            }
            return map
        }

        private fun validRejectionString(token: String) =
            token.length in 1..32 && token.none(Char::isDigit) && token.none(Char::isWhitespace)

        private fun rejectionKey(
            typed: String,
            corrected: String,
        ) = "${key(typed)}\t${key(corrected)}"

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
            val now = nowMillis().coerceAtLeast(0L)
            val entry = map.getOrPut(k) { Word(display, 0, 0, now) }
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

        fun reject(
            typed: String,
            corrected: String,
            language: Language,
        ) {
            if (!validRejectionString(typed) || !validRejectionString(corrected)) return
            val kTyped = key(typed)
            val kCorrected = key(corrected)
            if (kTyped == kCorrected) return

            val k = rejectionKey(typed, corrected)
            val map = rejections(language)
            val entry = map.getOrPut(k) { Rejection(typed, corrected, 0) }
            entry.used = ++clock
            if (map.size > REJECTION_LIMIT) {
                map.entries
                    .sortedWith(compareBy { it.value.used })
                    .take(map.size - REJECTION_LIMIT)
                    .forEach { map.remove(it.key) }
            }
            dirty = true
        }

        fun rejects(
            typed: String,
            corrected: String,
            language: Language,
        ): Boolean {
            if (!validRejectionString(typed) || !validRejectionString(corrected)) return false
            val kTyped = key(typed)
            val kCorrected = key(corrected)
            if (kTyped == kCorrected) return false
            return rejections(language).containsKey(rejectionKey(typed, corrected))
        }

        fun acceptCorrection(
            typed: String,
            corrected: String,
            language: Language,
        ) {
            if (!validRejectionString(typed) || !validRejectionString(corrected)) return
            val kTyped = key(typed)
            val kCorrected = key(corrected)
            if (kTyped == kCorrected) return
            val map = rejections(language)
            if (map.remove(rejectionKey(typed, corrected)) != null) {
                dirty = true
            }
        }

        /** Words the user has typed after [previous] at least twice, with how often; most used first. */
        fun next(
            previous: String,
            language: Language,
        ): Map<String, Int> {
            if (previous.isEmpty()) return emptyMap()
            val start = key(previous) + " "
            return pairs(language)
                .entries
                .filter { it.value.count >= TRUSTED && it.key.startsWith(start) }
                .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.used })
                .take(6)
                .associate { it.value.display to it.value.count }
        }

        /** A word typed often enough that auto-correction should leave it alone. */
        fun knows(
            word: String,
            language: Language,
        ) = (words(language)[key(word)]?.count ?: 0) >= TRUSTED

        /** Trusted learned words longer than [typed] that start with it, with how often they were typed. */
        fun completions(
            typed: String,
            language: Language,
        ): Map<String, Int> {
            if (typed.isEmpty()) return emptyMap()
            val prefix = key(typed)
            return words(language)
                .entries
                .filter { it.value.count >= TRUSTED && it.key.length > prefix.length && it.key.startsWith(prefix) }
                .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.count }.thenByDescending { it.value.used })
                .take(6)
                .associate { it.value.display to it.value.count }
        }

        fun size() = Languages.ALL.sumOf { words(it).size }

        fun save() {
            if (!dirty) return
            val edit = prefs.edit()
            loaded.forEach { (language, map) -> edit.putString(pref(language), json(map)) }
            loadedPairs.forEach { (language, map) -> edit.putString(pairPref(language), json(map)) }
            loadedRejections.forEach { (language, map) -> edit.putString(rejectionPref(language), jsonRejections(map)) }
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
                        .put(w.used)
                        .put(w.decayEpoch),
                )
            }
            return json.toString()
        }

        private fun jsonRejections(map: Map<String, Rejection>): String {
            val json = JSONObject()
            map.forEach { (k, r) ->
                json.put(
                    k,
                    org.json
                        .JSONArray()
                        .put(r.typed)
                        .put(r.corrected)
                        .put(r.used),
                )
            }
            return json.toString()
        }

        fun clear() {
            loaded.clear()
            loadedPairs.clear()
            loadedRejections.clear()
            dirty = false
            prefs.edit().clear().apply()
        }

        private companion object {
            const val LIMIT = 3_000
            const val PAIR_LIMIT = 5_000
            const val REJECTION_LIMIT = 500
            const val TRUSTED = 2
            const val DECAY_PERIOD_MILLIS = 14L * 24 * 60 * 60 * 1000
        }
    }
