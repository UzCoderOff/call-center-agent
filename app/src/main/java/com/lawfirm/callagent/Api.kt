package com.lawfirm.callagent

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The backend's endpoints for the app (call-center-backend/src/routes/device.js).
 * Every call blocks — use from Dispatchers.IO / a background thread.
 *
 * These go straight to the VPS (BuildConfig.API_URL); only the in-app portal
 * view goes through the portal's address (BuildConfig.PORTAL_URL).
 */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The server answered with an error; `code` is its stable error string. */
    class HttpError(val status: Int, val code: String?) : IOException("HTTP $status ${code ?: ""}".trim())

    data class SignIn(val token: String, val displayName: String, val setCookies: List<String>)

    data class Config(
        val collectCalls: Boolean,
        val displayName: String,
        val latestVersionCode: Int,
        val latestVersionName: String?,
        val downloadUrl: String?,
        /** Calling through Ledger's phone line; null when it's off for them. */
        val calling: Calling?,
    )

    /**
     * Their place on Ledger's phone line (backend: routes/device.js callingFor):
     * their phone ID, where to connect (TLS), whether calls can go outside
     * yet, and whether this phone holds the current sign-in.
     */
    data class Calling(
        val ext: String,
        val host: String,
        val port: Int,
        val realm: String,
        val canCallOut: Boolean,
        val outboundReady: Boolean,
        val signedIn: Boolean,
    )

    /** This phone's sign-in to the line: the secret is shown once. */
    data class SipSignIn(val calling: Calling, val secret: String)

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Username + password -> this phone's device token (+ a portal session cookie). */
    fun signIn(username: String, password: String): SignIn {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("label", deviceLabel())
            .put("appVersion", BuildConfig.VERSION_NAME)
        val request = Request.Builder()
            .url(url("/api/device/login"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { res ->
            val json = parse(res)
            return SignIn(
                token = json.getString("token"),
                displayName = displayName(json.getJSONObject("user")),
                setCookies = res.headers("Set-Cookie"),
            )
        }
    }

    /** A fresh portal session cookie, from the device token — no password needed. */
    fun renewSession(token: String): List<String> {
        val request = authed(token, "/api/device/session").post("{}".toRequestBody(JSON)).build()
        client.newCall(request).execute().use { res ->
            parse(res)
            return res.headers("Set-Cookie")
        }
    }

    /** "Collect calls from this person?" and whether a newer app version exists. */
    fun config(token: String): Config {
        val request = authed(token, "/api/device/config").get().build()
        client.newCall(request).execute().use { res ->
            val json = parse(res)
            val latest = json.optJSONObject("latestApp")
            return Config(
                collectCalls = json.optBoolean("collectCalls", false),
                displayName = displayName(json.getJSONObject("user")),
                latestVersionCode = latest?.optInt("versionCode", 0) ?: 0,
                latestVersionName = latest?.optString("versionName")?.takeIf { it.isNotBlank() && it != "null" },
                downloadUrl = latest?.optString("downloadUrl")?.takeIf { it.isNotBlank() && it != "null" },
                calling = json.optJSONObject("calling")?.let(::calling),
            )
        }
    }

    /** A new sign-in to the phone line for this phone (the previous one stops working). */
    fun sipSignIn(token: String): SipSignIn {
        val request = authed(token, "/api/device/sip").post("{}".toRequestBody(JSON)).build()
        client.newCall(request).execute().use { res ->
            val json = parse(res)
            val calling = calling(json) ?: throw IOException("no phone ID")
            return SipSignIn(calling, json.getString("secret"))
        }
    }

    /** This phone's Google push address: Ledger's notifications, and a call can wake it. */
    fun sendPushToken(token: String, pushToken: String) {
        val body = JSONObject().put("token", pushToken)
        val request = authed(token, "/api/device/push").post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { parse(it) }
    }

    /** One of Ledger's notifications for this person (the push carries only its id). */
    data class Notice(
        val id: Long,
        val kind: String?,
        val title: String,
        val body: String?,
        val link: String?,
        val phone: String?,
        val tag: String?,
    )

    fun notice(token: String, id: Long): Notice {
        val request = authed(token, "/api/device/notices/$id").get().build()
        client.newCall(request).execute().use { res ->
            val json = parse(res)
            fun text(key: String) = json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() }
            return Notice(
                id = json.getLong("id"),
                kind = text("kind"),
                title = json.optString("title", "Ledger"),
                body = text("body"),
                link = text("link"),
                phone = text("phone"),
                tag = text("tag"),
            )
        }
    }

    /** "Remind me" on a call coming in: call this number back in `minutes`. */
    fun remindMe(token: String, phone: String, name: String?, minutes: Int) {
        val body = JSONObject().put("phone", phone).put("minutes", minutes)
        if (!name.isNullOrBlank()) body.put("name", name)
        val request = authed(token, "/api/device/reminders").post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { parse(it) }
    }

    private fun calling(json: JSONObject): Calling? {
        val ext = json.optString("ext").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Calling(
            ext = ext,
            host = json.optString("host"),
            port = json.optInt("port", 5161),
            realm = json.optString("realm", "ledger"),
            canCallOut = json.optBoolean("canCallOut", false),
            outboundReady = json.optBoolean("outboundReady", false),
            signedIn = json.optBoolean("signedIn", false),
        )
    }

    /** Revokes this phone's token on the server. */
    fun signOut(token: String) {
        val request = authed(token, "/api/device/logout").post("{}".toRequestBody(JSON)).build()
        client.newCall(request).execute().close()
    }

    fun url(path: String): String = BuildConfig.API_URL.trimEnd('/') + path

    fun authed(token: String, path: String): Request.Builder =
        Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $token")
            .header("X-App-Version", BuildConfig.VERSION_NAME)

    private fun parse(res: Response): JSONObject {
        val text = res.body?.string().orEmpty()
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
        if (!res.isSuccessful) throw HttpError(res.code, json.optString("error").takeIf { it.isNotBlank() })
        return json
    }

    private fun displayName(user: JSONObject): String =
        user.optJSONObject("employee")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: user.optString("username")
}
