package com.lawfirm.callagent.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Rad etish" / "Tugatish" on a call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE -> LedgerPhone.decline()
            HANG_UP -> LedgerPhone.hangUp()
        }
    }

    companion object {
        const val DECLINE = "com.lawfirm.callagent.call.DECLINE"
        const val HANG_UP = "com.lawfirm.callagent.call.HANG_UP"
    }
}
