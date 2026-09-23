package com.lawfirm.callagent

import android.content.Context
import android.os.Environment
import android.provider.CallLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.abs

private val AUDIO_EXTENSIONS = setOf("m4a", "amr", "wav", "mp3", "3gp", "aac", "opus")

// A recording file must land within this many minutes of an actual call log
// entry's timestamp to be considered "that call's recording."
private val RECORDING_MATCH_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)

// Folders that are never call recordings — skipped to keep the scan fast.
private val EXCLUDED_DIR_NAMES = setOf(
    "WhatsApp", "Telegram", "DCIM", "Camera", "Pictures", "Download", "obb", ".thumbnails"
)

// WorkManager retries Result.retry() forever by default — there is no
// built-in cap. Without this, any persistent problem (server down, wrong
// URL, DNS failure, whatever) freezes the UI on "Syncing…" indefinitely,
// since the UI only updates on SUCCEEDED/FAILED. runAttemptCount is
// WorkManager's own per-work-request counter (survives process death,
// resets on the next scheduled/manual run) — no need to track it ourselves.
private const val MAX_RETRY_ATTEMPTS = 8

private data class CallEntry(
    val callLogId: Long,
    val number: String,
    val type: Int,
    val dateMs: Long,
    val durationSec: Long
)

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val employeeId = prefs.getString(Prefs.EMPLOYEE_ID, null)
        val serverUrl = prefs.getString(Prefs.SERVER_URL, null)

        if (employeeId.isNullOrBlank() || serverUrl.isNullOrBlank()) {
            DiagnosticLog.append(applicationContext, "sync: not configured (missing employeeId/serverUrl)")
            return@withContext Result.failure(workDataOf("error" to "not configured"))
        }

        DiagnosticLog.append(applicationContext, "sync: starting (attempt ${runAttemptCount + 1})")

        val now = System.currentTimeMillis()

        // First ever run: record the install-time floor so old files/calls can
        // never be uploaded even on a future run, regardless of their
        // timestamps. This is a one-time, permanent floor for this device.
        var installFloor = prefs.getLong(Prefs.INSTALL_FLOOR, -1L)
        if (installFloor < 0) {
            installFloor = now
            prefs.edit().putLong(Prefs.INSTALL_FLOOR, installFloor).apply()
        }

        // First ever run looks back 48h for call log entries, so nothing from
        // just before setup is missed. Recording files use a *tighter* window
        // (see findRecordings) — we only want files created by an actual
        // recent call, not old audio files that happen to have a recent
        // filesystem-modified timestamp for unrelated reasons.
        val sinceRaw = prefs.getLong(Prefs.LAST_SYNC, now - TimeUnit.HOURS.toMillis(48))
        // Never look further back than install time, no matter what LAST_SYNC says.
        val since = maxOf(sinceRaw, installFloor - TimeUnit.HOURS.toMillis(48))

        val calls = readCallLog(since).filter { it.dateMs >= installFloor }
        // Recordings are only ever considered if they're within RECORDING_MATCH_WINDOW_MS
        // of an actual logged call (enforced below at match time), so widening this
        // scan window slightly beyond `since` costs nothing — a stray old file still
        // can't match unless it happens to sit right next to a real call timestamp.
        // The installFloor filter here is the hard guarantee: nothing older than
        // "when this app was installed on this device" is ever considered, period.
        val recordings = findRecordings(since).filter { it.lastModified() >= installFloor }
        val integrityFlag = checkLogIntegrity(prefs, calls)

        if (calls.isEmpty()) {
            prefs.edit().putLong(Prefs.LAST_SYNC, now).apply()
            DiagnosticLog.append(applicationContext, "sync: success, nothing new (0 calls)")
            return@withContext Result.success(workDataOf("uploaded" to 0, "calls" to 0))
        }

        // Match each call log entry to a recording file by closest timestamp,
        // each file used at most once.
        val usedFiles = mutableSetOf<File>()
        val callsWithFiles = calls.map { call ->
            val match = recordings
                .filter { it !in usedFiles && abs(it.lastModified() - call.dateMs) < RECORDING_MATCH_WINDOW_MS }
                .minByOrNull { abs(it.lastModified() - call.dateMs) }
            if (match != null) usedFiles.add(match)
            call to match
        }

        val payload = buildPayload(employeeId, callsWithFiles, integrityFlag)
        val uploadResult = uploadBatch(serverUrl, payload, callsWithFiles.mapNotNull { it.second })

        when (uploadResult) {
            is UploadResult.Success -> {
                prefs.edit()
                    .putLong(Prefs.LAST_SYNC, now)
                    .putInt(Prefs.LAST_SEEN_CALL_COUNT, calls.size)
                    .putLong(Prefs.LAST_SEEN_CALL_TIMESTAMP, calls.maxOf { it.dateMs })
                    .apply()
                DiagnosticLog.append(
                    applicationContext,
                    "sync: success — ${usedFiles.size} recording(s), ${calls.size} call(s)"
                )
                Result.success(workDataOf("uploaded" to usedFiles.size, "calls" to calls.size))
            }
            is UploadResult.Rejected -> {
                // Server actively rejected the request (bad employeeId, inactive
                // employee, payload/file too large, etc). Retrying won't help
                // until config changes on the server or in the app, so fail
                // fast with a real message instead of looping silently forever.
                // LAST_SYNC is left untouched so a fixed config picks this
                // batch back up on the next run.
                DiagnosticLog.append(
                    applicationContext,
                    "sync: rejected by server — HTTP ${uploadResult.code}: ${uploadResult.message ?: "(no message)"}"
                )
                Result.failure(
                    workDataOf(
                        "error" to "rejected",
                        "code" to uploadResult.code,
                        "message" to uploadResult.message
                    )
                )
            }
            is UploadResult.NetworkError -> {
                DiagnosticLog.append(
                    applicationContext,
                    "sync: transient failure (${uploadResult.reason}) — ${uploadResult.detail}"
                )
                // Transient (no connection, timeout, server down, DNS, TLS,
                // etc) — worth retrying, but not forever. After
                // MAX_RETRY_ATTEMPTS, give up with a clear reason instead of
                // leaving the UI stuck on "Syncing…" indefinitely; the next
                // scheduled periodic run (or a manual Sync Now) starts the
                // attempt count over.
                if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                    DiagnosticLog.append(
                        applicationContext,
                        "sync: giving up after ${runAttemptCount + 1} attempts"
                    )
                    Result.failure(
                        workDataOf(
                            "error" to "max_retries_exceeded",
                            "reason" to uploadResult.reason,
                            "message" to uploadResult.detail
                        )
                    )
                } else {
                    Result.retry()
                }
            }
        }
    }

    private fun readCallLog(since: Long): List<CallEntry> {
        val entries = mutableListOf<CallEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
        )
        applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection,
            "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                entries.add(
                    CallEntry(
                        callLogId = cursor.getLong(idIdx),
                        number = cursor.getString(numberIdx) ?: "unknown",
                        type = cursor.getInt(typeIdx),
                        dateMs = cursor.getLong(dateIdx),
                        durationSec = cursor.getLong(durIdx)
                    )
                )
            }
        }
        return entries
    }

    // Walks shared storage looking for recently-modified audio files, rather than
    // assuming one exact folder path — works the same whether the recording came
    // from the phone's native dialer or a fallback app like Cube ACR.
    private fun findRecordings(since: Long): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val found = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (f.name !in EXCLUDED_DIR_NAMES) walk(f, depth + 1)
                } else if (f.lastModified() > since && f.extension.lowercase() in AUDIO_EXTENSIONS) {
                    found.add(f)
                }
            }
        }
        walk(root, 0)
        return found
    }

    /**
     * Best-effort check for entries missing from the call log since the last sync —
     * e.g. someone deleted call history on the device between runs. This can't be
     * proven from on-device data alone (we only see what's currently in the log),
     * so it's a heuristic flag for the server to review, not a guarantee.
     *
     * Signal: the total call log row count (all-time) should never decrease between
     * syncs unless entries were removed. A drop is a strong signal of deletion.
     */
    private fun checkLogIntegrity(
        prefs: android.content.SharedPreferences,
        newEntries: List<CallEntry>
    ): String? {
        val previousCount = prefs.getInt(Prefs.LAST_SEEN_CALL_COUNT, -1)
        if (previousCount < 0) return null // no baseline yet, first run

        val totalNow = applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null
        )?.use { it.count } ?: return null

        val lastSeenTs = prefs.getLong(Prefs.LAST_SEEN_CALL_TIMESTAMP, -1)
        val expectedMinimum = previousCount + newEntries.size

        return when {
            totalNow < previousCount -> "call_log_shrank"
            lastSeenTs > 0 && newEntries.isNotEmpty() && newEntries.none { it.dateMs > lastSeenTs } ->
                "no_new_entries_since_last_sync"
            totalNow < expectedMinimum -> "possible_gap"
            else -> null
        }
    }

    private fun callTypeLabel(type: Int?) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "unknown"
    }

    private fun isMissedLike(type: Int) =
        type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE

    /**
     * Builds the JSON payload sent to the server. One object per call log entry,
     * each carrying enough to identify, dedupe, and analyze it — with
     * `recordingFilename` set for entries that have a matched audio file, so the
     * server can associate that entry with the right multipart `recording` part
     * by filename without guessing.
     */
    private fun buildPayload(
        employeeId: String,
        calls: List<Pair<CallEntry, File?>>,
        integrityFlag: String?
    ): JSONObject {
        val callsJson = JSONArray()
        calls.forEach { (call, file) ->
            callsJson.put(
                JSONObject().apply {
                    put("callLogId", call.callLogId)
                    put("phoneNumber", call.number)
                    put("callType", callTypeLabel(call.type))
                    put("missed", isMissedLike(call.type))
                    put("callTimestampMs", call.dateMs)
                    put("durationSeconds", call.durationSec)
                    put("recordingFilename", file?.name ?: JSONObject.NULL)
                }
            )
        }

        return JSONObject().apply {
            put("employeeId", employeeId)
            put("syncedAtMs", System.currentTimeMillis())
            put("callCount", calls.size)
            put("missedCount", calls.count { isMissedLike(it.first.type) })
            put("calls", callsJson)
            // null when nothing looked off; a string reason when it did.
            put("logIntegrity", integrityFlag ?: JSONObject.NULL)
        }
    }

    /**
     * Uploads the JSON call-log payload plus any matched recordings as one
     * multipart request: a `payload` part (application/json) with the structured
     * data, and one part per recording file so the server can associate bytes
     * with the matching entry in `payload.calls` by filename.
     */
    private fun uploadBatch(
        serverUrl: String,
        payload: JSONObject,
        recordings: List<File>
    ): UploadResult {
        return try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addPart(
                    MultipartBody.Part.createFormData(
                        "payload", null,
                        payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    )
                )

            recordings.forEach { file ->
                bodyBuilder.addFormDataPart(
                    "recording", file.name, file.asRequestBody("audio/*".toMediaTypeOrNull())
                )
            }

            val request = Request.Builder()
                .url("$serverUrl/api/calls/sync")
                // Prevents ngrok's free-tier browser-warning interstitial from
                // being returned instead of a real response when the server
                // URL points at an ngrok tunnel. Harmless against any other host.
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Success
                    // 401/403: server understood the request and actively rejected
                    // it (bad/unknown/inactive employeeId, bad auth). 400/404/413/422
                    // are lumped in here too - all "config or data is wrong," not
                    // "network is flaky." Endless retry never fixes these.
                    response.code == 401 || response.code == 403 || response.code == 400 ||
                        response.code == 404 || response.code == 413 || response.code == 422 -> {
                        val body = try { response.body?.string() } catch (e: Exception) { null }
                        val message = extractServerMessage(body) ?: body?.take(200)
                        UploadResult.Rejected(response.code, message)
                    }
                    // 5xx and anything else unexpected: treat as transient, keep retrying.
                    else -> UploadResult.NetworkError("http_${response.code}", "server returned HTTP ${response.code}")
                }
            }
        } catch (e: UnknownHostException) {
            UploadResult.NetworkError("dns_error", e.message ?: "could not resolve server host")
        } catch (e: SSLException) {
            UploadResult.NetworkError("tls_error", e.message ?: "TLS/SSL handshake failed")
        } catch (e: SocketTimeoutException) {
            UploadResult.NetworkError("timeout", e.message ?: "request timed out")
        } catch (e: ConnectException) {
            UploadResult.NetworkError("connection_refused", e.message ?: "connection refused")
        } catch (e: IOException) {
            UploadResult.NetworkError("io_error", "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            UploadResult.NetworkError("unknown_exception", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // The server responds with {"error": "...", "message": "..."} — try to
    // surface the human-readable "message" rather than the raw body.
    private fun extractServerMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optString("message", null)
        } catch (e: Exception) {
            null
        }
    }
}

private sealed class UploadResult {
    object Success : UploadResult()
    data class Rejected(val code: Int, val message: String?) : UploadResult()
    data class NetworkError(val reason: String, val detail: String) : UploadResult()
}
