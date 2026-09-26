package com.lawfirm.callagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds this phone's device token — issued by the server when the person
 * signs in (see Api.signIn), and the only credential the app keeps: it syncs
 * calls with it and gets fresh portal sessions with it, so the password is
 * never stored.
 *
 * The token is encrypted with an AES key that lives in the Android Keystore.
 * That key never leaves the phone's secure hardware, so the stored value is
 * useless if the app's files are copied off the device.
 */
object SecureStore {
    private const val KEY_ALIAS = "ledger_device_token"
    private const val PREFS = "ledger_secure"
    private const val TOKEN = "token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveToken(context: Context, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs(context).edit().putString(TOKEN, blob).apply()
    }

    fun hasToken(context: Context): Boolean = prefs(context).contains(TOKEN)

    fun token(context: Context): String? {
        val blob = prefs(context).getString(TOKEN, null) ?: return null
        return try {
            val parts = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore key lost (e.g. device lock-screen reset on some OEMs):
            // the token is unrecoverable, so treat the phone as signed out.
            DiagnosticLog.append(context, "token: could not decrypt (${e.javaClass.simpleName}) — signing out")
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(TOKEN).apply()
    }
}
