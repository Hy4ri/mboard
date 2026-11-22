package com.m57.mboard

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("ViewConstructor") // Created in code with the active session’s gesture callbacks.
internal class TrackpadView(
    ui: BoardUi,
    private val move: (Float, Float) -> Unit,
    private val tap: () -> Unit,
    private val scroll: (Float, Float) -> Unit,
    private val startMove: () -> Unit,
    private val endMove: () -> Unit,
) : TextView(ui.context) {
    private enum class Gesture { POINTER, EDGE, MULTI }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val edgeWidth = ui.dp(EDGE_WIDTH)
    private val railHeight = ui.dp(RAIL_HEIGHT)
    private val railPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.mix(ui.surface, ui.text, RAIL_MIX)
            strokeWidth = ui.dp(Tokens.FOCUS_WIDTH).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var gesture = Gesture.POINTER
    private var twoDown = false
    private var scrollX = 0f
    private var scrollY = 0f

    init {
        text = context.getString(R.string.trackpad_hint)
        gravity = Gravity.CENTER
        textSize = Tokens.TEXT_BODY
        setLineSpacing(ui.dp(Tokens.SPACE_1).toFloat(), 1f)
        setTextColor(if (ui.contrast(ui.muted, ui.surface) >= Tokens.TEXT_RATIO) ui.muted else ui.text)

        fun surface(
            fill: Int,
            stroke: Int,
            width: Int = Tokens.KEY_OUTLINE_WIDTH,
        ) = ui.shape(fill, TRACKPAD_RADIUS).apply { setStroke(ui.dp(width), stroke) }

        val edge = ui.mix(ui.surface, ui.text, EDGE_MIX)
        val states =
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), surface(ui.surface, ui.text, Tokens.FOCUS_WIDTH))
                addState(intArrayOf(android.R.attr.state_pressed), surface(ui.pressed(ui.surface), edge))
                addState(intArrayOf(), surface(ui.surface, edge))
            }
        background = InsetDrawable(states, ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_2), 0)
        setPadding(edgeWidth + ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_1), edgeWidth + ui.dp(Tokens.SPACE_2), 0)
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = edgeWidth * .5f
        val center = height * .5f
        val half = minOf(railHeight * .5f, center * .5f)
        canvas.drawLine(x, center - half, x, center + half, railPaint)
        canvas.drawLine(width - x, center - half, width - x, center + half, railPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        tap()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                gesture = if (event.x < edgeWidth || event.x > width - edgeWidth) Gesture.EDGE else Gesture.POINTER
                twoDown = false
                scrollX = 0f
                scrollY = 0f
                if (gesture == Gesture.POINTER) startMove()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture = Gesture.MULTI
                if (event.pointerCount == 2) {
                    lastX = (event.getX(0) + event.getX(1)) / 2
                    lastY = (event.getY(0) + event.getY(1)) / 2
                    twoDown = true
                } else {
                    twoDown = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.MULTI -> if (twoDown && event.pointerCount == 2) collectScroll(event)
                    Gesture.EDGE -> if (hypot(event.x - startX, event.y - startY) > slop) moved = true
                    Gesture.POINTER -> movePointer(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (twoDown && event.pointerCount == 2) collectScroll(event)
                twoDown = false
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                val dx = event.x - startX
                val dy = event.y - startY
                when (gesture) {
                    Gesture.MULTI -> {
                        postScroll(scrollX, scrollY)
                    }

                    Gesture.EDGE -> {
                        if (abs(dy) > slop) {
                            postScroll(0f, dy)
                        } else if (!moved && hypot(dx, dy) <= slop) {
                            post { performClick() }
                        }
                    }

                    Gesture.POINTER -> {
                        movePointer(event)
                        if (moved) endMove() else post { performClick() }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                moved = false
                gesture = Gesture.POINTER
                twoDown = false
            }
        }
        return true
    }

    private fun postScroll(
        x: Float,
        y: Float,
    ) {
        // Android cancels in-flight touch streams when injecting a new one.
        if (hypot(x, y) > slop) post { scroll(x, y) }
    }

    private fun movePointer(event: MotionEvent) {
        if (!moved && hypot(event.x - startX, event.y - startY) <= slop) return
        moved = true
        move(event.x - lastX, event.y - lastY)
        lastX = event.x
        lastY = event.y
    }

    private fun collectScroll(event: MotionEvent) {
        val x = (event.getX(0) + event.getX(1)) / 2
        val y = (event.getY(0) + event.getY(1)) / 2
        scrollX += x - lastX
        scrollY += y - lastY
        lastX = x
        lastY = y
    }
}

internal class TrackpadControls(
    val hold: ImageButton,
    val close: View,
)

internal fun LinearLayout.addTrackpadControls(
    ui: BoardUi,
    pad: TrackpadView,
    close: () -> Unit,
    leftClick: () -> Unit,
    rightClick: () -> Unit,
): TrackpadControls {
    orientation = LinearLayout.HORIZONTAL
    val railWidth = ui.dp(Tokens.TARGET_MIN)
    val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun action(
        icon: Int,
        description: Int,
        onClick: () -> Unit,
    ): ImageButton {
        val button = ui.icon(icon, context.getString(description), onClick)
        rail.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return button
    }

    action(R.drawable.ic_mouse_left, R.string.trackpad_left_description, leftClick)
    action(R.drawable.ic_mouse_right, R.string.trackpad_right_description, rightClick)
    val hold =
        action(R.drawable.ic_drag_hold, R.string.trackpad_hold_description) {}.apply {
            background =
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), ui.ripple(ui.accentContainer))
                    addState(intArrayOf(), ui.ripple())
                }
            setTrackpadHold(false)
            setOnClickListener { setTrackpadHold(!isSelected) }
        }
    val closeButton =
        ui.icon(R.drawable.ic_close, context.getString(R.string.action_close), close).apply {
            background = ui.ripple(ui.surface)
        }
    val trackSurface =
        FrameLayout(context).apply {
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val size = ui.dp(Tokens.TARGET_MIN)
            addView(
                closeButton,
                FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                    topMargin = ui.dp(Tokens.SPACE_1)
                    marginEnd = ui.dp(Tokens.SPACE_2)
                },
            )
        }
    addView(trackSurface, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    addView(rail, LinearLayout.LayoutParams(railWidth, LinearLayout.LayoutParams.MATCH_PARENT))
    return TrackpadControls(hold, closeButton)
}

internal fun ImageButton.setTrackpadHold(armed: Boolean) {
    isSelected = armed
    stateDescription = context.getString(if (armed) R.string.trackpad_hold_armed else R.string.trackpad_hold_off)
}

private const val TRACKPAD_RADIUS = 2
private const val EDGE_WIDTH = 24
private const val RAIL_HEIGHT = 28
private const val EDGE_MIX = .21f
private const val RAIL_MIX = .42f
