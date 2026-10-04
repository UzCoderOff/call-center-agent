package com.lawfirm.callagent.calling

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.linphone.core.Call

/**
 * Keeps Ledger running while a call rings or goes on (Android stops apps in
 * the background otherwise), with the call's notification. Started for a
 * call coming in, going out or being woken for; stopped when it ends.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = LedgerPhone.currentInfo()
        val notification = CallNotifications.ongoing(this, info)
        // The microphone only once the call is answered (Android allows it
        // from then, when the person is in the app).
        val talking = info != null && info.state != Call.State.IncomingReceived && info.state != Call.State.IncomingEarlyMedia
        val mic = talking && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, notification, type)
        } catch (e: Exception) {
            // Not allowed right now (rare): the call goes on without it.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        /** Starts it, or refreshes its notification (the call's state changed). */
        fun update(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            } catch (e: Exception) {
                // Started from the background where Android refuses: the call still works.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
