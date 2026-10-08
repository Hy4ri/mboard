package com.m57.mboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Keyboard surface with an optional, accessible rail for one-handed placement. */
class OneHandSurface(
    context: Context,
    private val keyboard: KeyboardView,
    mode: String,
    private val onModeChange: (String) -> Unit,
) : FrameLayout(context) {
    private var mode = normalize(mode)
    private var renderedMode: String? = null
    private val ui = BoardUi(context)
    private val rail =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(ui.background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

    private val modeLabel =
        ui.label("", 12f, secondary = true).apply {
            gravity = Gravity.CENTER
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            contentDescription = "One-handed keyboard position"
            setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4))
        }

    init {
        clipChildren = false
        clipToPadding = false
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        rebuildRail(mode)
    }

    fun setMode(value: String) {
        val normalized = normalize(value)
        if (mode == normalized) return
        mode = normalized
        requestLayout()
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val compact = width < ui.dp(COMPACT_WIDTH_DP)
        val effectiveMode = if (compact) FULL else mode
        rebuildRail(effectiveMode)
        setMeasuredDimension(width, height)

        if (effectiveMode == FULL) {
            keyboard.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            rail.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        } else {
            val minimumRail = ui.dp(RAIL_MIN_DP)
            val desiredKeyboard = (width * KEYBOARD_FRACTION).toInt()
            val keyboardWidth = desiredKeyboard.coerceAtMost((width - minimumRail).coerceAtLeast(0))
            val railWidth = width - keyboardWidth
            keyboard.measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            rail.measure(
                MeasureSpec.makeMeasureSpec(railWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val width = right - left
        val height = bottom - top
        val compact = width < ui.dp(COMPACT_WIDTH_DP)
        val effectiveMode = if (compact) FULL else mode
        if (effectiveMode == FULL) {
            keyboard.layout(0, 0, width, height)
            rail.layout(width, 0, width, height)
            return
        }

        val keyboardWidth = keyboard.measuredWidth
        if (effectiveMode == LEFT) {
            keyboard.layout(0, 0, keyboardWidth, height)
            rail.layout(keyboardWidth, 0, width, height)
        } else {
            rail.layout(0, 0, width - keyboardWidth, height)
            keyboard.layout(width - keyboardWidth, 0, width, height)
        }
    }

    private fun rebuildRail(effectiveMode: String) {
        if (renderedMode == effectiveMode) return
        renderedMode = effectiveMode
        rail.removeAllViews()
        if (effectiveMode == FULL) {
            rail.visibility = View.GONE
            return
        }
        rail.visibility = View.VISIBLE
        modeLabel.text = if (effectiveMode == LEFT) "Keyboard\nleft" else "Keyboard\nright"
        modeLabel.contentDescription = "Keyboard positioned $effectiveMode"
        rail.addView(modeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rail.addView(
            ui.button("Full") { onModeChange(FULL) }.apply {
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(RAIL_MIN_DP)),
        )
        val switchIcon = if (effectiveMode == LEFT) R.drawable.ic_arrow_forward else R.drawable.ic_arrow_back
        val switchDescription = if (effectiveMode == LEFT) "Move keyboard right" else "Move keyboard left"
        rail.addView(
            ui.icon(switchIcon, switchDescription) {
                onModeChange(if (effectiveMode == LEFT) RIGHT else LEFT)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(RAIL_MIN_DP)),
        )
    }

    private fun normalize(value: String) =
        when (value) {
            LEFT, RIGHT -> value
            else -> FULL
        }

    private companion object {
        const val FULL = "full"
        const val LEFT = "left"
        const val RIGHT = "right"
        const val COMPACT_WIDTH_DP = 300
        const val RAIL_MIN_DP = 48
        const val KEYBOARD_FRACTION = 0.82f
    }
}
