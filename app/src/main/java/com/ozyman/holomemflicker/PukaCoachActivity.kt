package com.ozyman.holomemflicker

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Puka Puka Coach: best-move helper for the Puka Puka Fight minigame.
 * The board and search engine are one offline page in assets/puka-coach.html,
 * shown in the system WebView. The board is saved in the page's own storage, so it
 * survives closing the screen. Nothing is loaded from the internet.
 */
class PukaCoachActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Raden.apply(this)
        web = WebView(this).apply {
            setBackgroundColor(Raden.MINT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            // stay on the bundled page: any other link is ignored
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                    !r.url.toString().startsWith(PAGE)
            }
        }
        setContentView(web)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(PAGE)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val PAGE = "file:///android_asset/puka-coach.html"
    }
}
