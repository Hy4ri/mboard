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
import android.widget.LinearLayout

class MboardService : InputMethodService() {
    private var view: KeyboardView? = null
    private var panel: ClipboardPanel? = null
    private var clipButton: Button? = null
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
        if (panel?.visibility == View.VISIBLE) panel?.refresh()
    }

    override fun onCreateInputView(): View {
        val kv = KeyboardView(this, ::onKey, ::toggleLanguage)
        kv.layout = language
        view = kv
        val p =
            ClipboardPanel(this, store, ::paste).apply {
                visibility = View.GONE
                setBackgroundColor(kv.backgroundColor)
            }
        panel = p

        val toolbar = LinearLayout(this)
        val clipBtn = toolbarButton("📋") { toggleClipboard() }
        clipButton = clipBtn
        toolbar.addView(clipBtn)
        toolbar.addView(
            toolbarButton("Paste") {
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.let { paste(it.toString()) }
            },
        )

        val body = FrameLayout(this)
        body.addView(kv)
        body.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(kv.backgroundColor)
        root.addView(toolbar)
        root.addView(body)
        root.setOnApplyWindowInsetsListener { r, insets ->
            r.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
            insets
        }
        return root
    }

    private fun toolbarButton(
        label: String,
        action: () -> Unit,
    ) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun toggleClipboard() {
        val p = panel ?: return
        val show = p.visibility != View.VISIBLE
        if (show) p.refresh()
        p.visibility = if (show) View.VISIBLE else View.GONE
        // INVISIBLE keeps the keyboard's height so the panel fills the same space.
        view?.visibility = if (show) View.INVISIBLE else View.VISIBLE
        clipButton?.text = if (show) "⌨" else "📋"
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
        if (panel?.visibility == View.VISIBLE) toggleClipboard()
    }

    private fun toggleLanguage() {
        language = if (language == Layout.ENGLISH) Layout.ARABIC else Layout.ENGLISH
        view?.shifted = false
        view?.layout = language
    }

    private fun onKey(key: String) {
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
}
