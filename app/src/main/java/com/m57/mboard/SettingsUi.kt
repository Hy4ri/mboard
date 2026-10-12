package com.m57.mboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Settings-only components; keyboard widgets and palettes are deliberately unchanged. */
class SettingsUi(
    private val context: Context,
    private val ui: BoardUi,
) {
    fun section(title: String): TextView =
        ui.label(title, 12f, true).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ui.controlAccent)
            letterSpacing = .08f
            setPadding(ui.dp(4), ui.dp(24), ui.dp(4), ui.dp(8))
            isAccessibilityHeading = true
        }

    fun row(
        title: String,
        detail: String,
        swatch: Int? = null,
        destructive: Boolean = false,
        action: () -> Unit,
    ): Button =
        ui.button("", action = action).apply {
            textSize = 16f
            text = lines(title, detail)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            minHeight = ui.dp(68)
            minimumHeight = ui.dp(68)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            compoundDrawablePadding = ui.dp(14)
            background = ui.ripple(ui.surface, CARD_RADII.map { it * ui.dp(1) }.toFloatArray())
            val arrow =
                context.getDrawable(R.drawable.ic_keyboard_arrow_right)?.mutate()?.apply {
                    setTint(if (destructive) ui.danger else ui.muted)
                    setBounds(0, 0, ui.dp(20), ui.dp(20))
                }
            val color =
                swatch?.let {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(it)
                        setStroke(ui.dp(1), ui.muted)
                        setBounds(0, 0, ui.dp(24), ui.dp(24))
                    }
                }
            setCompoundDrawablesRelative(color, null, arrow, null)
            if (destructive) setTextColor(ui.danger)
            contentDescription = "$title. $detail"
        }

    fun toggle(
        title: String,
        detail: String,
        checked: Boolean,
        changed: (Boolean) -> Unit,
    ): Switch =
        Switch(context).apply {
            textSize = 16f
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            text = lines(title, detail)
            setTextColor(ui.text)
            minHeight = ui.dp(72)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            switchPadding = ui.dp(16)
            background = ui.ripple(ui.surface, CARD_RADII.map { it * ui.dp(1) }.toFloatArray())
            val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(checkedState, intArrayOf(ui.controlAccent, ui.muted))
            // A tinted track makes on/off readable at a glance instead of relying on thumb position alone.
            trackTintList = ColorStateList(checkedState, intArrayOf(ui.mix(ui.surface, ui.controlAccent, .45f), ui.modifier))
            isChecked = checked
            contentDescription = "$title. $detail"
            setOnCheckedChangeListener { _, value -> changed(value) }
        }

    fun note(text: String): TextView =
        ui.label(text, 13f, true).apply {
            setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4))
            setLineSpacing(ui.dp(3).toFloat(), 1f)
        }

    fun add(
        column: LinearLayout,
        view: View,
    ) {
        column.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(6)
            },
        )
    }

    private fun lines(
        title: String,
        detail: String,
    ): CharSequence {
        val value = "$title\n$detail"
        return SpannableString(value).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(13f / 16f), title.length + 1, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ui.muted), title.length + 1, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        val CARD_RADII = FloatArray(8) { 16f }
    }
}
