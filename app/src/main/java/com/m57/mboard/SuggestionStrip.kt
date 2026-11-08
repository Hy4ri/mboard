package com.m57.mboard

import android.content.Context
import android.content.res.ColorStateList
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
    private val onQuickPaste: (ClipQuickPaste.Action, Long) -> Unit = { _, _ -> },
) : LinearLayout(context) {
    private val ui = BoardUi(context)

    private data class Choice(
        val word: String,
        val prefix: String,
        val editor: Long,
        val quickPaste: ClipQuickPaste.Action? = null,
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
                        picked?.let {
                            if (it.quickPaste != null) {
                                onQuickPaste(it.quickPaste, it.editor)
                            } else {
                                onPick(it.word, it.prefix, it.editor)
                            }
                        }
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
        /** Leading quick-paste actions (OTP codes, recent clips, copied images) placed before [words]. */
        quickPaste: List<ClipQuickPaste.Action> = emptyList(),
    ) {
        val pasteItems = quickPaste.take(3)
        val wordItems = words.take(3 - pasteItems.size)
        val visible = pasteItems.map { "paste:${it.label}" } + wordItems
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
            val pasteAction = pasteItems.getOrNull(index)
            val word = if (pasteAction != null) pasteAction.label else wordItems.getOrNull(index - pasteItems.size)
            choices[index] = word?.let { Choice(it, prefix, editor, pasteAction) }
            val correction = pasteAction == null && highlightFirst && index == 0 && word != null
            val emphasized = correction || pasteAction != null
            button.text = word.orEmpty()
            button.setTextColor(if (emphasized) ui.accentInk else ui.text)
            button.typeface = if (emphasized) MEDIUM else android.graphics.Typeface.DEFAULT
            button.ellipsize = if (addresses && pasteAction == null) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
            button.background = ui.keyChip(if (emphasized) ui.accentContainer else ui.surface)
            if (pasteAction != null) {
                val icon =
                    context.getDrawable(R.drawable.ic_paste)?.mutate()?.apply {
                        val size = ui.dp(Tokens.ICON_SMALL - 4)
                        setBounds(0, 0, size, size)
                        setTintList(ColorStateList.valueOf(ui.accentInk))
                    }
                button.setCompoundDrawablesRelative(icon, null, null, null)
                button.compoundDrawablePadding = ui.dp(Tokens.SPACE_1)
            } else {
                button.setCompoundDrawablesRelative(null, null, null, null)
                button.compoundDrawablePadding = 0
            }
            button.tooltipText = pasteAction?.contentDescription ?: word
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
                when {
                    pasteAction != null -> pasteAction.contentDescription
                    word == null -> null
                    correction -> "Auto-correct to: $word"
                    prefix.isEmpty() -> "Insert common word: $word"
                    else -> "Complete word: $word"
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
