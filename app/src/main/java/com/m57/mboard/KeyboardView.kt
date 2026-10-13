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
    private val onDeleteSwipe: (Int) -> Unit = {},
    private val onDeleteSwipeEnd: (Boolean) -> Unit = {},
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
    var layout = Languages.ALL.first().layout
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
    var language = Languages.ALL.first()
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
    private var cachedRowsKey: Pair<Layout, Language>? = null
    private var cachedRows: List<List<String>> = emptyList()

    private val rows: List<List<String>>
        get() {
            val key = layout to language
            if (cachedRowsKey == key) return cachedRows
            val visible = if (!showNumberRow && layout.letters) layout.rows.drop(1) else layout.rows
            val computed = visible.map { row -> row.map { language.punctuation[it] ?: it } }
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
            "pad" to icon(R.drawable.ic_dialpad),
        )
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var origin: Cell? = null
    private var current: Cell? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var spaceSwiped = false

    // Sliding left from backspace marks whole words; release deletes them, sliding back un-marks.
    private var deleteSwiping = false
    private var deleteSteps = 0
    private var deleteRepeated = false
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

    // Released keys ease back to rest instead of snapping, which reads as smoother during fast typing.
    private val releasedAt = HashMap<Cell, Long>()
    private var previewStart = 0L

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun setPopup(cell: Cell?) {
        if (cell != null && cell != popup) previewStart = now()
        popup = cell
    }

    private fun fadeOut(cell: Cell?) {
        if (cell != null) releasedAt[cell] = now()
    }

    private fun pressAmount(
        cell: Cell,
        active: Boolean,
    ): Float {
        if (active) return 1f
        val at = releasedAt[cell] ?: return 0f
        val t = (now() - at) / RELEASE_FADE_MS.toFloat()
        if (t >= 1f) {
            releasedAt.remove(cell)
            return 0f
        }
        return 1f - t * t
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
                deleteRepeated = true
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
            Keys.SPACE -> {
                when {
                    layout.grid -> 1f

                    // Symbols gains the pad toggle; a narrower space keeps the row width unchanged.
                    layout == Layout.SYMBOLS -> 3f

                    else -> 4f
                }
            }

            Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER -> {
                if (layout.grid) 1f else 1.5f
            }

            Keys.PAD -> {
                1f
            }

            // Pads are grids: every key is one column wide so the rows line up.
            Keys.SYMBOLS, Keys.LETTERS -> {
                if (layout.grid) 1f else 1.5f
            }

            else -> {
                1f
            }
        }

    private fun geometry() {
        if (geometryWidth == width && geometryLayout == layout) return
        cells.clear()
        releasedAt.clear()
        if (width <= 0) return
        val visibleRows = rows
        val maxUnits = visibleRows.maxOf { row -> row.sumOf { weight(it).toDouble() } }.toFloat()
        visibleRows.forEachIndexed { rowIndex, row ->
            val units = row.sumOf { weight(it).toDouble() }.toFloat()
            val unit = width / (if (layout.stretch || layout.grid) units else maxUnits)
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
        val edge = density
        cells.forEach { cell ->
            val active = cell == current && pointer != MotionEvent.INVALID_POINTER_ID
            val calc = layout == Layout.CALC
            val equals = calc && cell.key == Keys.ENTER
            val special = cell.key in SPECIAL || (calc && cell.key in CALC_OPERATORS)
            val base =
                when {
                    equals -> ui.accent
                    special -> ui.modifier
                    else -> ui.surface
                }
            val amount = pressAmount(cell, active)
            // The edge sits under the key and the pressed key sinks onto it, like a real keycap.
            paint.color = ui.keyEdge
            canvas.drawRoundRect(cell.rect.left, cell.rect.top + edge, cell.rect.right, cell.rect.bottom + edge, radius, radius, paint)
            paint.color = ui.mix(base, ui.pressed(base), amount)
            val sink = edge * amount
            canvas.save()
            canvas.translate(0f, sink)
            canvas.drawRoundRect(cell.rect, radius, radius, paint)
            val foreground = if (equals) ui.onAccent else ui.text
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

                    Keys.PAD -> {
                        icons["pad"]
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
            canvas.restore()
        }
        if (releasedAt.isNotEmpty()) postInvalidateOnAnimation()
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
            altStrip.set(altRects.first().left, altRects.first().top, altRects.last().right, altRects.last().bottom)
            paint.color = ui.keyEdge
            canvas.drawRoundRect(altStrip.left, altStrip.top + 2 * edge, altStrip.right, altStrip.bottom + 2 * edge, radius, radius, paint)
            paint.color = ui.modifier
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
                language.lettersLabel
            }

            Keys.SYMBOLS -> {
                language.symbolsLabel
            }

            Keys.SPACE -> {
                if (scrubbing) "Move cursor" else language.label
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
        // A short ease-out grow from the key gives the bubble motion without delaying the letter.
        val t = ((now() - previewStart) / PREVIEW_POP_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        val scale = .82f + .18f * eased
        canvas.save()
        canvas.scale(scale, scale, previewBubble.centerX(), rect.bottom)
        paint.color = ui.keyEdge
        canvas.drawRoundRect(
            previewBubble.left,
            previewBubble.top + 2 * density,
            previewBubble.right,
            previewBubble.bottom + density,
            radius,
            radius,
            paint,
        )
        paint.color = ui.modifier
        canvas.drawRoundRect(previewBubble, radius, radius, paint)
        previewLabelBounds.set(previewBubble.left, previewBubble.top, previewBubble.right, previewBubble.top + rowHeight * .65f)
        drawLabel(canvas, label(cell.key), previewLabelBounds, ui.text, 26f)
        canvas.restore()
        if (t < 1f) postInvalidateOnAnimation()
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
        val extra = language.alternatesFor(base.key) ?: return
        alts = (listOf(base.key) + extra).map { if (shifted || capsLocked) it.uppercase(Locale.ROOT) else it }
        val cellWidth = minOf(maxOf(base.rect.width(), 32 * density), width.toFloat() / alts.size)
        val left = (base.rect.centerX() - cellWidth / 2).coerceIn(0f, (width - cellWidth * alts.size).coerceAtLeast(0f))
        val top = (base.rect.top - rowHeight).coerceAtLeast(0f)
        altRects = alts.indices.map { RectF(left + it * cellWidth, top, left + (it + 1) * cellWidth, top + rowHeight) }
        altSelected = 0
        altOriginX = lastX
        setPopup(null)
        invalidate()
    }

    private fun updatePressed(cell: Cell?) {
        if (cell == current) return
        removeCallbacks(hold)
        removeCallbacks(repeatDelete)
        fadeOut(current)
        current = cell
        setPopup(cell?.takeIf { it.key !in SPECIAL && it.key != Keys.SPACE })
        invalidate()
    }

    fun cancelTouch() {
        removeCallbacks(hold)
        removeCallbacks(repeatDelete)
        removeCallbacks(hidePopup)
        if (deleteSwiping) {
            deleteSwiping = false
            deleteSteps = 0
            onDeleteSwipeEnd(false)
        }
        if (pointer != MotionEvent.INVALID_POINTER_ID) fadeOut(current)
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
            cells.map { AccessibleKey(it.rect, it.key, accessibleLabel(it.key), language.alternatesFor(it.key) != null) }
        }, onKey, { key ->
            val values =
                (listOf(key) + language.alternatesFor(key).orEmpty()).map {
                    if (shifted ||
                        capsLocked
                    ) {
                        it.uppercase(Locale.ROOT)
                    } else {
                        it
                    }
                }
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

            Keys.PAD -> {
                "Number pad"
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

                    origin?.key == Keys.BACKSPACE && (deleteSwiping || startsDeleteSwipe(x)) -> {
                        if (!deleteSwiping) {
                            deleteSwiping = true
                            removeCallbacks(repeatDelete)
                        }
                        val steps = ((downX - x) / (DELETE_SWIPE_STEP_DP * density)).toInt().coerceAtLeast(0)
                        if (steps != deleteSteps) {
                            deleteSteps = steps
                            onDeleteSwipe(steps)
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

    private fun startsDeleteSwipe(x: Float) = !deleteRepeated && downX - x > DELETE_SWIPE_START_DP * density

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
        setPopup(origin?.takeIf { it.key !in SPECIAL && it.key != Keys.SPACE })
        val key = origin?.key
        if (key == Keys.SPACE || (key != null && language.alternatesFor(key) != null)) postDelayed(hold, 250L)
        if (key == Keys.BACKSPACE) {
            deleteStart = android.os.SystemClock.uptimeMillis()
            deleteRepeated = false
            onKey(Keys.BACKSPACE)
            postDelayed(repeatDelete, 400L)
        }
        invalidate()
    }

    private fun release(
        x: Float,
        y: Float,
    ) {
        if (deleteSwiping) {
            val commit = deleteSteps > 0
            deleteSwiping = false
            deleteSteps = 0
            cancelTouch()
            onDeleteSwipeEnd(commit)
            return
        }
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
        const val DELETE_SWIPE_START_DP = 20
        const val DELETE_SWIPE_STEP_DP = 28
        const val RELEASE_FADE_MS = 140L
        const val PREVIEW_POP_MS = 70L
        val SPECIAL = setOf(Keys.SHIFT, Keys.BACKSPACE, Keys.ENTER, Keys.GLOBE, Keys.SYMBOLS, Keys.MORE_SYMBOLS, Keys.LETTERS, Keys.PAD)
        val CALC_OPERATORS = setOf("÷", "×", "−", "+", "(", ")", "%")
    }
}
