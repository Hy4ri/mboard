package com.m57.mboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/** Small, defensive on-device history store. */
class EmojiHistory(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("emoji_history", Context.MODE_PRIVATE)

    private fun read(
        key: String,
        max: Int,
    ): MutableList<String> =
        try {
            val a = JSONArray(prefs.getString(key, "[]") ?: "[]")
            (0 until a.length())
                .mapNotNull { (a.opt(it) as? String)?.takeIf { text -> text.isNotBlank() && text.length <= 64 } }
                .distinct()
                .take(max)
                .toMutableList()
        } catch (_: JSONException) {
            mutableListOf()
        }

    private fun write(
        key: String,
        values: List<String>,
    ) = prefs.edit().putString(key, JSONArray(values).toString()).apply()

    fun recent(): List<String> = read("recent", 40)

    fun favorites(): List<String> = read("favorites", 60)

    fun record(text: String) {
        if (text.isBlank() || text.length > 64) return
        write("recent", (listOf(text) + recent()).distinct().take(40))
    }

    fun toggleFavorite(text: String) {
        if (text.isBlank() || text.length > 64) return
        val list = favorites()
        if (text in
            list
        ) {
            write("favorites", list - text)
        } else {
            write("favorites", (listOf(text) + list).take(60))
        }
    }

    fun isFavorite(text: String): Boolean = text in favorites()

    fun clearRecent() = prefs.edit().remove("recent").apply()
}
