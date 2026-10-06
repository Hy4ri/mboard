package com.m57.mboard

import android.content.Context
import android.graphics.Paint

data class Emoji(
    val text: String,
    val name: String,
)

/** Full Unicode emoji list bundled as an asset (from emoji-test.txt); nothing is fetched at runtime. */
object Emojis {
    @Volatile private var cache: List<Emoji>? = null

    fun all(context: Context): List<Emoji> =
        cache ?: run {
            val paint = Paint()
            context.assets
                .open("emoji.tsv")
                .bufferedReader()
                .readLines()
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 2 || !paint.hasGlyph(parts[0])) null else Emoji(parts[0], parts[1])
                }.also { cache = it }
        }

    fun search(
        context: Context,
        query: String,
    ): List<Emoji> {
        val words =
            query
                .lowercase()
                .trim()
                .split(" ")
                .filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all(context).filter { e ->
            val nameWords = e.name.split(" ", "-", ":")
            words.all { w -> nameWords.any { it.startsWith(w) } }
        }
    }
}
