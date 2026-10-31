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
        ui.label("Type a word for completions", Tokens.TEXT_CAPTION, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(Tokens.SPACE_3), 0, ui.dp(Tokens.SPACE_3), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    private val buttons = ArrayList<Button>(3)
    private var shown = emptyList<String>()

    init {
        orientation = HORIZONTAL
        // Chips fill the whole strip height and share the key grid's column edges (half a key gap each side).
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
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
                        // Key-style chips: same fill and edge as the letter keys, one fixed text size.
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(ui.dp(Tokens.SPACE_2), 0, ui.dp(Tokens.SPACE_2), 0)
                        includeFontPadding = false
                        textSize = Tokens.TEXT_BODY
                        background = ui.keyChip(ui.surface)
                        visibility = GONE
                    }
            buttons.add(button)
            addView(
                button,
                LayoutParams(0, ui.dp(Tokens.STRIP_HEIGHT), 1f).apply {
                    setMargins(
                        ui.dp(Tokens.KEY_GAP / 2),
                        0,
                        ui.dp(Tokens.KEY_GAP / 2),
                        0,
                    )
                },
            )
        }
    }

    fun render(
        words: List<String>,
        prefix: String,
        editor: Long,
        message: String,
        highlightFirst: Boolean = false,
        /** Saved e-mail addresses: keep the domain visible by cutting the middle of a long chip. */
        addresses: Boolean = false,
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
            button.setTextColor(if (correction) ui.accentInk else ui.text)
            button.typeface = if (correction) MEDIUM else android.graphics.Typeface.DEFAULT
            button.ellipsize = if (addresses) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
            button.background = ui.keyChip(if (correction) ui.accentContainer else ui.surface)
            button.tooltipText = word
            if (changed && word != null) {
                button.animate().cancel()
                button.alpha = ui.disabledAlpha
                button.translationY = ui.dp(Tokens.SPACE_1).toFloat()
                button
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(Tokens.MOTION_FAST)
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

    private companion object {
        val MEDIUM: android.graphics.Typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}
