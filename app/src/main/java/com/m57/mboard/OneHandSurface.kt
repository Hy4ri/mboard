package com.m57.mboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Keyboard surface with an optional, accessible rail for one-handed placement and edge-swipe side
 * panels (dial pad, recent emoji). Each panel owns one screen edge.
 */
class OneHandSurface(
    context: Context,
    private val keyboard: KeyboardView,
    mode: String,
    dialEdge: String,
    emojiEdge: String,
    private val onDialKey: (String) -> Unit,
    private val recentEmoji: () -> List<String>,
    private val onEmoji: (String) -> Unit,
    private val onOpenEmoji: () -> Unit,
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

    /** A panel that slides in from [edge] and pushes the keyboard aside by its visible width. */
    private inner class SidePanel(
        val edge: String,
        val fraction: Float,
        val view: LinearLayout,
        val name: String,
        val beforeShow: () -> Unit = {},
    ) {
        var shown = false
        var amount = 0f
        var animator: ValueAnimator? = null

        fun fullWidth(total: Int) = (total * fraction).toInt()

        fun visibleWidth(total: Int) = (fullWidth(total) * amount).toInt()

        fun contains(x: Float): Boolean {
            val visible = visibleWidth(width)
            return if (edge == LEFT) x < visible else x > width - visible
        }

        fun reveal(show: Boolean) {
            if (shown == show) return
            shown = show
            if (show) beforeShow()
            keyboard.cancelTouch()
            animator?.cancel()
            view.visibility = View.VISIBLE
            animator =
                ValueAnimator.ofFloat(amount, if (show) 1f else 0f).apply {
                    duration = 220L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        amount = it.animatedValue as Float
                        // The first frame of a show animation is also 0: only a finished hide removes the panel.
                        if (!shown && amount == 0f) view.visibility = View.GONE
                        requestLayout()
                    }
                    start()
                }
            view.announceForAccessibility(if (show) "$name shown" else "$name hidden")
        }
    }

    private val emojiGrid = buildEmojiGrid()
    private val panels: List<SidePanel>
    private var active: SidePanel? = null
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var swipePointer = MotionEvent.INVALID_POINTER_ID

    init {
        clipChildren = false
        clipToPadding = false
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        val list = ArrayList<SidePanel>(2)
        if (dialEdge == LEFT || dialEdge == RIGHT) list += SidePanel(dialEdge, DIAL_FRACTION, buildDial(), "Dial pad")
        // One panel per edge: the dial pad keeps its edge if both settings ever collide.
        if ((emojiEdge == LEFT || emojiEdge == RIGHT) && list.none { it.edge == emojiEdge }) {
            list += SidePanel(emojiEdge, EMOJI_FRACTION, emojiGrid, "Recent emoji", ::fillEmojiGrid)
        }
        panels = list
        panels.forEach {
            addView(it.view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            it.view.visibility = View.GONE
        }
        rebuildRail(mode)
    }

    fun setMode(value: String) {
        val normalized = normalize(value)
        if (mode == normalized) return
        mode = normalized
        requestLayout()
        invalidate()
    }

    private fun cellParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
        }

    private fun panelColumn(description: String) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(2))
            contentDescription = description
        }

    private fun buildDial(): LinearLayout {
        val pad = panelColumn("Dial pad")
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", Keys.BACKSPACE)).forEach { keys ->
            val row = LinearLayout(context)
            keys.forEach { key ->
                val view =
                    if (key == Keys.BACKSPACE) {
                        ui.icon(R.drawable.ic_backspace, "Delete") { onDialKey(key) }.apply { background = ui.ripple(ui.modifier) }
                    } else {
                        ui.button(key) { onDialKey(key) }.apply { textSize = 20f }
                    }
                row.addView(view, cellParams())
            }
            pad.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return pad
    }

    private fun buildEmojiGrid() = panelColumn("Recent emoji").apply { setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1)) }

    /** Rebuilt on every reveal so it always reflects the latest history: 2 columns by 4 rows plus an open-all button. */
    private fun fillEmojiGrid() {
        emojiGrid.removeAllViews()
        val recent = recentEmoji().take(EMOJI_COLUMNS * EMOJI_ROWS)
        if (recent.isEmpty()) {
            emojiGrid.addView(
                ui.label("No recent emoji yet", 13f, secondary = true).apply {
                    gravity = Gravity.CENTER
                    textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addOpenEmojiButton()
            return
        }
        repeat(EMOJI_ROWS) { rowIndex ->
            val row = LinearLayout(context)
            repeat(EMOJI_COLUMNS) { column ->
                val emoji = recent.getOrNull(rowIndex * EMOJI_COLUMNS + column)
                val cell =
                    if (emoji == null) {
                        View(context)
                    } else {
                        ui.button(emoji) { onEmoji(emoji) }.apply {
                            textSize = 22f
                            contentDescription = "Insert $emoji"
                            // Tight cells: the emoji glyph fills the tile instead of the default button padding.
                            setPadding(0, 0, 0, 0)
                            minHeight = 0
                            minimumHeight = 0
                            includeFontPadding = false
                        }
                    }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
                    },
                )
            }
            emojiGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addOpenEmojiButton()
    }

    private fun addOpenEmojiButton() {
        emojiGrid.addView(
            ui
                .icon(R.drawable.ic_mood, "Open all emoji") {
                    panels.firstOrNull { it.view === emojiGrid }?.reveal(false)
                    onOpenEmoji()
                }.apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7))
                    background = ui.ripple(ui.modifier)
                },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(OPEN_ROW_DP)).apply {
                setMargins(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
            },
        )
    }

    private fun panelAt(edge: String) = panels.firstOrNull { it.edge == edge }

    private fun nearEdge(x: Float): SidePanel? =
        when {
            x < ui.dp(EDGE_DP) -> panelAt(LEFT)
            x > width - ui.dp(EDGE_DP) -> panelAt(RIGHT)
            else -> null
        }

    override fun onInterceptTouchEvent(event: MotionEvent) = track(event)

    /** Shared by intercept and onTouchEvent: empty, non-clickable panel areas deliver moves here directly. */
    private fun track(event: MotionEvent): Boolean {
        if (panels.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startTime = event.eventTime
                swipePointer = event.getPointerId(0)
                active = panels.firstOrNull { it.shown && it.contains(event.x) } ?: nearEdge(event.x)
            }

            // A second finger means typing, not a swipe.
            MotionEvent.ACTION_POINTER_DOWN -> {
                active = null
            }

            MotionEvent.ACTION_MOVE -> {
                val panel = active ?: return false
                val index = event.findPointerIndex(swipePointer)
                if (index < 0) {
                    active = null
                    return false
                }
                val dx = event.getX(index) - startX
                val dy = event.getY(index) - startY
                // Mostly vertical drift means a key press or scroll, not a swipe.
                if (abs(dy) > ui.dp(SLOP_DP) && abs(dy) > abs(dx)) {
                    active = null
                    return false
                }
                val elapsed = (event.eventTime - startTime).coerceAtLeast(1L)
                val speed = abs(dx) / elapsed
                val horizontal = abs(dx) > 1.5f * abs(dy)
                // Either a full drag, or a short quick flick, triggers.
                val far = abs(dx) > ui.dp(SWIPE_DP)
                val flick = abs(dx) > ui.dp(FLICK_DP) && speed > ui.dp(1) * FLICK_DP_PER_MS
                if (horizontal && (far || flick)) {
                    active = null
                    // Inward from the panel's edge shows it; back out toward that edge hides it.
                    val inward = (panel.edge == LEFT) == (dx > 0)
                    if (inward != panel.shown) {
                        panel.reveal(inward)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
            }
        }
        return false
    }

    // Claims a DOWN no child wanted (e.g. an empty panel's label) so the swipe can still be tracked.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return active != null
        track(event)
        return true
    }

    private fun shownWidth(
        edge: String,
        total: Int,
    ) = panelAt(edge)?.visibleWidth(total) ?: 0

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val fullWidth = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(fullWidth, height)
        panels.forEach {
            it.view.measure(
                MeasureSpec.makeMeasureSpec(it.fullWidth(fullWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        val width = fullWidth - shownWidth(LEFT, fullWidth) - shownWidth(RIGHT, fullWidth)
        val compact = width < ui.dp(COMPACT_WIDTH_DP)
        val effectiveMode = if (compact) FULL else mode
        rebuildRail(effectiveMode)

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
        val fullWidth = right - left
        val height = bottom - top
        val leftShown = shownWidth(LEFT, fullWidth)
        val rightShown = shownWidth(RIGHT, fullWidth)
        panels.forEach {
            if (it.edge == LEFT) {
                it.view.layout(leftShown - it.view.measuredWidth, 0, leftShown, height)
            } else {
                it.view.layout(fullWidth - rightShown, 0, fullWidth - rightShown + it.view.measuredWidth, height)
            }
        }
        // Keep Android's back gesture from eating the edge swipes (the system caps this at 200 dp per edge).
        val edgeWidth = ui.dp(EDGE_DP)
        val exclusionTop = (height - ui.dp(200)).coerceAtLeast(0)
        systemGestureExclusionRects =
            panels.map {
                val x = if (it.edge == LEFT) 0 else fullWidth - edgeWidth
                Rect(x, exclusionTop, x + edgeWidth, height)
            }
        // Panels push the keyboard over by their visible width.
        val offset = leftShown
        val width = fullWidth - leftShown - rightShown
        val compact = width < ui.dp(COMPACT_WIDTH_DP)
        val effectiveMode = if (compact) FULL else mode
        if (effectiveMode == FULL) {
            keyboard.layout(offset, 0, offset + width, height)
            rail.layout(offset + width, 0, offset + width, height)
            return
        }

        val keyboardWidth = keyboard.measuredWidth
        if (effectiveMode == LEFT) {
            keyboard.layout(offset, 0, offset + keyboardWidth, height)
            rail.layout(offset + keyboardWidth, 0, offset + width, height)
        } else {
            rail.layout(offset, 0, offset + width - keyboardWidth, height)
            keyboard.layout(offset + width - keyboardWidth, 0, offset + width, height)
        }
    }

    override fun onDetachedFromWindow() {
        panels.forEach { it.animator?.cancel() }
        super.onDetachedFromWindow()
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
        const val DIAL_FRACTION = 0.34f
        const val EMOJI_FRACTION = 0.26f
        const val EMOJI_COLUMNS = 2
        const val EMOJI_ROWS = 4
        const val OPEN_ROW_DP = 32
        const val EDGE_DP = 40
        const val SWIPE_DP = 32
        const val FLICK_DP = 14
        const val FLICK_DP_PER_MS = 0.6f
        const val SLOP_DP = 12
    }
}
