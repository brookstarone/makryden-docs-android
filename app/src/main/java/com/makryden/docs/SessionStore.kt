package com.makryden.docs

import android.content.Context
import org.json.JSONObject

/**
 * Stores the Supabase session in the portal format (mk-portal-session)
 * so the bundled web tools can adopt it via their session bridge.
 * Shape: {access, refresh, exp, email}
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("mk_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SESSION = "mk-portal-session"
    }

    fun save(access: String, refresh: String, expiresInSec: Long, email: String) {
        val exp = System.currentTimeMillis() + (expiresInSec * 1000)
        val obj = JSONObject()
        obj.put("access", access)
        obj.put("refresh", refresh)
        obj.put("exp", exp)
        obj.put("email", email)
        prefs.edit().putString(KEY_SESSION, obj.toString()).apply()
    }

    fun getAccessToken(): String? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val exp = obj.optLong("exp", 0)
            if (exp > 0 && exp < System.currentTimeMillis()) {
                null // expired
            } else {
                obj.optString("access", null)?.ifEmpty { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getEmail(): String? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
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
        return prefs.getString(KEY_SESSION, null)
    }

    fun isSignedIn(): Boolean = getAccessToken() != null

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }
}
