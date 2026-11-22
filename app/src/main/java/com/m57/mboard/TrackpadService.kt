package com.m57.mboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.hypot

/** Explicitly opened, coordinate-only control. No accessibility node or window content access. */
class TrackpadService : AccessibilityService() {
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var session: Session? = null
    internal val isShowing get() = session != null
    private var listeningForScreenOff = false
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = dismiss()
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!listeningForScreenOff) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            listeningForScreenOff = true
        }
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val active = session ?: return
        val owner = event.packageName?.toString() ?: return
        if (owner != active.owner && owner != packageName) dismiss()
    }

    override fun onInterrupt() = dismiss()

    override fun onConfigurationChanged(newConfig: Configuration) {
        dismiss()
        super.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        dismiss()
        if (connected === this) connected = null
        if (listeningForScreenOff) {
            unregisterReceiver(screenOff)
            listeningForScreenOff = false
        }
    }

    internal fun show(
        owner: String,
        keyBounds: Rect,
        keyboardTop: () -> Int?,
        onDismiss: () -> Unit,
        returnToTyping: () -> Unit,
    ): Boolean {
        val wm = windows ?: return false
        if (session != null) return false
        val ui = BoardUi(this)
        val metrics = wm.currentWindowMetrics
        val display = metrics.bounds
        val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(insetTypes)
        val area = Rect(display.left + insets.left, display.top + insets.top, display.right - insets.right, display.bottom - insets.bottom)
        val dockBounds = Rect(keyBounds)
        val target = ui.dp(Tokens.TARGET_MIN)
        if (!dockBounds.intersect(area) || dockBounds.height() <= target || dockBounds.top - area.top < target) return false
        val safeBottom = area.bottom
        area.bottom = minOf(dockBounds.top, keyboardTop() ?: safeBottom).coerceIn(area.top, safeBottom)
        val marker = CursorMarker(this)
        val dock =
            LinearLayout(this).apply {
                setBackgroundColor(ui.background)
            }
        val active = Session(owner, area, dock, marker, dockBounds.top, keyboardTop, onDismiss)
        val controls =
            dock.addTrackpadControls(
                ui,
                TrackpadView(
                    ui,
                    move = { dx, dy -> move(active, dx, dy) },
                    tap = { press(active, TAP_MS) },
                    scroll = { dx, dy -> scroll(active, dx, dy) },
                    startMove = { beginDrag(active) },
                    endMove = { endDrag(active) },
                ),
                close = {
                    if (session === active) {
                        dismiss()
                        returnToTyping()
                    }
                },
                leftClick = { press(active, TAP_MS) },
                rightClick = { press(active, ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_MARGIN_MS) },
            )
        active.hold = controls.hold
        active.close = controls.close
        controls.close.visibility = if (keyboardTop() != null) View.GONE else View.VISIBLE
        val screenWidth = WindowManager.LayoutParams.MATCH_PARENT
        val markerParams =
            params(screenWidth, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

        // These are physical screen coordinates from the IME, not start/end layout directions.
        @SuppressLint("RtlHardcoded")
        val dockParams =
            params(dockBounds.width(), dockBounds.height(), Gravity.TOP or Gravity.LEFT).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                x = dockBounds.left - display.left
                y = dockBounds.top - display.top
            }
        val location = IntArray(2)
        val markerLocation = IntArray(2)
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            dock.getLocationOnScreen(location)
            // Use the actual overlay edge, not the possible IME offset, as the last safe row.
            active.dockTop = location[1].coerceIn(active.area.top, safeBottom)
            move(active, 0f, 0f)
        }
        marker.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            marker.getLocationOnScreen(markerLocation)
            marker.offsetX = markerLocation[0]
            marker.offsetY = markerLocation[1]
            if (!active.area.isEmpty) move(active, 0f, 0f)
        }
        session = active
        try {
            wm.addView(marker, markerParams)
            active.markerAdded = true
            wm.addView(dock, dockParams)
            active.dockAdded = true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Android denied the trackpad window", e)
            dismiss()
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "Trackpad permission is not available", e)
            dismiss()
            return false
        }
        move(active, 0f, 0f)
        return true
    }

    private fun params(
        width: Int,
        height: Int,
        position: Int,
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = position }

    internal fun setKeyboardVisible(visible: Boolean) {
        val active = session ?: return
        active.close?.visibility = if (visible) View.GONE else View.VISIBLE
        move(active, 0f, 0f)
    }

    internal fun dismiss() {
        val active = session ?: return
        session = null
        if (active.dockAdded) windows?.removeViewImmediate(active.dock)
        if (active.markerAdded) windows?.removeViewImmediate(active.marker)
        active.onDismiss()
    }

    private fun move(
        active: Session,
        dx: Float,
        dy: Float,
    ) {
        if (session !== active) return
        // If the IME is still visible, don't send pointer gestures into its toolbar or strip.
        active.area.bottom = minOf(active.dockTop, active.keyboardTop() ?: active.dockTop).coerceAtLeast(active.area.top)
        if (active.area.isEmpty) return
        active.x = (active.x + dx * POINTER_GAIN).coerceIn(active.area.left.toFloat(), (active.area.right - 1).toFloat())
        active.y = (active.y + dy * POINTER_GAIN).coerceIn(active.area.top.toFloat(), (active.area.bottom - 1).toFloat())
        active.marker.position(active.x, active.y)
    }

    private fun press(
        active: Session,
        duration: Long,
    ) {
        move(active, 0f, 0f)
        dispatch(active, Path().apply { moveTo(active.x, active.y) }, duration)
    }

    private fun scroll(
        active: Session,
        dx: Float,
        dy: Float,
    ) {
        move(active, 0f, 0f)
        if (session !== active || active.area.isEmpty) return
        val area = active.area
        val distance = hypot(dx, dy)
        val minTravel = ViewConfiguration.get(this).scaledTouchSlop * 2f
        val scale = (distance * SCROLL_GAIN).coerceIn(minTravel, maxOf(minTravel, area.height() * .7f)) / distance
        val x = (dx * scale).coerceIn((1 - area.width()).toFloat(), (area.width() - 1).toFloat())
        val y = (dy * scale).coerceIn((1 - area.height()).toFloat(), (area.height() - 1).toFloat())
        // Keep the full swipe above the pad even if the pointer is at the edge of the screen.
        val startX = active.x.coerceIn(area.left - minOf(x, 0f), area.right - 1 - maxOf(x, 0f))
        val startY = active.y.coerceIn(area.top - minOf(y, 0f), area.bottom - 1 - maxOf(y, 0f))
        dispatch(
            active,
            Path().apply {
                moveTo(startX, startY)
                lineTo(startX + x, startY + y)
            },
            SCROLL_MS,
        )
    }

    private fun beginDrag(active: Session) {
        if (session !== active) return
        move(active, 0f, 0f)
        active.dragStart = if (active.hold?.isSelected == true && !active.area.isEmpty) PointF(active.x, active.y) else null
    }

    private fun endDrag(active: Session) {
        if (session !== active) return
        val start = active.dragStart ?: return
        active.dragStart = null
        move(active, 0f, 0f)
        val area = active.area
        if (area.isEmpty) return
        val x = start.x.coerceIn(area.left.toFloat(), (area.right - 1).toFloat())
        val y = start.y.coerceIn(area.top.toFloat(), (area.bottom - 1).toFloat())
        if (hypot(active.x - x, active.y - y) <= ViewConfiguration.get(this).scaledTouchSlop) return
        val path =
            Path().apply {
                moveTo(x, y)
                lineTo(active.x, active.y)
            }
        active.hold?.setTrackpadHold(false)
        // Sending the gesture before the thumb lifts would cancel the real touch stream.
        active.dock.post { dispatch(active, path, DRAG_MS) }
    }

    private fun dispatch(
        active: Session,
        path: Path,
        duration: Long,
    ) {
        if (session !== active || active.area.isEmpty) return
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        if (!dispatchGesture(gesture, null, null)) {
            Toast.makeText(this, R.string.trackpad_action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private class Session(
        val owner: String,
        val area: Rect,
        val dock: View,
        val marker: CursorMarker,
        var dockTop: Int,
        val keyboardTop: () -> Int?,
        val onDismiss: () -> Unit,
    ) {
        var hold: ImageButton? = null
        var close: View? = null
        var dragStart: PointF? = null
        var x = area.exactCenterX()
        var y = area.exactCenterY()
        var markerAdded = false
        var dockAdded = false
    }

    private class CursorMarker(
        context: Context,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(4f, 17f)
                lineTo(7f, 11f)
                lineTo(14f, 10f)
                close()
            }
        private var x = 0f
        private var y = 0f
        var offsetX = 0
        var offsetY = 0

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun position(
            x: Float,
            y: Float,
        ) {
            this.x = x
            this.y = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(x - offsetX, y - offsetY)
            canvas.scale(density, density)
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = 2.5f
            ink.strokeJoin = Paint.Join.ROUND
            ink.color = Color.BLACK
            canvas.drawPath(outline, ink)
            ink.style = Paint.Style.FILL
            ink.color = Color.WHITE
            canvas.drawPath(outline, ink)
            canvas.restore()
        }
    }

    companion object {
        private const val TAG = "mboard.trackpad"
        private const val POINTER_GAIN = 1.45f
        private const val SCROLL_GAIN = 2f
        private const val LONG_PRESS_MARGIN_MS = 150L
        private const val TAP_MS = 55L
        private const val SCROLL_MS = 240L
        private const val DRAG_MS = 360L
        internal var connected: TrackpadService? = null
            private set
    }
}
