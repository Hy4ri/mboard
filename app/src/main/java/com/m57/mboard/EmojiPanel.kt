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
        ui.label("", Tokens.TEXT_ROW, true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_4), ui.dp(Tokens.SPACE_5), ui.dp(Tokens.SPACE_4))
        }
    private var active = "All"
    private var confirmingClear = false
    private val unarmRunnable =
        Runnable {
            if (confirmingClear) {
                confirmingClear = false
                updateClear()
            }
        }
    var recordingEnabled = true
    var loadFailed = false
    private val adapter = EmojiAdapter(ui, history, { recordingEnabled }, onPick, ::updateItems)
    private val searchButton =
        ui.icon(R.drawable.ic_search, context.getString(R.string.emoji_search_hint)) { onSearch() }
    private val clear =
        ui.icon(R.drawable.ic_delete_sweep, context.getString(R.string.emoji_clear_recent_a11y)) {
            if (confirmingClear) {
                removeCallbacks(unarmRunnable)
                history.clearRecent()
                confirmingClear = false
                refresh()
            } else {
                confirmingClear = true
                updateClear()
                removeCallbacks(unarmRunnable)
                postDelayed(unarmRunnable, 3000L)
            }
        }
    private val header = ui.panelHeader(context.getString(R.string.toolbar_emoji))

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.background)

        header.setActions(listOf(searchButton, clear))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(Tokens.PANEL_HEADER)))

        val tabScroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(categories)
            }
        addView(tabScroll, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(Tokens.TARGET_MIN)))

        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = ui.dp(Tokens.TARGET_MIN)
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
        removeCallbacks(unarmRunnable)
        updateClear()
        buildCategories()
        updateItems()
    }

    private fun updateClear() {
        val hasRecent = history.recent().isNotEmpty()
        ui.setEnabledLook(clear, hasRecent)
        clear.background = ui.ripple(if (confirmingClear) ui.accent else android.graphics.Color.TRANSPARENT)
        clear.imageTintList = ColorStateList.valueOf(if (confirmingClear) ui.onAccent else ui.text)
        clear.tooltipText =
            context.getString(if (confirmingClear) R.string.emoji_confirm_clear_recent else R.string.emoji_clear_recent_tooltip)
        clear.contentDescription =
            context.getString(if (confirmingClear) R.string.emoji_confirm_clear_recent_a11y else R.string.emoji_clear_recent_a11y)
        header.title.text =
            when {
                confirmingClear -> context.getString(R.string.emoji_confirm_clear_recent)
                active == "All" -> context.getString(R.string.toolbar_emoji)
                else -> context.getString(R.string.emoji_header_category, categoryLabel(active))
            }
    }

    private fun buildCategories() {
        categories.removeAllViews()
        (listOf("Recent", "Favorites", "All") + all.map { it.category }.filter(String::isNotBlank).distinct()).forEach { name ->
            categories.addView(
                tab(categoryIcon(name), categoryLabel(name), active == name) {
                    active = name
                    confirmingClear = false
                    removeCallbacks(unarmRunnable)
                    updateClear()
                    buildCategories()
                    updateItems()
                    grid.setSelection(0)
                },
                LayoutParams(ui.dp(Tokens.TARGET_MIN), ui.dp(Tokens.TARGET_MIN)),
            )
        }
    }

    private fun tab(
        res: Int,
        name: String,
        selected: Boolean,
        action: () -> Unit,
    ) = ui.icon(res, name, action).apply {
        isSelected = selected
        imageTintList = ColorStateList.valueOf(if (selected) ui.onAccent else ui.text)
        background = ui.ripple(if (selected) ui.accent else android.graphics.Color.TRANSPARENT)
    }

    private fun categoryLabel(name: String) =
        when (name) {
            "Recent" -> context.getString(R.string.emoji_cat_recent)
            "Favorites" -> context.getString(R.string.emoji_cat_favorites)
            "All" -> context.getString(R.string.emoji_cat_all)
            "Smileys & Emotion" -> context.getString(R.string.emoji_cat_smileys)
            "People & Body" -> context.getString(R.string.emoji_cat_people)
            "Animals & Nature" -> context.getString(R.string.emoji_cat_animals)
            "Food & Drink" -> context.getString(R.string.emoji_cat_food)
            "Travel & Places" -> context.getString(R.string.emoji_cat_travel)
            "Activities" -> context.getString(R.string.emoji_cat_activities)
            "Objects" -> context.getString(R.string.emoji_cat_objects)
            "Symbols" -> context.getString(R.string.emoji_cat_symbols)
            "Flags" -> context.getString(R.string.emoji_cat_flags)
            else -> name
        }

    private fun categoryIcon(name: String) =
        when (name) {
            "Recent" -> R.drawable.ic_history
            "Favorites" -> R.drawable.ic_star
            "All" -> R.drawable.ic_apps
            "Smileys & Emotion" -> R.drawable.ic_mood
            "People & Body" -> R.drawable.ic_emoji_people
            "Animals & Nature" -> R.drawable.ic_emoji_nature
            "Food & Drink" -> R.drawable.ic_emoji_food_beverage
            "Travel & Places" -> R.drawable.ic_emoji_transportation
            "Activities" -> R.drawable.ic_emoji_events
            "Objects" -> R.drawable.ic_emoji_objects
            "Symbols" -> R.drawable.ic_emoji_symbols
            "Flags" -> R.drawable.ic_emoji_flags
            else -> R.drawable.ic_sentiment_satisfied
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
            context.getString(
                when {
                    loadFailed -> R.string.emoji_data_unavailable
                    !Emojis.ready -> R.string.emoji_loading
                    active == "Recent" -> R.string.emoji_empty_recent
                    active == "Favorites" -> R.string.emoji_empty_favorites
                    else -> R.string.emoji_empty_category
                },
            )
        empty.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        adapter.setItems(values)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(unarmRunnable)
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
            (convertView as? TextView) ?: ui.label("", Tokens.KEY_POPUP).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(Tokens.TARGET_MIN)
                minWidth = ui.dp(Tokens.TARGET_MIN)
                setPadding(0, ui.dp(Tokens.SPACE_1), 0, ui.dp(Tokens.SPACE_1))
                isFocusable = true
                background = ui.ripple()
            }
        val emoji = items[position]
        val favorite = emoji.text in favorites
        cell.text = emoji.text
        cell.contentDescription =
            if (favorite) {
                ui.context.getString(R.string.emoji_cell_favorite_a11y, emoji.name)
            } else {
                emoji.name
            }
        cell.tooltipText = emoji.name
        cell.backgroundTintList = ColorStateList.valueOf(if (favorite) ui.modifier else ui.background)
        // The service records recent emoji only after the editor accepts the commit.
        cell.setOnClickListener { onPick(emoji.text) }
        cell.setOnLongClickListener {
            val message =
                if (!mayRecord()) {
                    R.string.emoji_favorites_paused
                } else {
                    history.toggleFavorite(emoji.text)
                    onFavoriteChanged()
                    if (history.isFavorite(emoji.text)) R.string.emoji_favorite_added else R.string.emoji_favorite_removed
                }
            Toast.makeText(ui.context, message, Toast.LENGTH_SHORT).show()
            true
        }
        return cell
    }
}
