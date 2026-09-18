package com.lawfirm.callagent

object Prefs {
    const val NAME = "call_agent_prefs"
    const val EMPLOYEE_ID = "employee_id"
    const val SERVER_URL = "server_url"
    const val LAST_SYNC = "last_sync_ts"
    // Tracks call-log continuity across syncs so we can flag gaps (e.g. entries
    // deleted from the log between runs) rather than silently trusting it.
    const val LAST_SEEN_CALL_COUNT = "last_seen_call_count"
    const val LAST_SEEN_CALL_TIMESTAMP = "last_seen_call_ts"
    // Epoch ms of the first-ever sync run on this device. A hard floor —
    // nothing timestamped before this is ever read from the call log or
    // uploaded from storage, no matter what other timestamps say.
    const val INSTALL_FLOOR = "install_floor_ts"
}
