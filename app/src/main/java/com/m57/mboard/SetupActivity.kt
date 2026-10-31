package com.m57.mboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** A compact settings home and focused pages, using native controls and no new dependencies. */
class SetupActivity : Activity() {
    private enum class Page(
        val title: String,
    ) {
        HOME("mboard"),
        APPEARANCE("Appearance"),
        TYPING("Typing"),
        PRIVACY("Clipboard & privacy"),
        PHRASES("Saved phrases"),
        PLAYGROUND("Try your keyboard"),
        ABOUT("About mboard"),
    }

    private lateinit var settings: BoardSettings
    private lateinit var ui: BoardUi
    private lateinit var components: SettingsUi
    private var page = Page.HOME
    private var scroller: ScrollView? = null
    private var status: TextView? = null
    private var setupActions: LinearLayout? = null
    private var sandbox: EditText? = null
    private var phraseEditor: EditText? = null
    private var draft = ""
    private var phraseDraft = ""
    private var activeDialog: AlertDialog? = null

    private data class RetainedPhrase(
        val text: String,
        val start: Int,
        val end: Int,
    )

    private var playgroundStart = 0
    private var playgroundEnd = 0
    private var phraseStart = 0
    private var phraseEnd = 0
    private var fieldType = NORMAL_INPUT
    private var renderGeneration = 0L
    private val scrollPositions = mutableMapOf<Page, Int>()
    private val navigation = mutableListOf<Page>()
    private val passwordPlayground get() = SuggestionPolicy.classify(fieldType).password
    private val backAction = OnBackInvokedCallback { if (page == Page.HOME) finish() else goBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BoardSettings(this)
        fieldType =
            (savedInstanceState?.getInt("field_type", NORMAL_INPUT) ?: NORMAL_INPUT).takeIf { it in PLAYGROUND_TYPES } ?: NORMAL_INPUT
        (lastNonConfigurationInstance as? RetainedPhrase)?.let {
            phraseDraft = it.text
            phraseStart = it.start
            phraseEnd = it.end
        }
        draft = if (passwordPlayground) "" else savedInstanceState?.getString("draft").orEmpty()
        page = Page.entries.firstOrNull { it.name == savedInstanceState?.getString("page") } ?: Page.HOME
        savedInstanceState
            ?.getStringArrayList("navigation")
            ?.mapNotNull { name ->
                Page.entries.firstOrNull { it.name == name }
            }?.let(navigation::addAll)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backAction)
        render()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        renderGeneration++
        activeDialog?.dismiss()
        activeDialog = null
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backAction)
        super.onDestroy()
    }

    // Native Activity retention keeps an unsaved phrase through rotation, never in a Bundle or store.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any {
        capture()
        return RetainedPhrase(phraseDraft, phraseStart, phraseEnd)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capture()
        outState.putString("draft", if (passwordPlayground) "" else draft)
        outState.putInt("field_type", fieldType)
        outState.putString("page", page.name)
        outState.putStringArrayList("navigation", ArrayList(navigation.map { it.name }))
        super.onSaveInstanceState(outState)
    }

    private fun capture() {
        scrollPositions[page] = scroller?.scrollY ?: 0
        sandbox?.let {
            draft = if (passwordPlayground) "" else it.text.toString()
            playgroundStart = if (passwordPlayground) 0 else it.selectionStart.coerceAtLeast(0)
            playgroundEnd = if (passwordPlayground) 0 else it.selectionEnd.coerceAtLeast(0)
        }
        phraseEditor?.let {
            phraseDraft = it.text.toString()
            phraseStart = it.selectionStart.coerceAtLeast(0)
            phraseEnd = it.selectionEnd.coerceAtLeast(0)
        }
    }

    private fun goBack() = navigate(navigation.removeLastOrNull() ?: Page.HOME, remember = false)

    private fun navigate(
        next: Page,
        remember: Boolean = true,
    ) {
        if (page == next) return
        capture()
        if (next == Page.HOME) {
            navigation.clear()
        } else if (remember) {
            navigation.add(page)
        }
        if (passwordPlayground) {
            sandbox?.text?.clear()
            draft = ""
        }
        window.decorView.windowInsetsController?.hide(WindowInsets.Type.ime())
        val forward = remember && next != Page.HOME
        page = next
        render(captureState = false)
        // A short slide-and-fade tells forward from back without slowing navigation down.
        scroller?.apply {
            alpha = 0f
            translationX = ui.dp(if (forward) Tokens.SPACE_5 else -Tokens.SPACE_5).toFloat()
            animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(Tokens.MOTION_BASE)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun render(captureState: Boolean = true) {
        activeDialog?.dismiss()
        activeDialog = null
        val focused =
            when {
                sandbox?.hasFocus() == true -> Page.PLAYGROUND
                phraseEditor?.hasFocus() == true -> Page.PHRASES
                else -> null
            }
        val hadIme = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (captureState) capture()
        if (passwordPlayground) {
            draft = ""
            sandbox?.text?.clear()
        }
        sandbox = null
        phraseEditor = null
        status = null
        setupActions = null
        val generation = ++renderGeneration
        ui = BoardUi(this)
        components = SettingsUi(this, ui)
        val appearances = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.decorView.windowInsetsController?.setSystemBarsAppearance(if (ui.dark) 0 else appearances, appearances)

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ui.background)
                setOnApplyWindowInsetsListener { target, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                    insets
                }
            }
        val header =
            CappedColumn(this, ui.dp(Tokens.CONTENT_MAX_WIDTH)).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(Tokens.SETTINGS_HEADER)
                setPadding(ui.dp(Tokens.SPACE_3), 0, ui.dp(Tokens.SPACE_4), 0)
            }
        if (page != Page.HOME) {
            header.addView(
                ui.icon(R.drawable.ic_arrow_back, "Back") { goBack() },
                LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)),
            )
        }
        header.addView(
            ui.label(page.title, if (page == Page.HOME) Tokens.TEXT_TITLE else Tokens.TEXT_HEADER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAccessibilityHeading = true
                setPadding(if (page == Page.HOME) ui.dp(Tokens.SPACE_2) else ui.dp(Tokens.SPACE_1), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        val column =
            CappedColumn(this, ui.dp(Tokens.CONTENT_MAX_WIDTH)).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(Tokens.SPACE_4), 0, ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_5))
                isFocusableInTouchMode = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    column,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
        scroller = scroll
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        when (page) {
            Page.HOME -> home(column)
            Page.APPEARANCE -> appearance(column)
            Page.TYPING -> typing(column)
            Page.PRIVACY -> privacy(column)
            Page.PHRASES -> phrases(column)
            Page.PLAYGROUND -> playground(column)
            Page.ABOUT -> about(column)
        }
        column.requestFocus()
        root.requestApplyInsets()
        scroll.post {
            if (generation != renderGeneration || isFinishing || isDestroyed) return@post
            val editor = if (captureState && focused == page) sandbox ?: phraseEditor else null
            if (editor != null) {
                editor.requestFocus()
                val start = if (page == Page.PLAYGROUND) playgroundStart else phraseStart
                val end = if (page == Page.PLAYGROUND) playgroundEnd else phraseEnd
                editor.setSelection(start.coerceIn(0, editor.length()), end.coerceIn(0, editor.length()))
                if (hadIme) editor.windowInsetsController?.show(WindowInsets.Type.ime())
            }
            scroll.scrollTo(0, scrollPositions[page] ?: 0)
        }
        updateStatus()
    }

    private fun home(column: LinearLayout) {
        components.add(column, components.note("${Languages.labels}. Entirely offline."))
        val statusView =
            ui.label("", Tokens.TEXT_BODY).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4))
                background = ui.shape(ui.accentContainer, Tokens.RADIUS_CARD)
            }
        status = statusView
        components.add(column, statusView)
        setupActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(setupActions)
        column.addView(components.section("MAKE IT YOURS"))
        components.add(
            column,
            components.row("Appearance", "${themeName()} · ${accentName().substringBefore(" ·")}", ui.accent) {
                navigate(Page.APPEARANCE)
            },
        )
        components.add(
            column,
            components.row(
                "Typing",
                "Suggestions ${onOff(settings.suggestions)} · capitalization ${onOff(settings.autoCapitalize)}",
            ) {
                navigate(Page.TYPING)
            },
        )
        column.addView(components.section("ON YOUR DEVICE"))
        components.add(
            column,
            components.row("Clipboard & privacy", "History ${onOff(settings.clipboardHistory)} · local controls") {
                navigate(Page.PRIVACY)
            },
        )
        components.add(column, components.row("Saved phrases", "Replies, addresses and signatures") { navigate(Page.PHRASES) })
        column.addView(components.section("EXPLORE"))
        components.add(
            column,
            components.row("Try your keyboard", "A separate playground, not another setting") { navigate(Page.PLAYGROUND) },
        )
        components.add(column, components.row("About mboard", "Gestures, offline tools and credits") { navigate(Page.ABOUT) })
    }

    private fun appearance(column: LinearLayout) {
        components.add(column, components.note("Tune the look and feel. Changes apply to the keyboard immediately."))
        components.add(
            column,
            components.row("Preview in playground", "Type with the keyboard as it looks now") { navigate(Page.PLAYGROUND) },
        )
        column.addView(components.section("COLOR"))
        choice(
            column,
            "Theme",
            themeName(),
            listOf("Follow system", "Dark", "Light"),
            listOf("system", "dark", "light").indexOf(settings.appearance),
        ) {
            settings.appearance = listOf("system", "dark", "light")[it]
            render()
        }
        choice(
            column,
            "Accent preset",
            accentName(),
            listOf("Violet", "Teal", "Amber", "Crimson · #990000"),
            listOf("violet", "teal", "amber", "crimson").indexOf(settings.accent),
            ui.accent,
            listOf("violet", "teal", "amber", "crimson").map { Tokens.accents.getValue(it).let { a -> if (ui.dark) a.dark else a.light } },
        ) {
            settings.accent = listOf("violet", "teal", "amber", "crimson")[it]
            render()
        }
        column.addView(components.section("CUSTOM COLORS"))
        COLOR_ROLES.forEach { (role, title) ->
            val custom = settings.customColor(role)
            components.add(
                column,
                components.row(title, custom?.let(::hex) ?: "Theme default", custom ?: themeColor(role)) { colorDialog(role, title) },
            )
        }
        if (BoardSettings.COLOR_ROLES.any { settings.customColor(it) != null }) {
            components.add(
                column,
                components.row("Reset custom colors", "Go back to the theme and accent above", destructive = true) {
                    BoardSettings.COLOR_ROLES.forEach { settings.setCustomColor(it, null) }
                    render()
                },
            )
        }
        components.add(column, components.note("Custom colors override the theme and accent. Text on accent keys adjusts for contrast."))
        column.addView(components.section("KEYBOARD LAYOUT"))
        choice(
            column,
            "Key height",
            "${settings.rowHeightDp} dp",
            listOf("Compact · 44 dp", "Balanced · 54 dp", "Roomy · 64 dp"),
            listOf(44, 54, 64).indexOf(settings.rowHeightDp),
        ) {
            settings.rowHeightDp = listOf(44, 54, 64)[it]
            render()
        }
        choice(
            column,
            "Thumb mode",
            thumbName(),
            listOf("Full width", "Left thumb", "Right thumb"),
            listOf("full", "left", "right").indexOf(settings.oneHandMode),
        ) {
            settings.oneHandMode = listOf("full", "left", "right")[it]
            render()
        }
        choice(
            column,
            "Dial pad swipe",
            dialName(),
            listOf("Off", "Swipe from left edge", "Swipe from right edge"),
            listOf("off", "left", "right").indexOf(settings.dialPadEdge),
        ) {
            val edge = listOf("off", "left", "right")[it]
            settings.dialPadEdge = edge
            // Each edge holds one panel: the other panel moves to the opposite edge.
            if (edge != "off" && settings.emojiEdge == edge) settings.emojiEdge = opposite(edge)
            render()
        }
        choice(
            column,
            "Recent emoji swipe",
            edgeName(settings.emojiEdge),
            listOf("Off", "Swipe from left edge", "Swipe from right edge"),
            listOf("off", "left", "right").indexOf(settings.emojiEdge),
        ) {
            val edge = listOf("off", "left", "right")[it]
            settings.emojiEdge = edge
            if (edge != "off" && settings.dialPadEdge == edge) settings.dialPadEdge = opposite(edge)
            render()
        }
        components.add(
            column,
            components.note(
                "Thumb mode moves real keys; narrow windows use full width to keep them usable. Swipe in from an edge to show its panel; swipe back out to hide it.",
            ),
        )
        column.addView(components.section("KEY DETAILS"))
        components.add(
            column,
            components.toggle("Number row", "Keep digits above the letters", settings.numberRow) {
                settings.numberRow =
                    it
            },
        )
        components.add(
            column,
            components.toggle("Key previews", "Show a brief letter preview when pressed", settings.keyPreview) {
                settings.keyPreview =
                    it
            },
        )
    }

    private fun typing(column: LinearLayout) {
        components.add(
            column,
            components.note("Helpful when you want it. Bundled dictionaries plus, if you allow it, words you type, kept on this device."),
        )
        column.addView(components.section("INPUT ASSISTANCE"))
        components.add(
            column,
            components.toggle(
                "Word suggestions",
                "${Languages.ALL.joinToString(" and ") { it.name }} · tap to complete",
                settings.suggestions,
            ) {
                settings.suggestions =
                    it
            },
        )
        components.add(
            column,
            components.toggle(
                "Auto-correction",
                "Fix misspellings on space · backspace undoes",
                settings.autoCorrect,
            ) {
                settings.autoCorrect =
                    it
            },
        )
        components.add(
            column,
            components.toggle(
                "Learn my words",
                "Learn frequent words and corrections on this device",
                settings.learnWords,
            ) {
                settings.learnWords = it
            },
        )
        Languages.ALL.mapNotNull { it.dialect }.forEach { dialect ->
            components.add(
                column,
                components.toggle(dialect.title, dialect.summary, settings.dialectEnabled(dialect)) {
                    settings.setDialectEnabled(dialect, it)
                },
            )
        }
        components.add(
            column,
            components.toggle("Emoji suggestions", "Offer a matching emoji for words like fire or love", settings.emojiSuggestions) {
                settings.emojiSuggestions = it
            },
        )
        components.add(
            column,
            components.toggle(
                "Arabizi suggestions",
                "Arabic words for Franco typing like 7abibi · never automatic",
                settings.arabiziSuggestions,
            ) {
                settings.arabiziSuggestions = it
            },
        )
        components.add(
            column,
            components.toggle("Automatic capitalization", "Follow the active app's capitalization hints", settings.autoCapitalize) {
                settings.autoCapitalize =
                    it
            },
        )
        components.add(
            column,
            components.toggle("Double-space period", "Two quick spaces after a word insert a period", settings.doubleSpacePeriod) {
                settings.doubleSpacePeriod =
                    it
            },
        )
        components.add(
            column,
            components.note(
                "Learned words fade over time. Recording pauses in protected fields and under the shield. Arabizi works on the English layout and only suggests.",
            ),
        )
        column.addView(components.section("QUICK GESTURES"))
        components.add(
            column,
            components.row("Try these gestures", "Swipe, hold and slide in the playground") { navigate(Page.PLAYGROUND) },
        )
    }

    private fun privacy(column: LinearLayout) {
        val clips = ClipStore(this)
        components.add(column, components.note("Nothing leaves this device. History is optional and can be cleared here."))
        column.addView(components.section("CLIPBOARD HISTORY"))
        components.add(
            column,
            components.toggle("Save copied text", "Only while mboard is visible. Sensitive clips are skipped.", settings.clipboardHistory) {
                settings.clipboardHistory =
                    it
            },
        )
        choice(
            column,
            "Unpinned retention",
            ClipStore.RETENTION_OPTIONS.firstOrNull { it.second == clips.retentionMs }?.first ?: "1 hour",
            ClipStore.RETENTION_OPTIONS.map { it.first },
            ClipStore.RETENTION_OPTIONS.indexOfFirst { it.second == clips.retentionMs },
        ) {
            clips.retentionMs = ClipStore.RETENTION_OPTIONS[it].second
            render()
        }
        components.add(
            column,
            components.note("Pinned clips stay until you delete them. Expired clips are removed on the next history access."),
        )
        column.addView(components.section("PRIVATE INPUT"))
        components.add(
            column,
            components.note(
                "The keyboard's shield toggles a private session: clipboard recording and word learning pause. Password fields are protected automatically. App no-learning flags stop capture and history; static word completions can still work.",
            ),
        )
        column.addView(components.section("CLEAR LOCAL DATA"))
        components.add(
            column,
            components.row(
                "Delete learned words",
                "${UserWords(this).size()} words learned from your typing, plus next-word pairs and remembered correction rejections",
                destructive = true,
            ) {
                confirm(
                    "Delete learned words?",
                    "Erase local words, pairs and remembered correction rejections. Keep the bundled dictionaries.",
                ) {
                    UserWords(this).clear()
                    settings.markLearnedReset()
                    toast("Learned words deleted")
                    render()
                }
            },
        )
        components.add(
            column,
            components.row("Delete clipboard history", "Includes pinned clips · asks for confirmation", destructive = true) {
                confirm("Delete clipboard history?", "Pinned clips are included. Your system clipboard is unchanged.") {
                    clips.clearAll()
                    toast("Clipboard history deleted")
                }
            },
        )
    }

    private fun phrases(column: LinearLayout) {
        val store = PhraseStore(this)
        components.add(column, components.note("Only phrases you explicitly save are stored. Drafts stay in this activity's memory."))
        column.addView(components.section("NEW PHRASE"))
        val editor =
            textEditor(
                "A reply, address or signature",
                "New saved phrase",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                2000,
            ).apply {
                setText(phraseDraft)
                setSelection(phraseStart.coerceIn(0, length()), phraseEnd.coerceIn(0, length()))
            }
        phraseEditor = editor
        components.add(column, editor)
        val count = ui.label("", Tokens.TEXT_CAPTION, true)
        val helper =
            ui.label("", Tokens.TEXT_CAPTION, true).apply {
                setPadding(ui.dp(Tokens.SPACE_1), 0, ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_1))
            }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var save: android.widget.Button

        // Disabled Save keeps a readable surface look and says why in the helper line below the field.
        fun updateSave() {
            val clean = editor.text.toString().trim()
            val saved = store.all()
            val reason =
                when {
                    clean.isEmpty() -> "Type a phrase"
                    saved.size >= 40 -> "40 of 40 saved"
                    clean in saved -> "Already saved"
                    else -> ""
                }
            val ok = reason.isEmpty()
            helper.text = reason
            helper.visibility = if (ok) View.INVISIBLE else View.VISIBLE
            save.setTextColor(if (ok) ui.onAccent else ui.muted)
            save.background = ui.ripple(if (ok) ui.accent else ui.surface)
            ui.setEnabledLook(save, ok)
        }

        fun refresh() {
            val values = store.all()
            count.text = "${values.size} of 40 saved"
            list.removeAllViews()
            if (values.isEmpty()) components.add(list, components.note("No saved phrases yet. Add your first one above."))
            values.forEach { phrase ->
                val row =
                    LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = ui.shape(ui.surface)
                        setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_2))
                    }
                row.addView(
                    ui.label(phrase, Tokens.TEXT_BODY).apply {
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    ui.icon(R.drawable.ic_delete, "Delete saved phrase") {
                        confirm("Delete this phrase?", "This removes it from your keyboard's phrase list.") {
                            store.remove(phrase)
                            refresh()
                        }
                    },
                    LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)),
                )
                components.add(list, row)
            }
            updateSave()
        }
        save =
            ui.button("Save phrase", true) {
                if (store.add(editor.text.toString())) {
                    editor.setText("")
                    phraseDraft = ""
                    refresh()
                    toast("Phrase saved")
                } else {
                    updateSave()
                }
            }
        components.add(column, helper)
        components.add(column, save)
        // Android hides device accounts from other apps; the system picker shares only the one the user taps.
        components.add(
            column,
            ui.button("Add emails from device accounts", action = ::pickAccount),
        )
        column.addView(components.section("YOUR PHRASES"))
        components.add(column, count)
        column.addView(list)
        editor.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    updateSave()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
        refresh()
    }

    private fun playground(column: LinearLayout) {
        components.add(column, components.note("Try typing, gestures and suggestions here without opening another app."))
        val names = listOf("Normal text", "Number", "Email", "Password")
        val types = PLAYGROUND_TYPES
        choice(column, "Field type", names[types.indexOf(fieldType).coerceAtLeast(0)], names, types.indexOf(fieldType)) {
            val next = types[it]
            if (SuggestionPolicy.clearPasswordTransition(fieldType, next)) {
                sandbox?.text?.clear()
                draft = ""
            }
            fieldType = next
            sandbox?.inputType = fieldType
            render()
        }
        val editor =
            textEditor("Tap here and start typing", "Typing playground", fieldType, 4000).apply {
                minHeight = ui.dp(Tokens.TARGET_MIN * 3)
                maxLines = 6
                setText(if (passwordPlayground) "" else draft)
                setSelection(playgroundStart.coerceIn(0, length()), playgroundEnd.coerceIn(0, length()))
            }
        sandbox = editor
        components.add(column, editor)
        components.add(
            column,
            components.note(
                "Password samples clear on leaving this page, changing field type, or rebuilding appearance. Don't enter a real password here.",
            ),
        )
        column.addView(components.section("WHAT TO TRY"))
        components.add(
            column,
            components.note(
                "Swipe space: switch language\nHold space: move cursor\nSlide left from backspace: delete words\nDouble-tap Shift: caps lock\nHold a letter: alternates",
            ),
        )
    }

    private fun about(column: LinearLayout) {
        components.add(column, components.note("${Languages.labels}. Built for local, quiet typing."))
        column.addView(components.section("OFFLINE BY DESIGN"))
        components.add(
            column,
            components.note(
                "No network permission. No sound or vibration. No cloud calls. Learned words stay on this device and can be deleted any time. App data is excluded from Android cloud backup and device transfer.",
            ),
        )
        column.addView(components.section("KEYBOARD TOOLS"))
        components.add(
            column,
            components.note(
                "Emoji: categories, favorites and Arabic/English search.\nSaved phrases: suggested when you start typing them.\nNumber pad: tap the dial pad key on the symbols page.\nCalculator: a math pad; = inserts the result.\nPercent: 50% means 0.5.",
            ),
        )
        column.addView(components.section("CREDITS"))
        components.add(
            column,
            components.row("Licenses & word lists", "Bundled data, attribution and full notices") {
                val notice = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
                AlertDialog
                    .Builder(
                        dialogContext(),
                    ).setTitle("Credits")
                    .setMessage(notice)
                    .setPositiveButton("Close", null)
                    .create()
                    .let(::showDialog)
            },
        )
        components.add(
            column,
            components.row("Manage keyboards", "Open Android's keyboard settings", external = true) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        components.add(
            column,
            components.row("Switch keyboard", "Open Android's keyboard picker", external = true) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
        )
    }

    private fun updateStatus() {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        status?.text =
            when {
                active -> "Active keyboard · ready to type"
                enabled -> "Enabled · choose mboard to start"
                else -> "Set up your keyboard"
            }
        setupActions?.let { actions ->
            actions.removeAllViews()
            if (!enabled) {
                components.add(
                    actions,
                    ui.button("Enable mboard", true) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                )
            }
            if (enabled && !active) components.add(actions, ui.button("Choose mboard", true) { manager.showInputMethodPicker() })
        }
    }

    private fun choice(
        column: LinearLayout,
        title: String,
        value: String,
        values: List<String>,
        selected: Int,
        swatch: Int? = null,
        swatches: List<Int>? = null,
        select: (Int) -> Unit,
    ) {
        components.add(
            column,
            components.row(title, value, swatch) {
                val generation = renderGeneration
                val adapter =
                    object : ArrayAdapter<String>(dialogContext(), 0, values) {
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View = ChoiceRow(dialogContext(), ui, values[position], swatches?.getOrNull(position))
                    }
                AlertDialog
                    .Builder(dialogContext())
                    .setTitle(title)
                    .setSingleChoiceItems(adapter, selected) { dialog, index ->
                        dialog.dismiss()
                        if (generation == renderGeneration) select(index)
                    }.setNegativeButton("Cancel", null)
                    .create()
                    .let(::showDialog)
            },
        )
    }

    private fun themeColor(role: String) =
        when (role) {
            BoardSettings.ACCENT -> ui.accent
            BoardSettings.BACKGROUND -> ui.background
            BoardSettings.KEYS -> ui.surface
            BoardSettings.SPECIAL_KEYS -> ui.functionSurface
            else -> ui.text
        }

    private fun hex(value: Int) = String.format("#%06X", value and 0xFFFFFF)

    /** The color a role is read against: keys and accent against the background, the background against the text, text against keys. */
    private fun contrastReference(role: String): Pair<Int, String> =
        when (role) {
            BoardSettings.BACKGROUND -> ui.text to "text"
            BoardSettings.TEXT -> ui.surface to "letter keys"
            else -> ui.background to "background"
        }

    /** Swatches for quick picks plus a hex field for exact colors; the live chip and contrast line preview either. */
    private fun colorDialog(
        role: String,
        title: String,
    ) {
        val generation = renderGeneration
        var picked = settings.customColor(role) ?: themeColor(role)
        var valid = true
        val (reference, referenceName) = contrastReference(role)
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_4), 0)
            }
        val preview = View(this)
        val info = ui.label("", Tokens.TEXT_BODY, true).apply { setPadding(0, ui.dp(Tokens.SPACE_2), 0, 0) }
        val field =
            EditText(this).apply {
                setText(hex(picked))
                hint = "#RRGGBB"
                isSingleLine = true
                textSize = Tokens.TEXT_ROW
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(7))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        var applyButton: android.widget.Button? = null

        fun update() {
            val color = picked or 0xFF000000.toInt()
            preview.background = ui.shape(color, Tokens.RADIUS_CONTROL).apply { setStroke(ui.dp(1), ui.muted) }
            if (valid) {
                val ratio = ui.contrast(color, reference)
                val low = ratio < Tokens.WARN_RATIO
                info.text = "Contrast with $referenceName ${String.format("%.1f", ratio)}:1" + if (low) " · low, may be hard to see" else ""
                info.setTextColor(if (low) ui.danger else ui.muted)
            } else {
                info.text = "Enter six hex digits, like #1A2B3C"
                info.setTextColor(ui.danger)
            }
            applyButton?.let { ui.setEnabledLook(it, valid) }
        }
        update()
        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    val raw =
                        s
                            ?.toString()
                            ?.trim()
                            ?.removePrefix("#")
                            .orEmpty()
                    valid = HEX_DIGITS.matches(raw)
                    if (valid) picked = raw.toInt(16)
                    update()
                }
            },
        )
        val top =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)))
                addView(
                    field,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(Tokens.SPACE_3) },
                )
            }
        body.addView(top)
        body.addView(info)
        swatches().chunked(SWATCH_COLUMNS).forEach { rowValues ->
            val row = LinearLayout(this).apply { setPadding(0, ui.dp(Tokens.SPACE_2), 0, 0) }
            val margin = ui.dp(Tokens.SPACE_1 / 2)
            rowValues.forEach { color ->
                row.addView(
                    View(this).apply {
                        background = ui.shape(color, Tokens.RADIUS_PILL).apply { setStroke(ui.dp(1), ui.muted) }
                        contentDescription = hex(color)
                        setOnClickListener { field.setText(hex(color)) }
                    },
                    LinearLayout.LayoutParams(0, ui.dp(Tokens.TARGET_MIN), 1f).apply { setMargins(margin, 0, margin, 0) },
                )
            }
            repeat(SWATCH_COLUMNS - rowValues.size) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, ui.dp(Tokens.TARGET_MIN), 1f).apply { setMargins(margin, 0, margin, 0) },
                )
            }
            body.addView(row)
        }
        val dialog =
            AlertDialog
                .Builder(dialogContext())
                .setTitle(title)
                .setView(ScrollView(this).apply { addView(body) })
                .setPositiveButton("Apply") { _, _ ->
                    if (generation == renderGeneration && valid) {
                        settings.setCustomColor(role, picked or 0xFF000000.toInt())
                        render()
                    }
                }.setNeutralButton("Default") { _, _ ->
                    if (generation == renderGeneration) {
                        settings.setCustomColor(role, null)
                        render()
                    }
                }.setNegativeButton("Cancel", null)
                .create()
        showDialog(dialog)
        applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        update()
    }

    /** Quick picks come from the token palettes and accent presets, so they cannot drift from the theme. */
    private fun swatches(): List<Int> =
        (
            listOf(Tokens.onDarkFill) +
                listOf(Tokens.dark, Tokens.light).flatMap { listOf(it.bg, it.surface, it.function, it.text) } +
                Tokens.accents.values.flatMap { listOf(it.dark, it.light) }
        ).distinct()

    private fun textEditor(
        hintValue: String,
        description: String,
        type: Int,
        limit: Int,
    ) = EditText(this).apply {
        hint = hintValue
        contentDescription = description
        inputType = type
        minHeight = ui.dp(Tokens.TARGET_MIN * 2)
        maxLines = 4
        textSize = Tokens.TEXT_ROW
        setTextColor(ui.text)
        setHintTextColor(ui.muted)
        setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_4))
        background = ui.shape(ui.surface)
        backgroundTintList = null
        filters = arrayOf(InputFilter.LengthFilter(limit))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }

    private fun confirm(
        title: String,
        message: String,
        confirmed: () -> Unit,
    ) {
        val generation = renderGeneration
        AlertDialog
            .Builder(dialogContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> if (generation == renderGeneration) confirmed() }
            .create()
            .let { showDialog(it, destructive = true) }
    }

    private fun dialogContext() =
        ContextThemeWrapper(
            this,
            if (ui.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )

    private fun showDialog(
        dialog: AlertDialog,
        destructive: Boolean = false,
    ) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ui.shape(ui.raised, Tokens.RADIUS_SHEET))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (destructive) ui.danger else ui.controlAccent)
            // A destructive confirm must not offer two buttons in one ink: Cancel goes neutral, the action keeps danger.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(if (destructive) ui.text else ui.controlAccent)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ui.controlAccent)
        }
        dialog.show()
    }

    private fun themeName() =
        when (settings.appearance) {
            "dark" -> "Dark"
            "light" -> "Light"
            else -> "Follow system"
        }

    private fun accentName() =
        when (settings.accent) {
            "teal" -> "Teal"
            "amber" -> "Amber"
            "crimson" -> "Crimson · #990000"
            else -> "Violet"
        }

    private fun thumbName() =
        when (settings.oneHandMode) {
            "left" -> "Left thumb"
            "right" -> "Right thumb"
            else -> "Full width"
        }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /** The system chooser is single-choice and can't be restyled; each tap adds one address. */
    private fun pickAccount() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                android.accounts.AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null),
                PICK_ACCOUNT,
            )
        } catch (_: android.content.ActivityNotFoundException) {
            toast("Account picker is not available on this device")
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_ACCOUNT || resultCode != RESULT_OK) return
        val name = data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.trim()
        val store = PhraseStore(this)
        when {
            name.isNullOrEmpty() || '@' !in name -> toast("That account has no email address")
            store.add(name) -> toast("Added $name")
            else -> toast("Already saved, or the 40-phrase limit was reached")
        }
        render()
    }

    private fun dialName() = edgeName(settings.dialPadEdge)

    private fun opposite(edge: String) = if (edge == "left") "right" else "left"

    private fun edgeName(edge: String) =
        when (edge) {
            "left" -> "Left edge"
            "right" -> "Right edge"
            else -> "Off"
        }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PICK_ACCOUNT = 41
        val COLOR_ROLES =
            listOf(
                BoardSettings.ACCENT to "Accent override",
                BoardSettings.BACKGROUND to "Keyboard background",
                BoardSettings.KEYS to "Letter keys",
                BoardSettings.SPECIAL_KEYS to "Function keys",
                BoardSettings.TEXT to "Text & icons",
            )
        const val SWATCH_COLUMNS = 6
        val HEX_DIGITS = Regex("[0-9A-Fa-f]{6}")
        const val NORMAL_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val PLAYGROUND_TYPES =
            listOf(
                NORMAL_INPUT,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
    }
}

