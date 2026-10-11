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
    var jordanianArabic: Boolean
        get() = prefs.getBoolean("jordanian_arabic", false)
        set(value) = prefs.edit().putBoolean("jordanian_arabic", value).apply()
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
    var keyPreview: Boolean
        get() = prefs.getBoolean("preview", true)
        set(value) = prefs.edit().putBoolean("preview", value).apply()
    var clipboardHistory: Boolean
        get() = prefs.getBoolean("clipboard_history", true)
        set(value) = prefs.edit().putBoolean("clipboard_history", value).apply()
    var language: Layout
        get() = if (prefs.getString("language", "en") == "ar") Layout.ARABIC else Layout.ENGLISH
        set(value) = prefs.edit().putString("language", if (value == Layout.ARABIC) "ar" else "en").apply()

    var oneHandMode: String
        get() = (prefs.getString("one_hand", "full") ?: "full").takeIf { it in listOf("full", "left", "right") } ?: "full"
        set(value) {
            require(value in listOf("full", "left", "right"))
            prefs.edit().putString("one_hand", value).apply()
        }

    /** Screen edge a swipe starts from to reveal the dial pad: off, left, or right. */
    var dialPadEdge: String
        get() = (prefs.getString("dial_edge", "off") ?: "off").takeIf { it in listOf("off", "left", "right") } ?: "off"
        set(value) {
            require(value in listOf("off", "left", "right"))
            prefs.edit().putString("dial_edge", value).apply()
        }

    /** Screen edge a swipe starts from to reveal recent emoji: off, left, or right. */
    var emojiEdge: String
        get() = (prefs.getString("emoji_edge", "off") ?: "off").takeIf { it in listOf("off", "left", "right") } ?: "off"
        set(value) {
            require(value in listOf("off", "left", "right"))
            prefs.edit().putString("emoji_edge", value).apply()
        }

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun visualKey() = "$appearance:$accent:$rowHeightDp:$numberRow:$keyPreview:$oneHandMode:$dialPadEdge:$emojiEdge"
}
