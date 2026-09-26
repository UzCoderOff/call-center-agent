package com.lawfirm.callagent

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign-in with the person's portal username and password — the same account
 * the developer created in the portal. Nothing else to configure: the
 * server address is built into the app.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        username = findViewById(R.id.usernameInput)
        password = findViewById(R.id.passwordInput)
        button = findViewById(R.id.loginButton)
        progress = findViewById(R.id.loginProgress)
        error = findViewById(R.id.errorText)
        findViewById<TextView>(R.id.versionText).text = getString(R.string.app_version, BuildConfig.VERSION_NAME)

        button.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            showError(getString(R.string.login_fill))
            return
        }

        setBusy(true)
        error.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val signIn = withContext(Dispatchers.IO) {
                    val result = Api.signIn(user, pass)
                    SecureStore.saveToken(this@LoginActivity, result.token)
                    result
                }
                AppFlow.applyCookies(signIn.setCookies)
                Session.saveName(this@LoginActivity, signIn.displayName)

                // Whether this person's calls are collected decides the next screen.
                val config = withContext(Dispatchers.IO) {
                    try {
                        Api.config(signIn.token)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (config != null) Session.saveConfig(this@LoginActivity, config)
                DiagnosticLog.append(this@LoginActivity, "signed in as $user (collectCalls=${config?.collectCalls})")

                AppFlow.routeSignedIn(this@LoginActivity)
                finish()
            } catch (e: Api.HttpError) {
                showError(
                    when (e.status) {
                        401 -> getString(R.string.login_invalid)
                        429 -> getString(R.string.login_too_many)
                        else -> getString(R.string.login_network) + " (HTTP ${e.status})"
                    }
                )
            } catch (e: Exception) {
                DiagnosticLog.append(this@LoginActivity, "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
                showError(getString(R.string.login_network))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        button.isEnabled = !busy
        button.text = if (busy) "" else getString(R.string.login_submit)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
}
