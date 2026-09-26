package com.lawfirm.callagent

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling on-device log of sync attempts and their outcomes.
 *
 * The reason this exists: when sync gets stuck on one phone, nobody has a
 * USB cable or Android Studio handy to pull logcat — that's exactly the
 * situation we're in. Instead, every sync attempt appends one line here,
 * and "Share diagnostics" (Profile page inside the app) sends the whole
 * file over Telegram, email, or whatever they already have open.
 *
 * Deliberately a plain rolling text file rather than Log/Logcat, since
 * logcat isn't reachable without exactly the physical access this is meant
 * to avoid needing.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostics.log"

    // Capped so the file can't grow unbounded on a phone that's been
    // retrying for weeks — old lines roll off, newest ones are what matter
    // for debugging a current problem.
    private const val MAX_LINES = 300

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(context: Context, line: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val stamped = "${timestampFormat.format(Date())}  $line"
            val existing = if (file.exists()) file.readLines() else emptyList()
            val updated = (existing + stamped).takeLast(MAX_LINES)
            file.writeText(updated.joinToString("\n") + "\n")
        } catch (e: Exception) {
            // Logging must never crash the app, or the sync attempt it's
            // trying to record.
        }
    }

    /**
     * Launches the system share sheet with the log file attached, via
     * FileProvider (see res/xml/file_paths.xml and the <provider> entry in
     * AndroidManifest.xml) — no cable or Android Studio needed.
     */
    fun share(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            Toast.makeText(context, R.string.diagnostics_empty, Toast.LENGTH_LONG).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ledger diagnostics (${BuildConfig.VERSION_NAME})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share)))
    }
}
