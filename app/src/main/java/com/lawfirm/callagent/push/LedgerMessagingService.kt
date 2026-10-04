package com.lawfirm.callagent.push

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.DiagnosticLog
import com.lawfirm.callagent.SecureStore
import com.lawfirm.callagent.calling.CallService
import com.lawfirm.callagent.calling.LedgerPhone

/**
 * Google push to Ledger (backend: src/services/push). Messages carry ids
 * only — nothing about clients goes through Google:
 *   t=call    a call is coming to this phone — sign in to the line now so it
 *             rings (x: until when it's worth it, ms)
 *   t=notice  one of Ledger's notifications (n: its id) — fetched from
 *             Ledger and shown (Notices)
 * A new push address goes to the server (PushSetup).
 */
class LedgerMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["t"]) {
            "call" -> {
                val until = data["x"]?.toLongOrNull() ?: 0L
                if (until < System.currentTimeMillis()) return // the call has moved on
                DiagnosticLog.append(this, "push: call coming")
                Handler(Looper.getMainLooper()).post {
                    CallService.update(applicationContext)
                    LedgerPhone.wakeForCall(applicationContext)
                }
            }
            "notice" -> {
                // Runs on a background thread, with time to fetch the text.
                val id = data["n"]?.toLongOrNull() ?: return
                val token = SecureStore.token(this) ?: return
                try {
                    Notices.show(applicationContext, Api.notice(token, id))
                } catch (e: Exception) {
                    DiagnosticLog.append(this, "push: notice $id not shown (${e.javaClass.simpleName})")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        PushSetup.sendPushToken(applicationContext, token)
    }
}
