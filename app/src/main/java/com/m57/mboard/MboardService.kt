package com.m57.mboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale

class MboardService : InputMethodService() {
    private enum class Mode { KEYS, CLIPBOARD, EMOJI, EMOJI_SEARCH, CALCULATOR }

    private var mode = Mode.KEYS
    private val emojiLoader =
        java.util.concurrent.Executors
            .newSingleThreadExecutor()
    private val emojiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var destroyed = false
    private var emojiLoadFailed = false
    private var keyboard: KeyboardView? = null
    private var clipPanel: ClipboardPanel? = null
    private var emojiPanel: EmojiPanel? = null
    private var keyboardSurface: OneHandSurface? = null
    private var auxiliary: LinearLayout? = null
    private var panelBody: FrameLayout? = null
    private var toolbar: LinearLayout? = null
    private lateinit var ui: BoardUi
    private lateinit var settings: BoardSettings
    private lateinit var clips: ClipStore
    private lateinit var phrases: PhraseStore
    private lateinit var emojiHistory: EmojiHistory
    private lateinit var words: WordSuggestions
    private lateinit var userWords: UserWords

    // Backspace fires on touch-down; a swipe that follows puts that character back first.
    private var pressDeleted: String? = null
    private var swipeCursor = -1
    private var swipeBefore: String? = null
    private var swipeLength = 0
    private lateinit var clipboard: ClipboardManager
    private var language = Languages.ALL.first()
    private var visualKey = ""
    private var query = ""
    private var lastShift = 0L
    private var lastSpace = 0L
    private var privateSession = false
    private var lastClipStamp = 0L
    private var fieldPolicy = SuggestionPolicy.classify(0)
    private var editorGeneration = 0L
    private var suggestionStrip: SuggestionStrip? = null
    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshSuggestions =
        Runnable {
            if (!destroyed && mode == Mode.KEYS && isInputViewShown) updateAuxiliary()
        }
    private var defaultLayout = language.layout
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { recordClip() }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> settingsChanged(key) }

    override fun onCreate() {
        super.onCreate()
        settings = BoardSettings(this)
        language = settings.language
        clips = ClipStore(this)
        phrases = PhraseStore(this)
        emojiHistory = EmojiHistory(this)
        words = WordSuggestions(this)
        userWords = UserWords(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)
        settings.registerListener(settingsListener)
        emojiLoader.execute {
            val failed =
                try {
                    Emojis.all(applicationContext)
                    false
                } catch (_: java.io.IOException) {
                    true
                }
            if (!destroyed) {
                emojiHandler.post {
                    if (!destroyed) {
                        emojiLoadFailed = failed
                        emojiPanel?.loadFailed = failed
                        emojiPanel?.refresh()
                        if (mode == Mode.EMOJI_SEARCH) updateAuxiliary()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        emojiLoader.shutdownNow()
        emojiHandler.removeCallbacksAndMessages(null)
        keyboard?.cancelTouch()
        clipboard.removePrimaryClipChangedListener(clipListener)
        settings.unregisterListener(settingsListener)
        suggestionHandler.removeCallbacksAndMessages(null)
        userWords.save()
        words.close()
        super.onDestroy()
    }

    private fun settingsChanged(key: String?) {
        if (key == BoardSettings.LEARNED_RESET) userWords = UserWords(this)
        val previous = keyboard ?: return
        if (!isInputViewShown) return // onStartInputView applies hidden-view changes before it is shown again.
        if (visualKey != settings.visualKey()) {
            val previousMode = mode
            val previousLayout = previous.layout
            val previousShift = previous.shifted
            val previousCaps = previous.capsLocked
            val previousQuery = query
            setInputView(onCreateInputView())
            setMode(previousMode)
            query = previousQuery
            keyboard?.layout = previousLayout
            keyboard?.shifted = previousShift
            keyboard?.capsLocked = previousCaps
        }
        if (key == "capitalize" && !settings.autoCapitalize && keyboard?.capsLocked != true) keyboard?.shifted = false
        if (key == "suggestions" && settings.suggestions) {
            loadWords()
        }
        updateAuxiliary()
    }

    private fun resetEditorState() {
        editorGeneration++
        suggestionStrip?.render(emptyList(), "", editorGeneration, "No active text editor")
        suggestionHandler.removeCallbacks(refreshSuggestions)
        keyboard?.cancelTouch()
        query = ""
        lastCorrection = null
    }

    override fun onFinishInput() {
        resetEditorState()
        lastSpace = 0L
        lastShift = 0L
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode() = false

    private fun recordClip() {
        // Notifications also arrive while a living IME is hidden: never collect in the background.
        if (!isInputViewShown || fieldPolicy.password || privateSession || !settings.clipboardHistory) return
        try {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0 || clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
            // Each copy is saved once: a clip the user deleted from history is not re-added on the next open.
            val stamp = clip.description.timestamp
            if (stamp == lastClipStamp) return
            lastClipStamp = stamp
            // Do not resolve arbitrary content URIs to read their contents.
            val text = clip.getItemAt(0).text?.toString() ?: return
            clips.add(text)
            if (mode == Mode.CLIPBOARD) clipPanel?.refresh()
        } catch (_: SecurityException) {
            // Access may be revoked between the notification and the read.
        }
    }

    override fun onCreateInputView(): View {
        ui = BoardUi(this)
        visualKey = settings.visualKey()
        val kv = KeyboardView(this, ::onKey, ::toggleLanguage, ::moveCursor, ::deleteSwipe, ::deleteSwipeEnd)
        keyboard = kv
        kv.language = language
        kv.layout = defaultLayout
        clipPanel = ClipboardPanel(this, clips, ::paste)
        emojiPanel = EmojiPanel(this, { setMode(Mode.EMOJI_SEARCH) }, ::pasteEmoji).apply { loadFailed = emojiLoadFailed }
        toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        auxiliary =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        suggestionStrip = SuggestionStrip(this, ::acceptSuggestion)
        val body = FrameLayout(this)
        panelBody = body
        val fill = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        keyboardSurface =
            OneHandSurface(
                this,
                kv,
                settings.oneHandMode,
                settings.dialPadEdge,
                settings.emojiEdge,
                ::onKey,
                { if (fieldPolicy.password) emptyList() else emojiHistory.recent() },
                ::pasteEmoji,
                { setMode(Mode.EMOJI) },
            ) { settings.oneHandMode = it }
        body.addView(keyboardSurface, fill)
        body.addView(clipPanel, FrameLayout.LayoutParams(fill))
        body.addView(emojiPanel, FrameLayout.LayoutParams(fill))
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setBackgroundColor(ui.background)
                addView(
                    LinearLayout(this@MboardService).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            HorizontalScrollView(this@MboardService).apply {
                                isHorizontalScrollBarEnabled = false
                                addView(toolbar)
                            },
                            LinearLayout.LayoutParams(0, ui.dp(48), 1f),
                        )
                        addView(
                            ui.icon(R.drawable.ic_settings, "Settings") { openSettings() },
                            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
                        )
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)),
                )
                addView(auxiliary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(AUX_DP)))
                addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kv.keysHeight))
                setOnApplyWindowInsetsListener { target, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.navigationBars())
                    target.setPadding(bars.left, 0, bars.right, bars.bottom)
                    insets
                }
            }
        setMode(Mode.KEYS)
        return root
    }

    private fun rebuildToolbar() {
        val bar = toolbar ?: return
        bar.removeAllViews()

        fun icon(
            res: Int,
            label: String,
            enabled: Boolean = true,
            action: () -> Unit,
        ) {
            bar.addView(
                ui.icon(res, label, action).apply {
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.35f
                },
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
            )
        }
        val pasteEnabled = !fieldPolicy.password
        icon(R.drawable.ic_paste, "Paste", !fieldPolicy.password) { pasteClipboard() }
        icon(R.drawable.ic_content_paste, "Clipboard history", !fieldPolicy.password) { togglePanel(Mode.CLIPBOARD) }
        icon(R.drawable.ic_mood, "Search emoji", !fieldPolicy.password) { togglePanel(Mode.EMOJI_SEARCH) }
        icon(R.drawable.ic_calculate, "Calculator", !fieldPolicy.password) { togglePanel(Mode.CALCULATOR) }
        if (mode != Mode.KEYS) icon(R.drawable.ic_keyboard, "Return to keyboard") { setMode(Mode.KEYS) }
        val privacyLabel =
            when {
                fieldPolicy.password -> "Shield on for password field. No history recording; static suggestions available"
                privateSession -> "Shield on. History recording paused; suggestions available. Tap to turn off"
                else -> "Shield off. Tap to pause history recording without disabling suggestions"
            }
        bar.addView(
            ui
                .icon(R.drawable.ic_privacy, privacyLabel) {
                    if (!fieldPolicy.password) {
                        privateSession = !privateSession
                        emojiPanel?.recordingEnabled = !privateSession && !fieldPolicy.password
                        if (settings.suggestions) loadWords()
                        rebuildToolbar()
                        updateAuxiliary()
                    }
                }.apply {
                    isEnabled = !fieldPolicy.password
                    isSelected = privateSession || fieldPolicy.password
                    imageTintList =
                        android.content.res.ColorStateList
                            .valueOf(if (isSelected) ui.onAccent else ui.text)
                    background = ui.ripple(if (isSelected) ui.accent else android.graphics.Color.TRANSPARENT)
                    alpha = if (isEnabled) 1f else .4f
                },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
        )
        icon(R.drawable.ic_keyboard_hide, "Hide keyboard") { requestHideSelf(0) }
    }

    private fun openSettings() = startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun togglePanel(next: Mode) = setMode(if (mode == next) Mode.KEYS else next)

    private fun setMode(next: Mode) {
        suggestionHandler.removeCallbacks(refreshSuggestions)
        keyboard?.cancelTouch()
        if (fieldPolicy.password && next != Mode.KEYS) return
        val previous = mode
        mode = next
        lastSpace = 0L
        if (next == Mode.EMOJI_SEARCH || next == Mode.CALCULATOR) {
            if (previous != next) query = ""
            keyboard?.layout = if (next == Mode.CALCULATOR) Layout.CALC else language.layout
            keyboard?.shifted = false
            keyboard?.capsLocked = false
        } else if (previous == Mode.EMOJI_SEARCH || previous == Mode.CALCULATOR) {
            keyboard?.layout = defaultLayout
        }
        keyboard?.enterLabel =
            when (next) {
                Mode.CALCULATOR -> "="
                Mode.EMOJI_SEARCH -> "Pick"
                else -> enterLabel(currentInputEditorInfo)
            }
        val keysVisible = next in setOf(Mode.KEYS, Mode.EMOJI_SEARCH, Mode.CALCULATOR)
        // Clipboard and emoji drop the label bar and take its height, so the window size never changes.
        val clipboardOpen = next == Mode.CLIPBOARD || next == Mode.EMOJI
        auxiliary?.visibility = if (clipboardOpen) View.GONE else View.VISIBLE
        panelBody?.let { body ->
            val height = (keyboard?.keysHeight ?: 0) + if (clipboardOpen) ui.dp(AUX_DP) else 0
            if (body.layoutParams != null && body.layoutParams.height != height) {
                body.layoutParams = body.layoutParams.apply { this.height = height }
            }
        }
        keyboardSurface?.visibility = if (keysVisible) View.VISIBLE else View.INVISIBLE
        keyboard?.visibility = if (keysVisible) View.VISIBLE else View.INVISIBLE
        clipPanel?.visibility = if (next == Mode.CLIPBOARD) View.VISIBLE else View.GONE
        emojiPanel?.visibility = if (next == Mode.EMOJI) View.VISIBLE else View.GONE
        if (next == Mode.CLIPBOARD) clipPanel?.refresh()
        emojiPanel?.recordingEnabled = !privateSession && !fieldPolicy.password
        if (next == Mode.EMOJI) emojiPanel?.refresh()
        rebuildToolbar()
        updateAuxiliary()
    }

    override fun onStartInput(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(info, restarting)
        editorGeneration++
        lastCorrection = null
        if (!restarting) rejectedCorrections.clear()
        fieldPolicy = SuggestionPolicy.classify(info?.inputType ?: 0)
        defaultLayout = if (fieldPolicy.numeric) Layout.NUMBER else language.layout
        lastSpace = 0L
        lastShift = 0L
        query = ""
        keyboard?.cancelTouch()
        if (toolbar != null) setMode(Mode.KEYS)
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        if (visualKey != settings.visualKey()) setInputView(onCreateInputView())
        keyboard?.layout = defaultLayout
        keyboard?.capsLocked = false
        keyboard?.shifted = false
        keyboard?.enterLabel = enterLabel(info)
        setMode(Mode.KEYS)
        updateCapitalization()
        if (settings.suggestions) loadWords()
        // Copying usually happens while the keyboard is hidden: pick that clip up once mboard is on screen.
        suggestionHandler.post { if (!destroyed) recordClip() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        userWords.save()
        resetEditorState()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // A caret jump (tap elsewhere) means backspace no longer refers to the correction.
        lastCorrection?.let { last ->
            if (newSelStart != newSelEnd || kotlin.math.abs(newSelStart - oldSelStart) > last.corrected.length + 2) lastCorrection = null
        }
        if (mode == Mode.KEYS) {
            updateCapitalization()
            updateAuxiliary()
        }
    }

    private fun loadWords() {
        val generation = editorGeneration
        val requestedLanguage = language
        words.load(requestedLanguage) {
            if (!destroyed && generation == editorGeneration && requestedLanguage == language &&
                mode == Mode.KEYS && isInputViewShown
            ) {
                updateAuxiliary()
            }
        }
        updateAuxiliary()
    }

    private fun enterLabel(info: EditorInfo?): String {
        if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Keys.ENTER
        info.actionLabel
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.take(8) }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> Keys.ENTER
        }
    }

    private fun toggleLanguage() {
        language = Languages.next(language)
        settings.language = language
        if (defaultLayout != Layout.NUMBER) defaultLayout = language.layout
        keyboard?.capsLocked = false
        keyboard?.shifted = false
        keyboard?.language = language
        keyboard?.layout =
            when (mode) {
                Mode.CALCULATOR -> Layout.CALC
                Mode.EMOJI_SEARCH -> language.layout
                else -> defaultLayout
            }
        if (mode == Mode.KEYS && settings.suggestions) loadWords()
        updateAuxiliary()
    }

    private fun updateCapitalization() {
        val kv = keyboard ?: return
        if (mode != Mode.KEYS || kv.layout != language.layout || !language.cased || kv.capsLocked ||
            fieldPolicy.password
        ) {
            return
        }
        if (settings.autoCapitalize) {
            kv.shifted =
                (currentInputConnection?.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) ?: 0) != 0
        }
    }

    private data class PrefixContext(
        val prefix: String,
        val status: String,
        val commonEligible: Boolean = false,
        val contextWords: List<String> = emptyList(),
        val sentenceStart: Boolean = false,
        /** Word letters right after the caret: the caret sits inside or at the start of an existing word. */
        val suffix: String = "",
        /** Whether anything follows the word, so a picked suggestion needs no trailing space. */
        val textAfterWord: Boolean = false,
    ) {
        /** The whole word the caret touches; suggestions are for it and a pick replaces all of it. */
        val word get() = prefix + suffix
    }

    private fun prefixContext(): PrefixContext {
        val ic = currentInputConnection ?: return PrefixContext("", "No active text editor")
        if (!ic.getSelectedText(0).isNullOrEmpty()) return PrefixContext("", "Selection active · completions paused")
        val after = ic.getTextAfterCursor(65, 0)?.toString() ?: return PrefixContext("", "App does not expose text for completions")
        val before =
            ic.getTextBeforeCursor(65, 0)?.toString()
                ?: return PrefixContext("", "App does not expose text for completions")

        val result = before.takeLastWhile(::wordChar)
        val suffix = after.takeWhile(::wordChar)
        if (result.length + suffix.length > 64) return PrefixContext("", "Word is too long for completions")
        val idle = result.isEmpty() && before.lastOrNull()?.isWhitespace() != false && after.firstOrNull()?.isWhitespace() != false
        val beforeWord = before.dropLast(result.length).trimEnd(' ', '\t')
        val sentenceStart = beforeWord.isEmpty() || beforeWord.last() in ".!?؟\n"
        // Only the current sentence contributes context; punctuation and line breaks reset it.
        val recent =
            before
                .dropLast(result.length)
                .takeLastWhile { wordChar(it) || it == ' ' || it == '\t' }
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .takeLast(8)
        return PrefixContext(
            result,
            if (result.isEmpty() && suffix.isEmpty()) "Type a word · completions appear while typing" else "No matching completion",
            idle,
            recent,
            sentenceStart,
            suffix,
            after.length > suffix.length,
        )
    }

    private fun renderSuggestions(bar: LinearLayout) {
        val strip = suggestionStrip ?: return
        if (bar.childCount != 1 || bar.getChildAt(0) !== strip) {
            bar.removeAllViews()
            bar.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val blocked = if (!settings.suggestions) "Word suggestions off · enable them in Settings" else null
        if (blocked != null) {
            // Email/URL fields skip dictionary words but still offer the user's saved email addresses.
            val addresses = savedAddresses()
            if (addresses != null && addresses.second.isNotEmpty()) {
                strip.render(addresses.second, addresses.first, editorGeneration, blocked)
                return
            }
            strip.render(emptyList(), "", editorGeneration, blocked)
            return
        }
        val state = words.state(language)
        if (state != WordSuggestions.State.READY) {
            val message =
                when (state) {
                    WordSuggestions.State.LOADING -> "Loading ${language.name} dictionary…"
                    WordSuggestions.State.FAILED -> "Dictionary unavailable · reopen keyboard to retry"
                    WordSuggestions.State.CLOSED -> "Word suggestions unavailable"
                    else -> "Preparing word completions…"
                }
            strip.render(emptyList(), "", editorGeneration, message)
            return
        }
        if (fieldPolicy.password) {
            strip.render(words.commonWords(language), "", editorGeneration, "Static suggestions · shield on")
            return
        }
        val addresses = savedAddresses()
        if (addresses != null && addresses.second.isNotEmpty()) {
            strip.render(addresses.second, addresses.first, editorGeneration, "Saved addresses")
            return
        }
        val context = prefixContext()
        if (context.commonEligible) {
            val predicted =
                words.predict(context.contextWords, language, settings.dialectEnabled(language.dialect), learnedNext(context.contextWords))
            strip.render(
                predicted.map { matchCase(it, "") },
                "",
                editorGeneration,
                context.status,
            )
            return
        }
        // The caret may sit inside an earlier word: suggest for that whole word, not just the part before it.
        val typed = context.word
        val editingInside = context.suffix.isNotEmpty()
        // Space only auto-corrects at a word's end, so inside a word the correction is offered, not highlighted.
        val autoCorrection = autoCorrection(typed, context.sentenceStart, context.contextWords)
        val hint =
            if (autoCorrection == null && settings.autoCorrect && !userWords.knows(typed, language)) {
                words.likelyIntended(typed, language)?.let { matchCase(it, typed) }
            } else {
                null
            }
        val leading = autoCorrection ?: hint
        // A saved phrase starting with the typed word follows the correction; tapping it replaces the word.
        val phrase =
            if (fieldPolicy.password || typed.length < 2) {
                null
            } else {
                phrases.all().firstOrNull { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
            }
        // Learned words are ranked with the dictionary by how likely they are here.
        val ranked =
            words
                .rank(
                    typed,
                    context.contextWords,
                    language,
                    settings.dialectEnabled(language.dialect),
                    learnedNext(context.contextWords),
                    if (privateSession) emptyMap() else userWords.completions(typed, language),
                ).map { matchCase(it, typed) }
        val others =
            (listOfNotNull(leading, phrase) + ranked)
                .filter { it != typed }
                .distinctBy { it.lowercase(Locale.ROOT) }
        // The word as typed always keeps a slot, right after a correction or hint, so it can be kept with one tap
        // (tapping it finishes the word with a space and no correction). Single letters are left out.
        val keepAt = if (leading != null && leading != typed) 1 else 0
        val wordChoices = if (typed.length >= 2) others.take(keepAt) + typed + others.drop(keepAt) else others
        // A matching emoji takes the last slot so the correction and best completion stay put.
        val emoji = if (settings.emojiSuggestions && !fieldPolicy.password) Emojis.forWord(typed) else null
        val values = if (emoji == null) wordChoices else wordChoices.take(2) + emoji
        strip.render(values, typed, editorGeneration, context.status, highlightFirst = autoCorrection != null && !editingInside)
    }

    private fun autoCorrection(
        typed: String,
        wordStart: Boolean,
        contextWords: List<String> = emptyList(),
    ): String? {
        if (!settings.autoCorrect || typed.isEmpty() || typed in rejectedCorrections || fieldPolicy.password) return null
        if (userWords.knows(typed, language)) return null
        if (words.state(language) != WordSuggestions.State.READY) return null
        if (language.cased && typed.first().isUpperCase() && !wordStart && typed != "i") return null
        val correction =
            words
                .correction(typed, language, contextWords, settings.dialectEnabled(language.dialect), learnedNext(contextWords))
                ?.let { matchCase(it, typed) } ?: return null
        return if (correction == typed) null else correction
    }

    private val separators = setOf(Keys.SPACE, ".", ",", "،", "?", "؟", "!", ";", "؛", ":")

    /** Typed word before the cursor replaced by auto-correction; backspace right after restores it. */
    private data class Correction(
        val original: String,
        val corrected: String,
        val separator: String,
    )

    private var lastCorrection: Correction? = null
    private val rejectedCorrections = HashSet<String>()

    /**
     * Replace [expected] immediately before the caret. Editors such as Instagram ignore or defer
     * setSelection inside an IME edit, so delete-then-commit is used; a failed commit restores the word.
     */
    private fun replaceBeforeCursor(
        ic: InputConnection,
        expected: String,
        replacement: String,
    ): Boolean {
        if (expected.isEmpty() || !ic.getSelectedText(0).isNullOrEmpty()) return false
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(expected.length, 0)) return false
            if (!ic.commitText(replacement, 1)) {
                ic.commitText(expected, 1)
                return false
            }
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    /** Replace the whole word around the caret; a word ending at the caret is the ordinary case. */
    private fun replaceWord(
        ic: InputConnection,
        context: PrefixContext,
        replacement: String,
    ): Boolean {
        if (context.suffix.isEmpty()) return replaceBeforeCursor(ic, context.prefix, replacement)
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        if (ic.getTextBeforeCursor(context.prefix.length, 0)?.toString() != context.prefix) return false
        if (ic.getTextAfterCursor(context.suffix.length, 0)?.toString() != context.suffix) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(context.prefix.length, context.suffix.length)) return false
            if (!ic.commitText(replacement, 1)) {
                // Put the word back with the caret where it was.
                ic.commitText(context.prefix, 1)
                ic.commitText(context.suffix, 0)
                return false
            }
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    private fun autoCorrectBefore(
        ic: InputConnection,
        separator: String,
    ) {
        val kv = keyboard ?: return
        if (mode != Mode.KEYS || kv.layout != language.layout) return
        val context = prefixContext()
        // A separator inside a word splits it; correcting the first half would mangle it.
        if (context.suffix.isNotEmpty()) return
        val typed = context.prefix
        val corrected = autoCorrection(typed, context.sentenceStart, context.contextWords) ?: return
        if (replaceBeforeCursor(ic, typed, corrected)) lastCorrection = Correction(typed, corrected, separator)
    }

    /** Backspace straight after an auto-correction puts the typed word back and stops correcting it. */
    private fun undoCorrection(ic: InputConnection): Boolean {
        val last = lastCorrection ?: return false
        lastCorrection = null
        val tail = last.corrected + last.separator
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail || !ic.getSelectedText(0).isNullOrEmpty()) return false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(tail.length, 0)) return false
            ic.commitText(last.original, 1)
        } finally {
            ic.endBatchEdit()
        }
        rejectedCorrections += last.original
        learn(last.original, null)
        return true
    }

    private fun updateAuxiliary() {
        val bar = auxiliary ?: return
        if (mode == Mode.KEYS) {
            renderSuggestions(bar)
            return
        }
        bar.removeAllViews()
        when (mode) {
            Mode.CALCULATOR -> {
                val answer = Calculator.calculate(query)
                bar.addView(
                    ui.label(query.ifBlank { "Type a calculation" }, 14f, true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.START
                        tooltipText = query
                        setPadding(ui.dp(12), 0, ui.dp(8), 0)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                bar.addView(
                    ui.button(answer?.let { "= $it" } ?: "No result", primary = answer != null) { answer?.let(::paste) }.apply {
                        isEnabled = answer != null
                        alpha = if (answer == null) .5f else 1f
                        maxLines = 1
                        contentDescription = answer?.let { "Insert result $it" } ?: "No valid calculation result"
                        tooltipText = answer
                        setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }

            Mode.EMOJI_SEARCH -> {
                bar.addView(
                    emojiSearchField(),
                    LinearLayout.LayoutParams(ui.dp(150), ui.dp(32)).apply { setMargins(ui.dp(8), 0, ui.dp(4), 0) },
                )
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val matches = emojiSearchResults()
                if (matches.isEmpty()) {
                    val status =
                        when {
                            emojiLoadFailed -> "Emoji unavailable"
                            !Emojis.ready -> "Loading emoji…"
                            query.isNotBlank() -> "No matches"
                            else -> "Type to search"
                        }
                    row.addView(ui.label(status, 14f, true).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) })
                }
                matches.forEachIndexed { index, emoji ->
                    row.addView(
                        ui.button(emoji.text) { pasteEmoji(emoji.text) }.apply {
                            textSize = 24f
                            setPadding(ui.dp(6), 0, ui.dp(6), 0)
                            includeFontPadding = false
                            // The first result is what the Pick key inserts, so it is visibly selected.
                            background =
                                ui.ripple(if (index == 0 && query.isNotBlank()) ui.modifier else android.graphics.Color.TRANSPARENT)
                            contentDescription = emoji.name
                            setOnLongClickListener {
                                if (!privateSession) emojiHistory.toggleFavorite(emoji.text)
                                message(
                                    if (privateSession) {
                                        "Favorites paused in private mode"
                                    } else if (emojiHistory.isFavorite(emoji.text)) {
                                        "Added to favorites"
                                    } else {
                                        "Removed from favorites"
                                    },
                                )
                                true
                            }
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
                    )
                }
                bar.addView(
                    HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(row)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                bar.addView(
                    ui.icon(R.drawable.ic_mood, "Browse all emoji") { setMode(Mode.EMOJI) },
                    LinearLayout.LayoutParams(ui.dp(44), LinearLayout.LayoutParams.MATCH_PARENT),
                )
            }

            Mode.KEYS -> {
                Unit
            }

            // handled by the persistent strip above

            else -> {
                bar.addView(
                    ui
                        .label(
                            when (mode) {
                                Mode.CLIPBOARD -> "Clipboard · saved on this device"
                                Mode.EMOJI -> "Emoji · hold an emoji to favorite it"
                                else -> ""
                            },
                            14f,
                            true,
                        ).apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) },
                )
            }
        }
    }

    /** In email fields: the text typed so far and saved email phrases that complete it. */
    private fun savedAddresses(): Pair<String, List<String>>? {
        if (fieldPolicy.reason != SuggestionPolicy.Reason.ADDRESS || !settings.suggestions) return null
        // Browser omniboxes use URI variation: they need word predictions, not an email-only strip.
        val variation = (currentInputEditorInfo?.inputType ?: 0) and android.text.InputType.TYPE_MASK_VARIATION
        if (variation != android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) {
            return null
        }
        val ic = currentInputConnection ?: return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        val typed = ic.getTextBeforeCursor(100, 0)?.toString()?.takeLastWhile { !it.isWhitespace() && it != ',' } ?: return null
        val matches =
            phrases
                .all()
                .filter { '@' in it && it.none(Char::isWhitespace) && it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
                .take(3)
        return typed to matches
    }

    /** Recent emoji while the query is empty, ranked matches once typing starts. */
    private fun emojiSearchResults(): List<Emoji> {
        if (query.isNotBlank()) return Emojis.search(this, query).take(40)
        if (privateSession) return emptyList()
        val catalogue = Emojis.cached().associateBy { it.text }
        return emojiHistory.recent().take(40).map { catalogue[it] ?: Emoji(it, it) }
    }

    /** Looks like a real search box: icon, typed text with a caret, and a clear button. */
    private fun emojiSearchField(): LinearLayout {
        val field =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.shape(ui.surface, 19)
                setPadding(ui.dp(10), 0, ui.dp(2), 0)
            }
        field.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList =
                    android.content.res.ColorStateList
                        .valueOf(ui.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)),
        )
        val text =
            if (query.isEmpty()) {
                android.text.SpannableString("Search emoji")
            } else {
                android.text.SpannableString("$query|").apply {
                    setSpan(android.text.style.ForegroundColorSpan(ui.accent), query.length, query.length + 1, 0)
                }
            }
        field.addView(
            ui.label("", 15f, query.isEmpty()).apply {
                setText(text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                contentDescription = if (query.isEmpty()) "Emoji search, empty" else "Emoji search: $query"
                setPadding(ui.dp(6), 0, ui.dp(4), 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (query.isNotEmpty()) {
            field.addView(
                ui
                    .icon(R.drawable.ic_close, "Clear search") {
                        query = ""
                        updateAuxiliary()
                    }.apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        minimumWidth = 0
                        minimumHeight = 0
                        setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                    },
                LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)),
            )
        }
        return field
    }

    /** Words the user has typed after the last context word; nothing in a private session. */
    private fun learnedNext(contextWords: List<String>): Map<String, Int> =
        if (privateSession) emptyMap() else contextWords.lastOrNull()?.let { userWords.next(it, language) }.orEmpty()

    private fun matchCase(
        word: String,
        source: String,
    ): String = WordDictionary.displayWord(word, source, language, keyboard?.shifted == true, keyboard?.capsLocked == true)

    private fun acceptSuggestion(
        word: String,
        expected: String,
        generation: Long,
    ) {
        if (generation != editorGeneration || mode != Mode.KEYS || !settings.suggestions) return
        if (fieldPolicy.password) {
            if (expected.isEmpty() && word in words.commonWords(language)) currentInputConnection?.commitText(word, 1)
            return
        }
        if (fieldPolicy.reason == SuggestionPolicy.Reason.ADDRESS) {
            val ic = currentInputConnection ?: return
            val addresses = savedAddresses()
            if (addresses != null && word in addresses.second) {
                if (addresses.first != expected) return updateAuxiliary()
                val done = if (expected.isEmpty()) ic.commitText(word, 1) else replaceBeforeCursor(ic, expected, word)
                if (!done) message("This app did not accept the address")
                updateAuxiliary()
                return
            }
        }
        val ic = currentInputConnection ?: return
        val context = prefixContext()
        if (context.word != expected || (expected.isEmpty() && !context.commonEligible)) {
            updateAuxiliary()
            return
        }
        // An emoji replaces the typed word: "fire" → "🔥 ".
        if (expected.isNotEmpty() && Emojis.isEmoji(word)) {
            if (replaceWord(ic, context, word + if (context.textAfterWord) "" else " ")) {
                if (!privateSession && !fieldPolicy.password) emojiHistory.record(word)
            } else {
                message("This app did not accept the emoji")
            }
            lastSpace = 0L
            updateAuxiliary()
            return
        }
        // A new or last word gets its separator; a word fixed in the middle of text keeps the spacing around it.
        val value = word.trimEnd() + if (context.suffix.isNotEmpty() && context.textAfterWord) "" else " "
        val done = if (expected.isEmpty()) ic.commitText(value, 1) else replaceWord(ic, context, value)
        if (!done) {
            message("This app did not accept the completion")
        } else {
            if (' ' !in word.trim()) learn(word.trim(), context.contextWords.lastOrNull())
            if (keyboard?.capsLocked != true) keyboard?.shifted = false
        }
        lastSpace = 0L
        updateAuxiliary()
        // Some editors publish surrounding text after commitText returns, just like ordinary typing.
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.postDelayed(refreshSuggestions, 30L)
    }

    private fun pasteClipboard() {
        if (fieldPolicy.password) return
        val raw =
            try {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
            } catch (_: SecurityException) {
                null
            }
        if (mode == Mode.CALCULATOR || mode == Mode.EMOJI_SEARCH) {
            if (!raw.isNullOrEmpty()) {
                if (query.length + raw.length <= 200) query += raw
                updateAuxiliary()
            } else {
                message("Nothing to paste")
            }
            return
        }
        if (!raw.isNullOrEmpty()) {
            paste(raw)
            return
        }
        val ic = currentInputConnection
        if (ic?.performContextMenuAction(android.R.id.paste) == true) {
            lastSpace = 0L
            if (mode == Mode.KEYS) updateAuxiliary()
        } else {
            message("Nothing to paste")
        }
    }

    private fun paste(text: String) {
        if (currentInputConnection?.commitText(text, 1) == false) message("This app did not accept the text")
        lastSpace = 0L
        if (mode == Mode.KEYS) updateAuxiliary()
    }

    private fun pasteEmoji(text: String) {
        if (currentInputConnection?.commitText(text, 1) == true && !privateSession && !fieldPolicy.password) emojiHistory.record(text)
        lastSpace = 0L
    }

    private fun onInternalKey(key: String) {
        val kv = keyboard ?: return
        when (key) {
            Keys.BACKSPACE -> {
                if (query.isNotEmpty()) query = query.substring(0, query.offsetByCodePoints(query.length, -1))
            }

            Keys.DELETE_WORD -> {
                query = query.dropLast(wordDeleteLength(query))
            }

            Keys.ENTER -> {
                if (mode ==
                    Mode.CALCULATOR
                ) {
                    Calculator.calculate(query)?.let(::paste)
                } else {
                    Emojis.search(this, query).firstOrNull()?.let { pasteEmoji(it.text) }
                }
            }

            Keys.SHIFT -> {
                kv.shifted = !kv.shifted
            }

            Keys.GLOBE -> {
                toggleLanguage()
            }

            Keys.SYMBOLS -> {
                kv.layout = Layout.SYMBOLS
            }

            Keys.MORE_SYMBOLS -> {
                kv.layout = Layout.SYMBOLS_MORE
            }

            Keys.PAD -> {
                kv.layout = Layout.NUMPAD
            }

            Keys.LETTERS -> {
                kv.layout = language.layout
            }

            else -> {
                if (query.length + key.length <= 200) {
                    query += if (kv.shifted) key.uppercase(Locale.ROOT) else key
                    kv.shifted = false
                }
            }
        }
        updateAuxiliary()
    }

    private fun onKey(key: String) {
        if (mode == Mode.EMOJI_SEARCH || mode == Mode.CALCULATOR) return onInternalKey(key)
        val ic = currentInputConnection ?: return
        val kv = keyboard ?: return
        if (key != Keys.SPACE) lastSpace = 0L
        pressDeleted = null
        if (key == Keys.BACKSPACE && ic.getSelectedText(0).isNullOrEmpty() && undoCorrection(ic)) {
            lastShift = 0L
            updateAuxiliary()
            return
        }
        if (key != Keys.SHIFT) lastCorrection = null
        if (key in separators) {
            val before = prefixContext()
            autoCorrectBefore(ic, key)
            // Splitting a word inside teaches nothing: the half before the caret is not a word the user typed.
            if (before.suffix.isEmpty()) learn(lastCorrection?.corrected ?: before.prefix, before.contextWords.lastOrNull())
        }
        when (key) {
            Keys.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                when {
                    kv.capsLocked -> {
                        kv.capsLocked = false
                        kv.shifted = false
                    }

                    lastShift != 0L && now - lastShift <= 350 -> {
                        kv.capsLocked = true
                        kv.shifted = true
                    }

                    else -> {
                        kv.shifted = !kv.shifted
                    }
                }
                lastShift = if (kv.capsLocked) 0L else now
            }

            Keys.BACKSPACE -> {
                pressDeleted = null
                if (!ic.getSelectedText(0).isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(2, 0)?.toString()
                    if (!before.isNullOrEmpty()) {
                        pressDeleted = before.substring(before.offsetByCodePoints(before.length, -1))
                    }
                    sendKey(ic, KeyEvent.KEYCODE_DEL)
                }
            }

            Keys.DELETE_WORD -> {
                if (!ic.getSelectedText(0).isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(64, 0)?.toString()
                    val count = before?.let(::wordDeleteLength) ?: 0
                    // Editors that hide their text still get a plain backspace.
                    when {
                        count > 0 -> ic.deleteSurroundingText(count, 0)
                        before == null -> sendKey(ic, KeyEvent.KEYCODE_DEL)
                    }
                }
            }

            Keys.ENTER -> {
                val info = currentInputEditorInfo
                val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!noAction && info?.actionLabel != null) {
                    if (!ic.performEditorAction(info.actionId)) message("This app did not handle the action")
                } else if (!noAction && action > EditorInfo.IME_ACTION_NONE) {
                    if (!ic.performEditorAction(action)) message("This app did not handle the action")
                } else {
                    ic.commitText("\n", 1)
                }
            }

            Keys.GLOBE -> {
                toggleLanguage()
            }

            Keys.SYMBOLS -> {
                kv.layout = Layout.SYMBOLS
            }

            Keys.MORE_SYMBOLS -> {
                kv.layout = Layout.SYMBOLS_MORE
            }

            Keys.PAD -> {
                kv.layout = Layout.NUMPAD
            }

            Keys.LETTERS -> {
                kv.layout = language.layout
            }

            Keys.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
                val canPeriod =
                    settings.doubleSpacePeriod && !fieldPolicy.password && lastSpace != 0L &&
                        now - lastSpace < 600 &&
                        before.endsWith(
                            " ",
                        ) && before.dropLast(1).lastOrNull()?.let { it.isLetterOrDigit() } == true &&
                        ic.getSelectedText(0).isNullOrEmpty()
                if (canPeriod) {
                    ic.beginBatchEdit()
                    try {
                        if (ic.deleteSurroundingText(1, 0)) ic.commitText(". ", 1)
                    } finally {
                        ic.endBatchEdit()
                    }
                    lastSpace = 0L
                } else {
                    ic.commitText(" ", 1)
                    lastSpace = now
                }
            }

            else -> {
                ic.commitText(if (kv.shifted) key.uppercase(Locale.ROOT) else key, 1)
                if (!kv.capsLocked) kv.shifted = false
            }
        }
        if (key != Keys.SHIFT) lastShift = 0L
        updateAuxiliary()
        // Re-read once after editors publish an asynchronously committed surrounding-text update.
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.postDelayed(refreshSuggestions, 30L)
    }

    /** Learns only in ordinary fields, with learning on, outside the shield and apps' no-learning flag. */
    private fun learn(
        word: String,
        previous: String?,
    ) {
        if (word.isEmpty() || !settings.learnWords || privateSession || fieldPolicy.reason != SuggestionPolicy.Reason.AVAILABLE) return
        if ((currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return
        if (keyboard?.layout != language.layout || mode != Mode.KEYS) return
        userWords.record(word, previous, language)
    }

    /** Slide left from backspace: [steps] whole words get selected so the user sees what release deletes. */
    private fun deleteSwipe(steps: Int) {
        val ic = currentInputConnection ?: return
        if (swipeCursor < 0 && swipeBefore == null) {
            // The press already removed one character; give it back so the swipe owns the deletion.
            pressDeleted?.let { ic.commitText(it, 1) }
            pressDeleted = null
            swipeBefore = ic.getTextBeforeCursor(400, 0)?.toString() ?: ""
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            swipeCursor = extracted?.let { it.startOffset + it.selectionStart } ?: -1
        }
        var text = swipeBefore.orEmpty()
        var length = 0
        repeat(steps) {
            val run = wordDeleteLength(text)
            length += run
            text = text.dropLast(run)
        }
        swipeLength = length
        if (swipeCursor >= 0) ic.setSelection(swipeCursor - length, swipeCursor)
    }

    private fun deleteSwipeEnd(commit: Boolean) {
        val ic = currentInputConnection
        val cursor = swipeCursor
        val length = swipeLength
        val started = swipeBefore != null
        swipeCursor = -1
        swipeBefore = null
        swipeLength = 0
        if (ic == null || !started) return
        when {
            commit && length > 0 && cursor >= 0 -> ic.commitText("", 1)
            commit && length > 0 -> ic.deleteSurroundingText(length, 0)
            cursor >= 0 -> ic.setSelection(cursor, cursor)
        }
        lastCorrection = null
        lastSpace = 0L
        updateAuxiliary()
    }

    /** UTF-16 length of trailing whitespace plus the word (or punctuation/emoji run) before it. */
    private fun wordDeleteLength(text: String): Int {
        val spaces = text.length - text.trimEnd().length
        val rest = text.dropLast(spaces)
        val last = rest.lastOrNull() ?: return spaces
        val run = if (wordChar(last)) rest.takeLastWhile(::wordChar) else rest.takeLastWhile { !wordChar(it) && !it.isWhitespace() }
        return spaces + run.length
    }

    private fun wordChar(char: Char): Boolean =
        char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char == '\''

    private fun sendKey(
        ic: InputConnection,
        code: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return down && up
    }

    private fun moveCursor(steps: Int) {
        if (mode != Mode.KEYS) return
        val ic = currentInputConnection ?: return
        repeat(kotlin.math.abs(steps).coerceAtMost(20)) {
            sendKey(
                ic,
                if (steps <
                    0
                ) {
                    KeyEvent.KEYCODE_DPAD_LEFT
                } else {
                    KeyEvent.KEYCODE_DPAD_RIGHT
                },
            )
        }
        lastSpace = 0L
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val AUX_DP = 40
    }
}
