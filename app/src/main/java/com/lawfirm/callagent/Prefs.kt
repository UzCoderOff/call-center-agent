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

// Single source of truth for the periodic sync interval, referenced by both
// MainActivity (initial schedule) and BootReceiver (re-arm after reboot) so
// the two can never drift apart again the way they easily could before —
// each had its own hardcoded number. Shorter than the old 12h partly to
// keep each batch (and therefore each JSON payload) smaller, which lowers
// the odds of hitting the server's per-field size limit in the first place.
const val SYNC_INTERVAL_HOURS = 6L
