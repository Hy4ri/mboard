package com.m57.mboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView

/** One quiet visual language for the keyboard, its panels, and the settings screen. */
class BoardUi(
    val context: Context,
) {
    private val settings = BoardSettings(context)
    val dark =
        when (settings.appearance) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

    private fun custom(role: String) = settings.customColor(role)

    val background = custom(BoardSettings.BACKGROUND) ?: color(if (dark) "#14141A" else "#F1F0F7")
    val surface = custom(BoardSettings.KEYS) ?: color(if (dark) "#24242E" else "#FFFFFF")
    val modifier = custom(BoardSettings.SPECIAL_KEYS) ?: color(if (dark) "#353540" else "#E0DEEA")
    val text = custom(BoardSettings.TEXT) ?: color(if (dark) "#F4F2FA" else "#21212C")

    // Secondary ink follows a custom text color so labels stay in the same family.
    val muted = custom(BoardSettings.TEXT)?.let { mix(it, background, .35f) } ?: color(if (dark) "#BDBACB" else "#605C70")
    private val customAccent = custom(BoardSettings.ACCENT)
    val accent =
        customAccent ?: color(
            when (settings.accent) {
                "teal" -> if (dark) "#80D8CE" else "#006B61"
                "amber" -> if (dark) "#F6CF80" else "#785300"
                "crimson" -> "#990000"
                else -> if (dark) "#C9B8FF" else "#6241A6"
            },
        )
    val onAccent =
        if (customAccent != null) {
            if (luminance(customAccent) > .5f) color("#20202A") else color("#FFFFFF")
        } else {
            color(if (dark && settings.accent != "crimson") "#20202A" else "#FFFFFF")
        }

    // Keep the requested crimson fill exact; lift only standalone dark-theme control ink.
    val controlAccent = if (customAccent == null && dark && settings.accent == "crimson") color("#FFB4AB") else accent
    val danger = color(if (dark) "#FFB4AB" else "#A32626")

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(
        fill: Int,
        radius: Int = 12,
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    fun ripple(
        fill: Int = Color.TRANSPARENT,
        radii: FloatArray? = null,
    ) = RippleDrawable(
        ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x30000000),
        shape(fill).apply { radii?.let { cornerRadii = it } },
        shape(Color.WHITE).apply { radii?.let { cornerRadii = it } },
    )

    /** Same fill, radius and lower edge as a keyboard key, with a press ripple. */
    fun keyChip(fill: Int): RippleDrawable {
        val radius = dp(8)
        val body =
            android.graphics.drawable.LayerDrawable(arrayOf(shape(keyEdge, 8), shape(fill, 8))).apply {
                setLayerInset(0, 0, dp(1), 0, 0)
                setLayerInset(1, 0, 0, 0, dp(1))
            }
        return RippleDrawable(
            ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x30000000),
            body,
            shape(Color.WHITE, 8).apply { cornerRadius = radius.toFloat() },
        )
    }

    /** Linear blend of two opaque colors; [t] = 0 gives [from], 1 gives [to]. */
    fun mix(
        from: Int,
        to: Int,
        t: Float,
    ): Int {
        val k = t.coerceIn(0f, 1f)

        fun channel(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * k).toInt()
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    /** Pressed keys tint toward the accent instead of flashing it, so fast typing doesn't strobe. */
    fun pressed(base: Int) = mix(base, accent, if (dark) .32f else .22f)

    /** A thin lower edge that gives keys depth without a real shadow pass. */
    val keyEdge = mix(background, Color.BLACK, if (dark) .35f else .12f)

    fun label(
        value: String,
        size: Float = 15f,
        secondary: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (secondary) muted else this@BoardUi.text)
    }

    fun button(
        value: String,
        primary: Boolean = false,
        action: () -> Unit,
    ) = Button(context).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        setTextColor(if (primary) onAccent else this@BoardUi.text)
        background = ripple(if (primary) accent else surface)
        backgroundTintList = null
        elevation = 0f
        stateListAnimator = null
        setOnClickListener { action() }
    }

    fun icon(
        res: Int,
        description: String,
        action: () -> Unit,
    ) = ImageButton(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(text)
        contentDescription = description
        tooltipText = description
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple()
        backgroundTintList = null
        elevation = 0f
        stateListAnimator = null
        setOnClickListener { action() }
    }

    fun heading(value: String) =
        label(value, 19f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(12))
        }

    private fun color(value: String) = Color.parseColor(value)

    private fun luminance(value: Int) = Color.luminance(value)
}