/** A column that never grows wider than [maxWidth] px; its parent centers it. */
private class CappedColumn(
    context: android.content.Context,
    private val maxWidth: Int,
) : LinearLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) size else minOf(size, maxWidth)
        super.onMeasure(MeasureSpec.makeMeasureSpec(capped, MeasureSpec.getMode(widthMeasureSpec)), heightMeasureSpec)
    }
}

/** One choice-dialog row: optional swatch, label on the left, radio on the right, [Tokens.TARGET_MIN] + 8 dp high. */
private class ChoiceRow(
    context: android.content.Context,
    ui: BoardUi,
    label: String,
    swatch: Int?,
) : LinearLayout(context),
    android.widget.Checkable {
    private val radio =
        android.widget.RadioButton(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ui.controlAccent, ui.muted),
                )
        }

    init {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(Tokens.TARGET_MIN + Tokens.SPACE_2)
        setPadding(ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_2))
        background = ui.ripple(ui.raised)
        swatch?.let {
            addView(
                View(context).apply { background = ui.shape(it, Tokens.RADIUS_PILL).apply { setStroke(ui.dp(1), ui.muted) } },
                LayoutParams(ui.dp(Tokens.ICON), ui.dp(Tokens.ICON)).apply { marginEnd = ui.dp(Tokens.SPACE_3) },
            )
        }
        addView(
            ui.label(label, Tokens.TEXT_ROW).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(radio, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        contentDescription = label
        layoutParams = android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    override fun setChecked(checked: Boolean) {
        radio.isChecked = checked
    }

    override fun isChecked() = radio.isChecked

    override fun toggle() = radio.toggle()
}
