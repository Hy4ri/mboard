package com.m57.mboard

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
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
    private var firstSuggestionLogged = false
    private val status =
        ui.label("Type a word for completions", 13f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    private val buttons = ArrayList<Button>(3)
    private var shown = emptyList<String>()

    init {
        orientation = HORIZONTAL
        // Chips sit low in the bar so they read as part of the keyboard, not a separate strip.
        gravity = Gravity.BOTTOM
        setPadding(ui.dp(3), 0, ui.dp(3), ui.dp(2))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
                        // Slim key-style chips: same fill and edge as the letter keys, tight padding.
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(ui.dp(6), 0, ui.dp(6), 0)
                        includeFontPadding = false
                        setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                        background = ui.keyChip(ui.surface)
                        visibility = GONE
                    }
            buttons.add(button)
            addView(button, LayoutParams(0, ui.dp(34), 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
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
        // Only a real change of words animates; rerenders with the same list stay still while typing.
        val changed = visible != shown
        shown = visible
        if (!firstSuggestionLogged && visible.isNotEmpty()) {
            firstSuggestionLogged = true
            Log.i(
                "mboard.startup",
                "first suggestion rendered: count=${visible.size} elapsedRealtime=${SystemClock.elapsedRealtime()} uptimeMillis=${SystemClock.uptimeMillis()}",
            )
        }
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
            button.background = ui.keyChip(if (correction) ui.mix(ui.surface, ui.accent, .16f) else ui.surface)
            if (changed && word != null) {
                button.animate().cancel()
                button.alpha = .35f
                button.translationY = ui.dp(3).toFloat()
                button
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(110L)
                    .start()
            }
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
