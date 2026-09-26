package com.lawfirm.callagent

import android.content.Context
import android.os.Environment
import android.provider.CallLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
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

private val AUDIO_EXTENSIONS = setOf("m4a", "amr", "awb", "wav", "mp3", "3gp", "aac", "opus", "ogg")

// Folders that never hold call recordings — skipped to keep the scan fast.
// (Android/data of other apps isn't readable on Android 11+ anyway.)
private val EXCLUDED_DIR_NAMES = setOf(
    "Android", "WhatsApp", "Telegram", "DCIM", "Camera", "Pictures", "Movies", "Download", "obb"
)

// A call only syncs once it ended at least this long ago, so the recorder
// app has finished writing its file. Anything newer is picked up next run.
private val SETTLE_MS = TimeUnit.MINUTES.toMillis(2)

// A recording belongs to a call if the file was last written between
// shortly before the call started and a few minutes after it ENDED (the
// recorder finalises the file when the call ends; some move/convert it
// afterwards). Matching against the call's end, not its start, is what makes
// long calls work — the old 5-minutes-from-start window silently dropped the
// recording of every call longer than ~5 minutes.
private val MATCH_EARLY_MS = TimeUnit.SECONDS.toMillis(30)
private val MATCH_LATE_MS = TimeUnit.MINUTES.toMillis(5)

// WorkManager retries Result.retry() forever by default; this caps it so a
// persistent problem ends with a clear failure instead of silent looping.
private const val MAX_RETRY_ATTEMPTS = 8

// Full storage scans (expensive) at most daily; known folders otherwise. When
// a recording can't be found in the known folders, a full scan is retried at
// most this often.
private val FULL_SCAN_EVERY_MS = TimeUnit.HOURS.toMillis(24)
private val FULL_SCAN_RETRY_MS = TimeUnit.HOURS.toMillis(1)

// Keeps one request (and its JSON) reasonably sized after a long offline gap;
// the rest follows on the next run.
private const val MAX_CALLS_PER_BATCH = 400

data class CallEntry(
    val callLogId: Long,
    val number: String,
    val type: Int,
    val dateMs: Long,
    val durationSec: Long,
) {
    val endMs: Long get() = dateMs + durationSec * 1000
    val key: String get() = "$callLogId:$dateMs"
}

