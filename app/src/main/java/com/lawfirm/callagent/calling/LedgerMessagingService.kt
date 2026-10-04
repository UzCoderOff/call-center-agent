package com.lawfirm.callagent.calling

import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lawfirm.callagent.DiagnosticLog

/**
 * Google push to Ledger (backend: src/services/push). Messages carry ids only:
 *   t=call  a call is coming to this phone — sign in to the line now so it
 *           rings (x: until when it's worth it, ms)
 * A new push address goes to the server (CallingSetup).
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
        }
    }

    override fun onNewToken(token: String) {
        CallingSetup.sendPushToken(applicationContext, token)
    }
}
