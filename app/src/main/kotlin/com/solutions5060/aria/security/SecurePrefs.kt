package com.solutions5060.aria.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for sensitive values (SIP password, extension JWT,
 * gateway token).
 *
 * Backed by an AndroidKeyStore-derived [MasterKey] (AES256_GCM); values are
 * encrypted at rest so they are not readable from the raw shared_prefs XML,
 * from a device backup, or from a rooted/lost device.
 *
 * The public contract mirrors [SharedPreferences]: the same logical keys and
 * values are stored, only encrypted. Non-sensitive config (username, domain,
 * transport, server URL, UI toggles) stays in normal prefs.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val SECURE_PREFS_NAME = "aria_secure_prefs"

    /**
     * Keys that must live in the encrypted store. Any of these still present in
     * a legacy plaintext store are migrated across and then removed from it.
     */
    val SENSITIVE_KEYS: Set<String> = setOf(
        "sip_password",
        "jwt",
        "gateway_token",
    )

    @Volatile
    private var instance: SharedPreferences? = null

    /** Returns the encrypted shared-preferences instance (lazily created). */
    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    fun getString(context: Context, key: String, default: String? = null): String? =
        get(context).getString(key, default)

    fun putString(context: Context, key: String, value: String?) {
        get(context).edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    fun remove(context: Context, key: String) {
        get(context).edit().remove(key).apply()
    }

    /** Clears all encrypted values (used on sign-out). */
    fun clear(context: Context) {
        get(context).edit().clear().apply()
    }

    /**
     * One-time migration: copy any [SENSITIVE_KEYS] still living in the legacy
     * [plaintext] store into the encrypted store, then strip them from the
     * plaintext store so the secrets no longer exist in cleartext on disk.
     *
     * Safe to call on every launch — it is a no-op once the plaintext store no
     * longer holds any sensitive keys.
     */
    fun migrateFrom(context: Context, plaintext: SharedPreferences) {
        val hasLegacySecrets = SENSITIVE_KEYS.any { plaintext.contains(it) }
        if (!hasLegacySecrets) return

        val secure = get(context)
        val secureEditor = secure.edit()
        val plaintextEditor = plaintext.edit()
        for (key in SENSITIVE_KEYS) {
            if (plaintext.contains(key)) {
                val value = plaintext.getString(key, null)
                // Do not clobber a value already migrated into the secure store.
                if (value != null && !secure.contains(key)) {
                    secureEditor.putString(key, value)
                }
                plaintextEditor.remove(key)
            }
        }
        secureEditor.apply()
        plaintextEditor.apply()
        Log.i(TAG, "Migrated ${SENSITIVE_KEYS.size} sensitive keys to encrypted storage")
    }

    private fun build(appContext: Context): SharedPreferences {
        return try {
            create(appContext)
        } catch (e: Exception) {
            // A corrupted keystore/master-key entry (e.g. after a botched backup
            // restore) makes create() throw. Recover by discarding the encrypted
            // file and rebuilding; the user simply re-provisions.
            Log.w(TAG, "Failed to open encrypted prefs, recreating: ${e.message}")
            appContext.deleteSharedPreferences(SECURE_PREFS_NAME)
            create(appContext)
        }
    }

    private fun create(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
