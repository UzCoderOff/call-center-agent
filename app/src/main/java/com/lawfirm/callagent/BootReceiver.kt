package com.lawfirm.callagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Re-arms the periodic sync after a device reboot.
 *
 * WorkManager's own schedule is normally durable across reboots by itself,
 * but relies on its internal receiver getting a chance to run — some OEM
 * battery managers (Honor/MagicOS included) block BOOT_COMPLETED for apps
 * not marked as auto-launch, which can leave the schedule never re-armed.
 * This is a explicit fallback: if we're configured (employee ID + server
 * URL already saved), re-enqueue the same unique periodic work used in
 * MainActivity so a reboot can't silently stop syncing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val employeeId = prefs.getString(Prefs.EMPLOYEE_ID, null)
        val serverUrl = prefs.getString(Prefs.SERVER_URL, null)
        if (employeeId.isNullOrBlank() || serverUrl.isNullOrBlank()) return // never configured yet

        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "call_sync", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
