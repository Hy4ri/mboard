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
    private var pressed: Pair<RectF, String>? = null
    private val popupPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF5A5A64.toInt() else 0xFFFFFFFF.toInt()
            setShadowLayer(6 * density, 0f, 2 * density, 0x66000000)
        }
    private var alts: List<String> = emptyList()
    private var altRects: List<RectF> = emptyList()
    private var altSelected = 0
    private val altPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt() }
    private val showAlternates =
        Runnable {
            val (rect, key) = pressed ?: return@Runnable
            val extra = ALTERNATES[key] ?: return@Runnable
            alts = (listOf(key) + extra).map { if (shifted) it.uppercase() else it }
            val w = rect.width()
            val top = (rect.top - rowHeight * 1.1f).coerceAtLeast(0f)
            var left = (rect.centerX() - w / 2).coerceAtMost(width - w * alts.size)
            left = left.coerceAtLeast(0f)
            altRects = alts.indices.map { RectF(left + it * w, top, left + (it + 1) * w, top + rowHeight) }
            altSelected = 0
            pressed = null
            invalidate()
        }
    private val hidePopup =
        Runnable {
            pressed = null
            invalidate()
        }

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
        drawPopup(canvas, radius)
        drawAlternates(canvas, radius)
    }

    private fun drawAlternates(
        canvas: Canvas,
        radius: Float,
    ) {
        if (alts.isEmpty()) return
        val strip = RectF(altRects.first().left, altRects.first().top, altRects.last().right, altRects.last().bottom)
        canvas.drawRoundRect(strip, radius * 1.5f, radius * 1.5f, popupPaint)
        textPaint.textSize = 24 * density
        alts.forEachIndexed { i, label ->
            val cell = altRects[i]
            if (i == altSelected) canvas.drawRoundRect(cell, radius, radius, altPaint)
            val y = cell.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, cell.centerX(), y, textPaint)
        }
    }

    private fun clearAlternates() {
        removeCallbacks(showAlternates)
        alts = emptyList()
        invalidate()
    }

    /** Enlarged bubble over the pressed letter key, like a key preview. */
    private fun drawPopup(
        canvas: Canvas,
        radius: Float,
    ) {
        val (rect, key) = pressed ?: return
        val label = if (shifted) key.uppercase() else key
        val grow = rect.width() * 0.2f
        val bubble =
            RectF(rect.left - grow, (rect.top - rowHeight * 0.55f).coerceAtLeast(0f), rect.right + grow, rect.bottom)
        bubble.offset(-bubble.left.coerceAtMost(0f), 0f)
        bubble.offset(-(bubble.right - width).coerceAtLeast(0f), 0f)
        canvas.drawRoundRect(bubble, radius * 1.5f, radius * 1.5f, popupPaint)
        textPaint.textSize = 26 * density
        val cy = bubble.top + (rect.top - bubble.top) * 0.9f
        canvas.drawText(label, bubble.centerX(), cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
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
                val hit = keys.firstOrNull { it.first.contains(event.x, event.y) }
                removeCallbacks(hidePopup)
                pressed = hit?.takeIf { (_, k) -> k.length == 1 && k != Keys.SPACE && k !in SPECIAL }
                invalidate()
                if (pressed != null && downKey in ALTERNATES) postDelayed(showAlternates, LONG_PRESS_MS)
                if (downKey == Keys.BACKSPACE) {
                    onKey(Keys.BACKSPACE)
                    postDelayed(repeatDelete, REPEAT_START_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (alts.isNotEmpty()) {
                    val i = altRects.indexOfFirst { event.x < it.right }
                    val next = if (i < 0) alts.lastIndex else i
                    if (next != altSelected) {
                        altSelected = next
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatDelete)
                if (alts.isNotEmpty()) {
                    val choice = alts[altSelected]
                    clearAlternates()
                    downKey = null
                    onKey(choice)
                    return true
                }
                removeCallbacks(showAlternates)
                postDelayed(hidePopup, POPUP_LINGER_MS)
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
                hidePopup.run()
                clearAlternates()
                downKey = null
            }
        }
        return true
    }

    private companion object {
        const val POPUP_LINGER_MS = 100L
        const val LONG_PRESS_MS = 250L
        val SPECIAL = setOf(Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER, Keys.GLOBE)
        const val REPEAT_START_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
