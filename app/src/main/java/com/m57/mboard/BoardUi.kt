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
import android.widget.LinearLayout
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

    private val palette = if (dark) Tokens.dark else Tokens.light

    private fun custom(role: String) = settings.customColor(role)

    val background = custom(BoardSettings.BACKGROUND) ?: palette.bg
    val surface = custom(BoardSettings.KEYS) ?: palette.surface

    /** Function-key fill (shift, backspace, enter, rail buttons, selected cells). */
    val functionSurface = custom(BoardSettings.SPECIAL_KEYS) ?: palette.function
    val modifier get() = functionSurface
    val text = custom(BoardSettings.TEXT) ?: palette.text

    // Secondary ink follows a custom text color so labels stay in the same family.
    val muted = custom(BoardSettings.TEXT)?.let { mix(it, background, Tokens.CUSTOM_MUTED_MIX) } ?: palette.muted
    private val customAccent = custom(BoardSettings.ACCENT)
    private val preset = Tokens.accents[settings.accent] ?: Tokens.accents.getValue(Tokens.DEFAULT_ACCENT)
    val accent = customAccent ?: if (dark) preset.dark else preset.light
    val onAccent =
        if (customAccent != null) {
            if (luminance(customAccent) > .5f) Tokens.onLightFill else Tokens.onDarkFill
        } else {
            if (dark) preset.darkOn else preset.lightOn
        }

    // Keep the requested crimson fill exact; lift only standalone dark-theme control ink.
    val controlAccent = if (customAccent == null) (if (dark) preset.darkInk else preset.lightInk) else accent

    /** Accent ink: text and glyphs in accent color on a neutral surface. */
    val accentInk get() = controlAccent
    val danger = palette.danger

    /** Raised surfaces: alternates popup, preview bubble, dialogs. Equals [surface] in light themes. */
    val raised = mix(surface, text, palette.raisedMix)

    /** Selected chip, shift/caps on, active toolbar icon, first emoji result. */
    val accentContainer = mix(surface, accent, Tokens.ACCENT_CONTAINER_MIX)
    val switchTrackOff = mix(surface, text, Tokens.SWITCH_TRACK_OFF_MIX)
    val switchTrackOn = mix(surface, controlAccent, Tokens.SWITCH_TRACK_ON_MIX)
    val disabledAlpha = Tokens.DISABLED_ALPHA

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(
        fill: Int,
        radius: Int = Tokens.RADIUS_CONTROL,
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    fun ripple(
        fill: Int = Color.TRANSPARENT,
        radii: FloatArray? = null,
    ) = RippleDrawable(
        ColorStateList.valueOf((accent and 0x00FFFFFF) or (Tokens.RIPPLE_ALPHA shl 24)),
        shape(fill).apply { radii?.let { cornerRadii = it } },
        shape(Color.WHITE).apply { radii?.let { cornerRadii = it } },
    )

    /** Same fill, radius and lower edge as a keyboard key, with a press ripple. */
    fun keyChip(fill: Int): RippleDrawable {
        val radius = dp(Tokens.RADIUS_KEY)
        val body =
            android.graphics.drawable.LayerDrawable(arrayOf(shape(keyEdge, Tokens.RADIUS_KEY), shape(fill, Tokens.RADIUS_KEY))).apply {
                setLayerInset(0, 0, dp(1), 0, 0)
                setLayerInset(1, 0, 0, 0, dp(1))
            }
        return RippleDrawable(
            ColorStateList.valueOf((accent and 0x00FFFFFF) or (Tokens.RIPPLE_ALPHA shl 24)),
            body,
            shape(Color.WHITE, Tokens.RADIUS_KEY).apply { cornerRadius = radius.toFloat() },
        )
    }

    /** Linear blend of two opaque colors; [t] = 0 gives [from], 1 gives [to]. */
    fun mix(
        from: Int,
        to: Int,
        t: Float,
    ): Int = Tokens.mix(from, to, t)

    /** WCAG contrast ratio of two colors, 1.0 to 21.0. */
    fun contrast(
        a: Int,
        b: Int,
    ): Double = Tokens.contrast(a, b)

    /** True when [fill] is too close to the keyboard background to show a key shape: draw an [keyEdge] outline. */
    fun needsOutline(fill: Int) = contrast(fill, background) < Tokens.KEY_OUTLINE_RATIO

    /** Pressed keys tint toward the accent instead of flashing it, so fast typing doesn't strobe. */
    fun pressed(base: Int) = mix(base, accent, palette.pressMix)

    /** A thin lower edge that gives keys depth without a real shadow pass. */
    val keyEdge = mix(background, Color.BLACK, palette.edgeShade)

    fun label(
        value: String,
        size: Float = LABEL_DEFAULT_SP,
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
        textSize = Tokens.TEXT_BODY
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(Tokens.TARGET_MIN)
        minimumHeight = dp(Tokens.TARGET_MIN)
        setPadding(dp(Tokens.SPACE_3), dp(Tokens.SPACE_1), dp(Tokens.SPACE_3), dp(Tokens.SPACE_1))
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
        minimumWidth = dp(Tokens.TARGET_MIN)
        minimumHeight = dp(Tokens.TARGET_MIN)
        setPadding(dp(Tokens.SPACE_3), dp(Tokens.SPACE_3), dp(Tokens.SPACE_3), dp(Tokens.SPACE_3))
        background = ripple()
        backgroundTintList = null
        elevation = 0f
        stateListAnimator = null
        setOnClickListener { action() }
    }

    fun heading(value: String) =
        label(value, HEADING_SP).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(Tokens.SPACE_5), 0, dp(Tokens.SPACE_3))
        }

    private fun luminance(value: Int) = Color.luminance(value)

    /** Disabled look for any control: same glyph or text at [Tokens.DISABLED_ALPHA]; never hidden. */
    fun setEnabledLook(
        view: android.view.View,
        enabled: Boolean,
    ) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else Tokens.DISABLED_ALPHA
    }

    /**
     * The one header every non-key panel uses: [Tokens.PANEL_HEADER] dp high, title or count on the left,
     * action views on the right. Callers set [PanelHeader.title] and [PanelHeader.setActions] as state changes.
     */
    fun panelHeader(title: CharSequence = ""): PanelHeader = PanelHeader(this, title)

    class PanelHeader(
        private val ui: BoardUi,
        initial: CharSequence,
    ) : LinearLayout(ui.context) {
        val title: TextView =
            ui.label(initial.toString(), Tokens.TEXT_BODY, true).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isAccessibilityHeading = true
            }
        private val actions = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }

        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(Tokens.PANEL_HEADER)
            setPadding(ui.dp(Tokens.SPACE_3), 0, ui.dp(Tokens.SPACE_1), 0)
            addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        /** Replaces the right-hand actions; give each at least [Tokens.TARGET_MIN] dp (use [BoardUi.icon] or [BoardUi.button]). */
        fun setActions(views: List<android.view.View>) {
            actions.removeAllViews()
            views.forEach { actions.addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(Tokens.TARGET_MIN))) }
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) = super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(ui.dp(Tokens.PANEL_HEADER), MeasureSpec.EXACTLY))
    }

    private companion object {
        const val LABEL_DEFAULT_SP = 15f
        const val HEADING_SP = 19f
    }
}
