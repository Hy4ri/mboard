package com.m57.mboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs

/** Single-owner gestures: a key tap, a space swipe, or a held-space scrub, never two. */
class KeyboardView(
    context: Context,
    private val onKey: (String) -> Unit,
    private val onSpaceSwipe: () -> Unit,
    private val onCursorMove: (Int) -> Unit = {},
) : View(context) {
    private val ui = BoardUi(context)
    private val settings = BoardSettings(context)
    private val density = resources.displayMetrics.density
    private val rowHeight = settings.rowHeightDp * density
    private val gap = 4 * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val showNumberRow = settings.numberRow
    private val showPreview = settings.keyPreview

    private data class Cell(
        val rect: RectF,
        val key: String,
        val row: Int,
    )

    private val cells = mutableListOf<Cell>()
    private var geometryLayout: Layout? = null
    private var geometryWidth = -1
    var layout = Layout.ENGLISH
        set(value) {
            if (field == value) return
            cancelTouch()
            field = value
            geometryLayout = null
            if (touchReady) access.resetLayout()
            requestLayout()
            invalidate()
        }
    var shifted = false
        set(value) {
            field = value
            invalidate()
        }
    var capsLocked = false
        set(value) {
            field = value
            invalidate()
        }
    var enterLabel: String = Keys.ENTER
        set(value) {
            field = value
            invalidate()
        }
    var languageLabel = "English"
        set(value) {
            if (field == value) return
            cancelTouch()
            field = value
            geometryLayout = null
            if (touchReady) access.resetLayout()
            invalidate()
        }
    val backgroundColor get() = ui.background
    val keysHeight get() = (rowHeight * (if (showNumberRow) 5 else 4) + gap).toInt()
    private val visibleLayouts =
        Layout.entries.associateWith { candidate ->
            if (!showNumberRow && (candidate == Layout.ENGLISH || candidate == Layout.ARABIC)) candidate.rows.drop(1) else candidate.rows
        }
    private var cachedRowsKey: Pair<Layout, String>? = null
    private var cachedRows: List<List<String>> = emptyList()

    private val rows: List<List<String>>
        get() {
            val key = layout to languageLabel
            if (cachedRowsKey == key) return cachedRows
            val computed =
                visibleLayouts.getValue(layout).map { row ->
                    row.map { keyChar ->
                        if (languageLabel == "العربية") {
                            when (keyChar) {
                                "?" -> "؟"
                                "," -> "،"
                                ";" -> "؛"
                                else -> keyChar
                            }
                        } else {
                            keyChar
                        }
                    }
                }
            cachedRowsKey = key
            cachedRows = computed
            return computed
        }

    // Shorter symbol/keypad layouts fill the same IME body instead of leaving a dead bottom band.
    private val activeRowHeight get() = (keysHeight - gap) / rows.size
    private val altStrip = RectF()
    private val previewBubble = RectF()
    private val previewLabelBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icons =
        mapOf(
            "globe" to icon(R.drawable.ic_language),
            "shift" to icon(R.drawable.ic_shift),
            "shifted" to icon(R.drawable.ic_shift_fill1),
            "locked" to icon(R.drawable.ic_shift_lock_fill1),
            "delete" to icon(R.drawable.ic_backspace),
            "enter" to icon(R.drawable.ic_keyboard_return),
        )
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var origin: Cell? = null
    private var current: Cell? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var spaceSwiped = false
    private var scrubbing = false
    private var scrubAnchor = 0f
    private var popup: Cell? = null
    private var alts = emptyList<String>()
    private var altRects = emptyList<RectF>()
    private var altSelected = 0
    private var altOriginX = 0f
    private val hidePopup =
        Runnable {
            popup = null
            invalidate()
        }
    private val hold =
        Runnable {
            val base = origin ?: return@Runnable
            if (current != base || pointer == MotionEvent.INVALID_POINTER_ID || spaceSwiped) return@Runnable
            if (base.key == Keys.SPACE) {
                scrubbing = true
                scrubAnchor = lastX
                popup = null
                invalidate()
            } else {
                showAlternates(base)
            }
        }
    private val repeatDelete =
        object : Runnable {
            override fun run() {
                if (pointer == MotionEvent.INVALID_POINTER_ID || current?.key != Keys.BACKSPACE || origin?.key != Keys.BACKSPACE) return
                // Hold time picks the pace: letters, then word levels 1-3 that each delete faster.
                val held = android.os.SystemClock.uptimeMillis() - deleteStart
                val interval =
                    when {
                        held < 900L -> 50L
                        held < 1_800L -> 200L
                        held < 2_800L -> 120L
                        else -> 70L
                    }
                onKey(if (held < 900L) Keys.BACKSPACE else Keys.DELETE_WORD)
                postDelayed(this, interval)
            }
        }
    private var deleteStart = 0L

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(keysHeight + paddingBottom, heightMeasureSpec))
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        cancelTouch()
        geometryWidth = -1
        if (touchReady) access.resetLayout()
    }

    private fun weight(key: String) =
        when (key) {
            Keys.SPACE -> if (layout == Layout.NUMBER) 1f else 4f
            Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER -> if (layout == Layout.NUMBER) 1f else 1.5f
            Keys.SYMBOLS, Keys.LETTERS -> 1.5f
            else -> 1f
        }

    private fun geometry() {
        if (geometryWidth == width && geometryLayout == layout) return
        cells.clear()
        if (width <= 0) return
        val visibleRows = rows
        val maxUnits = visibleRows.maxOf { row -> row.sumOf { weight(it).toDouble() } }.toFloat()
        visibleRows.forEachIndexed { rowIndex, row ->
            val units = row.sumOf { weight(it).toDouble() }.toFloat()
            val unit = width / (if (layout == Layout.ARABIC || layout == Layout.NUMBER) units else maxUnits)
            var x = (width - units * unit) / 2
            row.forEach { key ->
                val size = weight(key) * unit
                cells.add(
                    Cell(
                        RectF(x + gap / 2, rowIndex * activeRowHeight + gap, x + size - gap / 2, (rowIndex + 1) * activeRowHeight),
                        key,
                        rowIndex,
                    ),
                )
                x += size
            }
        }
        geometryWidth = width
        geometryLayout = layout
    }

    override fun onDraw(canvas: Canvas) {
        geometry()
        canvas.drawColor(ui.background)
        val radius = 8 * density
        cells.forEach { cell ->
            val active = cell == current && pointer != MotionEvent.INVALID_POINTER_ID
            val special = cell.key in SPECIAL
            paint.color =
                if (active) {
                    ui.accent
                } else if (special) {
                    ui.modifier
                } else {
                    ui.surface
                }
            canvas.drawRoundRect(cell.rect, radius, radius, paint)
            val foreground = if (active) ui.onAccent else ui.text
            val drawable =
                when (cell.key) {
                    Keys.GLOBE -> {
                        icons["globe"]
                    }

                    Keys.SHIFT -> {
                        icons[
                            if (capsLocked) {
                                "locked"
                            } else if (shifted) {
                                "shifted"
                            } else {
                                "shift"
                            },
                        ]
                    }

                    Keys.BACKSPACE -> {
                        icons["delete"]
                    }

                    Keys.ENTER -> {
                        if (enterLabel == Keys.ENTER) icons["enter"] else null
                    }

                    else -> {
                        null
                    }
                }
            if (drawable !=
                null
            ) {
                drawIcon(canvas, drawable, cell.rect, foreground)
            } else {
                drawLabel(canvas, label(cell.key), cell.rect, foreground)
            }
        }
        // Accessibility's virtual focus is independent of the pressed key.
        access.focusedBounds()?.let { bounds ->
            paint.color = ui.controlAccent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        popup?.takeIf { showPreview && alts.isEmpty() }?.let { drawPreview(canvas, it, radius) }
        if (alts.isNotEmpty()) {
            paint.color = ui.modifier
            altStrip.set(altRects.first().left, altRects.first().top, altRects.last().right, altRects.last().bottom)
            canvas.drawRoundRect(
                altStrip,
                radius,
                radius,
                paint,
            )
            alts.forEachIndexed { i, value ->
                if (i == altSelected) {
                    paint.color = ui.accent
                    canvas.drawRoundRect(altRects[i], radius, radius, paint)
                }
                drawLabel(canvas, value, altRects[i], if (i == altSelected) ui.onAccent else ui.text, 23f)
            }
        }
    }

    private fun label(key: String) =
        when (key) {
            Keys.LETTERS -> {
                if (languageLabel == "العربية") "أبج" else Keys.LETTERS
            }

            Keys.SYMBOLS -> {
                if (languageLabel == "العربية") "؟123" else Keys.SYMBOLS
            }

            Keys.SPACE -> {
                if (scrubbing) {
                    "Move cursor"
                } else if (layout == Layout.ARABIC) {
                    "العربية"
                } else {
                    languageLabel
                }
            }

            Keys.ENTER -> {
                enterLabel
            }

            else -> {
                if (shifted || capsLocked) key.uppercase(Locale.ROOT) else key
            }
        }

    private fun drawLabel(
        canvas: Canvas,
        value: String,
        rect: RectF,
        foreground: Int,
        size: Float? = null,
    ) {
        textPaint.color = foreground
        textPaint.textSize = (size ?: if (value.length > 2) 14f else 22f) * density
        val available = (rect.width() - 4 * density).coerceAtLeast(1f)
        val measured = textPaint.measureText(value)
        if (measured > available) textPaint.textSize *= available / measured
        canvas.drawText(value, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    private fun drawIcon(
        canvas: Canvas,
        drawable: Drawable,
        rect: RectF,
        foreground: Int,
    ) {
        val size = minOf(24 * density, rect.width() - gap, rect.height() - gap).toInt()
        drawable.setTint(foreground)
        drawable.setBounds(
            (rect.centerX() - size / 2).toInt(),
            (rect.centerY() - size / 2).toInt(),
            (rect.centerX() + size / 2).toInt(),
            (
                rect.centerY() +
                    size / 2
            ).toInt(),
        )
        drawable.draw(canvas)
    }

    private fun icon(res: Int): Drawable = resources.getDrawable(res, context.theme).mutate()

    private fun drawPreview(
        canvas: Canvas,
        cell: Cell,
        radius: Float,
    ) {
        val rect = cell.rect
        previewBubble.set(
            rect.left - rect.width() * .2f,
            (rect.top - rowHeight * .55f).coerceAtLeast(0f),
            rect.right + rect.width() * .2f,
            rect.bottom,
        )
        previewBubble.offset((-previewBubble.left).coerceAtLeast(0f), 0f)
        previewBubble.offset(-(previewBubble.right - width).coerceAtLeast(0f), 0f)
        paint.color = ui.modifier
        canvas.drawRoundRect(previewBubble, radius, radius, paint)
        previewLabelBounds.set(previewBubble.left, previewBubble.top, previewBubble.right, previewBubble.top + rowHeight * .65f)
        drawLabel(canvas, label(cell.key), previewLabelBounds, ui.text, 26f)
    }

    private fun keyAt(
        x: Float,
        y: Float,
    ): Cell? {
        geometry()
        if (x < 0 || x >= width || y < 0 || y >= keysHeight) return null
        val row = (y / activeRowHeight).toInt().coerceAtMost(rows.lastIndex)
        // Gaps are part of the closest key's target, but only within the same row.
        val candidates = cells.filter { it.row == row }
        return candidates.firstOrNull { x >= it.rect.left && x <= it.rect.right }
            ?: candidates.minByOrNull { minOf(abs(it.rect.left - x), abs(it.rect.right - x)) }
    }

    private fun showAlternates(base: Cell) {
        val extra = ALTERNATES[base.key] ?: return
        alts = (listOf(base.key) + extra).map { if (shifted || capsLocked) it.uppercase(Locale.ROOT) else it }
        val cellWidth = minOf(maxOf(base.rect.width(), 32 * density), width.toFloat() / alts.size)
        val left = (base.rect.centerX() - cellWidth / 2).coerceIn(0f, (width - cellWidth * alts.size).coerceAtLeast(0f))
        val top = (base.rect.top - rowHeight).coerceAtLeast(0f)
        altRects = alts.indices.map { RectF(left + it * cellWidth, top, left + (it + 1) * cellWidth, top + rowHeight) }
        altSelected = 0
        altOriginX = lastX
        popup = null
        invalidate()
    }

    private fun updatePressed(cell: Cell?) {
        if (cell == current) return
        removeCallbacks(hold)
        removeCallbacks(repeatDelete)
        current = cell
        popup = cell?.takeIf { it.key !in SPECIAL && it.key != Keys.SPACE }
        invalidate()
    }

    fun cancelTouch() {
        removeCallbacks(hold)
        removeCallbacks(repeatDelete)
        removeCallbacks(hidePopup)
        pointer = MotionEvent.INVALID_POINTER_ID
        origin = null
        current = null
        popup = null
        alts = emptyList()
        altRects = emptyList()
        scrubbing = false
        spaceSwiped = false
        invalidate()
    }

    private val access by lazy {
        KeyboardAccessibility(this, {
            geometry()
            cells.map { AccessibleKey(it.rect, it.key, accessibleLabel(it.key), it.key in ALTERNATES) }
        }, onKey, { key ->
            val values = (listOf(key) + ALTERNATES[key].orEmpty()).map { if (shifted || capsLocked) it.uppercase(Locale.ROOT) else it }
            android.widget.PopupMenu(context, this).apply {
                values.forEach { menu.add(it) }
                setOnMenuItemClickListener { item ->
                    onKey(item.title.toString())
                    true
                }
                show()
            }
        })
    }

    private fun accessibleLabel(key: String) =
        when (key) {
            Keys.SHIFT -> {
                if (capsLocked) {
                    "Caps lock on"
                } else if (shifted) {
                    "Shift on"
                } else {
                    "Shift"
                }
            }

            Keys.GLOBE -> {
                "Change language"
            }

            Keys.BACKSPACE -> {
                "Backspace"
            }

            Keys.ENTER -> {
                if (enterLabel == Keys.ENTER) "Enter" else enterLabel
            }

            Keys.SPACE -> {
                "Space"
            }

            Keys.SYMBOLS -> {
                "Numbers and symbols"
            }

            Keys.MORE_SYMBOLS -> {
                "More symbols"
            }

            Keys.LETTERS -> {
                "Letters"
            }

            else -> {
                label(key)
            }
        }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider? = if (touchReady) access else null

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = access.hover(event) || super.dispatchHoverEvent(event)

    private var touchReady = false

    init {
        touchReady = true
    }

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (touchReady &&
            visibility != VISIBLE
        ) {
            cancelTouch()
        }
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (touchReady &&
            visibility != VISIBLE
        ) {
            cancelTouch()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTouch(event.x, event.y, event.getPointerId(0))
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Roll over to the new finger only after committing the prior ordinary tap.
                // Its later UP is ignored, so fast two-thumb typing never emits it twice.
                if (!scrubbing && alts.isEmpty()) {
                    if (pointer != MotionEvent.INVALID_POINTER_ID) release(lastX, lastY)
                    beginTouch(event.getX(event.actionIndex), event.getY(event.actionIndex), event.getPointerId(event.actionIndex))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) {
                    cancelTouch()
                    return true
                }
                val x = event.getX(index)
                val y = event.getY(index)
                lastX = x
                lastY = y
                when {
                    alts.isNotEmpty() -> {
                        // Staying over the original key keeps the base selected even if the strip was clamped.
                        altSelected =
                            if (abs(x - altOriginX) < touchSlop && y >= (origin?.rect?.top ?: 0f)) {
                                0
                            } else {
                                ((x - altRects.first().left) / altRects.first().width()).toInt().coerceIn(0, alts.lastIndex)
                            }
                    }

                    scrubbing -> {
                        val steps = ((x - scrubAnchor) / (18 * density)).toInt()
                        if (steps != 0) {
                            onCursorMove(steps)
                            scrubAnchor += steps * 18 * density
                        }
                    }

                    origin?.key == Keys.SPACE -> {
                        if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) removeCallbacks(hold)
                        spaceSwiped = abs(x - downX) > 40 * density && abs(y - downY) < rowHeight
                    }

                    else -> {
                        updatePressed(keyAt(x, y))
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) ==
                    pointer
                ) {
                    release(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
            }

            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) ==
                    pointer
                ) {
                    release(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
            }
        }
        return true
    }

    private fun beginTouch(
        x: Float,
        y: Float,
        id: Int,
    ) {
        cancelTouch()
        pointer = id
        downX = x
        downY = y
        lastX = x
        lastY = y
        origin = keyAt(x, y)
        current = origin
        popup = origin?.takeIf { it.key !in SPECIAL && it.key != Keys.SPACE }
        val key = origin?.key
        if (key == Keys.SPACE || key in ALTERNATES) postDelayed(hold, 250L)
        if (key == Keys.BACKSPACE) {
            deleteStart = android.os.SystemClock.uptimeMillis()
            onKey(Keys.BACKSPACE)
            postDelayed(repeatDelete, 400L)
        }
        invalidate()
    }

    private fun release(
        x: Float,
        y: Float,
    ) {
        val base = origin
        val picked = current
        val alternate = alts.getOrNull(altSelected)
        val wasScrubbing = scrubbing
        val swipe = spaceSwiped
        val deleteWasSent = base?.key == Keys.BACKSPACE
        val releasedCell = keyAt(x, y)
        val preview = popup
        cancelTouch()
        when {
            alternate != null && releasedCell != null -> {
                onKey(alternate)
            }

            wasScrubbing -> {
                Unit
            }

            swipe && base?.key == Keys.SPACE -> {
                onSpaceSwipe()
            }

            base?.key == Keys.SPACE && releasedCell?.key == Keys.SPACE -> {
                onKey(Keys.SPACE)
            }

            releasedCell != null && picked != null && picked.key != Keys.SPACE -> {
                if (picked.key != Keys.BACKSPACE || !deleteWasSent) onKey(picked.key)
                if (showPreview && preview != null) {
                    popup = preview
                    postDelayed(hidePopup, 100L)
                }
            }
        }
    }

    private companion object {
        val SPECIAL = setOf(Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER, Keys.GLOBE, Keys.SYMBOLS, Keys.MORE_SYMBOLS, Keys.LETTERS)
    }
}
