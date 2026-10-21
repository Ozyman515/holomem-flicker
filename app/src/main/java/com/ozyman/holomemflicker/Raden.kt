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

    /** Pill button in one of the palette colours. */
    fun pill(b: Button, fill: Int, text: Int = CREAM, radius: Float = 60f) {
        b.background = RippleDrawable(
            ColorStateList.valueOf(alpha(Color.WHITE, 0x55)),
            rounded(fill, radius, alpha(Color.BLACK, 0x22), 2),
            null
        )
        b.setTextColor(text)
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.elevation = 0f
        b.setPadding(36, 18, 36, 18)
        b.minHeight = 0
        b.minimumHeight = 0
    }

    fun seek(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(ORANGE)
        s.progressBackgroundTintList = ColorStateList.valueOf(MINT_LINE)
        s.thumbTintList = ColorStateList.valueOf(CRIMSON)
    }

    fun card(v: View) {
        v.background = rounded(CREAM, 36f, MINT_LINE, 4)
        v.elevation = 0f
    }

    fun heading(t: TextView, size: Float = 19f) {
        t.setTextColor(TEAL_DEEP)
        t.textSize = size
        t.typeface = Typeface.DEFAULT_BOLD
    }
}
