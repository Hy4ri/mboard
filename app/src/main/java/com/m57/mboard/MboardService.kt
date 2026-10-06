package com.m57.mboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class MboardService : InputMethodService() {
    private var view: KeyboardView? = null
    private var language = Layout.ENGLISH

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, ::onKey, ::toggleLanguage)
        v.setOnApplyWindowInsetsListener { kv, insets ->
            val nav =
                insets
                    .getInsets(
                        android.view.WindowInsets.Type
                            .navigationBars(),
                    ).bottom
            kv.setPadding(0, 0, 0, nav)
            insets
        }
        v.layout = language
        view = v
        return v
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        view?.layout = language
        view?.shifted = false
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
