package com.m57.mboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
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
    private enum class Mode { KEYS, CLIPBOARD, EMOJI, EMOJI_SEARCH, EDIT, PHRASES, CALCULATOR }

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
    private var auxiliary: LinearLayout? = null
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
    private var sensitiveField = false
    private var suggestionsAllowed = false
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
            words.load(language) { updateAuxiliary() }
        }
        updateAuxiliary()
    }

    override fun onFinishInput() {
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
        auxiliary = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val body = FrameLayout(this)
        val fill = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        body.addView(kv, fill)
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
        if (mode != Mode.KEYS) icon(R.drawable.ic_keyboard, "Return to keyboard") { setMode(Mode.KEYS) }
        icon(R.drawable.ic_content_paste, "Clipboard history", !sensitiveField) { togglePanel(Mode.CLIPBOARD) }
        icon(R.drawable.ic_mood, "Search emoji", !sensitiveField) { togglePanel(Mode.EMOJI_SEARCH) }
        icon(R.drawable.ic_text_select_start, "Cursor and text tools") { togglePanel(Mode.EDIT) }
        val tools = ui.icon(R.drawable.ic_settings, "More tools and settings") {}
        tools.setOnClickListener {
            android.widget.PopupMenu(this, tools).apply {
                menu.add("Calculator").isEnabled = !sensitiveField
                menu.add("Saved phrases").isEnabled = !sensitiveField
                menu.add("Settings")
                menu.add("Choose another keyboard")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Calculator" -> {
                            togglePanel(Mode.CALCULATOR)
                        }

                        "Saved phrases" -> {
                            togglePanel(Mode.PHRASES)
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
        bar.addView(
            ui
                .button(if (privateSession || sensitiveField) "Private on" else "Private") {
                    privateSession = !privateSession
                    emojiPanel?.recordingEnabled = !privateSession && !sensitiveField
                    if (!privateSession && suggestionsAllowed && settings.suggestions) words.load(language) { updateAuxiliary() }
                    rebuildToolbar()
                    updateAuxiliary()
                }.apply {
                    isEnabled = !sensitiveField
                    isSelected = privateSession || sensitiveField
                },
        )
        icon(R.drawable.ic_keyboard_hide, "Hide keyboard") { requestHideSelf(0) }
    }

    private fun openSettings() = startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun togglePanel(next: Mode) = setMode(if (mode == next) Mode.KEYS else next)

    private fun setMode(next: Mode) {
        keyboard?.cancelTouch()
        if (sensitiveField && next !in setOf(Mode.KEYS, Mode.EDIT)) return
        val previous = mode
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
        keyboard?.visibility = if (keysVisible) View.VISIBLE else View.INVISIBLE
        clipPanel?.visibility = if (next == Mode.CLIPBOARD) View.VISIBLE else View.GONE
        emojiPanel?.visibility = if (next == Mode.EMOJI) View.VISIBLE else View.GONE
        toolsPanel?.visibility = if (next == Mode.EDIT || next == Mode.PHRASES) View.VISIBLE else View.GONE
        if (next == Mode.CLIPBOARD) clipPanel?.refresh()
        emojiPanel?.recordingEnabled = !privateSession && !sensitiveField
        if (next == Mode.EMOJI) emojiPanel?.refresh()
        if (next == Mode.EDIT || next == Mode.PHRASES) rebuildTools()
        rebuildToolbar()
        updateAuxiliary()
    }

    override fun onStartInput(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(info, restarting)
        val type = info?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        val inputClass = type and InputType.TYPE_MASK_CLASS
        sensitiveField = (
            inputClass == InputType.TYPE_CLASS_TEXT && variation in
                setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )
        ) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        suggestionsAllowed = inputClass == InputType.TYPE_CLASS_TEXT && !sensitiveField &&
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            variation !in
            setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
            )
        defaultLayout =
            if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME
            ) {
                Layout.NUMBER
            } else {
                language
            }
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
        if (suggestionsAllowed && settings.suggestions && !privateSession) words.load(language) { updateAuxiliary() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
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
        if (mode == Mode.KEYS) {
            updateCapitalization()
            updateAuxiliary()
        }
        if (mode == Mode.EDIT) rebuildTools()
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
        if (mode == Mode.KEYS && suggestionsAllowed && !privateSession && settings.suggestions) words.load(language) { updateAuxiliary() }
        updateAuxiliary()
    }

    private fun updateCapitalization() {
        val kv = keyboard ?: return
        if (mode != Mode.KEYS || kv.layout != Layout.ENGLISH || kv.capsLocked || sensitiveField) return
        if (settings.autoCapitalize) {
            kv.shifted =
                (currentInputConnection?.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) ?: 0) != 0
        }
    }

    private fun prefix(): String {
        val ic = currentInputConnection ?: return ""
        if (!ic.getSelectedText(0).isNullOrEmpty()) return ""
        val after = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        if (after != null &&
            (after.isLetter() || Character.getType(after) == Character.NON_SPACING_MARK.toInt() || after == '\'')
        ) {
            return ""
        }
        val before = ic.getTextBeforeCursor(65, 0)?.toString() ?: return ""
        val result = before.takeLastWhile { it.isLetter() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || it == '\'' }
        return result.takeIf { it.length <= 64 } ?: ""
    }

    private fun updateAuxiliary() {
        val bar = auxiliary ?: return
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
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    ui.label(query.ifBlank { "Type an emoji name" }, 14f, true).apply {
                        maxLines = 1
                        maxWidth = ui.dp(132)
                        ellipsize = android.text.TextUtils.TruncateAt.START
                        tooltipText = query
                        setPadding(ui.dp(12), 0, ui.dp(8), 0)
                    },
                )
                row.addView(ui.icon(R.drawable.ic_mood, "Browse all emoji") { setMode(Mode.EMOJI) })
                val matches = Emojis.search(this, query).take(40)
                if (matches.isEmpty()) {
                    val status =
                        when {
                            emojiLoadFailed -> "Emoji unavailable"
                            !Emojis.ready -> "Loading emoji…"
                            query.isNotBlank() -> "No matches"
                            else -> ""
                        }
                    if (status.isNotEmpty()) row.addView(ui.label(status, 14f, true))
                }
                matches.forEach { emoji ->
                    row.addView(
                        ui.button(emoji.text) { pasteEmoji(emoji.text) }.apply {
                            textSize = 26f
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
                    )
                }
                bar.addView(
                    HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(row)
                    },
                )
            }

            Mode.KEYS -> {
                val allowed = settings.suggestions && suggestionsAllowed && !privateSession
                val currentPrefix = if (allowed) prefix() else ""
                val suggestions = if (allowed) words.suggest(currentPrefix, language) else emptyList()
                if (suggestions.isNotEmpty()) {
                    suggestions.forEach { suggestion ->
                        val word = matchCase(suggestion, currentPrefix)
                        bar.addView(ui.button(word) { acceptSuggestion(word, currentPrefix) }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
                    }
                } else {
                    bar.addView(
                        ui
                            .label(
                                when {
                                    sensitiveField -> "Private field · history and suggestions paused"
                                    privateSession -> "Private session · no history or suggestions"
                                    else -> "${if (language == Layout.ARABIC) "العربية" else "English"} · hold space to move cursor"
                                },
                                13f,
                                true,
                            ).apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) },
                        LinearLayout.LayoutParams(0, ui.dp(48), 1f),
                    )
                }
            }

            else -> {
                bar.addView(
                    ui
                        .label(
                            when (mode) {
                                Mode.CLIPBOARD -> "Clipboard · saved on this device"
                                Mode.EMOJI -> "Emoji · hold an emoji to favorite it"
                                Mode.EDIT -> "Text tools · actions apply to the active app"
                                Mode.PHRASES -> "Phrases · tap to insert, manage in settings"
                                else -> ""
                            },
                            14f,
                            true,
                        ).apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) },
                )
            }
        }
    }

    private fun matchCase(
        word: String,
        source: String,
    ): String =
        when {
            language != Layout.ENGLISH -> word
            source.length > 1 && source.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
            source.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

    private fun acceptSuggestion(
        word: String,
        expected: String,
    ) {
        val ic = currentInputConnection ?: return
        if (mode != Mode.KEYS || sensitiveField || privateSession || !suggestionsAllowed || prefix() != expected ||
            expected.isEmpty()
        ) {
            return
        }
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(expected.length, 0)) ic.commitText("$word ", 1)
        } finally {
            ic.endBatchEdit()
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
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12))
            }
        panel.addView(list)
        if (mode == Mode.PHRASES) {
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
                    ui.button(phrase) { paste(phrase) }.apply {
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
        val selected = !ic.getSelectedText(0).isNullOrEmpty()

        fun row(vararg actions: Pair<String, () -> Unit>) {
            val r = LinearLayout(this)
            actions.forEach { (label, action) -> r.addView(ui.button(label, action = action), LinearLayout.LayoutParams(0, ui.dp(52), 1f)) }
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
                ui.button(label) { contextAction(id) }.apply {
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
                        val text = ic.getSelectedText(0)?.toString() ?: return@button
                        if (text.length <= 20_000) ic.commitText(transform(text), 1) else message("Select a smaller piece of text")
                    }.apply {
                        isEnabled = selected && !sensitiveField
                        alpha = if (isEnabled) 1f else 0.4f
                    },
                LinearLayout.LayoutParams(0, ui.dp(52), 1f),
            )
        }
        list.addView(transformRow)
    }

    private fun contextAction(id: Int) {
        if (currentInputConnection?.performContextMenuAction(id) != true) message("This app does not support that text action")
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
