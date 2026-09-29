package com.lawfirm.callagent

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
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
 * controls, tel: links that open the phone's dialer, downloads (Excel
 * exports, training materials) that open in the phone's own apps, and
 * picking files to upload.
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

    // A file picker the portal asked for (<input type="file">), waiting for
    // the person's choice.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val data = result.data
        val uris: Array<Uri>? =
            if (result.resultCode != RESULT_OK || data == null) {
                null
            } else {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
        // null on cancel too — the page's file input must always get an answer.
        callback.onReceiveValue(uris)
    }

    // Downloads started from the portal; each opens by itself when done.
    private val pendingDownloads = mutableSetOf<Long>()
    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (pendingDownloads.remove(id)) openDownloaded(id)
        }
    }

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

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                // Any file: the server checks the type again, and Android's
                // picker understands MIME types, not the ".pdf,.docx" list the
                // page gives.
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, userAgent, contentDisposition, mimetype)
        }
        ContextCompat.registerReceiver(
            this,
            downloadDone,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Settings (and "is there a newer app?") are checked on the first
        // resume right after opening, then again after 10 minutes away.
        lastConfigAt = 0L
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
        unregisterReceiver(downloadDone)
        fileCallback?.onReceiveValue(null)
        webView.destroy()
        super.onDestroy()
    }

    /**
     * A file the portal links to (an Excel export, a training material's
     * PDF): the phone downloads it with this session's cookie, then opens it
     * in whatever app handles it. Only the portal's own files — anything else
     * opens outside the app.
     */
    private fun startDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != Uri.parse(BuildConfig.PORTAL_URL).host) {
            openExternal(uri)
            return
        }
        val name = downloadName(url, contentDisposition, mimetype)
        val request = DownloadManager.Request(uri)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (!mimetype.isNullOrBlank()) request.setMimeType(mimetype)
        // Android 10+: the phone's Downloads folder, no permission needed.
        // Older phones: the app's own folder (no permission needed either).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            pendingDownloads.add(manager.enqueue(request))
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    // The file's own name (the server sends it UTF-8 encoded), made safe for
    // the phone's file system.
    private fun downloadName(url: String, contentDisposition: String?, mimetype: String?): String {
        val encoded = contentDisposition?.let { FILENAME_UTF8.find(it)?.groupValues?.get(1) }
        val decoded = encoded?.let { Uri.decode(it) }
        val name = decoded ?: URLUtil.guessFileName(url, contentDisposition, mimetype)
        return name.replace(UNSAFE_FILENAME_CHARS, "_").trim().ifEmpty { "ledger-file" }
    }

    private fun openDownloaded(id: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id)
        if (uri == null) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, manager.getMimeTypeForDownloadedFile(id) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_LONG).show()
        }
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

        /** This version downloads and opens files (PDFs, Excel exports). */
        @JavascriptInterface
        fun canDownload(): Boolean = true

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
        private val FILENAME_UTF8 = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
        private val UNSAFE_FILENAME_CHARS = Regex("""[\\/:*?"<>|]""")
    }
}
