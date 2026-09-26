package com.lawfirm.callagent

import android.content.Context
import android.provider.CallLog
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Two ways a sync starts (plus "sync now" and right after sign-in):
 *
 *  1. After every call. Android wakes the app when the call log changes —
 *     even with the app closed — so calls reach the portal a few minutes
 *     after they end. Costs nothing while the phone is idle.
 *  2. Hourly, as a safety net (and a heartbeat, so the portal can tell a
 *     quiet phone from one that stopped syncing).
 */
const val SYNC_INTERVAL_MINUTES = 60L

// After the call log changes, wait until it has been quiet this long before
// syncing: the call has ended and the recorder has finished its file
// (SyncWorker also skips calls that ended under 2 minutes ago).
private const val AFTER_CALL_DELAY_MINUTES = 3L
private const val AFTER_CALL_MAX_DELAY_MINUTES = 10L

object SyncScheduler {
    private const val PERIODIC = "call_sync"
    private const val NOW = "manual_sync"
    private const val AFTER_CALL = "call_log_watch"

    const val SOURCE_KEY = "source"
    const val SOURCE_CALL_LOG = "call_log"

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Hourly sync + the after-every-call trigger. Safe to call repeatedly. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE: re-scheduling (every app open) keeps the existing timing.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        watchCallLog(context, rearmAfterCurrent = false)
    }

    /**
     * A one-shot sync that waits for the next change to the call log.
     * Content triggers only exist for one-shot work, so every triggered run
     * arms the next one when it finishes (`rearmAfterCurrent`: queue behind
     * the run in progress rather than being skipped because it's running).
     */
    fun watchCallLog(context: Context, rearmAfterCurrent: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(AFTER_CALL_DELAY_MINUTES, TimeUnit.MINUTES)
            .setTriggerContentMaxDelay(AFTER_CALL_MAX_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SOURCE_KEY to SOURCE_CALL_LOG))
            .build()
        val policy = if (rearmAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(AFTER_CALL, policy, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(AFTER_CALL)
    }
}
