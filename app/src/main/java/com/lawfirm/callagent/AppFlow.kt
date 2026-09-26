package com.lawfirm.callagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's flow in one place:
 *
 *   not signed in             -> LoginActivity
 *   signed in, calls NOT      -> PortalActivity (nothing on the phone is read,
 *   collected for this person    no permissions are asked for, nothing runs
 *                                in the background)
 *   signed in, calls          -> PermissionsActivity until call log + files
 *   collected                    are allowed, then PortalActivity, with
 *                                background sync running
 *
 * "Collected or not" is the portal's per-person switch, re-read on every open.
 */
object AppFlow {

    /** Set by "Later" on the permissions screen; asked again next app start. */
    @Volatile
    var permissionsPostponed = false

    sealed class Refresh {
        data class Ok(val config: Api.Config) : Refresh()
        object SignedOut : Refresh()
        object Offline : Refresh()
    }

    /**
     * Renews the portal session and re-reads this person's settings. Network
     * trouble isn't fatal — the app carries on with what it last knew.
     */
    suspend fun refresh(context: Context, token: String): Refresh = withContext(Dispatchers.IO) {
        try {
            val cookies = Api.renewSession(token)
            val config = Api.config(token)
            withContext(Dispatchers.Main) { applyCookies(cookies) }
            Session.saveConfig(context, config)
            Refresh.Ok(config)
        } catch (e: Api.HttpError) {
            if (e.status == 401) Refresh.SignedOut else Refresh.Offline
        } catch (e: Exception) {
            // Network down, or an unexpected reply — carry on with what we knew.
            DiagnosticLog.append(context, "refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            Refresh.Offline
        }
    }

    /**
     * Hands the backend's session cookie to the in-app portal (WebView), for
     * the portal's own address. Main thread only.
     */
    fun applyCookies(setCookies: List<String>) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        for (cookie in setCookies) cookies.setCookie(BuildConfig.PORTAL_URL, cookie)
        cookies.flush()
    }

    /** Where to go once signed in; also starts/stops background sync to match. */
    fun routeSignedIn(activity: Activity) {
        if (Session.collectCalls(activity)) {
            SyncState.ensureInstallFloor(activity)
            if (Permissions.requiredGranted(activity)) {
                SyncScheduler.schedule(activity)
            } else if (!permissionsPostponed) {
                activity.startActivity(Intent(activity, PermissionsActivity::class.java))
                return
            }
        } else {
            SyncScheduler.cancel(activity)
        }
        activity.startActivity(Intent(activity, PortalActivity::class.java))
    }

    /**
     * Signs this phone out: revokes the token on the server (best effort),
     * forgets everything local, stops syncing, back to the sign-in screen.
     */
    fun signOut(activity: Activity, revokeOnServer: Boolean) {
        val token = SecureStore.token(activity)
        if (revokeOnServer && token != null) {
            // Signing out locally must never wait on the network.
            Thread {
                try {
                    Api.signOut(token)
                } catch (e: Exception) {
                    // Already signed out locally; the server token just expires unused.
                }
            }.start()
        }
        DiagnosticLog.append(activity, "signed out (revokeOnServer=$revokeOnServer)")
        SecureStore.clear(activity)
        Session.clear(activity)
        SyncScheduler.cancel(activity)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}
