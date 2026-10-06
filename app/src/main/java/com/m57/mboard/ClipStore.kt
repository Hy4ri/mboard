package com.m57.mboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Clip(
    val text: String,
    val time: Long,
    val pinned: Boolean,
)

/** On-device clipboard history. Pinned clips never expire; others follow the retention setting. */
class ClipStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("clips", Context.MODE_PRIVATE)

    var retentionMs: Long
        get() = prefs.getLong(KEY_RETENTION, RETENTION_OPTIONS[1].second)
        set(value) = prefs.edit().putLong(KEY_RETENTION, value).apply()

    fun all(): List<Clip> {
        val now = System.currentTimeMillis()
        val keep = read().filter { it.pinned || retentionMs == FOREVER || now - it.time < retentionMs }
        return keep.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    }

    fun add(text: String) {
        if (text.isBlank()) return
        val existing = read().firstOrNull { it.text == text }
        val rest = read().filter { it.text != text }
        val clip = Clip(text, System.currentTimeMillis(), existing?.pinned ?: false)
        val unpinned = rest.filter { !it.pinned }.sortedByDescending { it.time }.take(MAX_UNPINNED - 1)
        write(listOf(clip) + rest.filter { it.pinned } + unpinned)
    }

    fun togglePin(text: String) = write(read().map { if (it.text == text) it.copy(pinned = !it.pinned) else it })

    fun remove(text: String) = write(read().filter { it.text != text })

    fun clearUnpinned() = write(read().filter { it.pinned })

    private fun read(): List<Clip> {
        val arr = JSONArray(prefs.getString(KEY_CLIPS, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Clip(o.getString("t"), o.getLong("at"), o.optBoolean("p"))
        }
    }

    private fun write(clips: List<Clip>) {
        val arr = JSONArray()
        clips.forEach { arr.put(JSONObject().put("t", it.text).put("at", it.time).put("p", it.pinned)) }
        prefs.edit().putString(KEY_CLIPS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_CLIPS = "clips"
        private const val KEY_RETENTION = "retention_ms"
        private const val MAX_UNPINNED = 30
        const val FOREVER = -1L
        val RETENTION_OPTIONS =
            listOf(
                "5 minutes" to 5 * 60_000L,
                "1 hour" to 60 * 60_000L,
                "2 hours" to 2 * 60 * 60_000L,
                "1 day" to 24 * 60 * 60_000L,
                "Until cleared" to FOREVER,
            )
    }
}
