package com.lawfirm.callagent.push

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.DiagnosticLog
import com.lawfirm.callagent.SecureStore
import java.util.concurrent.TimeUnit

/**
 * Tells Ledger this phone's Google push address, after each settings check
 * (AppFlow.refresh) and whenever Google changes it — for everyone signed in:
 * Ledger's notifications come this way, and calls for people with calling.
 */
object PushSetup {
    /** Blocking — call from a background thread. */
    fun sync(context: Context, token: String) {
        try {
            pushToken(context)?.let { Api.sendPushToken(token, it) }
        } catch (e: Exception) {
            DiagnosticLog.append(context, "push: address not sent (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** A new push address from Google (LedgerMessagingService.onNewToken). */
    fun sendPushToken(context: Context, pushToken: String) {
        val token = SecureStore.token(context) ?: return
        Thread {
            try {
                Api.sendPushToken(token, pushToken)
            } catch (e: Exception) {
                // Sent again on the next settings check.
            }
        }.start()
    }

    // This phone's Google push address; null when this build has no Firebase
    // settings (google-services.json) or Google is unreachable.
    private fun pushToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return try {
            Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DiagnosticLog.append(context, "push: no address (${e.javaClass.simpleName})")
            null
        }
    }
}
