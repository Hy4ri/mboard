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
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

class MboardService : InputMethodService() {
    private enum class Mode { KEYS, CLIPBOARD, EMOJI, EMOJI_SEARCH, EDIT, PHRASES, CALCULATOR, TEXT_LAB }

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
    private var toolsPanel: ScrollView? = null
    private var keyboardSurface: OneHandSurface? = null
    private var labTransform = TextLab.Transform.TITLE_CASE
    private var labPalette = TextLab.Palette.KAOMOJI
    private var auxiliary: LinearLayout? = null
    private var panelBody: FrameLayout? = null
    private var toolbar: LinearLayout? = null
    private lateinit var ui: BoardUi
    private lateinit var settings: BoardSettings
    private lateinit var clips: ClipStore
    private lateinit var phrases: PhraseStore
    private lateinit var emojiHistory: EmojiHistory
    private lateinit var words: WordSuggestions
    private lateinit var clipboard: ClipboardManager
    private var language = Layout.ENGLISH
    private var visualKey = ""
    private var query = ""
    private var lastShift = 0L
    private var lastSpace = 0L
    private var privateSession = false
    private var lastClipStamp = 0L
    private var sensitiveField = false
    private var suggestionsAllowed = false
    private var fieldPolicy = SuggestionPolicy.classify(0, 0)
    private var editorGeneration = 0L
    private var selectionVersion = 0L
    private var suggestionStrip: SuggestionStrip? = null
    private val suggestionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshSuggestions =
        Runnable {
            if (!destroyed && mode == Mode.KEYS && isInputViewShown) updateAuxiliary()
        }
    private var defaultLayout = Layout.ENGLISH
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
        words.close()
        super.onDestroy()
    }

    private fun settingsChanged(key: String?) {
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
        if (key == "suggestions" && settings.suggestions && suggestionsAllowed &&
            !privateSession
        ) {
            loadWords()
        }
        updateAuxiliary()
    }

    override fun onFinishInput() {
        editorGeneration++
        toolsPanel?.removeAllViews()
        suggestionStrip?.render(emptyList(), "", editorGeneration, "No active text editor")
        suggestionHandler.removeCallbacks(refreshSuggestions)
        keyboard?.cancelTouch()
        query = ""
        lastSpace = 0L
        lastShift = 0L
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode() = false

    private fun recordClip() {
        // Notifications also arrive while a living IME is hidden: never collect in the background.
        if (!isInputViewShown || sensitiveField || privateSession || !settings.clipboardHistory) return
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
        val kv = KeyboardView(this, ::onKey, ::toggleLanguage, ::moveCursor)
        keyboard = kv
        kv.layout = defaultLayout
        kv.languageLabel = if (language == Layout.ARABIC) "العربية" else "English"
        clipPanel = ClipboardPanel(this, clips, ::paste)
        emojiPanel = EmojiPanel(this, { setMode(Mode.EMOJI_SEARCH) }, ::pasteEmoji).apply { loadFailed = emojiLoadFailed }
        toolsPanel = ScrollView(this).apply { setBackgroundColor(ui.background) }
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
                { if (sensitiveField) emptyList() else emojiHistory.recent() },
                ::pasteEmoji,
                { setMode(Mode.EMOJI) },
            ) { settings.oneHandMode = it }
        body.addView(keyboardSurface, fill)
        body.addView(clipPanel, FrameLayout.LayoutParams(fill))
        body.addView(emojiPanel, FrameLayout.LayoutParams(fill))
        body.addView(toolsPanel, FrameLayout.LayoutParams(fill))
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setBackgroundColor(ui.background)
                addView(
                    HorizontalScrollView(this@MboardService).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(toolbar)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)),
                )
                addView(auxiliary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)))
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
        icon(R.drawable.ic_content_paste, "Clipboard history", !sensitiveField) { togglePanel(Mode.CLIPBOARD) }
        icon(R.drawable.ic_mood, "Search emoji", !sensitiveField) { togglePanel(Mode.EMOJI_SEARCH) }
        // Panels replace the text-tools slot with Return, so the toolbar never gains another slot.
        if (mode != Mode.KEYS) {
            icon(R.drawable.ic_keyboard, "Return to keyboard") { setMode(Mode.KEYS) }
        } else {
            icon(R.drawable.ic_text_select_start, "Cursor and text tools", !sensitiveField) { togglePanel(Mode.EDIT) }
        }
        val tools = ui.icon(R.drawable.ic_settings, "More tools and settings") {}
        tools.setOnClickListener {
            android.widget.PopupMenu(this, tools).apply {
                menu.add("Cursor and text tools").isEnabled = !sensitiveField
                menu.add("Calculator").isEnabled = !sensitiveField
                menu.add("Saved phrases").isEnabled = !sensitiveField
                menu.add("Text Lab").isEnabled = !sensitiveField
                menu.add("Settings")
                menu.add("Choose another keyboard")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Cursor and text tools" -> {
                            togglePanel(Mode.EDIT)
                        }

                        "Calculator" -> {
                            togglePanel(Mode.CALCULATOR)
                        }

                        "Saved phrases" -> {
                            togglePanel(Mode.PHRASES)
                        }

                        "Text Lab" -> {
                            togglePanel(Mode.TEXT_LAB)
                        }

                        "Settings" -> {
                            openSettings()
                        }

                        "Choose another keyboard" -> {
                            getSystemService(
                                android.view.inputmethod.InputMethodManager::class.java,
                            ).showInputMethodPicker()
                        }
                    }
                    true
                }
                show()
            }
        }
        bar.addView(tools, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val privacyLabel =
            when {
                fieldPolicy.password -> "Protected password field. Clipboard and text tools unavailable"
                privateSession -> "Private session on. Tap to turn off"
                sensitiveField -> "App requested no learning. Tap to enable full private mode"
                else -> "Private session off. Tap to turn on"
            }
        bar.addView(
            ui
                .icon(R.drawable.ic_privacy, privacyLabel) {
                    if (!fieldPolicy.password) {
                        privateSession = !privateSession
                        emojiPanel?.recordingEnabled = !privateSession && !sensitiveField
                        if (!privateSession && suggestionsAllowed && settings.suggestions) loadWords()
                        rebuildToolbar()
                        updateAuxiliary()
                    }
                }.apply {
                    isEnabled = !fieldPolicy.password
                    isSelected = privateSession || sensitiveField
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
        if (sensitiveField && next != Mode.KEYS) return
        val previous = mode
        if (previous == Mode.TEXT_LAB && next != Mode.TEXT_LAB) toolsPanel?.removeAllViews()
        mode = next
        lastSpace = 0L
        if (next == Mode.EMOJI_SEARCH || next == Mode.CALCULATOR) {
            if (previous != next) query = ""
            keyboard?.layout = if (next == Mode.CALCULATOR) Layout.SYMBOLS else language
            keyboard?.shifted = false
            keyboard?.capsLocked = false
        } else if (previous == Mode.EMOJI_SEARCH || previous == Mode.CALCULATOR) {
            keyboard?.layout = defaultLayout
        }
        keyboard?.enterLabel =
            when (next) {
                Mode.CALCULATOR -> "Insert"
                Mode.EMOJI_SEARCH -> "Pick"
                else -> enterLabel(currentInputEditorInfo)
            }
        val keysVisible = next in setOf(Mode.KEYS, Mode.EMOJI_SEARCH, Mode.CALCULATOR)
        // Clipboard and emoji drop the label bar and take its height, so the window size never changes.
        val clipboardOpen = next == Mode.CLIPBOARD || next == Mode.EMOJI
        auxiliary?.visibility = if (clipboardOpen) View.GONE else View.VISIBLE
        panelBody?.let { body ->
            val height = (keyboard?.keysHeight ?: 0) + if (clipboardOpen) ui.dp(48) else 0
            if (body.layoutParams != null && body.layoutParams.height != height) {
                body.layoutParams = body.layoutParams.apply { this.height = height }
            }
        }
        keyboardSurface?.visibility = if (keysVisible) View.VISIBLE else View.INVISIBLE
        keyboard?.visibility = if (keysVisible) View.VISIBLE else View.INVISIBLE
        clipPanel?.visibility = if (next == Mode.CLIPBOARD) View.VISIBLE else View.GONE
        emojiPanel?.visibility = if (next == Mode.EMOJI) View.VISIBLE else View.GONE
        toolsPanel?.visibility = if (next == Mode.EDIT || next == Mode.PHRASES || next == Mode.TEXT_LAB) View.VISIBLE else View.GONE
        if (next == Mode.CLIPBOARD) clipPanel?.refresh()
        emojiPanel?.recordingEnabled = !privateSession && !sensitiveField
        if (next == Mode.EMOJI) emojiPanel?.refresh()
        if (next == Mode.EDIT || next == Mode.PHRASES || next == Mode.TEXT_LAB) rebuildTools()
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
        fieldPolicy = SuggestionPolicy.classify(info?.inputType ?: 0, info?.imeOptions ?: 0)
        sensitiveField = fieldPolicy.sensitive
        suggestionsAllowed = fieldPolicy.allowed
        defaultLayout = if (fieldPolicy.numeric) Layout.NUMBER else language
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
        if (suggestionsAllowed && settings.suggestions && !privateSession) loadWords()
        // Copying usually happens while the keyboard is hidden: pick that clip up once mboard is on screen.
        suggestionHandler.post { if (!destroyed) recordClip() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        editorGeneration++
        toolsPanel?.removeAllViews()
        suggestionStrip?.render(emptyList(), "", editorGeneration, "No active text editor")
        suggestionHandler.removeCallbacks(refreshSuggestions)
        keyboard?.cancelTouch()
        query = ""
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
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) selectionVersion++
        // A caret jump (tap elsewhere) means backspace no longer refers to the correction.
        lastCorrection?.let { last ->
            if (newSelStart != newSelEnd || kotlin.math.abs(newSelStart - oldSelStart) > last.corrected.length + 2) lastCorrection = null
        }
        if (mode == Mode.KEYS) {
            updateCapitalization()
            updateAuxiliary()
        }
        if (mode == Mode.EDIT || mode == Mode.TEXT_LAB) rebuildTools()
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
        language = if (language == Layout.ENGLISH) Layout.ARABIC else Layout.ENGLISH
        settings.language = language
        if (defaultLayout != Layout.NUMBER) defaultLayout = language
        keyboard?.capsLocked = false
        keyboard?.shifted = false
        keyboard?.languageLabel = if (language == Layout.ARABIC) "العربية" else "English"
        keyboard?.layout =
            when (mode) {
                Mode.CALCULATOR -> Layout.SYMBOLS
                Mode.EMOJI_SEARCH -> language
                else -> defaultLayout
            }
        if (mode == Mode.KEYS && suggestionsAllowed && !privateSession && settings.suggestions) loadWords()
        updateAuxiliary()
    }

    private fun updateCapitalization() {
        val kv = keyboard ?: return
        if (mode != Mode.KEYS || kv.layout != Layout.ENGLISH || kv.capsLocked ||
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
        val previousWord: String = "",
        val recentWords: Set<String> = emptySet(),
    )

    private fun prefixContext(): PrefixContext {
        val ic = currentInputConnection ?: return PrefixContext("", "No active text editor")
        if (!ic.getSelectedText(0).isNullOrEmpty()) return PrefixContext("", "Selection active · completions paused")
        val after = ic.getTextAfterCursor(1, 0) ?: return PrefixContext("", "App does not expose text for completions")
        val before =
            ic.getTextBeforeCursor(65, 0)?.toString()
                ?: return PrefixContext("", "App does not expose text for completions")

        fun wordChar(char: Char) = char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char == '\''
        if (after.firstOrNull()?.let(::wordChar) == true) return PrefixContext("", "Move cursor to the end of a word for completions")
        val result = before.takeLastWhile(::wordChar)
        if (result.length > 64) return PrefixContext("", "Word is too long for completions")
        val idle = result.isEmpty() && before.lastOrNull()?.isWhitespace() != false && after.firstOrNull()?.isWhitespace() != false
        // Only whitespace may sit between the caret and the previous word; punctuation ends the sentence chain.
        val previous = if (idle) before.trimEnd().takeLastWhile(::wordChar) else ""
        // Words already in the last few positions are never re-offered, so tapping predictions can't loop.
        val recent =
            if (idle) {
                before
                    .split(Regex("[^\\p{L}\\p{Mn}\\p{Nd}']+"))
                    .filter { it.isNotEmpty() }
                    .takeLast(4)
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
            } else {
                emptySet()
            }
        return PrefixContext(
            result,
            if (result.isEmpty()) "Type a word · completions appear while typing" else "No matching completion",
            idle,
            previous,
            recent,
        )
    }

    private fun renderSuggestions(bar: LinearLayout) {
        val strip = suggestionStrip ?: return
        if (bar.childCount != 1 || bar.getChildAt(0) !== strip) {
            bar.removeAllViews()
            bar.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)))
        }
        val blocked =
            when {
                fieldPolicy.password -> fieldPolicy.explanation
                privateSession -> "Private session · word suggestions paused"
                !settings.suggestions -> "Word suggestions off · enable them in Settings"
                !suggestionsAllowed -> fieldPolicy.explanation
                else -> null
            }
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
                    WordSuggestions.State.LOADING -> "Loading ${if (language == Layout.ARABIC) "Arabic" else "English"} dictionary…"
                    WordSuggestions.State.FAILED -> "Dictionary unavailable · reopen keyboard to retry"
                    WordSuggestions.State.CLOSED -> "Word suggestions unavailable"
                    else -> "Preparing word completions…"
                }
            strip.render(emptyList(), "", editorGeneration, message)
            return
        }
        val context = prefixContext()
        if (context.commonEligible) {
            val fresh = { w: String -> w.lowercase(Locale.ROOT) !in context.recentWords }
            val predicted =
                (words.nextWords(context.previousWord, language).filter(fresh) + words.commonWords(language).filter(fresh))
                    .distinctBy { it.lowercase(Locale.ROOT) }
            strip.render(
                predicted.map { matchCase(it, "") },
                "",
                editorGeneration,
                context.status,
            )
            return
        }
        val typed = context.prefix
        val correction =
            words
                .correction(typed, language)
                ?.takeIf { settings.autoCorrect && typed !in rejectedCorrections }
                ?.let { matchCase(it, typed) }
        // A saved phrase starting with the typed word follows the correction; tapping it replaces the word.
        val phrase =
            if (sensitiveField || typed.length < 2) {
                null
            } else {
                phrases.all().firstOrNull { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
            }
        // The typed word itself is never a useful choice; the correction (if any) leads.
        val wordChoices =
            (listOfNotNull(correction, phrase) + words.suggest(typed, language).map { matchCase(it, typed) })
                .filter { it != typed }
                .distinctBy { it.lowercase(Locale.ROOT) }
        // A matching emoji takes the last slot so the correction and best completion stay put.
        val emoji = if (settings.emojiSuggestions && !sensitiveField) Emojis.forWord(typed) else null
        val values = if (emoji == null) wordChoices else wordChoices.take(2) + emoji
        strip.render(values, typed, editorGeneration, context.status, highlightFirst = correction != null)
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

    private fun autoCorrectBefore(
        ic: InputConnection,
        separator: String,
    ) {
        val kv = keyboard ?: return
        if (!settings.autoCorrect || mode != Mode.KEYS || kv.layout != language || fieldPolicy.password ||
            privateSession || !suggestionsAllowed || words.state(language) != WordSuggestions.State.READY
        ) {
            return
        }
        val typed = prefixContext().prefix
        if (typed.isEmpty() || typed in rejectedCorrections) return
        val corrected = words.correction(typed, language)?.let { matchCase(it, typed) } ?: return
        if (corrected == typed) return
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
                    LinearLayout.LayoutParams(0, ui.dp(48), 1f),
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
                    LinearLayout.LayoutParams(0, ui.dp(48), 1f),
                )
            }

            Mode.EMOJI_SEARCH -> {
                bar.addView(
                    emojiSearchField(),
                    LinearLayout.LayoutParams(ui.dp(150), ui.dp(38)).apply { setMargins(ui.dp(8), 0, ui.dp(4), 0) },
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
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(42)),
                    )
                }
                bar.addView(
                    HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(row)
                    },
                    LinearLayout.LayoutParams(0, ui.dp(48), 1f),
                )
                bar.addView(
                    ui.icon(R.drawable.ic_mood, "Browse all emoji") { setMode(Mode.EMOJI) },
                    LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
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
                                Mode.EDIT -> "Text tools · actions apply to the active app"
                                Mode.PHRASES -> "Phrases · tap to insert, manage in settings"
                                Mode.TEXT_LAB -> "Text Lab · preview first, replace only on tap"
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
        if (fieldPolicy.reason != SuggestionPolicy.Reason.ADDRESS || privateSession || !settings.suggestions) return null
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

    private fun matchCase(
        word: String,
        source: String,
    ): String = WordDictionary.displayWord(word, source, language, keyboard?.shifted == true, keyboard?.capsLocked == true)

    private fun acceptSuggestion(
        word: String,
        expected: String,
        generation: Long,
    ) {
        if (generation == editorGeneration && mode == Mode.KEYS && fieldPolicy.reason == SuggestionPolicy.Reason.ADDRESS) {
            val ic = currentInputConnection ?: return
            if (savedAddresses()?.first != expected) return updateAuxiliary()
            val done = if (expected.isEmpty()) ic.commitText(word, 1) else replaceBeforeCursor(ic, expected, word)
            if (!done) message("This app did not accept the address")
            updateAuxiliary()
            return
        }
        if (generation != editorGeneration || mode != Mode.KEYS || fieldPolicy.password || privateSession ||
            !settings.suggestions || !suggestionsAllowed
        ) {
            return
        }
        val ic = currentInputConnection ?: return
        val context = prefixContext()
        if (context.prefix != expected || (expected.isEmpty() && !context.commonEligible)) {
            updateAuxiliary()
            return
        }
        val after = ic.getTextAfterCursor(1, 0)
        // An emoji goes after the typed word instead of replacing it: "fire" → "fire 🔥 ".
        if (expected.isNotEmpty() && Emojis.cached().any { it.text == word }) {
            if (ic.commitText(" $word" + if (after.isNullOrEmpty()) " " else "", 1)) {
                if (!privateSession && !sensitiveField) emojiHistory.record(word)
            } else {
                message("This app did not accept the emoji")
            }
            lastSpace = 0L
            updateAuxiliary()
            return
        }
        val value = word + if (after.isNullOrEmpty()) " " else ""
        val done = if (expected.isEmpty()) ic.commitText(value, 1) else replaceBeforeCursor(ic, expected, value)
        if (!done) {
            message("This app did not accept the completion")
        } else if (keyboard?.capsLocked != true) {
            keyboard?.shifted = false
        }
        lastSpace = 0L
        updateAuxiliary()
    }

    private fun paste(text: String) {
        if (currentInputConnection?.commitText(text, 1) == false) message("This app did not accept the text")
        lastSpace = 0L
        if (mode == Mode.KEYS) updateAuxiliary()
    }

    private fun pasteEmoji(text: String) {
        if (currentInputConnection?.commitText(text, 1) == true && !privateSession && !sensitiveField) emojiHistory.record(text)
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

            Keys.LETTERS -> {
                kv.layout = language
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
        if (key == Keys.BACKSPACE && ic.getSelectedText(0).isNullOrEmpty() && undoCorrection(ic)) {
            lastShift = 0L
            updateAuxiliary()
            return
        }
        if (key != Keys.SHIFT) lastCorrection = null
        if (key in separators) autoCorrectBefore(ic, key)
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
                if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else sendKey(ic, KeyEvent.KEYCODE_DEL)
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

            Keys.LETTERS -> {
                kv.layout = language
            }

            Keys.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
                val canPeriod =
                    settings.doubleSpacePeriod && !sensitiveField && suggestionsAllowed && lastSpace != 0L &&
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

    /** UTF-16 length of trailing whitespace plus the word (or punctuation/emoji run) before it. */
    private fun wordDeleteLength(text: String): Int {
        fun wordChar(char: Char) = char.isLetterOrDigit() || Character.getType(char) == Character.NON_SPACING_MARK.toInt() || char == '\''
        val spaces = text.length - text.trimEnd().length
        val rest = text.dropLast(spaces)
        val last = rest.lastOrNull() ?: return spaces
        val run = if (wordChar(last)) rest.takeLastWhile(::wordChar) else rest.takeLastWhile { !wordChar(it) && !it.isWhitespace() }
        return spaces + run.length
    }

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
        if (mode != Mode.KEYS && mode != Mode.EDIT) return
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

    private fun rebuildTools() {
        val panel = toolsPanel ?: return
        panel.removeAllViews()
        if (sensitiveField) return
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12))
            }
        panel.addView(list)
        if (mode == Mode.TEXT_LAB) {
            renderTextLab(list)
            return
        }
        if (mode == Mode.PHRASES) {
            val generation = editorGeneration
            val saved = phrases.all()
            if (saved.isEmpty()) {
                list.addView(
                    ui.label(
                        "Save often-used replies, addresses, or signatures in settings. Nothing you type is saved automatically.",
                        15f,
                        true,
                    ),
                )
            }
            saved.forEach { phrase ->
                list.addView(
                    ui
                        .button(phrase) {
                            if (mode == Mode.PHRASES && generation == editorGeneration && !sensitiveField) paste(phrase)
                        }.apply {
                            gravity = Gravity.START
                            textDirection =
                                View.TEXT_DIRECTION_FIRST_STRONG
                        },
                )
            }
            list.addView(ui.button("Manage phrases") { openSettings() })
            return
        }
        val ic = currentInputConnection ?: return
        val generation = editorGeneration
        val version = selectionVersion
        val selectedText = ic.getSelectedText(0)?.toString()
        val snapshot = selectedText?.takeIf { it.isNotEmpty() && it.length <= 20_000 }
        val selected = !selectedText.isNullOrEmpty()

        fun editAction(action: () -> Unit) {
            if (mode == Mode.EDIT && generation == editorGeneration && version == selectionVersion && !sensitiveField) action()
        }

        fun row(vararg actions: Pair<String, () -> Unit>) {
            val r = LinearLayout(this)
            actions.forEach { (label, action) ->
                r.addView(ui.button(label) { editAction(action) }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
            }
            list.addView(r)
        }
        row(
            "Left" to { moveCursor(-1) },
            "Up" to { sendKey(ic, KeyEvent.KEYCODE_DPAD_UP) },
            "Down" to { sendKey(ic, KeyEvent.KEYCODE_DPAD_DOWN) },
            "Right" to { moveCursor(1) },
        )
        row("Line start" to { sendKey(ic, KeyEvent.KEYCODE_MOVE_HOME) }, "Line end" to { sendKey(ic, KeyEvent.KEYCODE_MOVE_END) })
        row("Select all" to { contextAction(android.R.id.selectAll) }, "Paste" to { contextAction(android.R.id.paste) })
        val selectRow = LinearLayout(this)
        listOf("Copy" to android.R.id.copy, "Cut" to android.R.id.cut).forEach { (label, id) ->
            selectRow.addView(
                ui.button(label) { editAction { contextAction(id) } }.apply {
                    isEnabled = selected && !sensitiveField
                    alpha =
                        if (isEnabled) 1f else 0.4f
                },
                LinearLayout.LayoutParams(0, ui.dp(52), 1f),
            )
        }
        list.addView(selectRow)
        row("Undo" to { contextAction(android.R.id.undo) }, "Redo" to { contextAction(android.R.id.redo) })
        list.addView(
            ui.label("Select text in the app to change case or remove Arabic marks.", 13f, true).apply {
                setPadding(0, ui.dp(12), 0, ui.dp(8))
            },
        )
        val transformRow = LinearLayout(this)
        listOf(
            "UPPER" to { s: String -> s.uppercase(Locale.ROOT) },
            "lower" to { s: String -> s.lowercase(Locale.ROOT) },
            "بلا تشكيل" to { s: String ->
                s.filterNot { it in '\u064B'..'\u065F' || it == '\u0670' || it == '\u0640' }
            },
        ).forEach { (label, transform) ->
            transformRow.addView(
                ui
                    .button(label) {
                        editAction {
                            snapshot?.let { replaceSelection(it, transform(it), generation, version, Mode.EDIT) }
                        }
                    }.apply {
                        isEnabled = snapshot != null && !sensitiveField
                        alpha = if (isEnabled) 1f else 0.4f
                    },
                LinearLayout.LayoutParams(0, ui.dp(52), 1f),
            )
        }
        list.addView(transformRow)
    }

    private fun renderTextLab(list: LinearLayout) {
        if (sensitiveField) return
        val generation = editorGeneration
        val version = selectionVersion
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val source = selected?.takeIf { it.isNotEmpty() && it.length <= 20_000 }
        val preview = source?.let { TextLab.transform(it, labTransform) }
        val chooser = ui.button("Transform: ${labTransform.label}") {}
        chooser.setOnClickListener {
            android.widget.PopupMenu(this, chooser).apply {
                TextLab.Transform.entries.forEach { menu.add(0, it.ordinal + 1, 0, it.label) }
                setOnMenuItemClickListener { item ->
                    if (mode == Mode.TEXT_LAB && generation == editorGeneration) {
                        labTransform = TextLab.Transform.entries[item.itemId - 1]
                        rebuildTools()
                    }
                    true
                }
                show()
            }
        }
        list.addView(chooser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)))
        list.addView(
            ui
                .label(
                    when {
                        selected != null && selected.length > 20_000 -> "Select a smaller piece of text (maximum 20,000 characters)."
                        source == null -> "Select text in the app to preview a transformation. No text is saved here."
                        else -> "Preview · your selection stays unchanged until Replace"
                    },
                    13f,
                    true,
                ).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) },
        )
        if (preview != null) {
            list.addView(
                ui.label(preview, 15f).apply {
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    background = ui.shape(ui.surface)
                },
            )
        }
        if (labTransform == TextLab.Transform.KEYBOARD_TO_ARABIC || labTransform == TextLab.Transform.KEYBOARD_TO_ENGLISH) {
            list.addView(
                ui.label(
                    "Keyboard rescue is approximate: Arabic lam–alef cannot identify the original keystrokes. Check the preview.",
                    13f,
                    true,
                ),
            )
        }
        list.addView(
            ui
                .button("Replace selection", true) {
                    if (source != null && preview != null) replaceSelection(source, preview, generation, version, Mode.TEXT_LAB)
                }.apply {
                    isEnabled = source != null && preview != source
                    alpha = if (isEnabled) 1f else .4f
                },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)),
        )
        list.addView(ui.heading("Insert a little chaos"))
        val paletteChooser = ui.button("Palette: ${labPalette.label}") {}
        paletteChooser.setOnClickListener {
            android.widget.PopupMenu(this, paletteChooser).apply {
                TextLab.Palette.entries.forEach { menu.add(0, it.ordinal + 1, 0, it.label) }
                setOnMenuItemClickListener { item ->
                    if (mode == Mode.TEXT_LAB && generation == editorGeneration) {
                        labPalette = TextLab.Palette.entries[item.itemId - 1]
                        rebuildTools()
                    }
                    true
                }
                show()
            }
        }
        list.addView(paletteChooser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)))
        TextLab.palette(labPalette).forEach { (name, value) ->
            list.addView(
                ui
                    .button("$name  $value") {
                        if (mode == Mode.TEXT_LAB && generation == editorGeneration && !sensitiveField) paste(value)
                    }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        contentDescription = "Insert $name: $value"
                    },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(48)),
            )
        }
    }

    private fun replaceSelection(
        source: String,
        replacement: String,
        generation: Long,
        version: Long,
        expectedMode: Mode,
    ) {
        if (mode != expectedMode || generation != editorGeneration || version != selectionVersion || sensitiveField) {
            message("Selection changed · select the text again")
            return
        }
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0)?.toString() != source) {
            message("Selection changed · select the text again")
        } else if (!ic.commitText(replacement, 1)) {
            message("This app did not accept the replacement")
        }
    }

    private fun contextAction(id: Int) {
        if (currentInputConnection?.performContextMenuAction(id) != true) message("This app does not support that text action")
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
