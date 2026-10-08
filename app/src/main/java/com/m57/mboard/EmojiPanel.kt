package com.m57.mboard

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Opaque, stable-height browsing panel. Searching uses the keyboard, never an IME-owned EditText. */
class EmojiPanel(
    context: Context,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
) : LinearLayout(context) {
    private val ui = BoardUi(context)
    private val history = EmojiHistory(context)
    private var all = Emojis.cached()
    private var byText = all.associateBy { it.text }
    private val categories = LinearLayout(context)
    private val grid = GridView(context)
    private val empty =
        ui.label("", 15f, true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
        }
    private var active = "All"
    private var confirmingClear = false
    var recordingEnabled = true
    var loadFailed = false
    private val adapter = EmojiAdapter(ui, history, { recordingEnabled }, onPick, ::updateItems)
    private val clear =
        ui.button("Clear recent") {
            if (confirmingClear) {
                history.clearRecent()
                confirmingClear = false
                refresh()
            } else {
                confirmingClear = true
                updateClear()
            }
        }

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(ui.background)
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.button("Search emoji", action = onSearch), LayoutParams(0, ui.dp(48), 1f))
        head.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(48)))
        addView(head)
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(categories)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)),
        )
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = ui.dp(48)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun refresh() {
        val latest = Emojis.cached()
        if (latest !== all) {
            all = latest
            byText = all.associateBy { it.text }
        }
        confirmingClear = false
        updateClear()
        buildCategories()
        updateItems()
    }

    private fun updateClear() {
        clear.text = if (confirmingClear) "Confirm clear" else "Clear recent"
        clear.isEnabled = history.recent().isNotEmpty()
        clear.alpha = if (clear.isEnabled) 1f else .4f
        clear.contentDescription = if (confirmingClear) "Confirm clearing recent emoji" else "Clear recent emoji, keeps favorites"
    }

    private fun buildCategories() {
        categories.removeAllViews()
        (listOf("Recent", "Favorites", "All") + all.map { it.category }.filter(String::isNotBlank).distinct()).forEach { name ->
            categories.addView(
                ui
                    .button(shortName(name)) {
                        active = name
                        confirmingClear = false
                        updateClear()
                        buildCategories()
                        updateItems()
                        grid.setSelection(0)
                    }.apply {
                        isSelected = active == name
                        setTextColor(if (isSelected) ui.onAccent else ui.text)
                        background = ui.ripple(if (isSelected) ui.accent else ui.background)
                        contentDescription = name
                    },
                LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(48)),
            )
        }
    }

    private fun shortName(name: String) =
        when (name) {
            "Smileys & Emotion" -> "Smileys"
            "People & Body" -> "People"
            "Animals & Nature" -> "Nature"
            "Food & Drink" -> "Food"
            "Travel & Places" -> "Travel"
            else -> name
        }

    private fun updateItems() {
        val values =
            when (active) {
                "Favorites" -> history.favorites().mapNotNull(byText::get)
                "Recent" -> history.recent().mapNotNull(byText::get)
                "All" -> all
                else -> all.filter { it.category == active }
            }
        empty.text =
            when {
                loadFailed -> "Emoji data is unavailable"
                !Emojis.ready -> "Loading emoji…"
                active == "Recent" -> "No recent emoji available. Pick one in All to start."
                active == "Favorites" -> "Hold any emoji to save it here."
                else -> "No emoji in this category on this device."
            }
        empty.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        adapter.setItems(values)
    }
}

private class EmojiAdapter(
    private val ui: BoardUi,
    private val history: EmojiHistory,
    private val mayRecord: () -> Boolean,
    private val onPick: (String) -> Unit,
    private val onFavoriteChanged: () -> Unit,
) : BaseAdapter() {
    private var items = emptyList<Emoji>()
    private var favorites = emptySet<String>()

    fun setItems(values: List<Emoji>) {
        items = values
        favorites = history.favorites().toSet()
        notifyDataSetChanged()
    }

    override fun getCount() = items.size

    override fun getItem(position: Int) = items[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val cell =
            (convertView as? TextView) ?: ui.label("", 26f).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(48)
                minWidth = ui.dp(48)
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                isFocusable = true
                background = ui.ripple()
            }
        val emoji = items[position]
        val favorite = emoji.text in favorites
        cell.text = emoji.text
        cell.contentDescription = "${emoji.name}${if (favorite) ", favorite" else ""}"
        cell.tooltipText = emoji.name
        cell.backgroundTintList = ColorStateList.valueOf(if (favorite) ui.modifier else ui.background)
        // The service records recent emoji only after the editor accepts the commit.
        cell.setOnClickListener { onPick(emoji.text) }
        cell.setOnLongClickListener {
            val message =
                if (!mayRecord()) {
                    "Favorites paused in private mode"
                } else {
                    history.toggleFavorite(emoji.text)
                    onFavoriteChanged()
                    if (history.isFavorite(emoji.text)) "Added to favorites" else "Removed from favorites"
                }
            Toast.makeText(ui.context, message, Toast.LENGTH_SHORT).show()
            true
        }
        return cell
    }
}
