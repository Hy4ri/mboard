package com.m57.mboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Draws a [Layout] and reports key taps and spacebar swipes. */
class KeyboardView(
    context: Context,
    private val onKey: (String) -> Unit,
    private val onSpaceSwipe: () -> Unit,
) : View(context) {
    var layout: Layout = Layout.ENGLISH
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    var shifted = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowHeight = 54 * density
    private val gap = 4 * density
    private val dark =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bgColor = if (dark) 0xFF1B1B1F.toInt() else 0xFFE8E8EC.toInt()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF303036.toInt() else -1 }
    private val modPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF45454D.toInt() else 0xFFC8C8D0.toInt()
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) -1 else 0xFF111111.toInt()
            textSize = 22 * density
            textAlign = Paint.Align.CENTER
        }
    private val keys = mutableListOf<Pair<RectF, String>>()
    private var downX = 0f
    private var downKey: String? = null

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val h = (rowHeight * layout.rows.size + gap).toInt() + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun weight(key: String) =
        when (key) {
            Keys.SPACE -> 4f
            Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER, Keys.SYMBOLS, Keys.LETTERS -> 1.5f
            else -> 1f
        }

    private fun layoutKeys() {
        keys.clear()
        val maxUnits = layout.rows.maxOf { row -> row.sumOf { weight(it).toDouble() } }.toFloat()
        val unit = width / maxUnits
        layout.rows.forEachIndexed { r, row ->
            val rowUnits = row.sumOf { weight(it).toDouble() }.toFloat()
            // Arabic rows stretch edge to edge; English keeps the classic inset home row.
            val rowUnit = if (layout == Layout.ARABIC) width / rowUnits else unit
            var x = (width - rowUnits * rowUnit) / 2
            val top = r * rowHeight + gap
            // Rows are defined in visual left-to-right order for every layout.
            row.forEach { key ->
                val w = weight(key) * rowUnit
                keys += RectF(x + gap / 2, top, x + w - gap / 2, top + rowHeight - gap) to key
                x += w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        layoutKeys()
        canvas.drawColor(bgColor)
        val radius = 6 * density
        keys.forEach { (rect, key) ->
            val printable = key.length == 1 && key != Keys.SHIFT && key != Keys.BACKSPACE && key != Keys.ENTER
            canvas.drawRoundRect(rect, radius, radius, if (printable) keyPaint else modPaint)
            val label =
                when {
                    key == Keys.SPACE -> if (layout == Layout.ARABIC) "العربية" else "English"
                    key == Keys.SHIFT && shifted -> "⬆"
                    shifted && printable -> key.uppercase()
                    else -> key
                }
            textPaint.textSize = (if (label.length > 1 && key != Keys.SPACE) 15 else 22) * density
            if (key == Keys.SPACE) textPaint.textSize = 15 * density
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, rect.centerX(), y, textPaint)
        }
    }

    private fun keyAt(
        x: Float,
        y: Float,
    ) = keys.firstOrNull { it.first.contains(x, y) }?.second

    private val repeatDelete =
        object : Runnable {
            override fun run() {
                if (downKey != Keys.BACKSPACE) return
                onKey(Keys.BACKSPACE)
                postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }

    override fun onDetachedFromWindow() {
        removeCallbacks(repeatDelete)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downKey = keyAt(event.x, event.y)
                if (downKey != null) performHapticFeedback(HAPTIC_KEY)
                if (downKey == Keys.BACKSPACE) {
                    onKey(Keys.BACKSPACE)
                    postDelayed(repeatDelete, REPEAT_START_MS)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatDelete)
                val key = downKey ?: return true
                downKey = null
                if (key == Keys.BACKSPACE) return true
                if (key == Keys.SPACE && abs(event.x - downX) > 40 * density) {
                    onSpaceSwipe()
                } else {
                    onKey(key)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatDelete)
                downKey = null
            }
        }
        return true
    }

    private companion object {
        const val HAPTIC_KEY = 3 // HapticFeedbackConstants.KEYBOARD_TAP
        const val REPEAT_START_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
