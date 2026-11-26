package com.ozyman.holomemflicker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the in-game Puka Puka Coach panel (and its Fix board button/editor) over a stand-in game screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class CoachScreensTest {
    private val out = File(System.getProperty("screens.dir") ?: "screens").apply { mkdirs() }
    private val w = 2560
    private val h = 1600

    private fun coach() = Coach(runAsync = { it() }, post = { it() }, changed = {}, thinkMs = 400)

    private fun backdrop(c: Canvas) {
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF7FC8E8.toInt(), 0xFFF6D7A8.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // stand-in for the game's own board, centre-right
        p.shader = null
        p.color = 0x55FFFFFF
        c.drawRoundRect(w * 0.42f, h * 0.18f, w * 0.92f, h * 0.86f, 40f, 40f, p)
        p.color = 0x99304050.toInt(); p.textSize = 48f; p.textAlign = Paint.Align.CENTER
        c.drawText("(game board)", w * 0.67f, h * 0.53f, p)
    }

    private fun shot(name: String, coach: Coach, fix: Boolean = true) {
        Raden.apply(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        backdrop(c)
        // the overlay window itself is drawn at 0.75 alpha
        val layer = c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (0.75f * 255).toInt())
        val painter = CoachPainter(2f)
        painter.draw(c, w.toFloat(), h.toFloat(), coach, 0L)
        c.restoreToCount(layer)
        if (fix) {
            val b = CoachFixButton(RuntimeEnvironment.getApplication()) {}.also { it.scale = coach.settings.scale / 100f }
            b.measure(View.MeasureSpec.makeMeasureSpec(b.sizeW(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(b.sizeH(), View.MeasureSpec.EXACTLY))
            b.layout(0, 0, b.sizeW(), b.sizeH())
            c.save()
            c.translate(painter.panel.left, painter.panel.bottom + 16f)
            val l2 = c.saveLayerAlpha(0f, 0f, b.sizeW().toFloat(), b.sizeH().toFloat(), (0.9f * 255).toInt())
            b.draw(c)
            c.restoreToCount(l2)
            c.restore()
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A middle-game position with pieces on both benches. */
    private fun midgame(turn: Int): Puka.State {
        val s = Puka.empty(turn)
        s.b[Puka.index(0, 1)] = Puka.LEADER
        s.b[Puka.index(1, 0)] = Puka.WHALE
        s.b[Puka.index(1, 2)] = Puka.DOLPHIN
        s.b[Puka.index(2, 1)] = Puka.GOLD
        s.b[Puka.index(3, 2)] = Puka.LEADER or 8
        s.b[Puka.index(3, 0)] = Puka.DOLPHIN or 8
        s.b[Puka.index(2, 2)] = Puka.TURTLE or 8
        s.h[0 * 5 + Puka.TURTLE] = 1
        s.h[1 * 5 + Puka.WHALE] = 1
        s.h[1 * 5 + Puka.DOLPHIN] = 0
        return s
    }

    @Test
    fun screens() {
        // 1: start of a match
        shot("coach-0-start", coach())

        // 2: your move, best move highlighted
        val a = coach(); a.newMatch(0)
        shot("coach-1-opening-best", a)

        val b = coach(); b.newMatch(0); b.setPosition(midgame(0))
        shot("coach-2-your-move", b)

        // 3: their move, second reply picked in the list (violet)
        val c = coach(); c.newMatch(1); c.setPosition(midgame(1))
        c.onKey(Coach.KEY_DOWN)
        shot("coach-3-their-move", c)

        // 4: Other…
        val d = coach(); d.newMatch(0); d.setPosition(midgame(0)); d.onKey(Coach.KEY_B)
        shot("coach-4-other", d)

        // 5: entering a move by hand: Gold Turtle picked, targets shown, cursor on one
        val e = coach(); e.newMatch(0); e.setPosition(midgame(0)); e.onKey(Coach.KEY_B); e.onKey(Coach.KEY_A)
        e.onKey(Coach.KEY_RIGHT); e.onKey(Coach.KEY_RIGHT) // to the Gold Turtle (column 3 middle)
        e.onKey(Coach.KEY_A)
        e.onKey(Coach.KEY_RIGHT)
        shot("coach-5-by-hand", e)

        // 6: a won match, with Undo to take it back
        val f = coach(); f.newMatch(0)
        val s = Puka.empty(0)
        s.b[Puka.index(0, 1)] = Puka.LEADER; s.b[Puka.index(3, 1)] = Puka.LEADER or 8; s.b[Puka.index(2, 0)] = Puka.WHALE
        f.setPosition(s); f.onKey(Coach.KEY_A)
        shot("coach-6-you-win", f)

        // 7: panel settings: bigger and more see-through
        val g = coach(); g.newMatch(0); g.setPosition(midgame(0))
        g.settings.scale = 130; g.settings.opacity = 60
        g.onKey(Coach.KEY_B); repeat(4) { g.onKey(Coach.KEY_DOWN) }; g.onKey(Coach.KEY_A)
        shot("coach-7-settings", g)

        // 8: smaller, moved to the bottom-left
        val k = coach(); k.newMatch(0); k.setPosition(midgame(0))
        k.settings.scale = 80; k.settings.x = 0f; k.settings.y = 1f
        shot("coach-8-small-bottom", k, fix = false)
    }

    @Test
    fun fixBoard() {
        val ctx = RuntimeEnvironment.getApplication()
        Raden.apply(ctx)
        val v = CoachFixView(ctx, midgame(0)) {}
        v.measure(View.MeasureSpec.makeMeasureSpec(v.sizeW(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(v.sizeH(), View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.sizeW(), v.sizeH())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        backdrop(c)
        c.translate((w - v.sizeW()) / 2f, (h - v.sizeH()) / 2f)
        v.draw(c)
        File(out, "coach-9-fix-board.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
