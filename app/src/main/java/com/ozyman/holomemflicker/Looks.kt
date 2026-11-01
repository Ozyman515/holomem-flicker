package com.ozyman.holomemflicker

import android.content.Context

/**
 * How the layout's circles and buttons are drawn (in-game overlay and both editors).
 * Colours always follow the member theme; the look only changes the shapes and effects.
 */
object Looks {
    const val PREF = "overlayLook"

    const val PENLIGHT = "penlight"
    const val MINIMAL = "minimal"
    const val NEON = "neon"
    const val BUBBLE = "bubble"
    const val GAMEPAD = "gamepad"
    const val PIXEL = "pixel"

    val ALL = listOf(PENLIGHT, MINIMAL, NEON, BUBBLE, GAMEPAD, PIXEL)

    val NAMES = mapOf(
        PENLIGHT to "Penlight",
        MINIMAL to "Minimal",
        NEON to "Neon",
        BUBBLE to "Bubble",
        GAMEPAD to "Gamepad",
        PIXEL to "Pixel"
    )

    val BLURBS = mapOf(
        PENLIGHT to "Lit round buttons with a glowing edge (the original look).",
        MINIMAL to "Thin outlines and small names - covers the least of the game.",
        NEON to "Hollow double rings in two theme colours with a strong glow.",
        BUBBLE to "Glossy see-through bubbles with a white shine, like the theme picker.",
        GAMEPAD to "Solid controller buttons (A green, B red, X blue, Y yellow) and thumbstick caps.",
        PIXEL to "Chunky 8-bit blocks and a monospace font."
    )

    fun current(ctx: Context): String = Prefs.getStr(ctx, PREF, PENLIGHT).takeIf { it in ALL } ?: PENLIGHT

    fun set(ctx: Context, look: String) = Prefs.putStr(ctx, PREF, look)
}
