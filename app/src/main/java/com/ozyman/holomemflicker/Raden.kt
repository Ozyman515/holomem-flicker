package com.ozyman.holomemflicker

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/**
 * Juufuutei Raden colour scheme (taken from her in-game menus):
 * deep teal + mint panels, cream cards, sienna-orange accents, crimson for "selected / stop",
 * and her black / platinum hair for ink and highlights.
 */
object Raden {
    // Live palette: starts as Raden's, swapped by [apply] when another member's theme is chosen.
    var TEAL_DEEP = 0xFF16463E.toInt()   // darkest: header, editor banners
    var TEAL = 0xFF2B6E61.toInt()        // main buttons
    var TEAL_SOFT = 0xFF4F8E80.toInt()   // secondary buttons
    var MINT = 0xFFDDEBE6.toInt()        // page background
    var MINT_LINE = 0xFFB7D3CA.toInt()   // card borders
    var CREAM = 0xFFFBF7EE.toInt()       // cards / text on dark
    var PLATINUM = 0xFFE9E1CF.toInt()    // light highlight text on dark
    const val INK = 0xFF1F2A28.toInt()         // body text
    const val INK_SOFT = 0xFF4A5B57.toInt()
    var ORANGE = 0xFFE0895B.toInt()      // accent: start / save / sliders
    var ORANGE_DEEP = 0xFFC86D3F.toInt()
    var CRIMSON = 0xFF9E1C1F.toInt()     // alert: stop / selected
    var NIGHT = 0xFF0E2622.toInt()       // dark screens
    var NIGHT_PANEL = 0xFF1C4039.toInt()
    const val SEA_GLASS = 0xFF5ED1B3.toInt()   // "engine sees it" in the controller test

    const val PREF_KEY = "theme"
    var current: ThemeSpec = ThemeData.ALL[0]
        private set

    /** Load the saved theme into the live palette. Call at the top of every screen's onCreate. */
    fun apply(ctx: android.content.Context) = use(ThemeData.byKey(Prefs.getStr(ctx, PREF_KEY, "raden")))

    fun use(t: ThemeSpec) {
        current = t
        TEAL_DEEP = t.deep; TEAL = t.primary; TEAL_SOFT = t.soft
        MINT = t.bg; MINT_LINE = t.line; CREAM = t.card
        ORANGE = t.accent; ORANGE_DEEP = mix(t.accent, 0xFF000000.toInt(), 0.18f)
        CRIMSON = t.alert; NIGHT = t.night; NIGHT_PANEL = mix(t.night, t.primary, 0.28f)
        PLATINUM = mix(t.card, t.line, 0.45f)
    }

    fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (strokeW > 0) setStroke(strokeW, stroke)
        }

    /**
     * Design tokens (spacing, corners, type) in the spirit of Meta's Astryx design system:
     * one small scale used everywhere so every screen lines up.
     */
    object T {
        const val R_SM = 16f     // chips, small controls
        const val R_MD = 26f     // buttons
        const val R_LG = 40f     // cards, header
        const val HAIR = 2       // hairline borders
        const val PAD_X = 40     // button padding
        const val PAD_Y = 22
        const val TITLE = 19f
        const val BODY = 16f
        const val CAPTION = 13f
    }

    /**
     * Button in one of the palette colours: solid, with a faint top highlight.
     * [tonal] (light screens only): the theme's main colour as a soft tinted fill with coloured
     * text, so the solid accent / alert buttons stand out as the important ones.
     */
    fun pill(b: Button, fill: Int, text: Int = CREAM, radius: Float = T.R_MD, tonal: Boolean = false) {
        val face = if (tonal) {
            rounded(mix(CREAM, TEAL, 0.13f), radius, alpha(TEAL, 0x33), T.HAIR)
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(mix(fill, Color.WHITE, 0.10f), fill)
            ).apply {
                cornerRadius = radius
                setStroke(T.HAIR, mix(fill, Color.BLACK, 0.12f))
            }
        }
        b.background = RippleDrawable(
            ColorStateList.valueOf(alpha(if (tonal) TEAL else Color.WHITE, 0x40)), face, null
        )
        b.setTextColor(if (tonal) TEAL_DEEP else text)
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.letterSpacing = 0.01f
        b.stateListAnimator = null
        b.elevation = 0f
        b.setPadding(T.PAD_X, T.PAD_Y, T.PAD_X, T.PAD_Y)
        b.minHeight = 0
        b.minimumHeight = 0
        Motion.press(b)
    }

    /** Light-screen button: the theme's main colour comes out tonal, anything else solid. */
    fun ui(b: Button, fill: Int) = pill(b, fill, tonal = fill == TEAL)

    fun seek(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(ORANGE)
        s.progressBackgroundTintList = ColorStateList.valueOf(mix(MINT_LINE, CREAM, 0.2f))
        s.thumbTintList = ColorStateList.valueOf(ORANGE_DEEP)
    }

    /** Card: cream surface, hairline border and a soft tinted shadow underneath. */
    fun card(v: View) {
        val shadow = rounded(alpha(TEAL_DEEP, 0x14), T.R_LG)
        val face = rounded(CREAM, T.R_LG, mix(MINT_LINE, CREAM, 0.35f), T.HAIR)
        v.background = android.graphics.drawable.LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(0, 0, 6, 0, 0)
            setLayerInset(1, 0, 0, 0, 6)
        }
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom + 6)
        v.elevation = 0f
    }

    fun heading(t: TextView, size: Float = T.TITLE) {
        t.setTextColor(TEAL_DEEP)
        t.textSize = size
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = -0.01f
    }

    /** Small all-caps label above a heading (e.g. "SETTINGS"). */
    fun eyebrow(t: TextView) {
        t.setTextColor(ORANGE_DEEP)
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.14f
        t.isAllCaps = true
    }

    /**
     * Header banner: diagonal gradient in the theme's deep/main colours with a soft glow of the
     * accent colour in the top-right corner (Aceternity-style spotlight, drawn once - no animation).
     */
    fun banner(v: View) {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(TEAL_DEEP, TEAL, mix(TEAL, TEAL_SOFT, 0.5f))).apply {
            cornerRadius = T.R_LG
        }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(alpha(ORANGE, 0x59), alpha(ORANGE, 0x00))
            gradientRadius = 520f
            setGradientCenter(0.85f, 0.1f)
            cornerRadius = T.R_LG
        }
        val edge = rounded(Color.TRANSPARENT, T.R_LG, alpha(Color.WHITE, 0x26), T.HAIR)
        v.background = android.graphics.drawable.LayerDrawable(arrayOf(base, glow, edge))
    }
}
