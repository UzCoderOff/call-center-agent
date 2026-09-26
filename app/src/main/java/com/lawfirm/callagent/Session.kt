package com.lawfirm.callagent

import android.content.Context

/**
 * Non-secret facts about who is signed in, cached from the server so the app
 * (and the background sync) can act without a network round trip. The
 * source of truth is always the portal — this is refreshed on every app open
 * (see AppFlow.refresh / PortalActivity.applyLatestConfig).
 */
object Session {
    private const val PREFS = "ledger_session"
    private const val NAME = "display_name"
    private const val COLLECT_CALLS = "collect_calls"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveName(context: Context, name: String) {
        prefs(context).edit().putString(NAME, name).apply()
    }

    fun saveConfig(context: Context, config: Api.Config) {
        prefs(context).edit()
            .putString(NAME, config.displayName)
            .putBoolean(COLLECT_CALLS, config.collectCalls)
            .apply()
    }

    fun displayName(context: Context): String? = prefs(context).getString(NAME, null)

    /** The portal's "collect calls from this person" switch, as last seen. */
    fun collectCalls(context: Context): Boolean = prefs(context).getBoolean(COLLECT_CALLS, false)

    fun setCollectCalls(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(COLLECT_CALLS, value).apply()
    }

    /** Forget everything about the signed-in person (sign-out). */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        SyncState.clear(context)
    }
}
