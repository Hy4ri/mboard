package com.m57.mboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Clip(
    val text: String,
    val time: Long,
    val pinned: Boolean,
)

/** On-device history. Expiry removes the stored value too, not just its visible row. */
class ClipStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("clips", Context.MODE_PRIVATE)
    private val settings = BoardSettings(context)
    var retentionMs: Long
        get() {
            val fallback = RETENTION_OPTIONS[1].second
            val value =
                try {
                    prefs.getLong(KEY_RETENTION, fallback)
                } catch (_: ClassCastException) {
                    fallback
                }
            return value.takeIf { candidate -> RETENTION_OPTIONS.any { it.second == candidate } } ?: fallback
        }
        set(value) {
            require(RETENTION_OPTIONS.any { it.second == value }) { "Unsupported clipboard retention" }
            // Purge against the old deadline before a longer retention can resurrect expired data.
            purgeExpired()
            prefs.edit().putLong(KEY_RETENTION, value).apply()
            purgeExpired()
        }

    fun all(): List<Clip> = purgeExpired().sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    fun add(text: String) {
        // Skip oversized clips intact rather than silently pasting a truncated version later.
        if (!settings.clipboardHistory || text.isBlank() || text.length > MAX_TEXT) return
        val current = purgeExpired()
        val existing = current.firstOrNull { it.text == text }
        val clip = Clip(text, System.currentTimeMillis(), existing?.pinned == true)
        write(listOf(clip) + current.filterNot { it.text == text })
    }

    fun togglePin(text: String): Boolean {
        val current = purgeExpired()
        val clip = current.firstOrNull { it.text == text } ?: return false
        if (!clip.pinned && current.count { it.pinned } >= MAX_PINNED) return false
        write(current.map { if (it.text == text) it.copy(pinned = !it.pinned) else it })
        return true
    }

    fun restore(clip: Clip): Boolean {
        val now = System.currentTimeMillis()
        if (clip.text.isBlank() || clip.text.length > MAX_TEXT ||
            (!clip.pinned && retentionMs != FOREVER && now - clip.time >= retentionMs)
        ) {
            return false
        }
        val current = purgeExpired()
        if (clip.pinned && current.count { it.pinned && it.text != clip.text } >= MAX_PINNED) return false
        write(listOf(clip) + current.filterNot { it.text == clip.text })
        return true
    }

    fun remove(text: String) = write(purgeExpired().filterNot { it.text == text })

    fun clearUnpinned() = write(purgeExpired().filter { it.pinned })

    fun clearAll() = write(emptyList())

    private fun purgeExpired(): List<Clip> {
        val current = readRaw()
        val now = System.currentTimeMillis()
        val valid = limit(current.filter { it.pinned || retentionMs == FOREVER || now - it.time < retentionMs })
        if (valid != current) write(valid)
        return valid
    }

    private fun readRaw(): List<Clip> {
        val raw = prefs.getString(KEY_CLIPS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val values =
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val text = item.opt("t") as? String ?: continue
                        val time = item.optLong("at", 0L)
                        if (text.isNotBlank() && text.length <= MAX_TEXT && time > 0L) add(Clip(text, time, item.optBoolean("p", false)))
                    }
                }
            if (values.size != array.length()) write(values)
            values
        } catch (_: JSONException) {
            write(emptyList())
            emptyList()
        }
    }

    private fun limit(clips: List<Clip>): List<Clip> {
        val unique =
            clips
                .groupBy { it.text }
                .map { (text, duplicates) ->
                    Clip(text, duplicates.maxOf { it.time }, duplicates.any { it.pinned })
                }.sortedByDescending { it.time }
        return unique.filter { it.pinned }.take(MAX_PINNED) + unique.filterNot { it.pinned }.take(MAX_UNPINNED)
    }

    private fun write(clips: List<Clip>) {
        val array = JSONArray()
        limit(clips).forEach { array.put(JSONObject().put("t", it.text).put("at", it.time).put("p", it.pinned)) }
        prefs.edit().putString(KEY_CLIPS, array.toString()).apply()
    }

    companion object {
        private const val KEY_CLIPS = "clips"
        private const val KEY_RETENTION = "retention_ms"
        private const val MAX_UNPINNED = 30
        private const val MAX_PINNED = 30
        private const val MAX_TEXT = 20_000
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
