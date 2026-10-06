package com.m57.mboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Scrollable list of clips: tap to paste, pin, or delete. */
class ClipboardPanel(
    context: Context,
    private val store: ClipStore,
    private val onPaste: (String) -> Unit,
) : ScrollView(context) {
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dp = resources.displayMetrics.density

    init {
        addView(list)
    }

    fun refresh() {
        list.removeAllViews()
        val clips = store.all()
        if (clips.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "Nothing copied yet"
                    gravity = Gravity.CENTER
                    setPadding(0, (32 * dp).toInt(), 0, 0)
                },
            )
            return
        }
        list.addView(
            Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Clear unpinned"
                setOnClickListener {
                    store.clearUnpinned()
                    refresh()
                }
            },
        )
        clips.forEach { clip ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                TextView(context).apply {
                    text = (if (clip.pinned) "📌 " else "") + clip.text
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 15f
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                    setOnClickListener { onPaste(clip.text) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                smallButton(if (clip.pinned) "Unpin" else "Pin") {
                    store.togglePin(clip.text)
                    refresh()
                },
            )
            row.addView(
                smallButton("✕") {
                    store.remove(clip.text)
                    refresh()
                },
            )
            list.addView(row)
        }
    }

    private fun smallButton(
        label: String,
        action: () -> Unit,
    ) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }
}
