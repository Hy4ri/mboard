package com.m57.mboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.TreeMap

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

        private class PairTable {
            val byPrev = HashMap<String, LinkedHashMap<String, Word>>()
            var size = 0

            fun put(
                prev: String,
                next: String,
                word: Word,
            ) {
                val followers = byPrev.getOrPut(prev) { LinkedHashMap() }
                if (followers.put(next, word) == null) size++
            }

            fun removeMin() {
                var minPrev: String? = null
                var minNext: String? = null
                var minWord: Word? = null
                for ((prev, followers) in byPrev) {
                    for ((next, word) in followers) {
                        if (minWord == null || isLessUsed(word, minWord)) {
                            minPrev = prev
                            minNext = next
                            minWord = word
                        }
                    }
                }
                if (minPrev != null && minNext != null) {
                    val followers = byPrev[minPrev] ?: return
                    if (followers.remove(minNext) != null) {
                        size--
                        if (followers.isEmpty()) byPrev.remove(minPrev)
                    }
                }
            }
        }

        private class Rejection(
            val typed: String,
            val corrected: String,
            var used: Long,
        )

        private val prefs = context.getSharedPreferences("user_words", Context.MODE_PRIVATE)
        private val loaded = HashMap<Language, TreeMap<String, Word>>()
        private val loadedPairs = HashMap<Language, PairTable>()
        private val loadedRejections = HashMap<Language, LinkedHashMap<String, Rejection>>()
        private val dirtyWords = HashSet<Language>()
        private val dirtyPairs = HashSet<Language>()
        private val dirtyRejections = HashSet<Language>()
        private var clock = 0L

        private fun key(word: String) = word.lowercase(Locale.ROOT)

        private fun pref(language: Language) = language.code

        private fun pairPref(language: Language) = "${language.code}_next"

        private fun rejectionPref(language: Language) = "${language.code}_rejections"

        private fun words(language: Language) = loaded.getOrPut(language) { readWords(language) }

        /** Indexed by lowercase previous word -> lowercase next word; display is the next word as last typed. */
        private fun pairs(language: Language) = loadedPairs.getOrPut(language) { readPairs(language) }

        private fun rejections(language: Language) = loadedRejections.getOrPut(language) { readRejections(rejectionPref(language)) }

        private fun decodeWord(
            entry: JSONArray,
            now: Long,
            onDirty: () -> Unit,
        ): Word? {
            val display = entry.optString(0)
            if (display.isEmpty()) return null
            var count = entry.optInt(1).coerceIn(1, 1_000)
            val used = entry.optLong(2)
            clock = maxOf(clock, used)
            var epoch = if (entry.length() > 3) entry.optLong(3, now) else now
            if (entry.length() <= 3) onDirty()
            if (epoch < 0L) {
                epoch = now
                onDirty()
            }

            if (now > epoch) {
                val periods = (now - epoch) / DECAY_PERIOD_MILLIS
                if (periods > 0L) {
                    val shift = periods.coerceAtMost(31L).toInt()
                    count = count shr shift
                    epoch += periods * DECAY_PERIOD_MILLIS
                    onDirty()
                }
            }

            if (count > 0) {
                return Word(display, count.coerceAtMost(1_000), used, epoch)
            }
            onDirty()
            return null
        }

        private fun readWords(language: Language): TreeMap<String, Word> {
            val map = TreeMap<String, Word>()
            val now = nowMillis().coerceAtLeast(0L)
            var changed = false
            try {
                val json = JSONObject(prefs.getString(pref(language), "{}") ?: "{}")
                json.keys().forEach { k ->
                    val entry = json.optJSONArray(k) ?: return@forEach
                    val word = decodeWord(entry, now) { changed = true }
                    if (word != null) map[k] = word
                }
            } catch (_: JSONException) {
                // A damaged list starts over rather than breaking typing.
            }
            if (changed) dirtyWords.add(language)
            return map
        }

        private fun readPairs(language: Language): PairTable {
            val table = PairTable()
            val now = nowMillis().coerceAtLeast(0L)
            var changed = false
            try {
                val json = JSONObject(prefs.getString(pairPref(language), "{}") ?: "{}")
                json.keys().forEach { k ->
                    val entry = json.optJSONArray(k) ?: return@forEach
                    val sep = k.indexOf(' ')
                    if (sep <= 0 || sep + 1 >= k.length) {
                        changed = true
                        return@forEach
                    }
                    val word = decodeWord(entry, now) { changed = true }
                    if (word != null) {
                        table.put(k.substring(0, sep), k.substring(sep + 1), word)
                    }
                }
            } catch (_: JSONException) {
                // A damaged list starts over rather than breaking typing.
            }
            if (changed) dirtyPairs.add(language)
            return table
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
            val wordKey = key(word)
            if (word.length >= 2) {
                bumpWord(words(language), wordKey, word)
                dirtyWords.add(language)
            }
            // Single letters still pair: "I" → "am", "a" → "lot".
            if (previous != null && pairable(previous)) {
                bumpPair(pairs(language), key(previous), wordKey, word)
                dirtyPairs.add(language)
            }
        }

        private fun pairable(word: String) = word.length in 1..32 && word.none(Char::isDigit)

        private fun bumpWord(
            map: TreeMap<String, Word>,
            k: String,
            display: String,
        ) {
            val now = nowMillis().coerceAtLeast(0L)
            val entry = map.getOrPut(k) { Word(display, 0, 0, now) }
            entry.display = display
            entry.count = (entry.count + 1).coerceAtMost(1_000)
            entry.used = ++clock
            while (map.size > LIMIT) {
                // Drop the least-used, oldest entries first.
                var minKey: String? = null
                var minWord: Word? = null
                for ((entryKey, word) in map) {
                    if (minWord == null || isLessUsed(word, minWord)) {
                        minKey = entryKey
                        minWord = word
                    }
                }
                if (minKey == null) break
                map.remove(minKey)
            }
        }

        private fun bumpPair(
            table: PairTable,
            prevKey: String,
            nextKey: String,
            display: String,
        ) {
            val now = nowMillis().coerceAtLeast(0L)
            val followers = table.byPrev.getOrPut(prevKey) { LinkedHashMap() }
            var entry = followers[nextKey]
            if (entry == null) {
                entry = Word(display, 0, 0, now)
                followers[nextKey] = entry
                table.size++
            }
            entry.display = display
            entry.count = (entry.count + 1).coerceAtMost(1_000)
            entry.used = ++clock
            while (table.size > PAIR_LIMIT) {
                // Drop the least-used, oldest entries first.
                table.removeMin()
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
            while (map.size > REJECTION_LIMIT) {
                var oldestKey: String? = null
                var oldestUsed = Long.MAX_VALUE
                for ((entryKey, rej) in map) {
                    if (oldestKey == null || rej.used < oldestUsed) {
                        oldestKey = entryKey
                        oldestUsed = rej.used
                    }
                }
                if (oldestKey == null) break
                map.remove(oldestKey)
            }
            dirtyRejections.add(language)
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
                dirtyRejections.add(language)
            }
        }

        /** Words the user has typed after [previous] at least twice, with how often; most used first. */
        fun next(
            previous: String,
            language: Language,
        ): Map<String, Int> {
            if (previous.isEmpty()) return emptyMap()
            val followers = pairs(language).byPrev[key(previous)] ?: return emptyMap()
            return topTrusted(followers.values)
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
            // TreeMap order keeps all keys strictly longer than prefix with the same prefix contiguous.
            val tail = words(language).tailMap(prefix, false)
            val top = ArrayList<Word>(TOP_LIMIT)
            for ((k, w) in tail) {
                if (!k.startsWith(prefix)) break
                if (w.count >= TRUSTED) insertTop(top, w)
            }
            if (top.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, Int>(top.size)
            for (w in top) result[w.display] = w.count
            return result
        }

        private fun topTrusted(candidates: Collection<Word>): Map<String, Int> {
            val top = ArrayList<Word>(TOP_LIMIT)
            for (w in candidates) {
                if (w.count >= TRUSTED) insertTop(top, w)
            }
            if (top.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, Int>(top.size)
            for (w in top) result[w.display] = w.count
            return result
        }

        private fun insertTop(
            top: ArrayList<Word>,
            candidate: Word,
        ) {
            if (top.size == TOP_LIMIT && !isMoreUsed(candidate, top[TOP_LIMIT - 1])) return
            var pos = top.size
            while (pos > 0 && isMoreUsed(candidate, top[pos - 1])) pos--
            if (top.size < TOP_LIMIT) {
                top.add(pos, candidate)
            } else {
                top.add(pos, candidate)
                top.removeAt(TOP_LIMIT)
            }
        }

        fun size() = Languages.ALL.sumOf { words(it).size }

        fun save() {
            if (dirtyWords.isEmpty() && dirtyPairs.isEmpty() && dirtyRejections.isEmpty()) return
            val edit = prefs.edit()
            for (language in dirtyWords) {
                loaded[language]?.let { edit.putString(pref(language), jsonWords(it)) }
            }
            for (language in dirtyPairs) {
                loadedPairs[language]?.let { edit.putString(pairPref(language), jsonPairs(it)) }
            }
            for (language in dirtyRejections) {
                loadedRejections[language]?.let { edit.putString(rejectionPref(language), jsonRejections(it)) }
            }
            edit.apply()
            dirtyWords.clear()
            dirtyPairs.clear()
            dirtyRejections.clear()
        }

        private fun jsonWords(map: Map<String, Word>): String {
            val json = JSONObject()
            map.forEach { (k, w) -> json.put(k, wordJson(w)) }
            return json.toString()
        }

        private fun jsonPairs(table: PairTable): String {
            val json = JSONObject()
            table.byPrev.forEach { (prev, followers) ->
                followers.forEach { (next, w) ->
                    json.put("$prev $next", wordJson(w))
                }
            }
            return json.toString()
        }

        private fun wordJson(w: Word): JSONArray =
            JSONArray()
                .put(w.display)
                .put(w.count)
                .put(w.used)
                .put(w.decayEpoch)

        private fun jsonRejections(map: Map<String, Rejection>): String {
            val json = JSONObject()
            map.forEach { (k, r) ->
                json.put(
                    k,
                    JSONArray()
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
            dirtyWords.clear()
            dirtyPairs.clear()
            dirtyRejections.clear()
            prefs.edit().clear().apply()
        }

        private companion object {
            const val LIMIT = 3_000
            const val PAIR_LIMIT = 5_000
            const val REJECTION_LIMIT = 500
            const val TRUSTED = 2
            const val TOP_LIMIT = 6
            const val DECAY_PERIOD_MILLIS = 14L * 24 * 60 * 60 * 1000

            fun isLessUsed(
                a: Word,
                b: Word,
            ) = a.count < b.count || (a.count == b.count && a.used < b.used)

            fun isMoreUsed(
                a: Word,
                b: Word,
            ) = a.count > b.count || (a.count == b.count && a.used > b.used)
        }
    }
