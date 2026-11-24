package com.ozyman.holomemflicker

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Motion tokens and small animations (timings modelled on transitions.dev):
 * quick "standard" easing, a press that sinks a little, and cards that rise in on open.
 * Every animation is skipped when the system "Remove animations" setting is on.
 */
object Motion {
    const val FAST = 120L      // press / release
    const val BASE = 220L      // small changes (selection, chips)
    const val SLOW = 320L      // things entering the screen
    const val STAGGER = 45L    // gap between cards rising in

    /** cubic-bezier(0.2, 0, 0, 1): starts quick, settles softly. */
    val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun off(v: View): Boolean = try {
        android.provider.Settings.Global.getFloat(
            v.context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (_: Throwable) { false }

    /** Buttons sink to 97% while held and spring back on release. Clicks still work as before. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            if (!off(view)) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(FAST).setInterpolator(EASE).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(BASE).setInterpolator(EASE).start()
            }
            false
        }
    }

    /** Cards fade in and rise 24px, one after another, when a screen opens. */
    fun enter(views: List<View>) {
        views.forEachIndexed { i, v ->
            if (off(v)) return@forEachIndexed
            v.alpha = 0f
            v.translationY = 24f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * STAGGER).setDuration(SLOW).setInterpolator(EASE).start()
        }
    }
}
