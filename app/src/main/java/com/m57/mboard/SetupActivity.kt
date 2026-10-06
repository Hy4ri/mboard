package com.m57.mboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Enable + select the keyboard, and a field to try it. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 3, pad, pad)
            }
        root.addView(
            TextView(this).apply {
                text = "mboard"
                textSize = 28f
            },
        )
        root.addView(
            Button(this).apply {
                text = "1. Enable mboard"
                setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "2. Switch to mboard"
                setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
            },
        )
        root.addView(EditText(this).apply { hint = "Try typing here" })
        val store = ClipStore(this)
        root.addView(
            TextView(this).apply {
                text = "Keep clipboard history for"
                setPadding(0, pad, 0, 0)
            },
        )
        val group = RadioGroup(this)
        ClipStore.RETENTION_OPTIONS.forEachIndexed { i, (label, ms) ->
            group.addView(
                RadioButton(this).apply {
                    id = i + 1
                    text = label
                    isChecked = store.retentionMs == ms
                    setOnClickListener { store.retentionMs = ms }
                },
            )
        }
        root.addView(group)
        setContentView(root)
    }
}
