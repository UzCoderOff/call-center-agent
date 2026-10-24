package com.lawfirm.callagent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A new Ledger without a browser and without Google Play: the APK the
 * server offers (config.downloadUrl, backend /downloads/ledger.apk) is
 * downloaded inside the app and handed to Android's own installer.
 *
 * Android never lets an app replace itself silently on the first go: the
 * person allows "install apps from Ledger" once (a system switch) and taps
 * "Update" in the system's box. From Android 12 on, once Ledger has
 * installed itself this way, later updates may go through with no box at
 * all (USER_ACTION_NOT_REQUIRED) — if the phone's maker allows it; when it
 * doesn't, Android simply shows the box (STATUS_PENDING_USER_ACTION).
 *
 * Only an APK of this same app, newer than this one, is installed; Android
 * itself refuses one signed with another key.
 */
object AppUpdate {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val ACTION_STATUS = "com.lawfirm.callagent.UPDATE_STATUS"

    /** Whether the system switch "install apps from Ledger" is on. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The system page with that switch, for Ledger. */
    fun allowIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads the APK into the app's cache, telling [onProgress] the
     * percent (or -1 while the size is unknown). Throws on failure, or when
     * the file isn't a newer Ledger.
     */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "ledger.apk")
        val partial = File(dir, "ledger.apk.part")
        partial.delete()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty")
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -2
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        val info = context.packageManager.getPackageArchiveInfo(partial.path, 0)
        @Suppress("DEPRECATION")
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        if (info?.packageName != context.packageName || code <= BuildConfig.VERSION_CODE) {
            partial.delete()
            throw IOException("not a newer Ledger")
        }
        file.delete()
        if (!partial.renameTo(file)) throw IOException("rename failed")
        return file
    }

    /** Hands the APK to Android's installer; the answer comes to [StatusReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("ledger.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val status = Intent(context, StatusReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    /** Android's answer: show its box when it wants one, say so when it failed. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // Ledger restarts as the new version.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // "Cancel" in the system's box.
                else -> {
                    DiagnosticLog.append(context, "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Leftovers from an update that's done. */
    fun cleanUp(context: Context) {
        File(context.cacheDir, "update").deleteRecursively()
    }
}
