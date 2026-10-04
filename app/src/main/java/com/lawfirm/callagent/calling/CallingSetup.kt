package com.lawfirm.callagent.calling

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.DiagnosticLog
import com.lawfirm.callagent.SecureStore
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone's calling in step with the server, after each settings
 * check (AppFlow.refresh): with calling on, it has its own sign-in to the
 * line (asked for when missing or taken over by another phone) and the
 * server knows its Google push address; with calling off, both are gone.
 *
 * Blocking — call from a background thread.
 */
object CallingSetup {
    fun sync(context: Context, token: String, calling: Api.Calling?) {
        if (calling == null) {
            if (SecureStore.sipSecret(context) != null) {
                SecureStore.clearSipSecret(context)
                android.os.Handler(android.os.Looper.getMainLooper()).post { LedgerPhone.stop() }
            }
            return
        }
        try {
            if (!calling.signedIn || SecureStore.sipSecret(context) == null) {
                val signIn = Api.sipSignIn(token)
                SecureStore.saveSipSecret(context, signIn.secret)
                DiagnosticLog.append(context, "phone line: signed in as ${signIn.calling.ext}")
                // The engine may hold the old sign-in: start again with the new one.
                android.os.Handler(android.os.Looper.getMainLooper()).post { LedgerPhone.stop() }
            }
            pushToken(context)?.let { Api.sendPushToken(token, it) }
        } catch (e: Exception) {
            DiagnosticLog.append(context, "phone line: setup failed (${e.javaClass.simpleName}: ${e.message})")
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
