package com.m57.mboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
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
        ui.label(title, Tokens.TEXT_CAPTION, true).apply {
            typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
            setTextColor(ui.controlAccent)
            letterSpacing = .08f
            setPadding(ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_2))
            isAccessibilityHeading = true
        }

    /** A navigation row. [external] swaps the chevron for an "open in new" glyph: the row leaves the app. */
    fun row(
        title: String,
        detail: String,
        swatch: Int? = null,
        destructive: Boolean = false,
        external: Boolean = false,
        action: () -> Unit,
    ): Button =
        ui.button("", action = action).apply {
            textSize = Tokens.TEXT_ROW
            text = lines(title, detail)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            minHeight = ui.dp(ROW_MIN)
            minimumHeight = ui.dp(ROW_MIN)
            setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_3), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_3))
            compoundDrawablePadding = ui.dp(Tokens.SPACE_3)
            background = ui.ripple(ui.surface, cardRadii())
            val arrow =
                context.getDrawable(if (external) R.drawable.ic_open_in_new else R.drawable.ic_keyboard_arrow_right)?.mutate()?.apply {
                    setTint(if (destructive) ui.danger else ui.muted)
                    setBounds(0, 0, ui.dp(Tokens.ICON_SMALL), ui.dp(Tokens.ICON_SMALL))
                }
            val color =
                swatch?.let {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(it)
                        setStroke(ui.dp(1), ui.muted)
                        setBounds(0, 0, ui.dp(Tokens.ICON), ui.dp(Tokens.ICON))
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
            textSize = Tokens.TEXT_ROW
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            text = lines(title, detail)
            setTextColor(ui.text)
            minHeight = ui.dp(ROW_MIN)
            setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_3), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_3))
            switchPadding = ui.dp(Tokens.SPACE_4)
            background = ui.ripple(ui.surface, cardRadii())
            val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            // Thumb contrasts with its own track in both states; the track tokens keep on/off readable at a glance.
            thumbTintList = ColorStateList(checkedState, intArrayOf(ui.onAccent, ui.text))
            // The stock track drawable is drawn at 30 % alpha, which would defeat the contrast tokens: draw it opaque.
            trackDrawable =
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), track(ui.switchTrackOn))
                    addState(intArrayOf(), track(ui.switchTrackOff))
                }
            isChecked = checked
            contentDescription = "$title. $detail"
            setOnCheckedChangeListener { _, value -> changed(value) }
        }

    fun note(text: String): TextView =
        ui.label(text, Tokens.TEXT_BODY, true).apply {
            setPadding(ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_3), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_1))
            setLineSpacing(ui.dp(Tokens.SPACE_1).toFloat(), 1f)
        }

    fun add(
        column: LinearLayout,
        view: View,
    ) {
        column.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(Tokens.SPACE_1)
            },
        )
    }

    private fun track(fill: Int) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = ui.dp(Tokens.RADIUS_PILL).toFloat()
            setSize(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.SPACE_4 + Tokens.SPACE_1))
        }

    private fun cardRadii() = FloatArray(8) { ui.dp(Tokens.RADIUS_CARD).toFloat() }

    private fun lines(
        title: String,
        detail: String,
    ): CharSequence {
        val value = "$title\n$detail"
        return SpannableString(value).apply {
            setSpan(TypefaceSpan(MEDIUM), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(Tokens.TEXT_BODY / Tokens.TEXT_ROW), title.length + 1, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ui.muted), title.length + 1, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val MEDIUM = "sans-serif-medium"

        /** Two-line row: title (16 sp) plus one detail line (14 sp) with padding, on the 8 dp grid. */
        const val ROW_MIN = Tokens.TARGET_MIN + Tokens.SPACE_4
    }
}
