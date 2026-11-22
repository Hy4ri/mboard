package com.m57.mboard

/** The text field as the controller sees it: the Android adapter wraps an InputConnection, the bench a string. */
interface EditorPort {
    val connected: Boolean

    fun hasSelection(): Boolean

    /** Up to [count] chars before the caret, or null when the editor hides its text. */
    fun textBefore(count: Int): String?

    fun textAfter(count: Int): String?

    /** Insert [text]; the caret ends after it, or before it when [caretAfter] is false. */
    fun commit(
        text: String,
        caretAfter: Boolean = true,
    ): Boolean

    /** Replace the active composing region (or insert at the caret) with [text] and keep it composing. */
    fun setComposing(text: String): Boolean

    /** Leave any active composing text as committed text without moving the caret. */
    fun finishComposing(): Boolean

    fun deleteSurrounding(
        before: Int,
        after: Int,
    ): Boolean

    fun beginBatch()

    fun endBatch()
}

/**
 * Pure-Kotlin editor logic shared by [MboardService] and the desktop bench: composing-word tracking,
 * auto-correct before a separator, picked-word replacement, the pick-owned trailing space,
 * double-space period and undo of a correction. Everything it changes goes through [EditorPort];
 * decisions come from [StripPlanner].
 */
class InputController(
    private val port: EditorPort,
    private val planner: StripPlanner,
    private val predictionTrail: PredictionTrail,
    private val rejectedCorrections: MutableSet<String>,
    private val host: Host,
) {
    interface Host {
        val language: Language

        /** Autocorrect applies only on the active letters layout. */
        val autoCorrectActive: Boolean
        val doubleSpacePeriod: Boolean

        /** Whether the word being typed should be kept as a composing span in the current field. */
        val composingEnabled: Boolean

        fun policy(): StripPlanner.StripPolicy

        fun learningAllowed(): Boolean

        fun now(): Long
    }

    /** Typed word before the cursor replaced by auto-correction; backspace right after restores it. */
    data class Correction(
        val original: String,
        val corrected: String,
        val separator: String,
    )

    var lastCorrection: Correction? = null
        private set
    private var lastSpace = 0L
    private var pickedSpaceTail: String? = null
    private var trackedToken = ""
    private var trackedContextWords = emptyList<String>()
    private val trackedTouches = mutableListOf<TouchSample?>()

    fun forgetTouchEvidence() {
        trackedToken = ""
        trackedContextWords = emptyList()
        trackedTouches.clear()
    }

    private fun matchesTrackedToken(context: StripPlanner.CaretContext): Boolean =
        trackedToken.isNotEmpty() && context.suffix.isEmpty() && context.prefix == trackedToken &&
            context.contextWords == trackedContextWords

    fun touchSamples(context: StripPlanner.CaretContext): List<TouchSample?> =
        if (matchesTrackedToken(context)) trackedTouches.toList() else emptyList()

    private var composingWord = ""
    var composingBroken = false
        private set

    val isComposing: Boolean get() = composingWord.isNotEmpty()

    private val useComposing: Boolean get() = host.composingEnabled && !composingBroken

    fun context(): StripPlanner.CaretContext = planner.context(readText())

    fun readText(): StripPlanner.EditorText {
        if (!port.connected) return StripPlanner.EditorText(null, null, hasEditor = false)
        if (port.hasSelection()) return StripPlanner.EditorText(null, null, hasSelection = true)
        val after = port.textAfter(StripPlanner.MAX_WORD_CHARS + 1)
        val before = if (after != null) port.textBefore(StripPlanner.CONTEXT_CHARS) else null
        return StripPlanner.EditorText(before, after)
    }

    /** The editor or field changed under us: nothing pending refers to the old text. */
    fun reset(clearFallback: Boolean = false) {
        forgetTouchEvidence()
        pickedSpaceTail = null
        lastCorrection = null
        composingWord = ""
        if (clearFallback) composingBroken = false
    }

    /** Commit any active composing word in place before a non-letter action, panel switch, or input end. */
    fun finishComposing() {
        if (composingWord.isNotEmpty()) {
            composingWord = ""
            port.finishComposing()
        }
    }

    fun forgetPickedSpace() {
        pickedSpaceTail = null
    }

    fun forgetSpace() {
        lastSpace = 0L
    }

    fun forgetCorrection() {
        lastCorrection = null
    }

    /**
     * Selection and candidate update from the editor. A caret jump clears undo and finishes composing;
     * if the editor drops our active composing span (candidatesStart/End == -1), fall back to commit-only
     * for the rest of this field session.
     */
    fun caretMoved(
        oldStart: Int,
        newStart: Int,
        newEnd: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1,
    ) {
        if (trackedToken.isNotEmpty() &&
            (newStart != newEnd || newStart - oldStart !in 0..trackedToken.length || !matchesTrackedToken(context()))
        ) {
            forgetTouchEvidence()
        }
        if (composingWord.isNotEmpty()) {
            val jumped = newStart != newEnd || kotlin.math.abs(newStart - oldStart) > composingWord.length + 2
            when {
                jumped -> {
                    finishComposing()
                }

                candidatesStart < 0 || candidatesEnd < 0 || candidatesStart == candidatesEnd -> {
                    // The report may be stale, so the editor can still hold our span: close it, or the next
                    // commitText would replace the letters already typed.
                    composingWord = ""
                    composingBroken = true
                    port.finishComposing()
                }
            }
        }
        val last = lastCorrection ?: return
        if (newStart != newEnd || kotlin.math.abs(newStart - oldStart) > last.corrected.length + 2) lastCorrection = null
    }

    /**
     * First step of every key: ends the double-space window and the pick-owned space for any key but
     * [keepsPick] ones (layout and shift). Returns the pick-owned space tail the key may absorb.
     */
    fun beginKey(
        key: String,
        keepsPick: Boolean,
    ): String? {
        val tail = pickedSpaceTail
        if (!keepsPick) pickedSpaceTail = null
        if (key != Keys.SPACE) lastSpace = 0L
        if (key != Keys.SHIFT && key !in SEPARATORS && key.singleOrNull()?.isLetter() != true) forgetTouchEvidence()
        return tail
    }

    /**
     * Backspace while a composing word is active removes its last code point inside the composing span
     * (or clears the span on the last character) so the editor never needs a synthetic DEL key event.
     */
    fun backspaceComposing(): Boolean {
        forgetTouchEvidence()
        if (composingWord.isEmpty()) return false
        if (port.hasSelection() || port.textBefore(composingWord.length) != composingWord) {
            finishComposing()
            return false
        }
        val cut = composingWord.offsetByCodePoints(composingWord.length, -1)
        val next = composingWord.substring(0, cut)
        return if (next.isEmpty()) {
            composingWord = ""
            if (!port.commit("")) {
                composingBroken = true
                false
            } else {
                true
            }
        } else if (port.setComposing(next)) {
            composingWord = next
            true
        } else {
            composingWord = ""
            composingBroken = true
            port.finishComposing()
            false
        }
    }

    /** Backspace straight after an auto-correction puts the typed word back and stops correcting it. */
    fun undoCorrection(): Correction? {
        val last = lastCorrection ?: return null
        lastCorrection = null
        val tail = last.corrected + last.separator
        if (port.textBefore(tail.length) != tail || port.hasSelection()) return null
        composingWord = ""
        port.beginBatch()
        try {
            if (!port.deleteSurrounding(tail.length, 0)) return null
            if (useComposing && last.original.isNotEmpty() && last.original.all(StripPlanner::wordChar)) {
                if (port.setComposing(last.original)) {
                    composingWord = last.original
                } else {
                    composingBroken = true
                    port.commit(last.original)
                }
            } else {
                port.commit(last.original)
            }
        } finally {
            port.endBatch()
        }
        rejectedCorrections += last.original
        learn(last.original, null)
        planner.rejectCorrection(last.original, last.corrected, host.language, host.learningAllowed())
        return last
    }

    /** Before a separator is committed: correct the word at the caret (or finish composing) and learn what was typed. */
    fun beforeSeparator(separator: String): Correction? {
        val before = context()
        val touches = touchSamples(before)
        forgetTouchEvidence()
        // A separator inside a word splits it; correcting the first half would mangle it.
        if (before.suffix.isEmpty()) {
            if (host.autoCorrectActive) {
                val typed = before.prefix
                val corrected = planner.autoCorrection(typed, before.sentenceStart, before.contextWords, host.policy(), touches)
                if (corrected != null && replaceBeforeCursor(typed, corrected)) {
                    lastCorrection = Correction(typed, corrected, separator)
                }
            }
            finishComposing()
            // Splitting a word inside teaches nothing: the half before the caret is not a word the user typed.
            learn(lastCorrection?.corrected ?: before.prefix, before.contextWords.lastOrNull())
        } else {
            finishComposing()
        }
        return lastCorrection
    }

    /** Space key; returns true when it turned a double space into a period. */
    fun space(): Boolean {
        finishComposing()
        val now = host.now()
        val before = port.textBefore(3).orEmpty()
        val canPeriod =
            host.doubleSpacePeriod && lastSpace != 0L && now - lastSpace < 600 &&
                before.endsWith(" ") && before.dropLast(1).lastOrNull()?.isLetterOrDigit() == true &&
                !port.hasSelection()
        if (canPeriod) {
            port.beginBatch()
            try {
                if (port.deleteSurrounding(1, 0)) port.commit(". ")
            } finally {
                port.endBatch()
            }
            lastSpace = 0L
        } else {
            port.commit(" ")
            lastSpace = now
        }
        return canPeriod
    }

    /**
     * A letter, digit, symbol or non-space separator. Only the separator we inserted on a pick is
     * absorbed, never a manually typed space; this runs after [beforeSeparator] so an explicit literal
     * pick is not corrected again.
     */
    fun character(
        text: String,
        key: String,
        pickedTail: String?,
        touch: TouchSample? = null,
    ) {
        val letter = text.singleOrNull()?.takeIf(Char::isLetter)
        val sample = touch?.takeIf { letter != null && it.key.equals(letter, ignoreCase = true) }
        val prior = if (sample != null || trackedToken.isNotEmpty()) context() else null

        fun record(success: Boolean) {
            if (prior == null) return
            if (!success || letter == null || prior.status !in TOUCHABLE_CARET || prior.suffix.isNotEmpty() ||
                trackedToken.length >= StripPlanner.MAX_WORD_CHARS
            ) {
                forgetTouchEvidence()
                return
            }
            if (!matchesTrackedToken(prior)) {
                forgetTouchEvidence()
                if (prior.prefix.isNotEmpty() || sample == null) return
                trackedContextWords = prior.contextWords
            }
            trackedToken += letter
            trackedTouches.add(sample)
        }

        val absorbSpace =
            pickedTail != null && key in SEPARATORS && key != Keys.SPACE && !port.hasSelection() &&
                port.textBefore(pickedTail.length)?.endsWith(pickedTail) == true
        if (absorbSpace) {
            forgetTouchEvidence()
            finishComposing()
            port.beginBatch()
            try {
                port.deleteSurrounding(1, 0)
                port.commit(text)
            } finally {
                port.endBatch()
            }
            return
        }
        val canCompose =
            useComposing && key !in SEPARATORS && text.isNotEmpty() && text.all(StripPlanner::wordChar) && !port.hasSelection()
        if (canCompose) {
            val context = context()
            if (context.suffix.isEmpty() && context.prefix.length + text.length <= StripPlanner.MAX_WORD_CHARS) {
                if (composingWord.isNotEmpty() && context.prefix == composingWord) {
                    val next = composingWord + text
                    if (port.setComposing(next)) {
                        composingWord = next
                        record(true)
                        return
                    }
                    composingWord = ""
                    composingBroken = true
                    port.finishComposing()
                } else if (context.prefix.isEmpty()) {
                    if (port.setComposing(text)) {
                        composingWord = text
                        record(true)
                        return
                    }
                    composingBroken = true
                } else {
                    finishComposing()
                }
            } else {
                finishComposing()
            }
        } else {
            finishComposing()
        }
        record(port.commit(text))
    }

    /**
     * Replace [expected] immediately before the caret. When [expected] is the active composing span,
     * [EditorPort.commit] replaces it directly; otherwise delete-then-commit is used and a failed
     * commit restores the word.
     */
    fun replaceBeforeCursor(
        expected: String,
        replacement: String,
    ): Boolean {
        forgetTouchEvidence()
        if (expected.isEmpty() || port.hasSelection()) return false
        if (port.textBefore(expected.length) != expected) return false
        if (composingWord.isNotEmpty() && composingWord == expected) {
            composingWord = ""
            if (port.commit(replacement)) return true
            composingBroken = true
        }
        finishComposing()
        port.beginBatch()
        try {
            if (!port.deleteSurrounding(expected.length, 0)) return false
            if (!port.commit(replacement)) {
                port.commit(expected)
                return false
            }
        } finally {
            port.endBatch()
        }
        return true
    }

    /** Replace the whole word around the caret; a word ending at the caret is the ordinary case. */
    fun replaceWord(
        context: StripPlanner.CaretContext,
        replacement: String,
    ): Boolean {
        if (context.suffix.isEmpty()) return replaceBeforeCursor(context.prefix, replacement)
        finishComposing()
        if (port.hasSelection()) return false
        if (port.textBefore(context.prefix.length) != context.prefix) return false
        if (port.textAfter(context.suffix.length) != context.suffix) return false
        port.beginBatch()
        try {
            if (!port.deleteSurrounding(context.prefix.length, context.suffix.length)) return false
            if (!port.commit(replacement)) {
                // Put the word back with the caret where it was.
                port.commit(context.prefix)
                port.commit(context.suffix, caretAfter = false)
                return false
            }
        } finally {
            port.endBatch()
        }
        return true
    }

    /**
     * Commit a tapped suggestion for the word at [context]. A new or last word gets its separator; a
     * word fixed in the middle of text keeps the spacing around it. Returns false when the editor
     * refused the edit.
     */
    fun accept(
        word: String,
        context: StripPlanner.CaretContext,
    ): Boolean {
        forgetTouchEvidence()
        val value = word.trimEnd() + if (context.suffix.isNotEmpty() && context.textAfterWord) "" else " "
        val done =
            if (context.word.isEmpty()) {
                finishComposing()
                port.commit(value)
            } else {
                replaceWord(context, value)
            }
        if (done) {
            pickedSpaceTail = value.takeIf { it.endsWith(' ') }?.takeLast(33)
            if (host.language.script == Script.LATIN && Arabizi.isReading(word)) {
                // An Arabic reading picked on the English layout is not learned into the English stores.
                predictionTrail.reset()
            } else if (' ' !in word.trim()) {
                planner.acceptCorrection(context.word, word.trim(), host.language, host.learningAllowed())
                predictionTrail.record(context.contextWords, word.trim())
                learn(word.trim(), context.contextWords.lastOrNull())
            } else {
                predictionTrail.reset()
            }
        }
        lastSpace = 0L
        return done
    }

    private fun learn(
        word: String,
        previous: String?,
    ) = planner.learn(word, previous, host.language, host.learningAllowed())

    companion object {
        private val TOUCHABLE_CARET = setOf(StripPlanner.CaretStatus.NO_MATCH, StripPlanner.CaretStatus.IDLE_HINT)
        val SEPARATORS = setOf(Keys.SPACE, ".", ",", "،", "?", "؟", "!", ";", "؛", ":")
    }
}
