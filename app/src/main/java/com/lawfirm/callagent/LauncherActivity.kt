package com.lawfirm.callagent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * App entry: decides where to go. Signed out -> sign-in. Signed in -> renew
 * the portal session and re-read "collect calls?" (so a change made in the
 * portal applies on the next open), then permissions or the portal.
 */
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        val token = SecureStore.token(this)
        if (token == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        lifecycleScope.launch {
            if (AppFlow.refresh(this@LauncherActivity, token) is AppFlow.Refresh.SignedOut) {
                // Signed out from the portal (phone revoked) or account deactivated.
                AppFlow.signOut(this@LauncherActivity, revokeOnServer = false)
                return@launch
            }
            AppFlow.routeSignedIn(this@LauncherActivity)
            finish()
        }
    }
}
