package com.ozyman.holomemflicker

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Is this allowed?" pop-up (main screen header): the question we sent to hololive Dreams support
 * and their reply, word for word, so players can judge for themselves.
 */
object AllowedInfo {

    const val TITLE = "Is this allowed?"

    const val SENT_ON = "Sent through the in-game Feedback form on October 22, 2026"

    /** The (shortened) message we sent. */
    val QUESTION = listOf(
        "Hello, I'd like to confirm something is allowed before sharing it with other players.",
        "I made a free, unofficial fan app for Android that lets a game controller (e.g. Razer Kishi) act as touch input:\n" +
            "- Each button press or stick movement makes a touch at a set spot, only at the moment I press it.\n" +
            "- No auto-play, turbo or timing help. It doesn't read, change or connect to the game's files, memory or servers.\n" +
            "- One button can touch more than one spot at once (e.g. both sides of a long note).\n" +
            "It works like the key mappers built into some gaming tablets (e.g. Lenovo Legion).",
        "Questions:\n" +
            "1. Is using a controller mapper like this allowed on mobile, including for leaderboard scores?\n" +
            "2. Is one button triggering more than one touch at the same time allowed?\n" +
            "3. Would publishing it for free for other players break the Terms of Service?",
        "I'm happy to change or not publish it based on your answer. Thank you!"
    )

    const val REPLIED_ON = "Reply from hololive Dreams Customer Support (Jenny), October 2026"

    /** Their answer, verbatim (the survey footer is left out). */
    val ANSWER = listOf(
        "This is Jenny from hololive Dreams Customer Support team.",
        "Thank you for playing hololive Dreams and for reaching out to us.",
        "We truly appreciate your passion for the game and for taking the time to check with us before sharing your project with other players.",
        "Regarding your questions, please be advised that \"hololive Dreams\" does not currently support gameplay via externally connected " +
            "controllers, such as joysticks or gamepads. Consequently, we cannot guarantee proper operation or provide technical support " +
            "if you connect and use an external controller or related mapping tools.",
        "Please refer to the below Terms of Service which outlines the rules to be followed when playing the game:\n" +
            "▼ Terms of Service\nhttps://www.hololive-dreams.com/en/terms/",
        "To ensure a safe, fair, and enjoyable experience for everyone, we kindly ask that you please adhere to the Terms of Service " +
            "and use your own judgment based on the provided guidelines.",
        "We appreciate your understanding and hope you continue to enjoy hololive Dreams.",
        "Best Regards,\nJenny\nhololive Dreams Customer Support Team"
    )

    const val NOTE = "In short: controllers aren't officially supported and they won't give technical support for them, " +
        "but they didn't call mapping tools a violation. Holomem Flicker turns your own button presses into touches - " +
        "it never plays by itself (no bots, no macros). Read the Terms of Service and decide for yourself; " +
        "see the Disclaimer for the full terms of use."

    /** The pop-up's content (also rendered by the screenshot test). */
    fun view(ctx: Context, onClose: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 36)
            background = Raden.rounded(Raden.CREAM, 40f, Raden.MINT_LINE, 4)
        }
        col.addView(TextView(ctx).apply {
            text = TITLE
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Raden.TEAL_DEEP)
        })

        fun label(t: String, sub: String) {
            col.addView(TextView(ctx).apply { text = t; Raden.heading(this, 18f); setPadding(0, 28, 0, 2) })
            col.addView(TextView(ctx).apply { text = sub; textSize = 13f; setTextColor(Raden.INK_SOFT); setPadding(0, 0, 0, 10) })
        }
        fun quote(paras: List<String>, bar: Int) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 18, 24, 18)
                background = Raden.rounded(Raden.alpha(bar, 0x22), 20f)
            }
            for (p in paras) box.addView(TextView(ctx).apply {
                text = p
                textSize = 15f
                setTextColor(Raden.INK)
                setLineSpacing(0f, 1.12f)
                setPadding(0, 6, 0, 6)
                setTextIsSelectable(true)
            })
            col.addView(box)
        }

        label("What we asked", SENT_ON)
        quote(QUESTION, Raden.TEAL)
        label("What they answered", REPLIED_ON)
        quote(ANSWER, Raden.ORANGE)
        col.addView(TextView(ctx).apply {
            text = NOTE
            textSize = 14f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(Raden.INK_SOFT)
            setPadding(0, 26, 0, 10)
        })
        col.addView(Button(ctx).apply {
            text = "Close"
            Raden.pill(this, Raden.TEAL)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.END; topMargin = 10 })

        return ScrollView(ctx).apply { addView(col) }
    }

    fun show(ctx: Context) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view(ctx) { d.dismiss() })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(1400),
            (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
        d.show()
    }

}
