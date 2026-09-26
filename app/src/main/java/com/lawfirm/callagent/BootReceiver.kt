package com.lawfirm.callagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the hourly sync after a reboot.
 *
 * WorkManager's own schedule normally survives reboots, but relies on its
 * internal receiver getting a chance to run — some OEM battery managers
 * (Honor/MagicOS included) block BOOT_COMPLETED for apps not marked as
 * auto-launch. This is the explicit fallback. Only for a signed-in phone
 * whose calls are collected; for everyone else there's nothing to schedule.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SecureStore.hasToken(context) || !Session.collectCalls(context)) return
        if (!Permissions.requiredGranted(context)) return
        SyncScheduler.schedule(context)
    }
}
