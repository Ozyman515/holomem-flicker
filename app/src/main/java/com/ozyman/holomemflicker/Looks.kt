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
    const val AURORA = "aurora"
    const val SPOTLIGHT = "spotlight"
    const val BEAM = "beam"

    val ALL = listOf(PENLIGHT, MINIMAL, NEON, BUBBLE, GAMEPAD, PIXEL, AURORA, SPOTLIGHT, BEAM)

    val NAMES = mapOf(
        PENLIGHT to "Penlight",
        MINIMAL to "Minimal",
        NEON to "Neon",
        BUBBLE to "Bubble",
        GAMEPAD to "Gamepad",
        PIXEL to "Pixel",
        AURORA to "Aurora",
        SPOTLIGHT to "Spotlight",
        BEAM to "Beam"
    )

    val BLURBS = mapOf(
        PENLIGHT to "Lit round buttons with a glowing edge (the original look).",
        MINIMAL to "Thin outlines and small names - covers the least of the game.",
        NEON to "Hollow double rings in two theme colours with a strong glow.",
        BUBBLE to "Glossy see-through bubbles with a white shine, like the theme picker.",
        GAMEPAD to "Solid controller buttons (A green, B red, X blue, Y yellow) and thumbstick caps.",
        PIXEL to "Chunky 8-bit blocks and a monospace font.",
        AURORA to "Smoky glass buttons ringed with a colour-shifting gradient and a soft halo.",
        SPOTLIGHT to "A light shining down on each button, with crisp hairline edges.",
        BEAM to "Dark hairline circles with a bright beam of light running along the edge."
    )

    fun current(ctx: Context): String = Prefs.getStr(ctx, PREF, PENLIGHT).takeIf { it in ALL } ?: PENLIGHT

    fun set(ctx: Context, look: String) = Prefs.putStr(ctx, PREF, look)
}
