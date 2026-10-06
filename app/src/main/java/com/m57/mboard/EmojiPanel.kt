package com.m57.mboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

/** Search bar plus a scrollable grid of every emoji. */
class EmojiPanel(
    context: Context,
    onSearch: () -> Unit,
    private val onPick: (String) -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        addView(
            TextView(context).apply {
                text = "🔍  Search emoji"
                textSize = 15f
                alpha = 0.7f
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), 0, (10 * dp).toInt())
                setOnClickListener { onSearch() }
            },
        )
        addView(
            GridView(context).apply {
                numColumns = 8
                adapter = EmojiAdapter(Emojis.all(context), onPick)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
}

class EmojiAdapter(
    private val items: List<Emoji>,
    private val onPick: (String) -> Unit,
) : BaseAdapter() {
    override fun getCount() = items.size

    override fun getItem(position: Int) = items[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val tv =
            (convertView as? TextView) ?: TextView(parent.context).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                val p = (6 * resources.displayMetrics.density).toInt()
                setPadding(0, p, 0, p)
            }
        val e = items[position]
        tv.text = e.text
        tv.setOnClickListener { onPick(e.text) }
        return tv
    }
}
