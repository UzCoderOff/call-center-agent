package com.lawfirm.callagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** What the phone has allowed. Only relevant for people whose calls are collected. */
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

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
