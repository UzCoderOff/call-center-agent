package com.lawfirm.callagent.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.PortalActivity
import com.lawfirm.callagent.R

/**
 * Ledger's notifications on the phone — the same as the staff bot's
 * Telegram messages, plus chat and reminders. Tapping one opens that page of
 * the portal; a reminder to call someone back has a Call button.
 */
object Notices {
    private const val CHANNEL_UPDATES = "ledger_updates"
    private const val CHANNEL_REMINDERS = "ledger_reminders"
    private const val CHANNEL_CHAT = "ledger_chat"
    private const val NOTICE_ID = 4200

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.notice_channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, context.getString(R.string.notice_channel_chat), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notice_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun channelFor(kind: String?) = when (kind) {
        "reminder", "chatEvent", "tasks", "followUps" -> CHANNEL_REMINDERS
        "chat" -> CHANNEL_CHAT
        else -> CHANNEL_UPDATES
    }

    fun show(context: Context, notice: Api.Notice) {
        ensureChannels(context)
        val tag = notice.tag ?: "notice:${notice.id}"
        val open = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            PortalActivity.openIntent(context, notice.link ?: "/"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channelFor(notice.kind))
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(notice.title)
            .setContentText(notice.body?.lineSequence()?.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body ?: ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(
                when (notice.kind) {
                    "chat" -> NotificationCompat.CATEGORY_MESSAGE
                    "reminder", "chatEvent" -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_STATUS
                }
            )
        val phone = notice.phone
        if (phone != null) {
            val call = PendingIntent.getActivity(
                context,
                ("call:$tag").hashCode(),
                PortalActivity.callIntent(context, phone),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.notice_call_back), call)
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTICE_ID, builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed on this phone: the setup guide asks for them.
        }
    }
}
