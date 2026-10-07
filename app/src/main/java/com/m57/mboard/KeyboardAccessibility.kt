package com.m57.mboard

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/** Virtual accessibility buttons for the custom-drawn keyboard. */
data class AccessibleKey(
    val bounds: RectF,
    val key: String,
    val label: String,
    val hasAlternates: Boolean = false,
)

class KeyboardAccessibility(
    private val host: View,
    private val keys: () -> List<AccessibleKey>,
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit,
) : AccessibilityNodeProvider() {
    private val accessibilityManager = host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var hoveredId = View.NO_ID
    private var focusedId = View.NO_ID

    fun hover(event: MotionEvent): Boolean {
        if (!accessibilityManager.isEnabled || !accessibilityManager.isTouchExplorationEnabled) return false
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_HOVER_MOVE &&
            action != MotionEvent.ACTION_HOVER_ENTER &&
            action != MotionEvent.ACTION_HOVER_EXIT
        ) {
            return false
        }
        val newId = if (action == MotionEvent.ACTION_HOVER_EXIT) View.NO_ID else keyIdAt(event.x, event.y)
        if (newId == hoveredId && action != MotionEvent.ACTION_HOVER_ENTER) return true
        if (hoveredId != View.NO_ID) sendHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, hoveredId)
        hoveredId = newId
        if (hoveredId != View.NO_ID) sendHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, hoveredId)
        return true
    }

    fun reset() {
        if (hoveredId != View.NO_ID) sendHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, hoveredId)
        if (focusedId != View.NO_ID) sendFocusEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, focusedId)
        hoveredId = View.NO_ID
        focusedId = View.NO_ID
        host.invalidate()
    }

    fun focusedBounds(): RectF? {
        if (focusedId == View.NO_ID) return null
        return keys().getOrNull(focusedId - 1)?.bounds
    }

    fun resetLayout() {
        reset()
        host.invalidate()
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val currentKeys = keys()
        if (virtualViewId == View.NO_ID) {
            return AccessibilityNodeInfo(host).apply {
                host.onInitializeAccessibilityNodeInfo(this)
                className = host.javaClass.name
                packageName = host.context.packageName
                setSource(host)
                isVisibleToUser = host.isShown
                currentKeys.indices.forEach { addChild(host, it + 1) }
            }
        }
        val key = currentKeys.getOrNull(virtualViewId - 1) ?: return null
        val info = AccessibilityNodeInfo()
        info.className = android.widget.Button::class.java.name
        info.packageName = host.context.packageName
        info.setSource(host, virtualViewId)
        info.setParent(host)
        info.text = key.label
        info.contentDescription = key.label
        info.isEnabled = host.isEnabled
        info.isClickable = true
        info.isFocusable = true
        info.isVisibleToUser = host.isShown
        info.setBoundsInParent(Rect(key.bounds.left.toInt(), key.bounds.top.toInt(), key.bounds.right.toInt(), key.bounds.bottom.toInt()))
        val location = IntArray(2)
        host.getLocationOnScreen(location)
        info.setBoundsInScreen(
            Rect(
                (key.bounds.left + location[0]).toInt(),
                (key.bounds.top + location[1]).toInt(),
                (key.bounds.right + location[0]).toInt(),
                (key.bounds.bottom + location[1]).toInt(),
            ),
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        if (key.hasAlternates) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        if (focusedId == virtualViewId) info.isAccessibilityFocused = true
        return info
    }

    override fun performAction(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        val key = keys().getOrNull(virtualViewId - 1) ?: return false
        if (!host.isShown || !host.isEnabled) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                onClick(key.key)
                host.sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                true
            }

            AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                if (!key.hasAlternates) {
                    false
                } else {
                    onLongClick(key.key)
                    host.sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                    true
                }
            }

            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focusedId == virtualViewId) {
                    true
                } else {
                    if (focusedId != View.NO_ID) sendFocusEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, focusedId)
                    focusedId = virtualViewId
                    host.invalidate()
                    sendFocusEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, virtualViewId)
                    true
                }
            }

            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedId != virtualViewId) {
                    false
                } else {
                    focusedId = View.NO_ID
                    host.invalidate()
                    sendFocusEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, virtualViewId)
                    true
                }
            }

            else -> {
                false
            }
        }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focusedId != View.NO_ID) {
            createAccessibilityNodeInfo(focusedId)
        } else {
            null
        }

    private fun keyIdAt(
        x: Float,
        y: Float,
    ): Int =
        keys().indexOfFirst { it.bounds.contains(x, y) }.let {
            if (it <
                0
            ) {
                View.NO_ID
            } else {
                it + 1
            }
        }

    private fun sendHoverEvent(
        type: Int,
        id: Int,
    ) = sendFocusEvent(type, id)

    private fun sendFocusEvent(
        type: Int,
        id: Int,
    ) {
        val event = AccessibilityEvent.obtain(type)
        event.packageName = host.context.packageName
        event.className = android.widget.Button::class.java.name
        event.setSource(host, id)
        host.parent?.requestSendAccessibilityEvent(host, event)
    }
}

private fun View.sendAccessibilityEventForVirtualView(
    id: Int,
    type: Int,
) {
    val event = AccessibilityEvent.obtain(type)
    event.packageName = context.packageName
    event.className = android.widget.Button::class.java.name
    event.setSource(this, id)
    parent?.requestSendAccessibilityEvent(this, event)
}
