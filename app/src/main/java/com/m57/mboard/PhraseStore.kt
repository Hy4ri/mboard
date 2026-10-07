package com.m57.mboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/** Explicitly saved text only; never automatically records what somebody types. */
class PhraseStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("phrases", Context.MODE_PRIVATE)

    fun all(): List<String> =
        try {
            val array = JSONArray(prefs.getString("items", "[]"))
            (0 until array.length())
                .mapNotNull {
                    array.optString(it).takeIf { s ->
                        s.isNotBlank() && s.length <= 2000
                    }
                }.distinct()
                .take(40)
        } catch (_: JSONException) {
            emptyList()
        }

    fun add(text: String): Boolean {
        val clean = text.trim()
        val current = all()
        if (clean.isBlank() || clean.length > 2000 || clean in current || current.size >= 40) return false
        write(current + clean)
        return true
    }

    fun remove(text: String) = write(all().filterNot { it == text })

    private fun write(values: List<String>) {
        prefs.edit().putString("items", JSONArray(values).toString()).apply()
    }
}
