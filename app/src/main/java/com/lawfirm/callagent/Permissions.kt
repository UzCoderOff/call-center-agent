package com.lawfirm.callagent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What the phone has allowed: for people whose calls are collected (call
 * log, files), and for people who call through Ledger's line (microphone,
 * notifications, calls over the lock screen). Battery is for both.
 */
object Permissions {
    fun callLog(context: Context): Boolean = granted(context, Manifest.permission.READ_CALL_LOG)

    /** Recordings can be in any folder: "All files access" on Android 11+, storage read below. */
    fun files(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun battery(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }


    /** Without these two, calls can't be collected at all. */
    fun requiredGranted(context: Context): Boolean = callLog(context) && files(context)

    fun mic(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)

    /** Android 13+ asks; below that they're on unless switched off in settings. */
    fun notifications(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 14+: showing a call over the lock screen is a setting of its own. */
    fun fullScreenCalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Without these two, calls through the line can't ring or be heard. */
    fun callingReady(context: Context): Boolean = mic(context) && notifications(context)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
