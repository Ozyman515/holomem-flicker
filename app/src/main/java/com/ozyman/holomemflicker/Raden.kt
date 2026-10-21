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
    const val TEAL_DEEP = 0xFF16463E.toInt()   // darkest teal: header, editor banners
    const val TEAL = 0xFF2B6E61.toInt()        // menu panel teal: buttons
    const val TEAL_SOFT = 0xFF4F8E80.toInt()
    const val MINT = 0xFFDDEBE6.toInt()        // page background
    const val MINT_LINE = 0xFFB7D3CA.toInt()   // card borders
    const val CREAM = 0xFFFBF7EE.toInt()       // cards / text on teal
    const val PLATINUM = 0xFFE9E1CF.toInt()    // her hair highlight
    const val INK = 0xFF1F2A28.toInt()         // her black hair: body text
    const val INK_SOFT = 0xFF4A5B57.toInt()
    const val ORANGE = 0xFFE0895B.toInt()      // sienna accent bar
    const val ORANGE_DEEP = 0xFFC86D3F.toInt()
    const val CRIMSON = 0xFF9E1C1F.toInt()     // selected tab red
    const val NIGHT = 0xFF0E2622.toInt()       // dark screens (editor / controller test)
    const val NIGHT_PANEL = 0xFF1C4039.toInt()
    const val SEA_GLASS = 0xFF5ED1B3.toInt()   // bright teal: "engine sees it"

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
