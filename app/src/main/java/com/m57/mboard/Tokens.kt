package com.m57.mboard

import kotlin.math.pow

/**
 * The single source of design values: colors, type, spacing, radii, sizes, motion.
 * Pure Kotlin (no Android imports). Views read these through [BoardUi]; no other file may add literals.
 */
object Tokens {
    /** Opaque ARGB from 0xRRGGBB. */
    private fun rgb(value: Int) = value or (0xFF shl 24)

    /** One theme's base colors. */
    class Palette(
        val bg: Int,
        val surface: Int,
        val function: Int,
        val text: Int,
        val muted: Int,
        val danger: Int,
        /** Weight of black mixed into [bg] for the key lower edge. */
        val edgeShade: Float,
        /** Weight of the accent mixed into a base color when pressed. */
        val pressMix: Float,
        /** Weight of [text] mixed into [surface] for raised surfaces (popup, preview, dialog). */
        val raisedMix: Float,
    )

    val dark =
        Palette(
            bg = rgb(0x14141A),
            surface = rgb(0x24242E),
            function = rgb(0x353540),
            text = rgb(0xF4F2FA),
            muted = rgb(0xBDBACB),
            danger = rgb(0xFFB4AB),
            edgeShade = .35f,
            pressMix = .32f,
            raisedMix = .06f,
        )
    val light =
        Palette(
            bg = rgb(0xF1F0F7),
            surface = rgb(0xFFFFFF),
            function = rgb(0xE0DEEA),
            text = rgb(0x21212C),
            muted = rgb(0x605C70),
            danger = rgb(0xA32626),
            edgeShade = .12f,
            pressMix = .22f,
            raisedMix = 0f,
        )

    /** Accent preset: fill color for dark and light, plus the ink used for text and icons on a neutral surface. */
    class Accent(
        val dark: Int,
        val light: Int,
        val darkInk: Int = dark,
        val lightInk: Int = light,
        /** Color of text and glyphs drawn on the accent fill. */
        val darkOn: Int = rgb(0x20202A),
        val lightOn: Int = rgb(0xFFFFFF),
    )

    val accents =
        mapOf(
            "violet" to Accent(rgb(0xC9B8FF), rgb(0x6241A6)),
            "teal" to Accent(rgb(0x80D8CE), rgb(0x006B61)),
            "amber" to Accent(rgb(0xF6CF80), rgb(0x785300)),
            // Crimson keeps the requested exact fill; only dark-theme ink and on-color are lifted.
            "crimson" to Accent(rgb(0x990000), rgb(0x990000), darkInk = rgb(0xFFB4AB), darkOn = rgb(0xFFFFFF)),
        )
    const val DEFAULT_ACCENT = "violet"

    /** Text colors used on custom accents: dark ink on light fills and white on dark fills. */
    val onLightFill = rgb(0x20202A)
    val onDarkFill = rgb(0xFFFFFF)

    // Mix weights (0..1)
    const val ACCENT_CONTAINER_MIX = .16f
    const val SWITCH_TRACK_OFF_MIX = .5f
    const val SWITCH_TRACK_ON_MIX = .7f
    const val CUSTOM_MUTED_MIX = .35f

    /** Ripple alpha in the high byte: about 19 percent. */
    const val RIPPLE_ALPHA = 0x30

    /** Every disabled control, text or glyph, is drawn at this alpha. */
    const val DISABLED_ALPHA = .5f

    /** Below this ratio against the background a key needs an outline; below 3 the color dialog warns. */
    const val KEY_OUTLINE_RATIO = 1.3
    const val WARN_RATIO = 3.0
    const val TEXT_RATIO = 4.5

    // Type scale in sp. Keyboard key text is in dp and does not follow font scale.
    const val TEXT_TITLE = 28f
    const val TEXT_HEADER = 22f
    const val TEXT_ROW = 16f
    const val TEXT_BODY = 14f
    const val TEXT_CAPTION = 12f
    const val TEXT_EMOJI = 24f
    const val KEY_LABEL = 22f
    const val KEY_LABEL_LONG = 14f
    const val KEY_POPUP = 26f

    // Spacing in dp, 4-based.
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_5 = 24

    // Radius in dp.
    const val RADIUS_KEY = 8
    const val RADIUS_CONTROL = 12
    const val RADIUS_CARD = 16
    const val RADIUS_SHEET = 20

    /** Large enough for any control height here: renders as a pill. */
    const val RADIUS_PILL = 999

    // Sizes in dp.
    const val ICON = 24
    const val ICON_SMALL = 20
    const val TARGET_MIN = 48
    const val TOOLBAR_HEIGHT = 48
    const val STRIP_HEIGHT = 40
    const val PANEL_HEADER = 48
    const val KEY_GAP = 4

    /** Minimum key row height in landscape (dp); wins over [LANDSCAPE_HEIGHT_FRACTION]. */
    const val KEY_ROW_MIN = 34

    /** Landscape target for toolbar + strip + keys as a fraction of window height. */
    const val LANDSCAPE_HEIGHT_FRACTION = 0.60f
    const val KEY_OUTLINE_WIDTH = 1
    const val FOCUS_WIDTH = 2
    const val SETTINGS_HEADER = 56
    const val CONTENT_MAX_WIDTH = 640

    // Motion in ms.
    const val MOTION_INSTANT = 70L
    const val MOTION_FAST = 120L
    const val MOTION_BASE = 200L
    const val MOTION_HOLD = 250L

    /** Linear blend of two opaque colors; [t] = 0 gives [from], 1 gives [to]. */
    fun mix(
        from: Int,
        to: Int,
        t: Float,
    ): Int {
        val k = t.coerceIn(0f, 1f)

        fun channel(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** WCAG relative luminance of an sRGB color. */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= .03928) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    /** WCAG contrast ratio, 1.0 to 21.0, symmetric in its arguments. */
    fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + .05) / (minOf(la, lb) + .05)
    }
}
