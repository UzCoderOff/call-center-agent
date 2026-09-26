package com.lawfirm.callagent

import android.content.Context
import org.json.JSONObject

/**
 * Where syncing stands on this phone, kept across runs:
 *
 *  - installFloor:  when collection started on this phone. Nothing older is
 *                   ever read or uploaded, whatever other timestamps say.
 *  - lastSyncStart: when the last fully successful sync began. The next run
 *                   re-reads the call log from a day before that (a call's
 *                   log entry is dated by when it STARTED but only appears
 *                   when it ENDS, so a call in progress during a sync would
 *                   otherwise be skipped for good).
 *  - synced:        call-log entries already uploaded, so that overlap
 *                   doesn't upload them (or their recordings) twice.
 *  - lastMaxId:     highest call-log row id seen — used to notice rows that
 *                   were deleted before they could be synced.
 *
 * Cleared on sign-out, so a phone handed to someone else starts fresh.
 */
class SyncState(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val installFloor: Long get() = prefs.getLong(INSTALL_FLOOR, -1L)

    var lastSyncStart: Long
        get() = prefs.getLong(LAST_SYNC_START, 0L)
        set(value) = prefs.edit().putLong(LAST_SYNC_START, value).apply()

    var lastMaxId: Long
        get() = prefs.getLong(LAST_MAX_ID, -1L)
        set(value) = prefs.edit().putLong(LAST_MAX_ID, value).apply()

    /**
     * Folders where recordings were found before. Scanning just these is
     * far cheaper than walking all storage — what keeps a sync after every
     * call light on the battery. A full scan still runs daily, and whenever
     * a recording isn't where it used to be.
     */
    fun recordingDirs(): List<String> {
        val json = prefs.getString(RECORDING_DIRS, null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecordingDirs(dirs: Collection<String>) {
        val merged = (dirs + recordingDirs()).distinct().take(MAX_RECORDING_DIRS)
        prefs.edit().putString(RECORDING_DIRS, org.json.JSONArray(merged).toString()).apply()
    }

    var lastFullScan: Long
        get() = prefs.getLong(LAST_FULL_SCAN, 0L)
        set(value) = prefs.edit().putLong(LAST_FULL_SCAN, value).apply()

    /** "callLogId:dateMs" -> dateMs, for entries already uploaded. */
    fun syncedKeys(): Set<String> {
        val json = prefs.getString(SYNCED, null) ?: return emptySet()
        return try {
            JSONObject(json).keys().asSequence().toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun markSynced(calls: List<CallEntry>, now: Long) {
        val json = try {
            JSONObject(prefs.getString(SYNCED, null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        for (call in calls) json.put(call.key, call.dateMs)
        // Keep only what the next run's look-back window can still see.
        val cutoff = now - OVERLAP_MS - RETENTION_SLACK_MS
        val stale = json.keys().asSequence().filter { json.optLong(it, 0L) < cutoff }.toList()
        for (key in stale) json.remove(key)
        prefs.edit().putString(SYNCED, json.toString()).apply()
    }

    companion object {
        private const val NAME = "ledger_sync"
        private const val INSTALL_FLOOR = "install_floor"
        private const val LAST_SYNC_START = "last_sync_start"
        private const val LAST_MAX_ID = "last_max_id"
        private const val SYNCED = "synced"
        private const val RECORDING_DIRS = "recording_dirs"
        private const val LAST_FULL_SCAN = "last_full_scan"
        private const val MAX_RECORDING_DIRS = 8

        /** How far before the last successful sync each run looks again. */
        const val OVERLAP_MS = 24L * 60 * 60 * 1000
        private const val RETENTION_SLACK_MS = 24L * 60 * 60 * 1000

        /** Start the "nothing before this" clock the first time collection is on. */
        fun ensureInstallFloor(context: Context) {
            val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            if (prefs.getLong(INSTALL_FLOOR, -1L) < 0) {
                prefs.edit().putLong(INSTALL_FLOOR, System.currentTimeMillis()).apply()
            }
        }

        fun clear(context: Context) {
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
