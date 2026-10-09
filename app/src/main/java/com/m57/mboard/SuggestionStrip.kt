package com.m57.mboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/** Persistent, explicitly sized targets: updates never tear down a row during a tap. */
class SuggestionStrip(
    context: Context,
    private val onPick: (String, String, Long) -> Unit,
) : LinearLayout(context) {
    private val ui = BoardUi(context)

    private data class Choice(
        val word: String,
        val prefix: String,
        val editor: Long,
    )

    private val choices = arrayOfNulls<Choice>(3)
    private val pressedChoices = arrayOfNulls<Choice>(3)
    private val status =
        ui.label("Type a word for completions", 13f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    private val buttons = ArrayList<Button>(3)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)))
        repeat(3) { index ->
            val button =
                ui
                    .button("") {
                        val picked = pressedChoices[index] ?: choices[index]
                        pressedChoices[index] = null
                        picked?.let { onPick(it.word, it.prefix, it.editor) }
                    }.apply {
                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> pressedChoices[index] = choices[index]
                                android.view.MotionEvent.ACTION_CANCEL -> pressedChoices[index] = null
                            }
                            false // Native Button owns the click; this only snapshots its displayed choice.
                        }
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setAutoSizeTextTypeUniformWithConfiguration(12, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                        visibility = GONE
                    }
            buttons.add(button)
            addView(button, LayoutParams(0, ui.dp(48), 1f))
        }
    }

    fun render(
        words: List<String>,
        prefix: String,
        editor: Long,
        message: String,
        highlightFirst: Boolean = false,
    ) {
        val visible = words.take(3)
        status.text = message
        status.tooltipText = message
        status.visibility = if (visible.isEmpty()) VISIBLE else GONE
        buttons.forEachIndexed { index, button ->
            val word = visible.getOrNull(index)
            choices[index] = word?.let { Choice(it, prefix, editor) }
            val correction = highlightFirst && index == 0 && word != null
            button.text = word.orEmpty()
            button.setTextColor(if (correction) ui.controlAccent else ui.text)
            button.typeface = if (correction) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            button.contentDescription =
                word?.let {
                    when {
                        correction -> "Auto-correct to: $it"
                        prefix.isEmpty() -> "Insert common word: $it"
                        else -> "Complete word: $it"
                    }
                }
            button.visibility = if (word == null) GONE else VISIBLE
            button.isEnabled = word != null
            if (word == null) pressedChoices[index] = null
        }
    }
}
