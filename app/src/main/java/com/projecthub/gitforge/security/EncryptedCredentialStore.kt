package com.projecthub.gitforge.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.Redaction

/**
 * Keystore-backed credential store.
 *
 * The token is written to an [EncryptedSharedPreferences] file whose master key
 * lives in the Android Keystore (AES-256-GCM), so the ciphertext is unusable even
 * if the app-private directory is extracted from a rooted device. Nothing here is
 * ever logged; [AppLog] additionally scrubs anything that looks like a token.
 */
class EncryptedCredentialStore(context: Context) : CredentialStore {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun hasToken(): Boolean = !token().isNullOrBlank()

    override fun token(): String? = read(KEY_TOKEN)

    override fun login(): String? = read(KEY_LOGIN)

    override fun scopes(): String? = read(KEY_SCOPES)

    override fun tokenType(): String? = read(KEY_TOKEN_TYPE)

    /**
     * Eagerly opens the encrypted file, forcing Keystore + MasterKey creation.
     * Returns false (and logs) when the device cannot provide secure storage, so
     * [AppContainer] can fall back to [UnavailableCredentialStore] at startup
     * instead of discovering the failure on first use.
     */
    fun probe(): Boolean = try {
        prefs.getString(KEY_TOKEN, null)
        true
    } catch (t: Throwable) {
        AppLog.w("Encrypted credential store probe failed", t)
        false
    }

    override fun save(token: String, login: String?, scopes: String?, tokenType: String?) {
        try {
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_LOGIN, login)
                .putString(KEY_SCOPES, scopes)
                .putString(KEY_TOKEN_TYPE, tokenType)
                .apply()
            // Deliberately does not log the token or any derivative of it.
            AppLog.i("Stored GitHub credential for ${Redaction.redact(login ?: "unknown account")}")
        } catch (t: Throwable) {
            AppLog.w("Could not store the GitHub credential", t)
        }
    }

    override fun clear() {
        try {
            prefs.edit().clear().apply()
            AppLog.i("Cleared stored GitHub credential")
        } catch (t: Throwable) {
            AppLog.w("Could not clear the stored GitHub credential", t)
        }
    }

    private fun read(key: String): String? = try {
        prefs.getString(key, null)
    } catch (t: Throwable) {
        // A rotated or invalidated master key makes the file unreadable. Treat that
        // as "disconnected" rather than crashing the app on launch.
        AppLog.w("Credential store unreadable; treating account as disconnected", t)
        null
    }

    private companion object {
        const val PREFS_NAME = "gitforge_credentials"
        const val KEY_TOKEN = "github_token"
        const val KEY_LOGIN = "github_login"
        const val KEY_SCOPES = "github_scopes"
        const val KEY_TOKEN_TYPE = "github_token_type"
    }
}
