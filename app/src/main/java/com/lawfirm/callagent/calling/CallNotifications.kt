package com.lawfirm.callagent.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.lawfirm.callagent.R

/**
 * The call notifications: an incoming call rings like a phone call — on the
 * lock screen too, full screen (CallActivity) — and a call going on keeps a
 * notification (which also keeps the app running, CallService).
 */
object CallNotifications {
    const val INCOMING_ID = 4101
    const val ONGOING_ID = 4102
    private const val CHANNEL_INCOMING = "calls_incoming"
    private const val CHANNEL_ONGOING = "calls_ongoing"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // The ringtone comes from the call itself (the phone's own ringtone)
        // and the buzz from CallVibration, so the notification is silent.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
    }

    private fun who(context: Context, info: LedgerPhone.CallInfo?): String =
        info?.name ?: info?.number?.takeIf { it.isNotBlank() }?.let { CallUi.formatPhone(it) } ?: context.getString(R.string.call_unknown)

    private fun activityIntent(context: Context, action: String?, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun declineIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun hangUpIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ringing: full screen on a locked phone, a heads-up otherwise. */
    fun showIncoming(context: Context, info: LedgerPhone.CallInfo?) {
        ensureChannels(context)
        val caller = Person.Builder().setName(who(context, info)).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(who(context, info))
            // The name is the title; under it who passed the call on, the
            // number, or "incoming call".
            .setContentText(
                CallUi.noteText(context, info?.note)
                    ?: info?.number?.takeIf { info.name != null && it.isNotBlank() }?.let { CallUi.formatPhone(it) }
                    ?: context.getString(R.string.call_incoming)
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(activityIntent(context, null, 1), true)
            .setContentIntent(activityIntent(context, null, 1))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent(context), activityIntent(context, CallActivity.ACTION_ANSWER, 4)))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(INCOMING_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed: the call still rings, and the call screen opens if Ledger is on screen.
        }
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
    }

    /** A call going on (or starting): shown while it lasts, tap to return to it. */
    fun ongoing(context: Context, info: LedgerPhone.CallInfo?): Notification {
        ensureChannels(context)
        val caller = Person.Builder().setName(who(context, info)).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(if (info == null) context.getString(R.string.call_connecting) else who(context, info))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(activityIntent(context, null, 5))
        if (info != null && info.connectedAtMs != null) {
            builder.setUsesChronometer(true).setWhen(info.connectedAtMs)
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUpIntent(context)))
        } else {
            builder.setContentText(context.getString(R.string.call_connecting))
        }
        return builder.build()
    }
}
