package com.m57.mboard

import android.content.Context
import android.graphics.Paint
import java.util.Locale

data class Emoji(
    val text: String,
    val name: String,
    val category: String = "",
)

/** Offline Unicode emoji catalogue, loaded once from the bundled asset. */
object Emojis {
    private data class Indexed(
        val emoji: Emoji,
        val tokens: Set<String>,
    )

    @Volatile private var cache: List<Emoji>? = null

    @Volatile private var index: List<Indexed>? = null

    val ready get() = cache != null

    fun cached(): List<Emoji> = cache.orEmpty()

    /** Called by the service's background loader, never by the rendering path. */
    fun all(context: Context): List<Emoji> =
        cache ?: synchronized(this) {
            cache ?: context.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
                val paint = Paint()
                lines
                    .mapNotNull { line ->
                        val p = line.split('\t')
                        if (p.size < 2 || !paint.hasGlyph(p[0])) {
                            null
                        } else {
                            Emoji(p[0], p[1], p.getOrElse(2) { "" })
                        }
                    }.toList()
                    .also { list ->
                        index = list.map { Indexed(it, tokenize(it.name).toSet()) }
                        cache = list
                    }
            }
        }

    fun search(
        context: Context,
        query: String,
    ): List<Emoji> {
        val words = tokenize(query).map { aliases[it] ?: listOf(it) }
        val all = cache ?: return emptyList()
        if (words.isEmpty()) return if (query.isBlank()) emptyList() else all.filter { it.text == query.trim() }
        val phrase = tokenize(query).joinToString(" ")
        val first = words.first()
        // Each query word is required; its synonym translations are alternatives, not extra requirements.
        return index
            .orEmpty()
            .filter { item ->
                words.all { alternatives -> alternatives.any { w -> item.tokens.any { it.startsWith(w) } } }
            }.sortedBy { item ->
                // Best first: exact name, name prefix, whole-word hit, then partial; skin-tone variants last.
                val name = item.emoji.name
                val rank =
                    when {
                        name == phrase -> 0
                        name.startsWith(phrase) -> 1
                        first.any { it in item.tokens } -> 2
                        else -> 3
                    }
                rank + if ("skin tone" in name && "skin" !in phrase) 4 else 0
            }.map { it.emoji }
    }

    /** Best emoji for a whole typed word ("fire" → 🔥), or null. Exact names win, then the shortest name. */
    fun forWord(word: String): String? {
        val key = tokenize(word).singleOrNull() ?: return null
        // Short words only match through an explicit alias ("ok"), never by loose name tokens.
        if ((key.length < 3 && key !in aliases) || key.all { it.isDigit() }) return null
        val items = index ?: return null
        val wanted = aliases[key] ?: listOf(key)
        wanted.forEach { w -> items.firstOrNull { it.emoji.name == w }?.let { return it.emoji.text } }
        return items
            .filter { item -> wanted.any { it in item.tokens } }
            .minByOrNull { it.emoji.name.length }
            ?.emoji
            ?.text
    }

    private fun tokenize(value: String): List<String> =
        normalizeSearch(value)
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    private fun normalizeSearch(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    in '\u064B'..'\u065F', '\u0670', '\u0640', in '\u06D6'..'\u06ED' -> Unit
                    'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                    'ى' -> append('ي')
                    else -> append(char)
                }
            }
        }

    private val aliases =
        mapOf(
            "حب" to listOf("heart", "love"),
            "قلب" to listOf("heart"),
            "ضحك" to listOf("laugh", "smile"),
            "حزين" to listOf("sad", "cry"),
            "نار" to listOf("fire"),
            "ورد" to listOf("rose", "flower"),
            "كلب" to listOf("dog"),
            "قط" to listOf("cat"),
            "فرح" to listOf("joy", "smile"),
            "بكاء" to listOf("cry"),
            "نجمة" to listOf("star"),
            "ماء" to listOf("water"),
            "طعام" to listOf("food"),
            "بيت" to listOf("house"),
            "happy" to listOf("happy", "grinning", "smiling"),
            "sad" to listOf("sad", "crying", "frowning", "disappointed"),
            "laugh" to listOf("laugh", "joy", "rolling"),
            "coffee" to listOf("coffee", "beverage"),
            "قهوة" to listOf("coffee", "beverage"),
            // Exact emoji names for common chat words, used by emoji suggestions.
            "love" to listOf("red heart"),
            "lol" to listOf("face with tears of joy"),
            "haha" to listOf("face with tears of joy"),
            "ok" to listOf("ok hand"),
            "okay" to listOf("ok hand"),
            "thanks" to listOf("folded hands"),
            "please" to listOf("folded hands"),
            "cool" to listOf("smiling face with sunglasses"),
            "party" to listOf("party popper"),
            "kiss" to listOf("face blowing a kiss"),
            "شكرا" to listOf("folded hands"),
            "هههه" to listOf("face with tears of joy"),
        ).mapKeys { normalizeSearch(it.key) }
}
