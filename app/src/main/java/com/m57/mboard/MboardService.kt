package com.m57.mboard

import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextDirectionHeuristics
import android.util.Log
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
import com.m57.mboard.KeyboardView.DeleteRepeat
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
    private var keyboardRoot: View? = null
    private lateinit var ui: BoardUi
    private lateinit var settings: BoardSettings
    private lateinit var clips: ClipStore
    private lateinit var phrases: PhraseStore
    private lateinit var emojiHistory: EmojiHistory
    private lateinit var words: WordSuggestions
    private lateinit var userWords: UserWords
    private lateinit var stripPlanner: StripPlanner
    private val predictionTrail = PredictionTrail()

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
    private lateinit var input: InputController
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
    private val refreshAfterDeleteRepeat =
        Runnable {
            if (!destroyed && isInputViewShown && keyboard?.isCursorScrubbing != true) {
                updateCapitalization()
                updateAuxiliary()
            }
        }
    private val refreshAfterCursorScrub =
        Runnable {
            if (!destroyed && mode == Mode.KEYS && isInputViewShown && keyboard?.isCursorScrubbing != true) {
                updateCapitalization()
                updateAuxiliary()
            }
        }
    private var defaultLayout = language.layout
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { recordClip() }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> settingsChanged(key) }

    override fun onCreate() {
        Log.i(
            "mboard.startup",
            "service onCreate start: elapsedRealtime=${SystemClock.elapsedRealtime()} uptimeMillis=${SystemClock.uptimeMillis()}",
        )
        super.onCreate()
        settings = BoardSettings(this)
        language = settings.language
        clips = ClipStore(this)
        phrases = PhraseStore(this)
        emojiHistory = EmojiHistory(this)
        words = WordSuggestions(this)
        userWords = UserWords(this)
        stripPlanner = StripPlanner(words, { userWords }, predictionTrail, rejectedCorrections)
        input = InputController(editorPort, stripPlanner, predictionTrail, rejectedCorrections, inputHost)
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
        TrackpadService.connected?.dismiss()
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
        } else {
            applyLiveSettings()
        }
        if (key == "capitalize" && !settings.autoCapitalize && keyboard?.capsLocked != true) keyboard?.shifted = false
        if (key == "suggestions" && settings.suggestions) {
            loadWords()
        }
        updateAuxiliary()
    }

    private fun applyLiveSettings() {
        val kv = keyboard ?: return
        kv.showPreview = settings.keyPreview
        kv.rowHeightDp = settings.rowHeightDp
        kv.showNumberRow = settings.numberRow
        kv.splitWide = settings.splitWide
        keyboardSurface?.setMode(settings.oneHandMode)
        panelBody?.let { body ->
            val clipboardOpen = mode == Mode.CLIPBOARD || mode == Mode.EMOJI
            val height = kv.keysHeight + if (clipboardOpen && stripNeeded()) ui.dp(Tokens.STRIP_HEIGHT) else 0
            if (body.layoutParams != null && body.layoutParams.height != height) {
                body.layoutParams = body.layoutParams.apply { this.height = height }
            }
        }
    }

    private fun resetEditorState() {
        input.finishComposing()
        input.reset()
        predictionTrail.reset()
        editorGeneration++
        suggestionStrip?.render(emptyList(), "", editorGeneration, getString(R.string.strip_no_editor))
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.removeCallbacks(refreshAfterDeleteRepeat)
        keyboard?.cancelTouch()
        suggestionHandler.removeCallbacks(refreshAfterCursorScrub)
        query = ""
    }

    override fun onFinishInput() {
        resetEditorState()
        input.forgetSpace()
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
        val kv =
            KeyboardView(this, ::onKey, ::moveCursor, ::deleteSwipe, ::deleteSwipeEnd, ::canRepeatWords, ::repeatDelete) {
                suggestionHandler.removeCallbacks(refreshAfterCursorScrub)
                suggestionHandler.postDelayed(refreshAfterCursorScrub, CURSOR_REFRESH_MS)
            }
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
                setBackgroundColor(ui.background)
                addView(
                    HorizontalScrollView(this@MboardService).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(toolbar)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(Tokens.TOOLBAR_HEIGHT)),
                )
                addView(auxiliary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(Tokens.STRIP_HEIGHT)))
                addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kv.keysHeight))
                setOnApplyWindowInsetsListener { target, insets ->
                    val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    if (target.paddingBottom != bars.bottom || target.paddingLeft != bars.left || target.paddingRight != bars.right) {
                        target.setPadding(bars.left, 0, bars.right, bars.bottom)
                    }
                    insets
                }
                // The first insets pass can run before this listener sees real bars, leaving the bottom row
                // cropped until a touch forces another pass. Ask again on attach and after the first layout.
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            v.requestApplyInsets()
                            v.post { v.requestApplyInsets() }
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    },
                )
            }
        keyboardRoot = root
        setMode(Mode.KEYS)
        return root
    }

    private fun rebuildToolbar() {
        val bar = toolbar ?: return
        bar.removeAllViews()
        val password = fieldPolicy.password

        // Fixed slots, never inserted or shifted. Active = accent container behind an accent-ink glyph.
        fun slot(
            res: Int,
            label: String,
            active: Boolean = false,
            offInPassword: Boolean = false,
            keepsTrackpad: Boolean = false,
            action: () -> Unit,
        ) {
            val off = offInPassword && password
            bar.addView(
                ui
                    .icon(res, label) {
                        if (off) {
                            message(R.string.toolbar_off_in_password)
                        } else {
                            if (!keepsTrackpad) TrackpadService.connected?.dismiss()
                            action()
                        }
                    }.apply {
                        isSelected = active
                        imageTintList = ColorStateList.valueOf(if (active) ui.accentInk else ui.text)
                        background = ui.ripple(if (active) ui.accentContainer else Color.TRANSPARENT)
                        if (off) {
                            isEnabled = false
                            // Greyed, not hidden; accessibility and tooltip explain why.
                            alpha = ui.disabledAlpha
                            tooltipText = getString(R.string.toolbar_slot_off_tooltip, label)
                            contentDescription = getString(R.string.toolbar_slot_off_a11y, label)
                        }
                        if (active) stateDescription = getString(R.string.state_open)
                    },
                LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TOOLBAR_HEIGHT)),
            )
        }
        slot(R.drawable.ic_paste, getString(R.string.toolbar_paste), offInPassword = true) { pasteClipboard() }
        slot(R.drawable.ic_content_paste, getString(R.string.toolbar_clipboard_history), mode == Mode.CLIPBOARD, true) {
            togglePanel(Mode.CLIPBOARD)
        }
        slot(R.drawable.ic_mood, getString(R.string.toolbar_emoji), mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH, true) {
            toggleEmoji()
        }
        slot(R.drawable.ic_calculate, getString(R.string.toolbar_calculator), mode == Mode.CALCULATOR, true) {
            togglePanel(Mode.CALCULATOR)
        }
        slot(
            R.drawable.ic_trackpad,
            getString(R.string.trackpad_title),
            active = TrackpadService.connected?.isShowing == true,
            offInPassword = true,
            keepsTrackpad = true,
        ) { openTrackpad() }
        val shieldOn = privateSession || password
        val privacyLabel =
            getString(
                when {
                    password -> R.string.shield_password
                    privateSession -> R.string.shield_on
                    else -> R.string.shield_off
                },
            )
        slot(R.drawable.ic_privacy, privacyLabel, shieldOn) {
            if (!password) {
                privateSession = !privateSession
                emojiPanel?.recordingEnabled = !privateSession && !password
                if (settings.suggestions) loadWords()
                rebuildToolbar()
                updateAuxiliary()
            }
        }
        slot(R.drawable.ic_settings, getString(R.string.toolbar_settings)) { openSettings() }
        slot(R.drawable.ic_keyboard_hide, getString(R.string.toolbar_hide_keyboard)) { requestHideSelf(0) }
    }

    private fun openTrackpad() {
        val service = TrackpadService.connected
        if (service == null) {
            message(R.string.trackpad_enable)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Log.w("mboard.trackpad", "Accessibility settings unavailable", e)
                message(R.string.trackpad_unavailable)
            }
            return
        }
        if (service.isShowing) {
            service.dismiss()
            return
        }
        val root = keyboardRoot ?: return
        val body = panelBody ?: return
        val keyHeight = keyboard?.keysHeight ?: return
        val owner = currentInputEditorInfo?.packageName
        if (owner.isNullOrBlank() || root.display?.displayId != android.view.Display.DEFAULT_DISPLAY) {
            message(R.string.trackpad_unavailable)
            return
        }
        val location = IntArray(2)
        body.getLocationOnScreen(location)
        val bottom = location[1] + body.height
        val bounds = Rect(location[0], bottom - keyHeight, location[0] + body.width, bottom)
        // Keep the toolbar in place, even if the previously open panel covered the suggestion strip.
        setMode(Mode.KEYS)
        val showing =
            service.show(
                owner,
                bounds,
                keyboardTop = {
                    val current = keyboardRoot
                    if (isInputViewShown && current?.isAttachedToWindow == true) {
                        current.getLocationOnScreen(location)
                        location[1]
                    } else {
                        null
                    }
                },
                onDismiss = ::refreshTrackpadChrome,
                returnToTyping = {
                    if (!destroyed && !isInputViewShown && currentInputEditorInfo?.packageName == owner && !fieldPolicy.password) {
                        requestShowSelf(0)
                    }
                },
            )
        if (showing) refreshTrackpadChrome() else message(R.string.trackpad_unavailable)
    }

    private fun refreshTrackpadChrome() {
        if (destroyed) return
        rebuildToolbar()
        updateAuxiliary()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        TrackpadService.connected?.setKeyboardVisible(true)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        TrackpadService.connected?.setKeyboardVisible(false)
    }

    private fun openSettings() = startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun togglePanel(next: Mode) = setMode(if (mode == next) Mode.KEYS else next)

    /** The emoji icon owns both emoji modes: the open one is closed by the same tap. */
    private fun toggleEmoji() = setMode(if (mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH) Mode.KEYS else Mode.EMOJI)

    private fun setMode(next: Mode) {
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.removeCallbacks(refreshAfterDeleteRepeat)
        keyboard?.cancelTouch()
        if (fieldPolicy.password && next != Mode.KEYS) return
        suggestionHandler.removeCallbacks(refreshAfterCursorScrub)
        val previous = mode
        if (previous != next) input.finishComposing()
        mode = next
        keyboard?.cursorScrubEnabled = next == Mode.KEYS
        input.forgetSpace()
        if (next == Mode.EMOJI_SEARCH || next == Mode.CALCULATOR) {
            if (previous != next) query = ""
            keyboard?.layout = if (next == Mode.CALCULATOR) Layout.CALC else language.layout
            keyboard?.shifted = false
            keyboard?.capsLocked = false
        } else if (previous == Mode.EMOJI_SEARCH || previous == Mode.CALCULATOR) {
            keyboard?.layout = defaultLayout
        }
        applyEnter(next)
        val keysVisible = next in setOf(Mode.KEYS, Mode.EMOJI_SEARCH, Mode.CALCULATOR)
        // Browse panels own their header and fill the keys plus strip area, keeping the keyboard height constant.
        val clipboardOpen = next == Mode.CLIPBOARD || next == Mode.EMOJI
        auxiliary?.visibility = if (clipboardOpen || (next == Mode.KEYS && !keyStripVisible())) View.GONE else View.VISIBLE
        panelBody?.let { body ->
            // Panels replace the hidden strip too, so opening one never shrinks the keyboard and small boards keep room.
            // Number pads have no strip row to replace, so their panels keep the pad's own height.
            val height = (keyboard?.keysHeight ?: 0) + if (clipboardOpen && stripNeeded()) ui.dp(Tokens.STRIP_HEIGHT) else 0
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

    /** Back from a panel (emoji, clipboard, calculator) returns to the keys instead of hiding the keyboard. */
    override fun onKeyDown(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && (mode != Mode.KEYS || TrackpadService.connected?.isShowing == true)) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: android.view.KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && event.isTracking && !event.isCanceled) {
            val trackpad = TrackpadService.connected
            if (trackpad?.isShowing == true) {
                trackpad.dismiss()
                return true
            }
            if (mode != Mode.KEYS) {
                setMode(Mode.KEYS)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStartInput(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(info, restarting)
        input.reset(clearFallback = true)
        predictionTrail.reset()
        editorGeneration++
        if (!restarting) rejectedCorrections.clear()
        fieldPolicy = SuggestionPolicy.classify(info?.inputType ?: 0)
        if (fieldPolicy.password) TrackpadService.connected?.dismiss()
        defaultLayout = if (fieldPolicy.numeric) Layout.NUMBER else language.layout
        input.forgetSpace()
        lastShift = 0L
        query = ""
        keyboard?.cancelTouch()
        suggestionHandler.removeCallbacks(refreshAfterCursorScrub)
        if (toolbar != null) setMode(Mode.KEYS)
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        if (visualKey != settings.visualKey()) setInputView(onCreateInputView()) else applyLiveSettings()
        keyboard?.layout = defaultLayout
        keyboard?.capsLocked = false
        keyboard?.shifted = false
        applyEnter(mode, info)
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
        input.caretMoved(oldSelStart, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (mode == Mode.KEYS && keyboard?.isCursorScrubbing != true && !suggestionHandler.hasCallbacks(refreshAfterCursorScrub)) {
            if (keyboard?.isRepeatingDelete == true) {
                scheduleDeleteRefresh()
            } else {
                suggestionHandler.removeCallbacks(refreshAfterDeleteRepeat)
                updateCapitalization()
                updateAuxiliary()
            }
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
        if (settings.arabiziSuggestions && requestedLanguage.script == Script.LATIN) {
            words.loadArabizi {
                if (!destroyed && generation == editorGeneration && requestedLanguage == language &&
                    mode == Mode.KEYS && isInputViewShown
                ) {
                    updateAuxiliary()
                }
            }
        }
        updateAuxiliary()
    }

    /** What the Enter key shows: a drawable (0 = draw [label] as text) and the action word for accessibility. */
    private class EnterKey(
        val icon: Int,
        val label: String,
    )

    private fun applyEnter(
        mode: Mode,
        info: EditorInfo? = currentInputEditorInfo,
    ) {
        val key =
            when (mode) {
                Mode.CALCULATOR -> EnterKey(0, "=")
                Mode.EMOJI_SEARCH -> EnterKey(R.drawable.ic_check, getString(R.string.key_pick))
                else -> enterKey(info)
            }
        keyboard?.enterIcon = key.icon
        keyboard?.enterLabel = key.label
    }

    private fun enterKey(info: EditorInfo?): EnterKey {
        val plain = EnterKey(R.drawable.ic_keyboard_return, getString(R.string.key_enter))
        if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return plain
        info.actionLabel
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return EnterKey(R.drawable.ic_keyboard_return, it) }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterKey(R.drawable.ic_arrow_forward, getString(R.string.key_go))
            EditorInfo.IME_ACTION_SEARCH -> EnterKey(R.drawable.ic_search, getString(R.string.key_search))
            EditorInfo.IME_ACTION_SEND -> EnterKey(R.drawable.ic_send, getString(R.string.key_send))
            EditorInfo.IME_ACTION_NEXT -> EnterKey(R.drawable.ic_last_page, getString(R.string.key_next))
            EditorInfo.IME_ACTION_DONE -> EnterKey(R.drawable.ic_check, getString(R.string.key_done))
            EditorInfo.IME_ACTION_PREVIOUS -> EnterKey(R.drawable.ic_first_page, getString(R.string.key_previous))
            else -> plain
        }
    }

    private fun toggleLanguage() {
        input.finishComposing()
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

    private fun readEditorText(): StripPlanner.EditorText = input.readText()

    private fun prefixContext(): StripPlanner.CaretContext = input.context()

    private fun stripPolicy(): StripPlanner.StripPolicy =
        StripPlanner.StripPolicy(
            language = language,
            dialect = settings.dialectEnabled(language.dialect),
            suggestionsEnabled = settings.suggestions,
            autoCorrect = settings.autoCorrect,
            useLearnedHistory = !privateSession,
            arabizi = settings.arabiziSuggestions,
            numeric = fieldPolicy.numeric,
            password = fieldPolicy.password,
            shifted = keyboard?.shifted == true,
            capsLocked = keyboard?.capsLocked == true,
        )

    private fun statusText(status: StripPlanner.StripStatus): String =
        when (status) {
            StripPlanner.StripStatus.NUMBER_FIELD -> getString(R.string.strip_number_field)
            StripPlanner.StripStatus.SUGGESTIONS_OFF -> getString(R.string.strip_suggestions_off)
            StripPlanner.StripStatus.LOADING_DICTIONARY -> getString(R.string.strip_loading_dictionary, language.localizedName(this))
            StripPlanner.StripStatus.DICTIONARY_FAILED -> getString(R.string.strip_dictionary_failed)
            StripPlanner.StripStatus.SUGGESTIONS_UNAVAILABLE -> getString(R.string.strip_suggestions_unavailable)
            StripPlanner.StripStatus.PREPARING -> getString(R.string.strip_preparing)
            StripPlanner.StripStatus.STATIC_SUGGESTIONS -> getString(R.string.strip_static_suggestions)
            StripPlanner.StripStatus.SAVED_ADDRESSES -> getString(R.string.strip_saved_addresses)
            StripPlanner.StripStatus.NO_EDITOR -> getString(R.string.strip_no_editor)
            StripPlanner.StripStatus.SELECTION_ACTIVE -> getString(R.string.strip_selection_active)
            StripPlanner.StripStatus.NO_SURROUNDING_TEXT -> getString(R.string.strip_no_surrounding_text)
            StripPlanner.StripStatus.WORD_TOO_LONG -> getString(R.string.strip_word_too_long)
            StripPlanner.StripStatus.IDLE_HINT -> getString(R.string.strip_idle_hint)
            StripPlanner.StripStatus.NO_MATCH -> getString(R.string.strip_no_match)
        }

    private fun renderSuggestions(bar: LinearLayout) {
        val strip = suggestionStrip ?: return
        if (bar.childCount != 1 || bar.getChildAt(0) !== strip) {
            bar.removeAllViews()
            bar.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val plan =
            stripPlanner.plan(
                policy = stripPolicy(),
                readEditor = ::readEditorText,
                readAddresses = ::savedAddresses,
                readPhrases = phrases::all,
                emojiForWord = if (settings.emojiSuggestions) Emojis::forWord else null,
            )
        val status = statusText(plan.status)
        strip.render(
            plan.suggestions,
            plan.word,
            editorGeneration,
            if (plan.shieldNote) shieldNote(status) else status,
            highlightFirst = plan.highlightFirst,
            addresses = plan.addresses,
        )
    }

    /** The strip status says why history is off whenever the shield or a password field is active. */
    private fun shieldNote(message: String) =
        when {
            fieldPolicy.password -> getString(R.string.strip_note_password, message)
            privateSession -> getString(R.string.strip_note_shield, message)
            else -> message
        }

    private val rejectedCorrections = HashSet<String>()

    private val editorPort =
        object : EditorPort {
            private val ic get() = currentInputConnection
            override val connected get() = ic != null

            override fun hasSelection() = !ic?.getSelectedText(0).isNullOrEmpty()

            override fun textBefore(count: Int) = ic?.getTextBeforeCursor(count, 0)?.toString()

            override fun textAfter(count: Int) = ic?.getTextAfterCursor(count, 0)?.toString()

            override fun commit(
                text: String,
                caretAfter: Boolean,
            ) = ic?.commitText(text, if (caretAfter) 1 else 0) ?: false

            override fun setComposing(text: String) = ic?.setComposingText(text, 1) ?: false

            override fun finishComposing() = ic?.finishComposingText() ?: false

            override fun deleteSurrounding(
                before: Int,
                after: Int,
            ) = ic?.deleteSurroundingText(before, after) ?: false

            override fun beginBatch() {
                ic?.beginBatchEdit()
            }

            override fun endBatch() {
                ic?.endBatchEdit()
            }
        }

    private val inputHost =
        object : InputController.Host {
            override val language get() = this@MboardService.language
            override val autoCorrectActive get() = mode == Mode.KEYS && keyboard?.let { it.layout == language.layout } == true
            override val doubleSpacePeriod get() = settings.doubleSpacePeriod && !fieldPolicy.password
            override val composingEnabled get() =
                settings.composingText && fieldPolicy.allowComposing &&
                    mode == Mode.KEYS && keyboard?.let { it.layout == language.layout } == true

            override fun policy() = stripPolicy()

            override fun learningAllowed() = this@MboardService.learningAllowed()

            override fun now() = SystemClock.uptimeMillis()
        }

    /**
     * PIN, number, phone and date pads take digits, not words: they drop the strip row entirely.
     * Anything that must still reach those fields from the strip (a copied code to quick-paste)
     * returns true here so the row comes back for it. The toolbar Paste button is unaffected.
     */
    private fun stripNeeded(): Boolean = !fieldPolicy.numeric

    private fun keyStripVisible() = stripNeeded() && TrackpadService.connected?.isShowing != true

    private fun updateAuxiliary() {
        if (keyboard?.isCursorScrubbing == true) return
        val bar = auxiliary ?: return
        if (mode == Mode.KEYS) {
            val visible = keyStripVisible()
            bar.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible && TrackpadService.connected?.isShowing == true) {
                if (bar.childCount > 0) bar.removeAllViews()
                return
            }
            bar.layoutParams = bar.layoutParams.apply { height = ui.dp(Tokens.STRIP_HEIGHT) }
            renderSuggestions(bar)
            return
        }
        bar.removeAllViews()
        bar.layoutParams =
            bar.layoutParams.apply {
                height = ui.dp(if (mode == Mode.CALCULATOR) Tokens.PANEL_HEADER else Tokens.STRIP_HEIGHT)
            }
        when (mode) {
            Mode.CALCULATOR -> {
                val answer = Calculator.calculate(query)
                val header = ui.panelHeader(query.ifBlank { getString(R.string.calc_placeholder) })
                header.title.apply {
                    setTextColor(if (query.isBlank()) ui.muted else ui.text)
                    ellipsize = android.text.TextUtils.TruncateAt.START
                    tooltipText = query
                }
                val result =
                    ui.button(answer?.let { "= $it" } ?: getString(R.string.calc_no_result)) { answer?.let(::paste) }.apply {
                        ui.setEnabledLook(this, answer != null)
                        background = ui.keyChip(ui.surface)
                        setTextColor(if (answer != null) ui.accentInk else ui.muted)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        contentDescription =
                            answer?.let { getString(R.string.calc_insert_result_a11y, it) }
                                ?: getString(R.string.calc_no_result_a11y)
                        tooltipText = answer ?: getString(R.string.calc_type_valid_tooltip)
                    }
                header.setActions(listOf(result))
                bar.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(Tokens.PANEL_HEADER)))
            }

            Mode.EMOJI_SEARCH -> {
                // Field and results share the row by weight (about 45 / 55), never below a readable field width.
                bar.addView(
                    emojiSearchField().apply { minimumWidth = ui.dp(Tokens.TARGET_MIN * 3) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 9f).apply {
                        marginStart = ui.dp(Tokens.SPACE_2)
                        marginEnd = ui.dp(Tokens.SPACE_1)
                    },
                )
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val matches = emojiSearchResults()
                if (matches.isEmpty()) {
                    val status =
                        getString(
                            when {
                                emojiLoadFailed -> R.string.emoji_unavailable
                                !Emojis.ready -> R.string.emoji_loading
                                query.isNotBlank() -> R.string.emoji_no_matches
                                else -> R.string.emoji_type_to_search
                            },
                        )
                    row.addView(
                        ui.label(status, Tokens.TEXT_BODY, true).apply { setPadding(ui.dp(Tokens.SPACE_2), 0, ui.dp(Tokens.SPACE_2), 0) },
                    )
                }
                matches.forEachIndexed { index, emoji ->
                    row.addView(
                        ui.button(emoji.text) { pasteEmoji(emoji.text) }.apply {
                            textSize = Tokens.TEXT_EMOJI
                            setPadding(ui.dp(Tokens.SPACE_2), 0, ui.dp(Tokens.SPACE_2), 0)
                            includeFontPadding = false
                            // The first result is what the Pick key inserts, so it is visibly selected.
                            background =
                                ui.ripple(if (index == 0 && query.isNotBlank()) ui.accentContainer else Color.TRANSPARENT)
                            contentDescription = emoji.name
                            setOnLongClickListener {
                                if (!privateSession) emojiHistory.toggleFavorite(emoji.text)
                                message(
                                    if (privateSession) {
                                        R.string.emoji_favorites_paused
                                    } else if (emojiHistory.isFavorite(emoji.text)) {
                                        R.string.emoji_favorite_added
                                    } else {
                                        R.string.emoji_favorite_removed
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
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 11f),
                )
                bar.addView(
                    ui.icon(R.drawable.ic_apps, getString(R.string.emoji_browse_all)) { setMode(Mode.EMOJI) },
                    LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), LinearLayout.LayoutParams.MATCH_PARENT),
                )
            }

            // Keys: the persistent strip above. Clipboard and emoji browsing hide this row; their panels own the header.
            else -> {
                Unit
            }
        }
    }

    /** In email fields: the text typed so far and saved email phrases that complete it. */
    private fun savedAddresses(): StripPlanner.SavedAddresses? {
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
        return StripPlanner.SavedAddresses(typed, matches)
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
                // The touch area is the full strip height; only the pill is inset.
                background =
                    android.graphics.drawable.InsetDrawable(
                        ui.shape(ui.surface, Tokens.RADIUS_PILL),
                        0,
                        ui.dp(Tokens.KEY_GAP / 2),
                        0,
                        ui.dp(Tokens.KEY_GAP / 2),
                    )
                setPaddingRelative(ui.dp(Tokens.SPACE_3), 0, 0, 0)
            }
        field.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(ui.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ui.dp(Tokens.ICON_SMALL), ui.dp(Tokens.ICON_SMALL)),
        )
        val text =
            if (query.isEmpty()) {
                android.text.SpannableString(getString(R.string.emoji_search_hint))
            } else {
                android.text.SpannableString("$query|").apply {
                    setSpan(android.text.style.ForegroundColorSpan(ui.accent), query.length, query.length + 1, 0)
                }
            }
        field.addView(
            ui.label("", Tokens.TEXT_BODY, query.isEmpty()).apply {
                setText(text)
                maxLines = 1
                // The typed end stays visible; the hint reads from its start.
                ellipsize = if (query.isEmpty()) android.text.TextUtils.TruncateAt.END else android.text.TextUtils.TruncateAt.START
                contentDescription =
                    if (query.isEmpty()) {
                        getString(R.string.emoji_search_empty_a11y)
                    } else {
                        getString(R.string.emoji_search_query_a11y, query)
                    }
                setPaddingRelative(ui.dp(Tokens.SPACE_2), 0, ui.dp(Tokens.SPACE_1), 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (query.isNotEmpty()) {
            field.addView(
                ui
                    .icon(R.drawable.ic_close, getString(R.string.emoji_clear_search)) {
                        query = ""
                        updateAuxiliary()
                    }.apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        minimumWidth = 0
                        minimumHeight = 0
                        val inset = ui.dp((Tokens.TARGET_MIN - Tokens.ICON_SMALL) / 2)
                        setPadding(
                            inset,
                            (ui.dp(Tokens.STRIP_HEIGHT) - ui.dp(Tokens.ICON_SMALL)) / 2,
                            inset,
                            (ui.dp(Tokens.STRIP_HEIGHT) - ui.dp(Tokens.ICON_SMALL)) / 2,
                        )
                    },
                LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        return field
    }

    private fun acceptSuggestion(
        word: String,
        expected: String,
        generation: Long,
    ) {
        if (generation != editorGeneration || mode != Mode.KEYS || !settings.suggestions ||
            keyboard?.isCursorScrubbing == true || suggestionHandler.hasCallbacks(refreshAfterCursorScrub)
        ) {
            return
        }
        input.forgetPickedSpace()
        if (fieldPolicy.password) {
            if (expected.isEmpty() && word in words.commonWords(language)) currentInputConnection?.commitText(word, 1)
            return
        }
        if (fieldPolicy.reason == SuggestionPolicy.Reason.ADDRESS) {
            val ic = currentInputConnection ?: return
            val addresses = savedAddresses()
            if (addresses != null && word in addresses.matches) {
                if (addresses.typed != expected) return updateAuxiliary()
                val done = if (expected.isEmpty()) ic.commitText(word, 1) else input.replaceBeforeCursor(expected, word)
                if (!done) message(R.string.msg_app_rejected_address)
                updateAuxiliary()
                return
            }
        }
        val ic = currentInputConnection ?: return
        val context = prefixContext()
        if (context.word != expected || (expected.isEmpty() && !context.idle)) {
            updateAuxiliary()
            return
        }
        // An emoji replaces the typed word: "fire" → "🔥 ".
        if (expected.isNotEmpty() && Emojis.isEmoji(word)) {
            if (input.replaceWord(context, word + if (context.textAfterWord) "" else " ")) {
                if (!privateSession && !fieldPolicy.password) emojiHistory.record(word)
            } else {
                message(R.string.msg_app_rejected_emoji)
            }
            input.forgetSpace()
            updateAuxiliary()
            return
        }
        if (!input.accept(word, context)) {
            message(R.string.msg_app_rejected_completion)
        } else if (keyboard?.capsLocked != true) {
            keyboard?.shifted = false
        }
        updateAuxiliary()
        // Some editors publish surrounding text after commitText returns, just like ordinary typing.
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.postDelayed(refreshSuggestions, 30L)
    }

    private fun pasteClipboard() {
        input.forgetPickedSpace()
        predictionTrail.reset()
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
                message(R.string.msg_nothing_to_paste)
            }
            return
        }
        if (!raw.isNullOrEmpty()) {
            paste(raw)
            return
        }
        input.finishComposing()
        val ic = currentInputConnection
        if (ic?.performContextMenuAction(android.R.id.paste) == true) {
            input.forgetSpace()
            if (mode == Mode.KEYS) updateAuxiliary()
        } else {
            message(R.string.msg_nothing_to_paste)
        }
    }

    private fun paste(text: String) {
        input.finishComposing()
        input.forgetPickedSpace()
        predictionTrail.reset()
        if (currentInputConnection?.commitText(text, 1) == false) message(R.string.msg_app_rejected_text)
        input.forgetSpace()
        if (mode == Mode.KEYS) updateAuxiliary()
    }

    private fun pasteEmoji(text: String) {
        input.finishComposing()
        if (currentInputConnection?.commitText(text, 1) == true && !privateSession && !fieldPolicy.password) emojiHistory.record(text)
        input.forgetSpace()
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

    private fun canRepeatWords(): Boolean =
        mode == Mode.EMOJI_SEARCH ||
            (mode == Mode.KEYS && !fieldPolicy.password && !fieldPolicy.numeric && currentInputEditorInfo?.inputType != InputType.TYPE_NULL)

    private fun scheduleDeleteRefresh() {
        // Throttle rather than debounce: the strip still updates during a sustained hold.
        if (!suggestionHandler.hasCallbacks(refreshAfterDeleteRepeat)) {
            suggestionHandler.postDelayed(refreshAfterDeleteRepeat, DELETE_REPEAT_REFRESH_MS)
        }
    }

    private fun repeatDelete(preferWord: Boolean): DeleteRepeat {
        val word = preferWord && canRepeatWords()
        val result =
            when (mode) {
                Mode.EMOJI_SEARCH, Mode.CALCULATOR -> {
                    if (query.isEmpty()) return DeleteRepeat.STOP
                    if (word) {
                        query = query.dropLast(repeatWordDeleteLength(query))
                        DeleteRepeat.WORD
                    } else {
                        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
                        DeleteRepeat.CHARACTER
                    }
                }

                Mode.KEYS -> {
                    val ic = currentInputConnection ?: return DeleteRepeat.STOP
                    input.finishComposing()
                    repeatEditorDelete(ic, word)
                }

                else -> {
                    return DeleteRepeat.STOP
                }
            }
        if (result != DeleteRepeat.STOP) {
            pressDeleted = null
            input.forgetCorrection()
            input.forgetPickedSpace()
            input.forgetSpace()
            lastShift = 0L
            predictionTrail.reset()
            scheduleDeleteRefresh()
        }
        return result
    }

    private fun repeatEditorDelete(
        ic: InputConnection,
        word: Boolean,
    ): DeleteRepeat {
        // Leave password and raw-key editors to native backspace without reading their text.
        val before =
            if (fieldPolicy.password || currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
                null
            } else {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return DeleteRepeat.STOP
                ic.getTextBeforeCursor(if (word) DELETE_WORD_LOOKBACK else 2, 0)
            }
        if (before != null) {
            if (before.isEmpty()) return DeleteRepeat.STOP
            if (word) {
                // A bounded editor response can start halfway through a surrogate pair.
                val tail = if (before[0].isLowSurrogate()) before.subSequence(1, before.length) else before
                if (tail.isNotEmpty()) {
                    val count = repeatWordDeleteLength(tail.toString())
                    return if (ic.deleteSurroundingText(count, 0)) DeleteRepeat.WORD else DeleteRepeat.STOP
                }
            }
        }
        if (!sendKey(ic, KeyEvent.KEYCODE_DEL)) return DeleteRepeat.STOP
        // If the editor hides its text, don't pause or try to switch into word mode later in this hold.
        return if (before == null || word) DeleteRepeat.CHARACTER_ONLY else DeleteRepeat.CHARACTER
    }

    private fun onKey(key: String) {
        if (suggestionHandler.hasCallbacks(refreshAfterDeleteRepeat)) {
            suggestionHandler.removeCallbacks(refreshAfterDeleteRepeat)
            updateCapitalization()
        }
        predictionTrail.reset()
        if (mode == Mode.EMOJI_SEARCH || mode == Mode.CALCULATOR) return onInternalKey(key)
        val ic = currentInputConnection ?: return
        val kv = keyboard ?: return
        val pickedTail = input.beginKey(key, key in setOf(Keys.SHIFT, Keys.SYMBOLS, Keys.MORE_SYMBOLS, Keys.LETTERS, Keys.PAD))
        pressDeleted = null
        if (key == Keys.BACKSPACE && ic.getSelectedText(0).isNullOrEmpty() && input.undoCorrection() != null) {
            lastShift = 0L
            updateAuxiliary()
            return
        }
        if (key != Keys.SHIFT) input.forgetCorrection()
        if (key in InputController.SEPARATORS) input.beforeSeparator(key)
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
                    input.finishComposing()
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(2, 0)?.toString()
                    if (!before.isNullOrEmpty()) {
                        pressDeleted = before.substring(before.offsetByCodePoints(before.length, -1))
                    }
                    if (!input.backspaceComposing()) sendKey(ic, KeyEvent.KEYCODE_DEL)
                }
            }

            Keys.ENTER -> {
                input.finishComposing()
                val info = currentInputEditorInfo
                val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!noAction && !info?.actionLabel.isNullOrBlank()) {
                    if (!ic.performEditorAction(info.actionId)) message(R.string.msg_app_ignored_action)
                } else if (!noAction && action > EditorInfo.IME_ACTION_NONE) {
                    if (!ic.performEditorAction(action)) message(R.string.msg_app_ignored_action)
                } else {
                    ic.commitText("\n", 1)
                }
            }

            Keys.GLOBE -> {
                toggleLanguage()
            }

            Keys.SYMBOLS -> {
                input.finishComposing()
                kv.layout = Layout.SYMBOLS
            }

            Keys.MORE_SYMBOLS -> {
                input.finishComposing()
                kv.layout = Layout.SYMBOLS_MORE
            }

            Keys.PAD -> {
                input.finishComposing()
                kv.layout = Layout.NUMPAD
            }

            Keys.LETTERS -> {
                input.finishComposing()
                kv.layout = language.layout
            }

            Keys.SPACE -> {
                input.space()
            }

            else -> {
                input.character(if (kv.shifted) key.uppercase(Locale.ROOT) else key, key, pickedTail)
                if (!kv.capsLocked) kv.shifted = false
            }
        }
        if (key != Keys.SHIFT) lastShift = 0L
        updateAuxiliary()
        // Re-read once after editors publish an asynchronously committed surrounding-text update.
        suggestionHandler.removeCallbacks(refreshSuggestions)
        suggestionHandler.postDelayed(refreshSuggestions, 30L)
    }

    /** One recording guard for learned words, pairs and correction rejections. */
    private fun learningAllowed(): Boolean =
        settings.learnWords && !privateSession && fieldPolicy.reason == SuggestionPolicy.Reason.AVAILABLE &&
            ((currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0 &&
            keyboard?.layout == language.layout && mode == Mode.KEYS

    /** Slide left from backspace: [steps] whole words get selected so the user sees what release deletes. */
    private fun deleteSwipe(steps: Int) {
        val ic = currentInputConnection ?: return
        if (swipeCursor < 0 && swipeBefore == null) {
            input.finishComposing()
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
        input.forgetCorrection()
        input.forgetSpace()
        updateAuxiliary()
    }

    /** Line breaks get their own repeat tick instead of disappearing with the preceding word. */
    private fun repeatWordDeleteLength(text: String): Int {
        val lineStart = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r')) + 1
        if (lineStart < text.length) return wordDeleteLength(text.substring(lineStart))
        return if (text.endsWith("\r\n")) 2 else 1
    }

    /** UTF-16 length of trailing whitespace plus the word (or punctuation/emoji run) before it. */
    private fun wordDeleteLength(text: String): Int {
        val spaces = text.length - text.trimEnd().length
        val rest = text.dropLast(spaces)
        val last = rest.lastOrNull() ?: return spaces
        val run =
            if (StripPlanner.wordChar(last)) {
                rest.takeLastWhile(StripPlanner::wordChar)
            } else {
                rest.takeLastWhile { !StripPlanner.wordChar(it) && !it.isWhitespace() }
            }
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
        if (mode != Mode.KEYS) return
        val ic = currentInputConnection ?: return
        val right = steps > 0
        val arrow = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        input.finishComposing()
        input.forgetSpace()
        input.reset()
        predictionTrail.reset()
        repeat(kotlin.math.abs(steps).coerceAtMost(MAX_CURSOR_STEPS_PER_EVENT)) {
            if (fieldPolicy.password) {
                // Masked fields use the existing logical guard without fetching extra password text.
                val next = (if (right) ic.getTextAfterCursor(1, 0) else ic.getTextBeforeCursor(1, 0))
                if (next.isNullOrEmpty() || !sendKey(ic, arrow)) return
                return@repeat
            }
            val around = ic.getSurroundingText(1, 1, 0) ?: return
            val start = minOf(around.selectionStart, around.selectionEnd)
            val end = maxOf(around.selectionStart, around.selectionEnd)
            if (start < 0 || end > around.text.length) return
            if (start == end && (start == 0 || end == around.text.length)) {
                if (around.text.isEmpty()) return
                val caret = around.offset.toLong() + start
                if (!canMoveInFromCursorEdge(ic, start == 0, caret.takeIf { around.offset >= 0 }, right)) return
            }
            // The editor owns visual navigation through shaped text, line wraps and bidi runs,
            // including the first visual caret stop when moving inward from a document edge.
            if (!sendKey(ic, arrow)) return
        }
    }

    private fun canMoveInFromCursorEdge(
        ic: InputConnection,
        atStart: Boolean,
        caret: Long?,
        right: Boolean,
    ): Boolean {
        val text =
            (if (atStart) ic.getTextAfterCursor(CURSOR_EDGE_CONTEXT, 0) else ic.getTextBeforeCursor(CURSOR_EDGE_CONTEXT, 0))
                ?: return false
        if (text.isEmpty()) return false
        val newline = if (atStart) text.indexOf('\n') else text.lastIndexOf('\n')
        val paragraphStart = if (atStart) 0 else newline + 1
        val paragraphEnd = if (atStart && newline >= 0) newline else text.length
        val length = paragraphEnd - paragraphStart
        val ltrDefault = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, paragraphStart, length)
        val rtlDefault = TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(text, paragraphStart, length)

        // Text before/after is in storage order, not screen order. At the edge, only send
        // an inward arrow. If the paragraph's first strong character could be outside
        // the window, don't risk sending a focus-traversal key.
        if (newline < 0 &&
            if (atStart) {
                text.length >= CURSOR_EDGE_CONTEXT && ltrDefault != rtlDefault
            } else {
                caret?.let { it > text.length } ?: (text.length >= CURSOR_EDGE_CONTEXT)
            }
        ) {
            return false
        }
        val rtl = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) rtlDefault else ltrDefault
        return right == (atStart != rtl)
    }

    private fun message(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val DELETE_REPEAT_REFRESH_MS = 90L
        const val DELETE_WORD_LOOKBACK = 64
        const val CURSOR_EDGE_CONTEXT = 2_048
        const val CURSOR_REFRESH_MS = 16L
        const val MAX_CURSOR_STEPS_PER_EVENT = 32
    }
}
