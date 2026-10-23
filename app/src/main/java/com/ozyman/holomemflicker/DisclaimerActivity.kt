package com.ozyman.holomemflicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The disclaimer. Opened from the "Disclaimer" tab (top right of the main screen), and shown once,
 * full screen, on first launch and whenever [VERSION] goes up: the app can't be used until it's accepted.
 */
class DisclaimerActivity : Activity() {

    companion object {
        /** Raise this whenever the text changes, so everyone is asked to accept it again. */
        const val VERSION = 1
        const val PREF = "disclaimerAccepted"
        const val EXTRA_MUST_ACCEPT = "mustAccept"

        fun accepted(ctx: Context): Boolean = Prefs.getInt(ctx, PREF, 0) >= VERSION

        const val GAME_RULES = "Game rules are your responsibility"

        /** Last line of the disclaimer. */
        const val SIGN_OFF = "Juufutei Raden is the best waifu."

        /** title to paragraphs; a paragraph starting with "• " is a bullet. */
        val SECTIONS: List<Pair<String, List<String>>> = listOf(
            "Made with AI" to listOf(
                "Holomem Flicker was built with Claude, an AI model made by Anthropic. Claude wrote the app's code, " +
                    "designed its screens and wrote this disclaimer, under the direction of the project owner, who tested it " +
                    "and decided what it should do. Anthropic is not affiliated with this project and does not endorse it."
            ),
            "Use at your own risk" to listOf(
                "Holomem Flicker is a free, unofficial hobby project provided \"as is\" and \"as available\", " +
                    "without warranties of any kind, express or implied. That includes warranties of merchantability, " +
                    "fitness for a particular purpose, accuracy, reliability or non-infringement."
            ),
            "No liability" to listOf(
                "To the fullest extent permitted by law, the developer(s) and contributors are not responsible for " +
                    "any damage or loss arising from installing, using or being unable to use this app. This includes:",
                "• damage to your device or its battery, lost data, or system problems;",
                "• game account suspensions, bans, lost progress or lost in-game items;",
                "• missed inputs, wrong inputs or poor game performance;",
                "• any direct, indirect, incidental, special or consequential damages, even if advised of the possibility of such damages."
            ),
            GAME_RULES to listOf(
                "Tools that map controller input to touch may break the terms of service of some games. It's up to you " +
                    "to check and follow the rules of any game you use it with. The developer(s) are not responsible for " +
                    "any action a game publisher takes against your account."
            ),
            "Elevated permissions" to listOf(
                "This app uses Shizuku and Android's Wireless debugging to read your controller and send touches to " +
                    "other apps. These give the app powerful access to your device. Only enable them if you understand " +
                    "and accept the risks, and turn Wireless debugging off when you're not using it."
            ),
            "Not affiliated" to listOf(
                "Holomem Flicker is not affiliated with, endorsed by or sponsored by COVER Corporation, hololive " +
                    "production, QualiArts, Razer, Lenovo or the Shizuku project. All names and trademarks belong to " +
                    "their respective owners."
            ),
            "Your data" to listOf(
                "The app stores your layouts and settings only on your device. It connects to the internet only to " +
                    "check GitHub for signed updates and download them."
            ),
            "License" to listOf(
                "Holomem Flicker is free software released under the GNU General Public License v3.0 (GPL-3.0). You can " +
                    "use, change and share it under those terms. If you share a changed version, it must also be released " +
                    "under GPL-3.0 with its source code. Full license: github.com/Ozyman515/holomem-flicker/blob/main/LICENSE"
            ),
            "Changes" to listOf(
                "This disclaimer may be updated in future versions. By installing or continuing to use the app, you " +
                    "accept these terms. If you don't agree, uninstall it."
            )
        )
    }

    private var mustAccept = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Raden.apply(this)
        mustAccept = intent.getBooleanExtra(EXTRA_MUST_ACCEPT, false)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 64)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 36)
            background = Raden.rounded(Raden.TEAL, 44f)
        }
        header.addView(TextView(this).apply {
            text = "Disclaimer"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Raden.CREAM)
        })
        header.addView(TextView(this).apply {
            text = if (mustAccept) "Please read before using Holomem Flicker" else "Holomem Flicker · v${BuildConfig.VERSION_CODE}"
            textSize = 15f
            setTextColor(Raden.PLATINUM)
        })
        col.addView(header)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 40)
            Raden.card(this)
        }
        for ((title, paras) in SECTIONS) {
            card.addView(TextView(this).apply {
                text = title
                Raden.heading(this)
                setPadding(0, 22, 0, 6)
            })
            for (p in paras) card.addView(TextView(this).apply {
                text = p
                textSize = 16f
                setTextColor(Raden.INK)
                setLineSpacing(0f, 1.15f)
                if (p.startsWith("• ")) setPadding(28, 4, 0, 4) else setPadding(0, 4, 0, 6)
            })
            // readable before accepting: what hololive Dreams support told us
            if (title == GAME_RULES) card.addView(Button(this).apply {
                text = "❓  ${AllowedInfo.TITLE}  (what hololive Dreams support told us)"
                textSize = 14f
                Raden.pill(this, Raden.ORANGE, Raden.CREAM)
                setOnClickListener { AllowedInfo.show(this@DisclaimerActivity) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10; bottomMargin = 6 })
        }
        card.addView(TextView(this).apply {
            text = SIGN_OFF
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD_ITALIC)
            setTextColor(Raden.ORANGE)
            setPadding(0, 30, 0, 0)
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 28 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(label: String, fill: Int, f: () -> Unit) = bar.addView(Button(this).apply {
            text = label
            isAllCaps = false
            Raden.pill(this, fill)
            setOnClickListener { f() }
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(12, 28, 0, 0) })
        if (mustAccept) {
            btn("Close app", Raden.CRIMSON) { finishAffinity() }
            btn("I understand and accept", Raden.ORANGE) {
                Prefs.putInt(this, PREF, VERSION)
                finish()
            }
        } else {
            btn("Back", Raden.TEAL) { finish() }
        }
        col.addView(bar)

        window.statusBarColor = Raden.TEAL_DEEP
        window.navigationBarColor = Raden.MINT
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Raden.MINT)
            addView(col)
        })
    }

    @Deprecated("plain Activity API")
    override fun onBackPressed() {
        // Not accepted yet: backing out closes the app rather than slipping past the disclaimer.
        if (mustAccept) finishAffinity() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}

/** Opens the disclaimer; [mustAccept] = the blocking first-launch version. */
fun Context.openDisclaimer(mustAccept: Boolean) =
    startActivity(Intent(this, DisclaimerActivity::class.java).putExtra(DisclaimerActivity.EXTRA_MUST_ACCEPT, mustAccept))
