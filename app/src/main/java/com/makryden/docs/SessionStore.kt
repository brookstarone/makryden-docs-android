package com.makryden.docs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Stores the Supabase session in the portal format (mk-portal-session)
 * so the bundled web tools can adopt it via their session bridge.
 * Shape: {access, refresh, exp, email}
 *
 * Tokens live in EncryptedSharedPreferences (file "mk_session_enc"),
 * never in plaintext. On first init, a session left in the old
 * plaintext prefs file ("mk_session") is migrated once into encrypted
 * storage and then removed from the plaintext file. Migration failure
 * never crashes: the store simply reports signed-out.
 */
class SessionStore(context: Context) {

    // Application context only, so holding this store never leaks an Activity.
    private val appContext: Context = context.applicationContext

    private val encryptedPrefs: SharedPreferences? = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        // Overload 2: create(String alias, String file, Context, keyScheme, valueScheme).
        // getOrCreate returns a String alias, NOT a MasterKey object, so the
        // (Context, String, MasterKey, ...) overload does not apply.
        EncryptedSharedPreferences.create(
            masterKeyAlias,
            ENCRYPTED_FILE,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Encrypted storage unavailable (e.g. keystore failure):
        // fall back to signed-out rather than crashing or
        // silently writing tokens to plaintext.
        null
    }

    init {
        migrateFromPlaintext()
    }

    companion object {
        private const val KEY_SESSION = "mk-portal-session"
        private const val ENCRYPTED_FILE = "mk_session_enc"
        private const val LEGACY_FILE = "mk_session"

        /** Refresh proactively when the access token has less than this long left. */
        private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    }

    /**
     * One-time migration: if the encrypted store has no session yet but
     * the old plaintext prefs still hold one, copy it over verbatim and
     * then remove it from the plaintext prefs. Any failure leaves the
     * plaintext key untouched only if the copy did not happen; the
     * store never throws from this path.
     */
    private fun migrateFromPlaintext() {
        val encrypted = encryptedPrefs ?: return
        try {
            if (encrypted.contains(KEY_SESSION)) {
                // Already migrated (or a fresh encrypted session exists):
                // still scrub any leftover plaintext key.
                removeLegacyKey()
                return
            }
            val legacy = try {
                appContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                return
            }
            val legacyJson = try {
                legacy.getString(KEY_SESSION, null)
            } catch (e: Exception) {
                null
            } ?: return
            encrypted.edit().putString(KEY_SESSION, legacyJson).apply()
            removeLegacyKey()
        } catch (e: Exception) {
            // Migration failure: fall back to signed-out. Never crash.
        }
    }

    private fun removeLegacyKey() {
        try {
            val legacy = appContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            if (legacy.contains(KEY_SESSION)) {
                legacy.edit().remove(KEY_SESSION).apply()
            }
        } catch (e: Exception) {
            // Best effort only; a leftover plaintext key is scrubbed
            // again on the next init and by clear().
        }
    }

    /** Raw stored session JSON, or null. Does not wipe corrupt data. */
    private fun readSessionJsonRaw(): String? {
        val prefs = encryptedPrefs ?: return null
        return try {
            prefs.getString(KEY_SESSION, null)
        } catch (e: Exception) {
            null
        }
    }

    /** Parsed session fields, or null if absent or unparseable. Never wipes. */
    private fun readSession(): JSONObject? {
        val json = readSessionJsonRaw() ?: return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun putSessionJson(json: String) {
        try {
            encryptedPrefs?.edit()?.putString(KEY_SESSION, json)?.apply()
        } catch (e: Exception) {
            // Store unavailable: callers see signed-out rather than a crash.
        }
    }

    fun save(access: String, refresh: String, expiresInSec: Long, email: String) {
        val exp = System.currentTimeMillis() + (expiresInSec * 1000)
        val obj = JSONObject()
        obj.put("access", access)
        obj.put("refresh", refresh)
        obj.put("exp", exp)
        obj.put("email", email)
        putSessionJson(obj.toString())
    }

    /**
     * Same as [save] but keeps whatever email is already stored, so a
     * token refresh never clobbers the signed-in identity (the refresh
     * response may carry no usable email).
     */
    fun updateTokens(access: String, refresh: String, expiresInSec: Long) {
        val email = getEmail() ?: ""
        save(access, refresh, expiresInSec, email)
    }

    fun getAccessToken(): String? {
        val json = try {
            encryptedPrefs?.getString(KEY_SESSION, null)
        } catch (e: Exception) {
            return null
        } ?: return null
        return try {
            val obj = JSONObject(json)
            val exp = obj.optLong("exp", 0)
            if (exp > 0 && exp < System.currentTimeMillis()) {
                null // expired
            } else {
                obj.optString("access", null)?.ifEmpty { null }
            }
        } catch (e: Exception) {
            // Corrupt stored session: wipe it so we stop failing,
            // then report signed-out instead of crashing.
            try {
                encryptedPrefs?.edit()?.remove(KEY_SESSION)?.apply()
            } catch (_: Exception) {
            }
            null
        }
    }

    fun getRefreshToken(): String? {
        val obj = readSession() ?: return null
        return obj.optString("refresh", null)?.ifEmpty { null }
    }

    fun hasRefreshToken(): Boolean = getRefreshToken() != null

    fun getEmail(): String? {
        val json = readSessionJsonRaw() ?: return null
        return try {
            JSONObject(json).optString("email", null)?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun getUserId(): String? {
        // Extract sub from JWT payload
        val token = getAccessToken() ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            JSONObject(payload).optString("sub", null)?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Raw session JSON for injecting into WebView localStorage. */
    fun getSessionJson(): String? {
        return readSessionJsonRaw()
    }

    fun isSignedIn(): Boolean = getAccessToken() != null

    /**
     * Returns a usable access token, refreshing it first if needed.
     *
     * Returns the current access token when it exists and expires more
     * than 5 minutes from now. Otherwise, when a refresh token exists,
     * refreshes via Supabase and persists the new tokens (preserving
     * the stored email), then returns the new access token. Returns
     * null on any failure WITHOUT clearing the session; the caller
     * decides what to do (retry, queue offline, or sign out).
     *
     * BACKGROUND THREADS ONLY - never call from the main thread: a
     * refresh performs network I/O on the calling thread.
     */
    fun getValidAccessTokenBlocking(): String? {
        val obj = readSession() ?: return null
        val access = obj.optString("access", null)?.ifEmpty { null }
        val exp = obj.optLong("exp", 0)
        val now = System.currentTimeMillis()
        if (access != null && (exp == 0L || exp > now + REFRESH_MARGIN_MS)) {
            return access
        }
        val refresh = obj.optString("refresh", null)?.ifEmpty { null } ?: return null
        return try {
            val result = SupabaseClient.refreshToken(refresh)
            if (result.accessToken.isEmpty()) {
                return null
            }
            // A refresh response normally carries a new refresh token.
            // If it does not, keep the old one rather than wiping the
            // only way to refresh again.
            val newRefresh = result.refreshToken.ifEmpty { refresh }
            updateTokens(result.accessToken, newRefresh, result.expiresIn)
            result.accessToken
        } catch (e: Exception) {
            // Do NOT clear the session here: a transient network or
            // server failure must not sign the user out. Caller decides.
            null
        }
    }

    fun clear() {
        try {
            encryptedPrefs?.edit()?.remove(KEY_SESSION)?.apply()
        } catch (e: Exception) {
            // Best effort; still scrub the plaintext leftover below.
        }
        // Clear any leftover plaintext key as well.
        removeLegacyKey()
    }
}
