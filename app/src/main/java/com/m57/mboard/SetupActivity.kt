package com.m57.mboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
        val titleRes: Int,
    ) {
        HOME(R.string.app_name),
        APPEARANCE(R.string.page_appearance),
        TYPING(R.string.page_typing),
        PRIVACY(R.string.page_privacy),
        PHRASES(R.string.page_phrases),
        PLAYGROUND(R.string.page_playground),
        ABOUT(R.string.page_about),
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
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val delta = ui.dp(if (forward != rtl) Tokens.SPACE_5 else -Tokens.SPACE_5).toFloat()
        scroller?.apply {
            alpha = 0f
            translationX = delta
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
                setPaddingRelative(ui.dp(Tokens.SPACE_3), 0, ui.dp(Tokens.SPACE_4), 0)
            }
        if (page != Page.HOME) {
            header.addView(
                ui.icon(R.drawable.ic_arrow_back, getString(R.string.action_back)) { goBack() },
                LinearLayout.LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)),
            )
        }
        header.addView(
            ui.label(getString(page.titleRes), if (page == Page.HOME) Tokens.TEXT_TITLE else Tokens.TEXT_HEADER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAccessibilityHeading = true
                setPaddingRelative(if (page == Page.HOME) ui.dp(Tokens.SPACE_2) else ui.dp(Tokens.SPACE_1), 0, 0, 0)
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
        val nativeLabels = joinLanguageLabels(this, Languages.ALL.map { it.label })
        components.add(column, components.note(getString(R.string.home_note, nativeLabels)))
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
        column.addView(components.section(getString(R.string.section_customize)))
        components.add(
            column,
            components.row(
                getString(R.string.page_appearance),
                getString(R.string.home_appearance_summary, themeName(), accentShortName()),
                ui.accent,
            ) {
                navigate(Page.APPEARANCE)
            },
        )
        components.add(
            column,
            components.row(
                getString(R.string.page_typing),
                getString(R.string.home_typing_summary, onOff(settings.suggestions), onOff(settings.autoCapitalize)),
            ) {
                navigate(Page.TYPING)
            },
        )
        column.addView(components.section(getString(R.string.section_on_device)))
        components.add(
            column,
            components.row(
                getString(R.string.page_privacy),
                getString(R.string.home_privacy_summary, onOff(settings.clipboardHistory)),
            ) {
                navigate(Page.PRIVACY)
            },
        )
        components.add(
            column,
            components.row(getString(R.string.page_phrases), getString(R.string.home_phrases_summary)) { navigate(Page.PHRASES) },
        )
        column.addView(components.section(getString(R.string.section_explore)))
        components.add(
            column,
            components.row(getString(R.string.page_playground), getString(R.string.home_playground_summary)) { navigate(Page.PLAYGROUND) },
        )
        components.add(
            column,
            components.row(getString(R.string.page_about), getString(R.string.home_about_summary)) { navigate(Page.ABOUT) },
        )
    }

    private fun appearance(column: LinearLayout) {
        components.add(column, components.note(getString(R.string.appearance_note)))
        components.add(
            column,
            components.row(getString(R.string.appearance_preview_title), getString(R.string.appearance_preview_summary)) {
                navigate(Page.PLAYGROUND)
            },
        )
        column.addView(components.section(getString(R.string.section_color)))
        choice(
            column,
            getString(R.string.appearance_theme_title),
            themeName(),
            listOf(getString(R.string.theme_system), getString(R.string.theme_dark), getString(R.string.theme_light)),
            listOf("system", "dark", "light").indexOf(settings.appearance),
        ) {
            settings.appearance = listOf("system", "dark", "light")[it]
            render()
        }
        choice(
            column,
            getString(R.string.appearance_accent_title),
            accentName(),
            listOf(
                getString(R.string.accent_violet),
                getString(R.string.accent_teal),
                getString(R.string.accent_amber),
                getString(R.string.accent_crimson_hex),
            ),
            listOf("violet", "teal", "amber", "crimson").indexOf(settings.accent),
            ui.accent,
            listOf("violet", "teal", "amber", "crimson").map { Tokens.accents.getValue(it).let { a -> if (ui.dark) a.dark else a.light } },
        ) {
            settings.accent = listOf("violet", "teal", "amber", "crimson")[it]
            render()
        }
        column.addView(components.section(getString(R.string.section_custom_colors)))
        COLOR_ROLES.forEach { (role, titleRes) ->
            val title = getString(titleRes)
            val custom = settings.customColor(role)
            components.add(
                column,
                components.row(title, custom?.let(::hex) ?: getString(R.string.color_theme_default), custom ?: themeColor(role)) {
                    colorDialog(role, title)
                },
            )
        }
        if (BoardSettings.COLOR_ROLES.any { settings.customColor(it) != null }) {
            components.add(
                column,
                components.row(
                    getString(R.string.color_reset_title),
                    getString(R.string.color_reset_summary),
                    destructive = true,
                ) {
                    BoardSettings.COLOR_ROLES.forEach { settings.setCustomColor(it, null) }
                    render()
                },
            )
        }
        components.add(column, components.note(getString(R.string.custom_colors_note)))
        column.addView(components.section(getString(R.string.section_keyboard_layout)))
        choice(
            column,
            getString(R.string.layout_key_height_title),
            getString(R.string.layout_key_height_value, settings.rowHeightDp),
            listOf(
                getString(R.string.layout_key_height_compact),
                getString(R.string.layout_key_height_balanced),
                getString(R.string.layout_key_height_roomy),
            ),
            listOf(44, 54, 64).indexOf(settings.rowHeightDp),
        ) {
            settings.rowHeightDp = listOf(44, 54, 64)[it]
            render()
        }
        choice(
            column,
            getString(R.string.layout_thumb_title),
            thumbName(),
            listOf(getString(R.string.thumb_full), getString(R.string.thumb_left), getString(R.string.thumb_right)),
            listOf("full", "left", "right").indexOf(settings.oneHandMode),
        ) {
            settings.oneHandMode = listOf("full", "left", "right")[it]
            render()
        }
        val swipeOptions =
            listOf(getString(R.string.edge_off), getString(R.string.edge_swipe_left), getString(R.string.edge_swipe_right))
        choice(
            column,
            getString(R.string.layout_dial_swipe_title),
            dialName(),
            swipeOptions,
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
            getString(R.string.layout_emoji_swipe_title),
            edgeName(settings.emojiEdge),
            swipeOptions,
            listOf("off", "left", "right").indexOf(settings.emojiEdge),
        ) {
            val edge = listOf("off", "left", "right")[it]
            settings.emojiEdge = edge
            if (edge != "off" && settings.dialPadEdge == edge) settings.dialPadEdge = opposite(edge)
            render()
        }
        components.add(
            column,
            components.note(getString(R.string.layout_thumb_note)),
        )
        column.addView(components.section(getString(R.string.section_key_details)))
        components.add(
            column,
            components.toggle(
                getString(R.string.detail_number_row_title),
                getString(R.string.detail_number_row_summary),
                settings.numberRow,
            ) {
                settings.numberRow = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.detail_key_previews_title),
                getString(R.string.detail_key_previews_summary),
                settings.keyPreview,
            ) {
                settings.keyPreview = it
            },
        )
    }

    private fun typing(column: LinearLayout) {
        components.add(
            column,
            components.note(getString(R.string.typing_note)),
        )
        column.addView(components.section(getString(R.string.section_input_assistance)))
        val langNames = joinLanguageLabels(this, Languages.ALL.map { it.localizedName(this) })
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_suggestions_title),
                getString(R.string.typing_suggestions_summary, langNames),
                settings.suggestions,
            ) {
                settings.suggestions = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_autocorrect_title),
                getString(R.string.typing_autocorrect_summary),
                settings.autoCorrect,
            ) {
                settings.autoCorrect = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_learn_title),
                getString(R.string.typing_learn_summary),
                settings.learnWords,
            ) {
                settings.learnWords = it
            },
        )
        Languages.ALL.mapNotNull { it.dialect }.forEach { dialect ->
            components.add(
                column,
                components.toggle(dialect.localizedTitle(this), dialect.localizedSummary(this), settings.dialectEnabled(dialect)) {
                    settings.setDialectEnabled(dialect, it)
                },
            )
        }
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_emoji_title),
                getString(R.string.typing_emoji_summary),
                settings.emojiSuggestions,
            ) {
                settings.emojiSuggestions = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_arabizi_title),
                getString(R.string.typing_arabizi_summary),
                settings.arabiziSuggestions,
            ) {
                settings.arabiziSuggestions = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_capitalize_title),
                getString(R.string.typing_capitalize_summary),
                settings.autoCapitalize,
            ) {
                settings.autoCapitalize = it
            },
        )
        components.add(
            column,
            components.toggle(
                getString(R.string.typing_double_space_title),
                getString(R.string.typing_double_space_summary),
                settings.doubleSpacePeriod,
            ) {
                settings.doubleSpacePeriod = it
            },
        )
        components.add(
            column,
            components.note(getString(R.string.typing_privacy_note)),
        )
        column.addView(components.section(getString(R.string.section_quick_gestures)))
        components.add(
            column,
            components.row(getString(R.string.typing_gestures_title), getString(R.string.typing_gestures_summary)) {
                navigate(Page.PLAYGROUND)
            },
        )
    }

    private fun privacy(column: LinearLayout) {
        val clips = ClipStore(this)
        components.add(column, components.note(getString(R.string.privacy_note)))
        column.addView(components.section(getString(R.string.section_clipboard_history)))
        components.add(
            column,
            components.toggle(
                getString(R.string.privacy_save_clips_title),
                getString(R.string.privacy_save_clips_summary),
                settings.clipboardHistory,
            ) {
                settings.clipboardHistory = it
            },
        )
        choice(
            column,
            getString(R.string.privacy_retention_title),
            getString(ClipStore.RETENTION_OPTIONS.firstOrNull { it.second == clips.retentionMs }?.first ?: R.string.retention_1h),
            ClipStore.RETENTION_OPTIONS.map { getString(it.first) },
            ClipStore.RETENTION_OPTIONS.indexOfFirst { it.second == clips.retentionMs },
        ) {
            clips.retentionMs = ClipStore.RETENTION_OPTIONS[it].second
            render()
        }
        components.add(
            column,
            components.note(getString(R.string.privacy_retention_note)),
        )
        column.addView(components.section(getString(R.string.section_private_input)))
        components.add(
            column,
            components.note(getString(R.string.privacy_shield_note)),
        )
        column.addView(components.section(getString(R.string.section_clear_data)))
        val learnedCount = UserWords(this).size()
        components.add(
            column,
            components.row(
                getString(R.string.privacy_delete_words_title),
                resources.getQuantityString(R.plurals.privacy_delete_words_summary, learnedCount, learnedCount),
                destructive = true,
            ) {
                confirm(
                    getString(R.string.privacy_delete_words_confirm_title),
                    getString(R.string.privacy_delete_words_confirm_body),
                ) {
                    UserWords(this).clear()
                    settings.markLearnedReset()
                    toast(R.string.toast_learned_words_deleted)
                    render()
                }
            },
        )
        components.add(
            column,
            components.row(
                getString(R.string.privacy_delete_clips_title),
                getString(R.string.privacy_delete_clips_summary),
                destructive = true,
            ) {
                confirm(
                    getString(R.string.privacy_delete_clips_confirm_title),
                    getString(R.string.privacy_delete_clips_confirm_body),
                ) {
                    clips.clearAll()
                    toast(R.string.toast_clips_deleted)
                }
            },
        )
    }

    private fun phrases(column: LinearLayout) {
        val store = PhraseStore(this)
        components.add(column, components.note(getString(R.string.phrases_note)))
        column.addView(components.section(getString(R.string.section_new_phrase)))
        val editor =
            textEditor(
                getString(R.string.phrases_editor_hint),
                getString(R.string.phrases_editor_a11y),
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
                    clean.isEmpty() -> getString(R.string.phrases_reason_empty)
                    saved.size >= 40 -> getString(R.string.phrases_count, 40, 40)
                    clean in saved -> getString(R.string.phrases_reason_duplicate)
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
            count.text = getString(R.string.phrases_count, values.size, 40)
            list.removeAllViews()
            if (values.isEmpty()) components.add(list, components.note(getString(R.string.phrases_empty_note)))
            values.forEach { phrase ->
                val row =
                    LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = ui.shape(ui.surface)
                        setPaddingRelative(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_2))
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
                    ui.icon(R.drawable.ic_delete, getString(R.string.phrases_delete_a11y)) {
                        confirm(
                            getString(R.string.phrases_delete_confirm_title),
                            getString(R.string.phrases_delete_confirm_body),
                        ) {
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
            ui.button(getString(R.string.phrases_save_action), true) {
                if (store.add(editor.text.toString())) {
                    editor.setText("")
                    phraseDraft = ""
                    refresh()
                    toast(R.string.toast_phrase_saved)
                } else {
                    updateSave()
                }
            }
        components.add(column, helper)
        components.add(column, save)
        // Android hides device accounts from other apps; the system picker shares only the one the user taps.
        components.add(
            column,
            ui.button(getString(R.string.phrases_add_account_emails), action = ::pickAccount),
        )
        column.addView(components.section(getString(R.string.section_your_phrases)))
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
        components.add(column, components.note(getString(R.string.playground_note)))
        val names =
            listOf(
                getString(R.string.playground_field_normal),
                getString(R.string.playground_field_number),
                getString(R.string.playground_field_email),
                getString(R.string.playground_field_password),
            )
        val types = PLAYGROUND_TYPES
        choice(
            column,
            getString(R.string.playground_field_title),
            names[types.indexOf(fieldType).coerceAtLeast(0)],
            names,
            types.indexOf(fieldType),
        ) {
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
            textEditor(
                getString(R.string.playground_editor_hint),
                getString(R.string.playground_editor_a11y),
                fieldType,
                4000,
            ).apply {
                minHeight = ui.dp(Tokens.TARGET_MIN * 3)
                maxLines = 6
                setText(if (passwordPlayground) "" else draft)
                setSelection(playgroundStart.coerceIn(0, length()), playgroundEnd.coerceIn(0, length()))
            }
        sandbox = editor
        components.add(column, editor)
        components.add(
            column,
            components.note(getString(R.string.playground_password_note)),
        )
        column.addView(components.section(getString(R.string.section_what_to_try)))
        components.add(
            column,
            components.note(getString(R.string.playground_tips)),
        )
    }

    private fun about(column: LinearLayout) {
        val nativeLabels = joinLanguageLabels(this, Languages.ALL.map { it.label })
        components.add(column, components.note(getString(R.string.about_note, nativeLabels)))
        column.addView(components.section(getString(R.string.section_offline)))
        components.add(
            column,
            components.note(getString(R.string.about_offline_note)),
        )
        column.addView(components.section(getString(R.string.section_keyboard_tools)))
        components.add(
            column,
            components.note(getString(R.string.about_tools_note)),
        )
        column.addView(components.section(getString(R.string.section_credits)))
        components.add(
            column,
            components.row(getString(R.string.about_licenses_title), getString(R.string.about_licenses_summary)) {
                val notice = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
                AlertDialog
                    .Builder(
                        dialogContext(),
                    ).setTitle(R.string.about_credits_dialog_title)
                    .setMessage(notice)
                    .setPositiveButton(R.string.action_close, null)
                    .create()
                    .let(::showDialog)
            },
        )
        components.add(
            column,
            components.row(
                getString(R.string.about_manage_keyboards_title),
                getString(R.string.about_manage_keyboards_summary),
                external = true,
            ) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        components.add(
            column,
            components.row(
                getString(R.string.about_switch_keyboard_title),
                getString(R.string.about_switch_keyboard_summary),
                external = true,
            ) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
        )
    }

    private fun updateStatus() {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        status?.text =
            getString(
                when {
                    active -> R.string.status_active
                    enabled -> R.string.status_enabled
                    else -> R.string.status_setup
                },
            )
        setupActions?.let { actions ->
            actions.removeAllViews()
            if (!enabled) {
                components.add(
                    actions,
                    ui.button(getString(R.string.action_enable_mboard), true) {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                )
            }
            if (enabled && !active) {
                components.add(
                    actions,
                    ui.button(getString(R.string.action_choose_mboard), true) { manager.showInputMethodPicker() },
                )
            }
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
                    }.setNegativeButton(R.string.action_cancel, null)
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

    private fun hex(value: Int) = String.format(java.util.Locale.US, "#%06X", value and 0xFFFFFF)

    /** The color a role is read against: keys and accent against the background, the background against the text, text against keys. */
    private fun contrastReference(role: String): Pair<Int, String> =
        when (role) {
            BoardSettings.BACKGROUND -> ui.text to getString(R.string.color_ref_text)
            BoardSettings.TEXT -> ui.surface to getString(R.string.color_ref_keys)
            else -> ui.background to getString(R.string.color_ref_background)
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
                textDirection = View.TEXT_DIRECTION_LTR
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
                val ratioText = String.format(java.util.Locale.US, "%.1f", ratio)
                val low = ratio < Tokens.WARN_RATIO
                info.text =
                    getString(
                        if (low) R.string.color_contrast_low else R.string.color_contrast_ok,
                        referenceName,
                        ratioText,
                    )
                info.setTextColor(if (low) ui.danger else ui.muted)
            } else {
                info.text = getString(R.string.color_invalid_hex)
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
                .setPositiveButton(R.string.action_apply) { _, _ ->
                    if (generation == renderGeneration && valid) {
                        settings.setCustomColor(role, picked or 0xFF000000.toInt())
                        render()
                    }
                }.setNeutralButton(R.string.action_default) { _, _ ->
                    if (generation == renderGeneration) {
                        settings.setCustomColor(role, null)
                        render()
                    }
                }.setNegativeButton(R.string.action_cancel, null)
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
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> if (generation == renderGeneration) confirmed() }
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
            // Real buttons, not bare text: the confirming action is filled, the others are tonal, all at touch-target height.
            val fill = if (destructive) ui.danger else ui.accent
            val ink = if (Color.luminance(fill) > .5f) Color.BLACK else Color.WHITE
            styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), fill, if (destructive) ink else ui.onAccent)
            styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), ui.surface, ui.text)
            styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), ui.surface, ui.text)
        }
        dialog.show()
    }

    private fun styleDialogButton(
        button: android.widget.Button?,
        fill: Int,
        ink: Int,
    ) {
        button ?: return
        button.isAllCaps = false
        button.setTextColor(ink)
        button.minWidth = ui.dp(Tokens.TARGET_MIN + Tokens.SPACE_4)
        button.minimumWidth = button.minWidth
        button.minHeight = ui.dp(Tokens.TARGET_MIN)
        button.minimumHeight = button.minHeight
        button.setPadding(ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_1), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_1))
        button.background = ui.ripple(fill)
        button.backgroundTintList = null
        (button.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.marginStart = ui.dp(Tokens.SPACE_2)
    }

    private fun themeName() =
        getString(
            when (settings.appearance) {
                "dark" -> R.string.theme_dark
                "light" -> R.string.theme_light
                else -> R.string.theme_system
            },
        )

    private fun accentName() =
        getString(
            when (settings.accent) {
                "teal" -> R.string.accent_teal
                "amber" -> R.string.accent_amber
                "crimson" -> R.string.accent_crimson_hex
                else -> R.string.accent_violet
            },
        )

    private fun accentShortName() =
        getString(
            when (settings.accent) {
                "teal" -> R.string.accent_teal
                "amber" -> R.string.accent_amber
                "crimson" -> R.string.accent_crimson
                else -> R.string.accent_violet
            },
        )

    private fun thumbName() =
        getString(
            when (settings.oneHandMode) {
                "left" -> R.string.thumb_left
                "right" -> R.string.thumb_right
                else -> R.string.thumb_full
            },
        )

    private fun onOff(value: Boolean) = getString(if (value) R.string.state_on else R.string.state_off)

    /** The system chooser is single-choice and can't be restyled; each tap adds one address. */
    private fun pickAccount() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                android.accounts.AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null),
                PICK_ACCOUNT,
            )
        } catch (_: android.content.ActivityNotFoundException) {
            toast(R.string.toast_account_picker_unavailable)
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
            name.isNullOrEmpty() || '@' !in name -> toast(R.string.toast_account_no_email)
            store.add(name) -> Toast.makeText(this, getString(R.string.toast_account_added, name), Toast.LENGTH_SHORT).show()
            else -> toast(R.string.toast_account_duplicate_or_full)
        }
        render()
    }

    private fun dialName() = edgeName(settings.dialPadEdge)

    private fun opposite(edge: String) = if (edge == "left") "right" else "left"

    private fun edgeName(edge: String) =
        getString(
            when (edge) {
                "left" -> R.string.edge_left
                "right" -> R.string.edge_right
                else -> R.string.edge_off
            },
        )

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PICK_ACCOUNT = 41
        val COLOR_ROLES =
            listOf(
                BoardSettings.ACCENT to R.string.color_role_accent,
                BoardSettings.BACKGROUND to R.string.color_role_background,
                BoardSettings.KEYS to R.string.color_role_keys,
                BoardSettings.SPECIAL_KEYS to R.string.color_role_special_keys,
                BoardSettings.TEXT to R.string.color_role_text,
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
        setPaddingRelative(ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_2), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_2))
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
