package com.m57.mboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
    private val title = ui.label("Clipboard", 14f, true)
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val undoRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private var deleted: Clip? = null
    private var confirmingClear = false
    private val clear =
        ui.button("Clear unpinned") {
            if (confirmingClear) {
                deleted = null
                store.clearUnpinned()
                confirmingClear = false
            } else {
                confirmingClear = true
            }
            refresh(false)
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.background)
        setPadding(ui.dp(8), ui.dp(4), ui.dp(8), 0)
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            ui.icon(R.drawable.ic_content_paste, "Paste current system clipboard") {
                try {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    if (text != null) onPaste(text) else Toast.makeText(context, "No plain text copied", Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, "Clipboard is not available", Toast.LENGTH_SHORT).show()
                }
            },
            LayoutParams(ui.dp(48), ui.dp(48)),
        )
        header.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(48)))
        addView(header)
        addView(undoRow)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() = refresh(true)

    private fun refresh(opening: Boolean) {
        if (opening) confirmingClear = false
        val scroll = scroller.scrollY
        val clips = store.all()
        title.text = "${clips.size} clips · ${clips.count { it.pinned }} pinned"
        clear.text = if (confirmingClear) "Confirm clear" else "Clear unpinned"
        clear.isEnabled = clips.any { !it.pinned }
        clear.alpha = if (clear.isEnabled) 1f else .4f
        clear.contentDescription = if (confirmingClear) "Confirm clearing unpinned clips" else "Clear unpinned clips, keeps pinned clips"
        undoRow.removeAllViews()
        deleted?.let { clip ->
            undoRow.addView(ui.label("Clip deleted", 13f, true), LayoutParams(0, ui.dp(48), 1f))
            undoRow.addView(
                ui.button("Undo") {
                    if (!store.restore(
                            clip,
                        )
                    ) {
                        Toast.makeText(context, "Clip changed, expired, or the pin limit was reached", Toast.LENGTH_SHORT).show()
                    }
                    deleted = null
                    refresh(false)
                },
            )
        }
        list.removeAllViews()
        if (clips.isEmpty()) {
            list.addView(
                ui
                    .label(
                        "Nothing saved yet\n\nOnly clips copied while mboard is visible appear here. Sensitive clips and clips over 20,000 characters are not saved.",
                        15f,
                        true,
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24))
                    },
            )
        } else {
            clips.forEach { clip ->
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    ui.button(clip.text) { onPaste(clip.text) }.apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        contentDescription = "Paste ${if (clip.pinned) "pinned " else ""}clip: ${clip.text.take(80)}"
                    },
                    LayoutParams(0, ui.dp(64), 1f),
                )
                row.addView(
                    ui.icon(
                        if (clip.pinned) R.drawable.ic_push_pin_fill1 else R.drawable.ic_push_pin,
                        if (clip.pinned) "Unpin clip" else "Pin clip",
                    ) {
                        if (!store.togglePin(
                                clip.text,
                            )
                        ) {
                            Toast.makeText(context, "Maximum 30 pinned clips, or clip expired", Toast.LENGTH_SHORT).show()
                        }
                        confirmingClear = false
                        refresh(false)
                    },
                    LayoutParams(ui.dp(48), ui.dp(64)),
                )
                row.addView(
                    ui.icon(R.drawable.ic_close, "Delete clip") {
                        store.remove(clip.text)
                        deleted = clip
                        confirmingClear = false
                        refresh(false)
                    },
                    LayoutParams(ui.dp(48), ui.dp(64)),
                )
                list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(68)))
            }
        }
        scroller.post { scroller.scrollTo(0, scroll) }
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) deleted = null
    }
}
