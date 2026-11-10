package com.aven.downloader.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.aven.downloader.AvenApp
import com.aven.downloader.data.CookieSites

/**
 * Plain WebView where the user logs in to a site. When they tap "Done", the session cookies are
 * saved so yt-dlp can use them. Nothing is sent anywhere except to the site itself.
 */
class CookieLoginActivity : ComponentActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = CookieSites.byId(intent.getStringExtra(EXTRA_SITE))
        if (site == null) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#131A2B"))
        }
        val cancel = Button(this).apply { text = "Cancel" }
        val done = Button(this).apply { text = "Done – save login for ${site.label}" }
        bar.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val wv = WebView(this)
        web = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // A normal mobile Chrome user agent: some sites refuse to show the login page to a bare WebView.
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = WebViewClient()
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        wv.loadUrl(site.loginUrl)

        cancel.setOnClickListener { finish() }
        done.setOnClickListener {
            cm.flush()
            val headers = site.cookieUrls.associateWith { cm.getCookie(it) }
            val saved = AvenApp.instance.cookies.saveSite(site, headers)
            if (saved) {
                Toast.makeText(this, "${site.label} login saved", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(
                    this,
                    "Login not detected yet. Finish signing in, open the site's home page, then tap Done again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "com.aven.downloader.extra.COOKIE_SITE"
    }
}
