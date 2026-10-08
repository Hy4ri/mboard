package com.m57.mboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast

/** A real settings screen and a small typing playground, all offline. */
class SetupActivity : Activity() {
    private lateinit var settings: BoardSettings
    private lateinit var ui: BoardUi
    private var content: LinearLayout? = null
    private var status: android.widget.TextView? = null
    private var sandbox: EditText? = null
    private var draft = ""
    private var fieldType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BoardSettings(this)
        draft = savedInstanceState?.getString("draft").orEmpty()
        fieldType = savedInstanceState?.getInt("field_type", fieldType) ?: fieldType
        render()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            "draft",
            if (fieldType and InputType.TYPE_MASK_VARIATION ==
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            ) {
                ""
            } else {
                sandbox?.text?.toString().orEmpty()
            },
        )
        outState.putInt("field_type", fieldType)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        draft = sandbox?.text?.toString() ?: draft
        ui = BoardUi(this)
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            if (ui.dark) {
                0
            } else {
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            },
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(32))
            }
        content = column
        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(ui.background)
                isFillViewport = true
                addView(column)
                setOnApplyWindowInsetsListener { target, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
            }
        setContentView(scroll)
        column.addView(ui.label("mboard", 36f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        column.addView(ui.label("Your words. Your device.\nEnglish + العربية, entirely offline.", 16f, true))
        status = ui.label("", 14f).apply { setPadding(0, ui.dp(20), 0, ui.dp(12)) }
        column.addView(status)
        column.addView(ui.button("Enable mboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        column.addView(
            ui.button("Choose keyboard", true) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
            spaced(),
        )
        updateStatus()

        column.addView(ui.heading("Typing playground"))
        dropdown(
            "Field type",
            listOf("Normal text", "Number", "Email", "Password"),
            when (fieldType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER -> {
                    1
                }

                else -> {
                    when (fieldType and InputType.TYPE_MASK_VARIATION) {
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> 2
                        InputType.TYPE_TEXT_VARIATION_PASSWORD -> 3
                        else -> 0
                    }
                }
            },
        ) { index ->
            fieldType =
                when (index) {
                    1 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    3 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
            sandbox?.inputType = fieldType
        }
        sandbox =
            EditText(this).apply {
                hint = "Tap here to try mboard"
                contentDescription = "Typing playground"
                inputType = fieldType
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                backgroundTintList =
                    android.content.res.ColorStateList
                        .valueOf(ui.controlAccent)
                minHeight = ui.dp(80)
                maxLines = 4
                filters = arrayOf(InputFilter.LengthFilter(4000))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setText(draft)
            }
        column.addView(sandbox)
        column.addView(
            ui.label(
                "Swipe space to change language. Hold space, then slide to move the cursor. Double-tap Shift for caps lock. Hold letters for accents and Arabic variants.",
                14f,
                true,
            ),
        )

        column.addView(ui.heading("Appearance"))
        dropdown(
            "Theme",
            listOf("Follow system", "Dark", "Light"),
            listOf("system", "dark", "light").indexOf(settings.appearance).coerceAtLeast(0),
        ) { index ->
            val value = listOf("system", "dark", "light")[index]
            if (settings.appearance != value) {
                settings.appearance = value
                render()
            }
        }
        dropdown(
            "Accent",
            listOf("Violet", "Teal", "Amber", "Crimson · #990000"),
            listOf("violet", "teal", "amber", "crimson").indexOf(settings.accent).coerceAtLeast(0),
        ) { index ->
            val value = listOf("violet", "teal", "amber", "crimson")[index]
            if (settings.accent != value) {
                settings.accent = value
                render()
            }
        }
        dropdown(
            "Key height",
            listOf("Compact · 44 dp", "Balanced · 54 dp", "Roomy · 64 dp"),
            listOf(44, 54, 64).indexOf(settings.rowHeightDp).coerceAtLeast(0),
        ) {
            settings.rowHeightDp =
                listOf(44, 54, 64)[it]
        }
        dropdown(
            "Thumb mode",
            listOf("Full width", "Left thumb", "Right thumb"),
            listOf("full", "left", "right").indexOf(settings.oneHandMode).coerceAtLeast(0),
        ) {
            settings.oneHandMode = listOf("full", "left", "right")[it]
        }
        toggle("Number row", "Keep digits above the letter keys", settings.numberRow) { settings.numberRow = it }
        toggle("Key previews", "A short bubble above the letter you touch", settings.keyPreview) { settings.keyPreview = it }

        column.addView(
            ui.label("Thumb mode uses real narrower keys, with Full/side controls. Below 300 dp it falls back to full width.", 13f, true),
        )
        column.addView(ui.heading("Typing"))
        toggle("Word suggestions", "Tap-to-complete English and Arabic. Never autocorrects or learns your typing.", settings.suggestions) {
            settings.suggestions =
                it
        }
        toggle("Automatic capitalization", "Follow the active app's sentence and capitalization hints", settings.autoCapitalize) {
            settings.autoCapitalize =
                it
        }
        toggle("Double-space period", "Two quick spaces after a word insert a period", settings.doubleSpacePeriod) {
            settings.doubleSpacePeriod =
                it
        }
        column.addView(ui.label("No sound. No vibration. No network permission.", 14f, true))

        column.addView(ui.heading("Clipboard & privacy"))
        val clips = ClipStore(this)
        toggle(
            "Clipboard history",
            "Record plain text only while mboard is visible. Sensitive clips and password fields are skipped.",
            settings.clipboardHistory,
        ) {
            settings.clipboardHistory =
                it
        }
        dropdown(
            "Delete unpinned clips after",
            ClipStore.RETENTION_OPTIONS.map { it.first },
            ClipStore.RETENTION_OPTIONS
                .indexOfFirst {
                    it.second ==
                        clips.retentionMs
                }.coerceAtLeast(0),
        ) { clips.retentionMs = ClipStore.RETENTION_OPTIONS[it].second }
        column.addView(
            ui.label(
                "Pinned clips remain until you delete them. Private mode pauses clipboard recording and word suggestions; existing saved items remain on your device.",
                14f,
                true,
            ),
        )
        column.addView(
            ui.button("Delete all clipboard history") {
                AlertDialog
                    .Builder(
                        this,
                    ).setTitle("Delete clipboard history?")
                    .setMessage("This also deletes pinned clips. It does not change your system clipboard.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        clips.clearAll()
                        toast("Clipboard history deleted")
                    }.show()
            },
            spaced(),
        )

        column.addView(ui.heading("Saved phrases"))
        column.addView(
            ui.label(
                "Save up to 40 replies, addresses, or signatures. They appear in the keyboard's phrases panel. Only text you explicitly save is stored.",
                14f,
                true,
            ),
        )
        val phraseInput =
            EditText(this).apply {
                hint = "A phrase to save"
                contentDescription = "New saved phrase"
                setTextColor(ui.text)
                setHintTextColor(ui.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                filters = arrayOf(InputFilter.LengthFilter(2000))
                maxLines = 4
            }
        column.addView(phraseInput)
        val phraseList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val phrases = PhraseStore(this)

        fun refreshPhrases() {
            phraseList.removeAllViews()
            phrases.all().forEach { phrase ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    ui.label(phrase).apply {
                        maxLines = 3
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    ui.icon(R.drawable.ic_close, "Delete saved phrase") {
                        AlertDialog
                            .Builder(this)
                            .setTitle("Delete this phrase?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ ->
                                phrases.remove(phrase)
                                refreshPhrases()
                            }.show()
                    },
                    LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)),
                )
                phraseList.addView(row)
            }
        }
        column.addView(
            ui.button("Save phrase") {
                if (phrases.add(phraseInput.text.toString())) {
                    phraseInput.setText("")
                    refreshPhrases()
                    toast("Phrase saved")
                } else {
                    toast("Enter a new phrase · maximum 40 saved")
                }
            },
            spaced(),
        )
        column.addView(phraseList)
        refreshPhrases()
        column.addView(ui.heading("Offline tools"))
        column.addView(
            ui.label(
                "Cursor arrows, line start/end, copy/cut/paste, host-app undo/redo, selected-text case changes, and Arabic mark removal. Calculator accepts + − × ÷, parentheses, decimals, and postfix %; tap its answer to insert it. For example, 50% means 0.5—not a retail discount calculation.\n\nEmoji supports categories, recent picks, favorites, and common Arabic search words. Hold an emoji to favorite it.",
                14f,
                true,
            ),
        )
        column.addView(
            ui.button("Licenses & word-list credits") {
                val notice = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
                AlertDialog
                    .Builder(this)
                    .setTitle("Credits")
                    .setMessage(notice)
                    .setPositiveButton("Close", null)
                    .show()
            },
            spaced(),
        )
    }

    private fun updateStatus() {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        status?.text =
            when {
                active -> "mboard is your active keyboard"
                enabled -> "Enabled · choose mboard to start typing"
                else -> "Enable mboard, then choose it as your keyboard"
            }
    }

    private fun dropdown(
        label: String,
        values: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        val column = content ?: return
        column.addView(ui.label(label, 14f, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        val spinner =
            Spinner(this).apply {
                contentDescription = label
                minimumHeight = ui.dp(48)
                adapter =
                    object : ArrayAdapter<String>(this@SetupActivity, android.R.layout.simple_spinner_dropdown_item, values) {
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View = decorate(super.getView(position, convertView, parent))

                        override fun getDropDownView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View = decorate(super.getDropDownView(position, convertView, parent))

                        private fun decorate(view: View): View =
                            view.apply {
                                setBackgroundColor(ui.surface)
                                minimumHeight = ui.dp(48)
                                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                                (this as? android.widget.TextView)?.setTextColor(ui.text)
                            }
                    }
                setSelection(selected)
            }
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    onSelect(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        column.addView(spinner)
    }

    private fun toggle(
        label: String,
        detail: String,
        checked: Boolean,
        changed: (Boolean) -> Unit,
    ) {
        content?.addView(
            Switch(this).apply {
                text = label
                setTextColor(ui.text)
                minHeight = ui.dp(48)
                thumbTintList =
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(ui.controlAccent, ui.muted),
                    )
                trackTintList =
                    android.content.res.ColorStateList
                        .valueOf(ui.modifier)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> changed(value) }
            },
        )
        content?.addView(ui.label(detail, 13f, true).apply { setPadding(0, 0, 0, ui.dp(8)) })
    }

    private fun spaced() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin =
                ui.dp(8)
        }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
