package com.m57.mboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class MboardService : InputMethodService() {
    private enum class Mode { KEYS, CLIPBOARD, EMOJI, EMOJI_SEARCH }

    private var mode = Mode.KEYS
    private var view: KeyboardView? = null
    private var clipPanel: ClipboardPanel? = null
    private var emojiPanel: EmojiPanel? = null
    private var toolbar: LinearLayout? = null
    private var searchBar: LinearLayout? = null
    private var searchText: TextView? = null
    private var results: LinearLayout? = null
    private var clipButton: Button? = null
    private var emojiButton: Button? = null
    private var query = ""
    private var language = Layout.ENGLISH
    private lateinit var store: ClipStore
    private lateinit var clipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { recordClip() }

    override fun onCreate() {
        super.onCreate()
        store = ClipStore(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    private fun recordClip() {
        val clip = clipboard.primaryClip ?: return
        // Skip passwords/OTPs that the source app flagged as sensitive.
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        store.add(text)
        if (mode == Mode.CLIPBOARD) clipPanel?.refresh()
    }

    override fun onCreateInputView(): View {
        val kv = KeyboardView(this, ::onKey, ::toggleLanguage)
        kv.layout = language
        view = kv
        val bg = kv.backgroundColor
        clipPanel = ClipboardPanel(this, store, ::paste).apply { setBackgroundColor(bg) }
        emojiPanel = EmojiPanel(this, { setMode(Mode.EMOJI_SEARCH) }, ::paste).apply { setBackgroundColor(bg) }

        val bar = LinearLayout(this)
        clipButton = toolbarButton("📋") { setMode(if (mode == Mode.CLIPBOARD) Mode.KEYS else Mode.CLIPBOARD) }
        emojiButton = toolbarButton("😀") { setMode(if (mode == Mode.EMOJI) Mode.KEYS else Mode.EMOJI_SEARCH) }
        bar.addView(clipButton)
        bar.addView(emojiButton)
        bar.addView(
            toolbarButton("Paste") {
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.let { paste(it.toString()) }
            },
        )
        toolbar = bar

        // Search mode: query line + live results, typed with the normal keys below.
        val sText = TextView(this).apply { textSize = 15f }
        searchText = sText
        val res = LinearLayout(this)
        results = res
        val sBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val head = LinearLayout(this@MboardService)
                head.addView(toolbarButton("⌨") { setMode(Mode.KEYS) })
                head.addView(sText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                head.addView(toolbarButton("☰") { setMode(Mode.EMOJI) })
                head.gravity = android.view.Gravity.CENTER_VERTICAL
                addView(head)
                addView(HorizontalScrollView(this@MboardService).apply { addView(res) })
            }
        searchBar = sBar

        val body = FrameLayout(this)
        val fill = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        body.addView(kv)
        body.addView(clipPanel, fill)
        body.addView(emojiPanel, fill)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(bg)
        root.addView(bar)
        root.addView(sBar)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kv.keysHeight))
        root.setOnApplyWindowInsetsListener { r, insets ->
            r.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            insets
        }
        setMode(Mode.KEYS)
        return root
    }

    private fun toolbarButton(
        label: String,
        action: () -> Unit,
    ) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun setMode(next: Mode) {
        mode = next
        if (next == Mode.CLIPBOARD) clipPanel?.refresh()
        if (next == Mode.EMOJI_SEARCH) {
            query = ""
            view?.layout = language
            updateSearch()
        }

        fun show(
            v: View?,
            on: Boolean,
        ) {
            v?.visibility = if (on) View.VISIBLE else View.GONE
        }
        show(toolbar, next != Mode.EMOJI_SEARCH)
        show(searchBar, next == Mode.EMOJI_SEARCH)
        show(clipPanel, next == Mode.CLIPBOARD)
        show(emojiPanel, next == Mode.EMOJI)
        // INVISIBLE keeps the keyboard's height so panels fill the same space.
        view?.visibility = if (next == Mode.KEYS || next == Mode.EMOJI_SEARCH) View.VISIBLE else View.INVISIBLE
        clipButton?.text = if (next == Mode.CLIPBOARD) "⌨" else "📋"
        emojiButton?.text = if (next == Mode.EMOJI) "⌨" else "😀"
    }

    private fun updateSearch() {
        searchText?.text = if (query.isEmpty()) "Type to search emoji" else query
        val res = results ?: return
        res.removeAllViews()
        Emojis.search(this, query).take(MAX_RESULTS).forEach { e ->
            res.addView(
                TextView(this).apply {
                    text = e.text
                    textSize = 26f
                    val p = (8 * resources.displayMetrics.density).toInt()
                    setPadding(p, p / 2, p, p / 2)
                    setOnClickListener { paste(e.text) }
                },
            )
        }
    }

    private fun paste(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        view?.layout = language
        view?.shifted = false
        setMode(Mode.KEYS)
    }

    private fun toggleLanguage() {
        language = if (language == Layout.ENGLISH) Layout.ARABIC else Layout.ENGLISH
        view?.shifted = false
        view?.layout = language
    }

    private fun onSearchKey(key: String) {
        when (key) {
            Keys.BACKSPACE -> query = query.dropLast(1)
            Keys.ENTER -> Emojis.search(this, query).firstOrNull()?.let { paste(it.text) }
            Keys.SHIFT, Keys.GLOBE, Keys.SYMBOLS, Keys.LETTERS -> return
            else -> query += key.lowercase()
        }
        updateSearch()
    }

    private fun onKey(key: String) {
        if (mode == Mode.EMOJI_SEARCH) return onSearchKey(key)
        val ic = currentInputConnection ?: return
        val v = view ?: return
        when (key) {
            Keys.SHIFT -> {
                v.shifted = !v.shifted
            }

            Keys.BACKSPACE -> {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                } else {
                    ic.commitText("", 1)
                }
            }

            Keys.ENTER -> {
                val action = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                val noEnterAction = currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (action > EditorInfo.IME_ACTION_NONE && !noEnterAction) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
            }

            Keys.GLOBE -> {
                toggleLanguage()
            }

            Keys.SYMBOLS -> {
                v.layout = Layout.SYMBOLS
            }

            Keys.LETTERS -> {
                v.layout = language
            }

            else -> {
                ic.commitText(if (v.shifted) key.uppercase() else key, 1)
                if (v.shifted) v.shifted = false
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 40
    }
}
