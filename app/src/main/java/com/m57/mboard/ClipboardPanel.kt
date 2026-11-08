package com.m57.mboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Sticky controls, independent pin/delete buttons, and undo without reviving expired clips. */
class ClipboardPanel(
    context: Context,
    private val store: ClipStore,
    private val onPaste: (String) -> Unit,
) : LinearLayout(context) {
    private val ui = BoardUi(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroller = ScrollView(context).apply { addView(list) }
    private val header = ui.panelHeader(context.getString(R.string.clip_panel_title))
    private val undoRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private var deleted: Clip? = null
    private var confirmingClear = false
    private val pasteCurrent =
        ui.icon(R.drawable.ic_paste, context.getString(R.string.clip_paste_current)) {
            try {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                if (text != null) {
                    onPaste(text)
                } else {
                    Toast.makeText(context, R.string.clip_no_plain_text, Toast.LENGTH_SHORT).show()
                }
            } catch (_: SecurityException) {
                Toast.makeText(context, R.string.clip_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    private val clear =
        ui.button(context.getString(R.string.clip_clear_unpinned)) {
            val clips = store.all()
            val hasUnpinned = clips.any { !it.pinned }
            if (hasUnpinned) {
                if (confirmingClear) {
                    deleted = null
                    store.clearUnpinned()
                    confirmingClear = false
                } else {
                    confirmingClear = true
                }
                refresh(false)
            }
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.background)
        header.setActions(listOf(pasteCurrent, clear))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(Tokens.PANEL_HEADER)))
        addView(undoRow)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() = refresh(true)

    private fun refresh(opening: Boolean) {
        if (opening) confirmingClear = false
        val scroll = scroller.scrollY
        val clips = store.all()
        val totalCount = clips.size
        val pinnedCount = clips.count { it.pinned }
        val res = context.resources
        val clipsLabel = res.getQuantityString(R.plurals.clip_count, totalCount, totalCount)
        val pinnedLabel = res.getQuantityString(R.plurals.clip_pinned_count, pinnedCount, pinnedCount)
        header.title.text = context.getString(R.string.clip_header_counts, clipsLabel, pinnedLabel)

        val hasUnpinned = clips.any { !it.pinned }
        clear.text = context.getString(if (confirmingClear) R.string.clip_confirm_clear else R.string.clip_clear_unpinned)
        ui.setEnabledLook(clear, hasUnpinned)
        clear.tooltipText =
            context.getString(
                if (hasUnpinned) {
                    if (confirmingClear) R.string.clip_confirm_clear_a11y else R.string.clip_clear_unpinned
                } else {
                    R.string.clip_nothing_to_clear
                },
            )
        clear.contentDescription =
            context.getString(
                if (confirmingClear) {
                    R.string.clip_confirm_clear_a11y
                } else if (hasUnpinned) {
                    R.string.clip_clear_unpinned_a11y
                } else {
                    R.string.clip_nothing_to_clear
                },
            )

        undoRow.removeAllViews()
        deleted?.let { clip ->
            undoRow.addView(
                ui.label(context.getString(R.string.clip_deleted), Tokens.TEXT_BODY, true).apply {
                    setPaddingRelative(ui.dp(Tokens.SPACE_3), 0, 0, 0)
                },
                LayoutParams(0, ui.dp(Tokens.TARGET_MIN), 1f),
            )
            undoRow.addView(
                ui.button(context.getString(R.string.action_undo)) {
                    if (!store.restore(clip)) {
                        Toast.makeText(context, R.string.clip_restore_failed, Toast.LENGTH_SHORT).show()
                    }
                    deleted = null
                    refresh(false)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(Tokens.TARGET_MIN)),
            )
        }
        list.removeAllViews()
        if (clips.isEmpty()) {
            list.addView(
                ui
                    .label(
                        context.getString(R.string.clip_empty_state),
                        Tokens.TEXT_ROW,
                        true,
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_5))
                    },
            )
        } else {
            clips.chunked(2).forEach { pair ->
                val row = LinearLayout(context)
                pair.forEach { clip -> row.addView(bubble(clip), bubbleParams()) }
                if (pair.size == 1) row.addView(View(context), bubbleParams())
                list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        scroller.post { scroller.scrollTo(0, scroll) }
    }

    private fun bubbleParams() =
        LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            val margin = ui.dp(Tokens.SPACE_1)
            setMargins(margin, margin, margin, margin)
        }

    private fun bubble(clip: Clip): LinearLayout {
        val bubble =
            LinearLayout(context).apply {
                orientation = VERTICAL
                background = ui.ripple(ui.surface)
                minimumHeight = ui.dp(BUBBLE_MIN_HEIGHT_DP)
            }

        val preview = if (clip.text.length > PREVIEW_CHARS) clip.text.take(PREVIEW_CHARS).trimEnd() + "…" else clip.text
        val textArea =
            FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                background = ui.ripple()
                contentDescription =
                    context.getString(
                        if (clip.pinned) R.string.clip_paste_pinned_a11y else R.string.clip_paste_a11y,
                        clip.text.take(80),
                    )
                setOnClickListener { onPaste(clip.text) }
                addView(
                    ui.label(preview, Tokens.TEXT_BODY).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.START or Gravity.TOP
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setPadding(
                            ui.dp(Tokens.SPACE_3),
                            ui.dp(Tokens.SPACE_2),
                            ui.dp(Tokens.SPACE_3),
                            ui.dp(Tokens.SPACE_1),
                        )
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                )
            }

        val actionRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                minimumHeight = ui.dp(Tokens.TARGET_MIN)
                setPadding(ui.dp(Tokens.SPACE_1), 0, ui.dp(Tokens.SPACE_1), 0)
            }

        val pinButton =
            ui.icon(
                if (clip.pinned) R.drawable.ic_push_pin_fill1 else R.drawable.ic_push_pin,
                context.getString(if (clip.pinned) R.string.clip_unpin else R.string.clip_pin),
            ) {
                if (!store.togglePin(clip.text)) {
                    Toast.makeText(context, R.string.clip_pin_limit, Toast.LENGTH_SHORT).show()
                }
                confirmingClear = false
                refresh(false)
            }

        val deleteButton =
            ui.icon(R.drawable.ic_close, context.getString(R.string.clip_delete)) {
                store.remove(clip.text)
                deleted = clip
                confirmingClear = false
                refresh(false)
            }

        actionRow.addView(pinButton, LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)))
        actionRow.addView(deleteButton, LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)))

        bubble.addView(textArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        bubble.addView(actionRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return bubble
    }

    private companion object {
        const val BUBBLE_MIN_HEIGHT_DP = 72
        const val PREVIEW_CHARS = 120
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) deleted = null
    }
}
