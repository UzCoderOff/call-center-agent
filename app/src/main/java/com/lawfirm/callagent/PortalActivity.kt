package com.lawfirm.callagent

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The portal itself, shown inside the app. Everything on screen is the
 * website — change the portal and every phone shows the change on the next
 * open, with no app update. The app only adds what a website can't do:
 * a signed-in session that never asks for the password again, call-sync
 * controls, and tel: links that open the phone's dialer.
 *
 * The portal talks back through `window.LedgerApp` (see Bridge below and
 * call-center-portal/src/lib/appBridge.js).
 */
class PortalActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View

    private var lastRenewAt = 0L
    private var lastConfigAt = 0L
    private var updateOffered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_portal)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.pageProgress)
        offline = findViewById(R.id.offlineView)
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            offline.visibility = View.GONE
            lastRenewAt = 0L
            webView.loadUrl(BuildConfig.PORTAL_URL)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString LedgerApp/${BuildConfig.VERSION_NAME}"
        }
        webView.addJavascriptInterface(Bridge(), "LedgerApp")
        webView.webViewClient = PortalClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // The launcher/sign-in just loaded the settings; no need to again right away.
        lastConfigAt = System.currentTimeMillis()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(BuildConfig.PORTAL_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Pick up changes made in the portal while the app sat in the
        // background (collection switched on/off, phone signed out) without
        // needing a restart.
        val now = System.currentTimeMillis()
        if (now - lastConfigAt > CONFIG_REFRESH_MS) {
            lastConfigAt = now
            lifecycleScope.launch { applyLatestConfig() }
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private suspend fun applyLatestConfig() {
        val token = SecureStore.token(this)
        if (token == null) {
            AppFlow.signOut(this, revokeOnServer = false)
            return
        }
        val config = try {
            withContext(Dispatchers.IO) { Api.config(token) }
        } catch (e: Api.HttpError) {
            if (e.status == 401) AppFlow.signOut(this, revokeOnServer = false)
            return
        } catch (e: Exception) {
            return // offline — try again next time
        }

        val wasCollecting = Session.collectCalls(this)
        Session.saveConfig(this, config)
        if (config.collectCalls) {
            SyncState.ensureInstallFloor(this)
            if (Permissions.requiredGranted(this)) {
                SyncScheduler.schedule(this)
            } else if (!wasCollecting || !AppFlow.permissionsPostponed) {
                startActivity(
                    Intent(this, PermissionsActivity::class.java).putExtra(PermissionsActivity.EXTRA_FROM_PORTAL, true)
                )
            }
        } else {
            SyncScheduler.cancel(this)
        }
        maybeOfferUpdate(config)
    }

    private fun maybeOfferUpdate(config: Api.Config) {
        val url = config.downloadUrl ?: return
        if (updateOffered || config.latestVersionCode <= BuildConfig.VERSION_CODE) return
        updateOffered = true
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_text, config.latestVersionName ?: ""))
            .setPositiveButton(R.string.update_download) { _, _ -> openExternal(Uri.parse(url)) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * The portal got a 401: its session cookie expired. Get a new one with
     * the device token and reload. If the phone was signed out remotely, go
     * to the sign-in screen instead.
     */
    private fun renewSession() {
        val now = System.currentTimeMillis()
        if (now - lastRenewAt < RENEW_COOLDOWN_MS) {
            // Just renewed and the portal still says 401 — don't loop.
            showOffline()
            return
        }
        lastRenewAt = now
        val token = SecureStore.token(this)
        if (token == null) {
            AppFlow.signOut(this, revokeOnServer = false)
            return
        }
        lifecycleScope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) { Api.renewSession(token) }
                AppFlow.applyCookies(cookies)
                webView.loadUrl(BuildConfig.PORTAL_URL)
            } catch (e: Api.HttpError) {
                if (e.status == 401) AppFlow.signOut(this@PortalActivity, revokeOnServer = false) else showOffline()
            } catch (e: Exception) {
                showOffline()
            }
        }
    }

    private fun showOffline() {
        offline.visibility = View.VISIBLE
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone can open it; ignore.
        }
    }

    private inner class PortalClient : WebViewClient() {
        private val portalHost = Uri.parse(BuildConfig.PORTAL_URL).host

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when {
                // "Call back" buttons in the portal: straight into the dialer.
                uri.scheme == "tel" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, uri))
                    } catch (e: ActivityNotFoundException) {
                        // No dialer (tablet) — nothing to do.
                    }
                    true
                }
                uri.host == portalHost -> false
                // Anything else leaves the app, so the portal bridge is only
                // ever exposed to the firm's own portal.
                else -> {
                    openExternal(uri)
                    true
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showOffline()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
        }
    }

    /**
     * `window.LedgerApp` inside the portal. Called on a background thread.
     * Public on purpose: WebView invokes these methods by reflection.
     */
    inner class Bridge {
        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun isCollectingCalls(): Boolean = Session.collectCalls(this@PortalActivity)

        @JavascriptInterface
        fun syncNow() {
            SyncScheduler.runNow(this@PortalActivity)
        }

        /** Profile -> "Phone setup": the step-by-step guide again, every step shown. */
        @JavascriptInterface
        fun openSetup() {
            runOnUiThread {
                startActivity(
                    Intent(this@PortalActivity, PermissionsActivity::class.java)
                        .putExtra(PermissionsActivity.EXTRA_FROM_PORTAL, true)
                        .putExtra(PermissionsActivity.EXTRA_REVIEW, true)
                )
            }
        }

        @JavascriptInterface
        fun shareDiagnostics() {
            runOnUiThread { DiagnosticLog.share(this@PortalActivity) }
        }

        @JavascriptInterface
        fun logout() {
            runOnUiThread { AppFlow.signOut(this@PortalActivity, revokeOnServer = true) }
        }

        @JavascriptInterface
        fun sessionExpired() {
            runOnUiThread { renewSession() }
        }
    }

    companion object {
        private const val CONFIG_REFRESH_MS = 10 * 60 * 1000L
        private const val RENEW_COOLDOWN_MS = 30 * 1000L
    }
}
