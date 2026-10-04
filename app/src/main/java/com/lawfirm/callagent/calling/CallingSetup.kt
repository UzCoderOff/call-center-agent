package com.lawfirm.callagent.calling

import android.content.Context
import com.lawfirm.callagent.Api
import com.lawfirm.callagent.DiagnosticLog
import com.lawfirm.callagent.SecureStore

/**
 * Keeps this phone's calling in step with the server, after each settings
 * check (AppFlow.refresh): with calling on, it has its own sign-in to the
 * line (asked for when missing or taken over by another phone); with calling
 * off, it's gone. (The push address that wakes it: push/PushSetup.)
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
        } catch (e: Exception) {
            DiagnosticLog.append(context, "phone line: setup failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }
}