/**
 * Collects the phone's calls (and matching recordings) and uploads them.
 * Runs a few minutes after every call and hourly (see SyncScheduler), only
 * for people whose account has "collect calls" on, and authenticates with
 * the device token from sign-in.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun log(line: String) = DiagnosticLog.append(applicationContext, line)

    override suspend fun doWork(): Result {
        val result = syncOnce()
        // A run that will be retried re-arms when it finally finishes.
        // (All Result.retry() instances are equal.)
        if (result == Result.retry()) return result

        // Keep the after-every-call trigger armed — unless syncing has
        // stopped (signed out, collection switched off).
        val ctx = applicationContext
        if (SecureStore.hasToken(ctx) && Session.collectCalls(ctx)) {
            val triggered = inputData.getString(SyncScheduler.SOURCE_KEY) == SyncScheduler.SOURCE_CALL_LOG
            SyncScheduler.watchCallLog(ctx, rearmAfterCurrent = triggered)
            // A triggered run's re-arm is queued behind it, and WorkManager
            // fails queued work along with a failed run — so a triggered run
            // always ends "succeeded". The failure is in the diagnostic log.
            if (triggered) return Result.success()
        }
        return result
    }

    private suspend fun syncOnce(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        val token = SecureStore.token(ctx)
        if (token == null) {
            log("sync: not signed in — stopping")
            SyncScheduler.cancel(ctx)
            return@withContext Result.failure(workDataOf("error" to "not_signed_in"))
        }
        if (!Session.collectCalls(ctx)) {
            log("sync: call collection is off for this account — stopping")
            SyncScheduler.cancel(ctx)
            return@withContext Result.success()
        }
        if (!Permissions.callLog(ctx)) {
            log("sync: no call-log permission")
            return@withContext Result.failure(workDataOf("error" to "no_call_log_permission"))
        }

        log("sync: starting (attempt ${runAttemptCount + 1})")
        SyncState.ensureInstallFloor(ctx)
        val state = SyncState(ctx)
        val now = System.currentTimeMillis()
        val floor = state.installFloor
        val since = if (state.lastSyncStart > 0) maxOf(state.lastSyncStart - SyncState.OVERLAP_MS, floor) else floor

        val synced = state.syncedKeys()
        val settledBefore = now - SETTLE_MS
        val newCalls = readCallLog(since).filter { it.dateMs >= floor && it.endMs <= settledBefore && it.key !in synced }
        val calls = newCalls.take(MAX_CALLS_PER_BATCH)
        val integrity = checkIntegrity(state)

        val connected = calls.filter { !isMissedLike(it.type) && it.durationSec > 0 }
        val matches = if (connected.isNotEmpty() && Permissions.files(ctx)) {
            findMatches(state, connected, notBefore = maxOf(connected.minOf { it.dateMs } - MATCH_EARLY_MS, floor), now)
        } else {
            emptyMap<Long, File>()
        }

        // Sent even when there's nothing new: it doubles as a heartbeat, so the
        // portal can tell a quiet phone from one that stopped syncing, and it
        // still reports deleted call-log entries.
        val payload = buildPayload(calls, matches, integrity)

        when (val result = upload(token, payload, calls, matches)) {
            is UploadResult.Success -> {
                state.markSynced(calls, now)
                // Only move the window forward once everything in it is uploaded.
                if (newCalls.size <= MAX_CALLS_PER_BATCH) state.lastSyncStart = now
                integrity.maxId?.let { state.lastMaxId = it }
                log("sync: ok — ${calls.size} call(s), ${matches.size} recording(s)" +
                    if (integrity.missing > 0) ", ${integrity.missing} deleted entr(y/ies) reported" else "")
                Result.success(workDataOf("uploaded" to matches.size, "calls" to calls.size))
            }

            is UploadResult.Rejected -> {
                log("sync: rejected — HTTP ${result.code} ${result.error ?: ""}")
                when {
                    // Phone signed out from the portal, or account deactivated.
                    result.code == 401 -> {
                        SecureStore.clear(ctx)
                        SyncScheduler.cancel(ctx)
                        Result.failure(workDataOf("error" to "signed_out"))
                    }
                    // "Collect calls" was switched off in the portal.
                    result.error == "collection_disabled" -> {
                        Session.setCollectCalls(ctx, false)
                        SyncScheduler.cancel(ctx)
                        Result.success()
                    }
                    else -> Result.failure(workDataOf("error" to "rejected", "code" to result.code))
                }
            }

            is UploadResult.NetworkError -> {
                log("sync: transient failure (${result.reason}) — ${result.detail}")
                if (runAttemptCount + 1 >= MAX_RETRY_ATTEMPTS) {
                    log("sync: giving up after ${runAttemptCount + 1} attempts")
                    Result.failure(workDataOf("error" to "max_retries_exceeded", "reason" to result.reason))
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
                        number = cursor.getString(numberIdx) ?: "",
                        type = cursor.getInt(typeIdx),
                        dateMs = cursor.getLong(dateIdx),
                        durationSec = cursor.getLong(durIdx)
                    )
                )
            }
        }
        return entries
    }

    private data class Integrity(val missing: Int, val maxId: Long?)

    /**
     * Heuristic tamper check. Call-log row ids only go up, one per call. If
     * the ids above the last one we saw have holes, rows were deleted before
     * they could sync (someone cleared call history). The old check compared
     * a batch size against the whole log's size and effectively never fired.
     * Pruning of the oldest rows (the log keeps ~500) never affects new ids.
     */
    private fun checkIntegrity(state: SyncState): Integrity {
        val lastMax = state.lastMaxId
        var count = 0
        var maxId: Long? = null
        val selection = if (lastMax >= 0) "${CallLog.Calls._ID} > ?" else null
        val args = if (lastMax >= 0) arrayOf(lastMax.toString()) else null
        applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), selection, args, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            while (cursor.moveToNext()) {
                count++
                val id = cursor.getLong(idIdx)
                if (maxId == null || id > maxId!!) maxId = id
            }
        }
        val top = maxId
        if (lastMax < 0 || top == null) return Integrity(0, top ?: lastMax.takeIf { it >= 0 })
        val missing = ((top - lastMax) - count).coerceAtLeast(0L).toInt()
        return Integrity(missing, top)
    }

    /**
     * Finds recordings for these calls. Looks in the folders recordings were
     * found in before (cheap — what keeps a sync after every call light on
     * the battery); walks all of storage at most once a day, or when a
     * recording isn't where it used to be (at most hourly).
     */
    private fun findMatches(state: SyncState, calls: List<CallEntry>, notBefore: Long, now: Long): Map<Long, File> {
        val known = state.recordingDirs().map { File(it) }.filter { it.isDirectory }
        val quick = known.isNotEmpty() && now - state.lastFullScan < FULL_SCAN_EVERY_MS
        var matches = matchRecordings(calls, if (quick) scanFolders(known, notBefore) else scanAllStorage(notBefore))
        var fullScanDone = !quick
        if (quick && matches.size < calls.size && now - state.lastFullScan > FULL_SCAN_RETRY_MS) {
            matches = matchRecordings(calls, scanAllStorage(notBefore))
            fullScanDone = true
        }
        if (fullScanDone) state.lastFullScan = now
        if (matches.isNotEmpty()) state.addRecordingDirs(matches.values.mapNotNull { it.parentFile?.absolutePath })
        return matches
    }

    // Walks shared storage for recently written audio files rather than
    // assuming one folder — works whether the native dialer or an app like
    // Cube ACR made the recording.
    private fun scanAllStorage(notBefore: Long): List<File> =
        walkForAudio(Environment.getExternalStorageDirectory(), notBefore, maxDepth = 6)

    // Known recording folders, plus a couple of levels below them (some
    // recorders file calls into per-month or per-contact subfolders).
    private fun scanFolders(dirs: List<File>, notBefore: Long): List<File> =
        dirs.flatMap { walkForAudio(it, notBefore, maxDepth = 2) }.distinctBy { it.absolutePath }

    private fun walkForAudio(root: File, notBefore: Long, maxDepth: Int): List<File> {
        val found = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (f.name !in EXCLUDED_DIR_NAMES && !f.name.startsWith(".")) walk(f, depth + 1)
                } else if (f.extension.lowercase() in AUDIO_EXTENSIONS && f.lastModified() >= notBefore) {
                    found.add(f)
                }
            }
        }
        walk(root, 0)
        return found
    }

    /**
     * Pairs calls with recording files: every (call, file) pair whose file
     * time falls in the call's window, closest-to-the-call's-end first, each
     * call and each file used at most once. Closest-first means back-to-back
     * calls each get their own file instead of the first call grabbing the
     * second call's recording.
     */
    private fun matchRecordings(calls: List<CallEntry>, files: List<File>): Map<Long, File> {
        data class Candidate(val call: CallEntry, val file: File, val distance: Long)

        val candidates = mutableListOf<Candidate>()
        for (call in calls) {
            for (file in files) {
                val t = file.lastModified()
                if (t >= call.dateMs - MATCH_EARLY_MS && t <= call.endMs + MATCH_LATE_MS) {
                    candidates.add(Candidate(call, file, abs(t - call.endMs)))
                }
            }
        }
        candidates.sortBy { it.distance }

        val result = LinkedHashMap<Long, File>()
        val usedFiles = HashSet<String>()
        for (c in candidates) {
            if (c.call.callLogId in result || c.file.absolutePath in usedFiles) continue
            result[c.call.callLogId] = c.file
            usedFiles.add(c.file.absolutePath)
        }
        return result
    }

    private fun callTypeLabel(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "unknown"
    }

    private fun isMissedLike(type: Int) =
        type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE

    // Unique per call, so two recorder files with the same name in
    // different folders can't be confused on the server.
    private fun partName(call: CallEntry, file: File) = "${call.callLogId}_${file.name}"

    private fun buildPayload(calls: List<CallEntry>, matches: Map<Long, File>, integrity: Integrity): JSONObject {
        val callsJson = JSONArray()
        for (call in calls) {
            val file = matches[call.callLogId]
            callsJson.put(
                JSONObject().apply {
                    put("callLogId", call.callLogId)
                    put("phoneNumber", call.number)
                    put("callType", callTypeLabel(call.type))
                    put("missed", isMissedLike(call.type))
                    put("callTimestampMs", call.dateMs)
                    put("durationSeconds", call.durationSec)
                    put("recordingFilename", if (file != null) partName(call, file) else JSONObject.NULL)
                }
            )
        }
        return JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("syncedAtMs", System.currentTimeMillis())
            put("callCount", calls.size)
            put("missedCount", calls.count { isMissedLike(it.type) })
            put("logIntegrity", if (integrity.missing > 0) "entries_deleted" else JSONObject.NULL)
            put("missingEntries", integrity.missing)
            put("calls", callsJson)
        }
    }

    /**
     * One multipart request: the JSON `payload` part FIRST (the server checks
     * the device before accepting any file), then one `recording` part per
     * matched file.
     */
    private fun upload(token: String, payload: JSONObject, calls: List<CallEntry>, matches: Map<Long, File>): UploadResult {
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "payload", null,
                    payload.toString().toRequestBody("application/json".toMediaType())
                )
            for (call in calls) {
                val file = matches[call.callLogId] ?: continue
                body.addFormDataPart("recording", partName(call, file), file.asRequestBody("application/octet-stream".toMediaType()))
            }

            val request = Api.authed(token, "/api/calls/sync").post(body.build()).build()
            client.newCall(request).execute().use { response ->
                val text = try {
                    response.body?.string()
                } catch (e: Exception) {
                    null
                }
                val error = try {
                    JSONObject(text ?: "").optString("error").takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
                when {
                    response.isSuccessful -> UploadResult.Success
                    // The server understood and refused (signed out, collection
                    // off, bad data, too large). Retrying won't change that.
                    response.code in setOf(400, 401, 403, 404, 413, 422) -> UploadResult.Rejected(response.code, error)
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
}

private sealed class UploadResult {
    object Success : UploadResult()
    data class Rejected(val code: Int, val error: String?) : UploadResult()
    data class NetworkError(val reason: String, val detail: String) : UploadResult()
}
