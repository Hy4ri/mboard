package com.m57.mboard

import android.content.Context

/** Preferences contain configuration only, never words typed into another app. */
class BoardSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("board", Context.MODE_PRIVATE)
    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(value) = prefs.edit().putString("appearance", value).apply()
    var accent: String
        get() = prefs.getString("accent", "violet") ?: "violet"
        set(value) = prefs.edit().putString("accent", value).apply()
    var rowHeightDp: Int
        get() = prefs.getInt("row_height", 54).coerceIn(44, 64)
        set(value) = prefs.edit().putInt("row_height", value.coerceIn(44, 64)).apply()
    var numberRow: Boolean
        get() = prefs.getBoolean("number_row", true)
        set(value) = prefs.edit().putBoolean("number_row", value).apply()
    var suggestions: Boolean
        get() = prefs.getBoolean("suggestions", true)
        set(value) = prefs.edit().putBoolean("suggestions", value).apply()

    /** Whether [dialect]'s extra vocabulary is on; off by default. */
    fun dialectEnabled(dialect: Dialect?) = dialect != null && prefs.getBoolean(dialect.settingKey, false)

    fun setDialectEnabled(
        dialect: Dialect,
        value: Boolean,
    ) = prefs.edit().putBoolean(dialect.settingKey, value).apply()

    var autoCorrect: Boolean
        get() = prefs.getBoolean("autocorrect", true)
        set(value) = prefs.edit().putBoolean("autocorrect", value).apply()
    var emojiSuggestions: Boolean
        get() = prefs.getBoolean("emoji_suggestions", false)
        set(value) = prefs.edit().putBoolean("emoji_suggestions", value).apply()
    var autoCapitalize: Boolean
        get() = prefs.getBoolean("capitalize", true)
        set(value) = prefs.edit().putBoolean("capitalize", value).apply()
    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean("double_space", true)
        set(value) = prefs.edit().putBoolean("double_space", value).apply()
    var learnWords: Boolean
        get() = prefs.getBoolean("learn_words", true)
        set(value) = prefs.edit().putBoolean("learn_words", value).apply()

    /** Bumped when learned words are deleted so a running keyboard drops its cached copy. */
    fun markLearnedReset() = prefs.edit().putLong(LEARNED_RESET, System.currentTimeMillis()).apply()

    var keyPreview: Boolean
        get() = prefs.getBoolean("preview", true)
        set(value) = prefs.edit().putBoolean("preview", value).apply()
    var clipboardHistory: Boolean
        get() = prefs.getBoolean("clipboard_history", true)
        set(value) = prefs.edit().putBoolean("clipboard_history", value).apply()

    /** User color overrides by role; null keeps the theme's own color. */
    fun customColor(role: String): Int? = if (prefs.contains("color_$role")) prefs.getInt("color_$role", 0) else null

    fun setCustomColor(
        role: String,
        value: Int?,
    ) = prefs.edit().apply { if (value == null) remove("color_$role") else putInt("color_$role", value) }.apply()

    var language: Language
        get() = Languages.byCode(prefs.getString("language", null))
        set(value) = prefs.edit().putString("language", value.code).apply()

    var oneHandMode: String
        get() = (prefs.getString("one_hand", "full") ?: "full").takeIf { it in THUMB_MODES } ?: "full"
        set(value) {
            require(value in THUMB_MODES)
            prefs.edit().putString("one_hand", value).apply()
        }

    /** Screen edge a swipe starts from to reveal the dial pad: off, left, or right. */
    var dialPadEdge: String
        get() = (prefs.getString("dial_edge", "off") ?: "off").takeIf { it in EDGE_MODES } ?: "off"
        set(value) {
            require(value in EDGE_MODES)
            prefs.edit().putString("dial_edge", value).apply()
        }

    /** Screen edge a swipe starts from to reveal recent emoji: off, left, or right. */
    var emojiEdge: String
        get() = (prefs.getString("emoji_edge", "off") ?: "off").takeIf { it in EDGE_MODES } ?: "off"
        set(value) {
            require(value in EDGE_MODES)
            prefs.edit().putString("emoji_edge", value).apply()
        }

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun visualKey() =
        "$appearance:$accent:$rowHeightDp:$numberRow:$keyPreview:$oneHandMode:$dialPadEdge:$emojiEdge:" +
            COLOR_ROLES.joinToString(",") { customColor(it)?.toString() ?: "-" }

    companion object {
        private val THUMB_MODES = setOf("full", "left", "right")
        private val EDGE_MODES = setOf("off", "left", "right")
        const val LEARNED_RESET = "learned_reset"
        const val ACCENT = "accent"
        const val BACKGROUND = "background"
        const val KEYS = "keys"
        const val SPECIAL_KEYS = "special_keys"
        const val TEXT = "text"
        val COLOR_ROLES = listOf(ACCENT, BACKGROUND, KEYS, SPECIAL_KEYS, TEXT)
    }
}
