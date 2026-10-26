package com.makryden.docs

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Supabase REST client using HttpURLConnection.
 * Uses org.json (built into Android) - fine on device, no unit tests touch this.
 *
 * HARDENED: every public method is crash-proof. Network/parse failures are
 * logged via Log.e and converted to safe return values (empty lists, empty
 * strings, empty pairs). signIn/signUp throw a clear Exception on failure
 * (AuthActivity already catches and shows the error) rather than risk a
 * false "signed in" state from an empty AuthResult.
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"

    private const val SUPABASE_URL = "https://bamrgsjgvakyucyqtwlg.supabase.co"
    // Public anon key (same as bundled web assets - designed to be public)
    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJhbXJnc2pndmFreXVjeXF0d2xnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODExMjUwMzAsImV4cCI6MjA5NjcwMTAzMH0.4rW9kVK1E-YFvjBvQd-VTPh5pd_O7ZTzYAx_BkyPbH0"

    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val email: String,
        val userId: String
    )

    data class DocItem(
        val id: String,
        val title: String,
        val snippet: String,
        val updatedAt: String,
        val kind: String // "doc", "note", "pdf"
    )

    private fun openConnection(path: String, method: String, token: String? = null): HttpURLConnection {
        val url = URL(SUPABASE_URL + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("apikey", ANON_KEY)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "readResponse: could not get response code: ${e.message}")
            throw Exception("Network error: ${e.message}")
        }
        // errorStream can be null on some failures - guard against NPE
        val stream = if (code in 200..299) {
            try { conn.inputStream } catch (e: Exception) {
                Log.e(TAG, "readResponse: null inputStream on HTTP $code")
                throw Exception("Empty response (HTTP $code)")
            }
        } else {
            try { conn.errorStream } catch (e: Exception) { null }
        }
        val body = if (stream != null) {
            try {
                val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                sb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "readResponse: error reading body: ${e.message}")
                ""
            } finally {
                try { stream.close() } catch (ignored: Exception) { }
            }
        } else {
            ""
        }
        if (code !in 200..299) {
            Log.e(TAG, "readResponse: HTTP $code body=${body.take(200)}")
            throw Exception("HTTP $code: ${body.take(200)}")
        }
        return body
    }

    private fun post(path: String, body: JSONObject, token: String? = null): String {
        val conn = openConnection(path, "POST", token)
        conn.doOutput = true
        try {
            val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
            writer.write(body.toString())
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "post $path: write failed: ${e.message}")
            throw Exception("Network error: ${e.message}")
        }
        return readResponse(conn)
    }

    private fun get(path: String, token: String): String {
        val conn = openConnection(path, "GET", token)
        return readResponse(conn)
    }

    /** Parse a JSON array safely. Returns empty list if the text is not an array. */
    private fun safeArray(text: String, where: String): JSONArray {
        if (text.isBlank()) {
            Log.e(TAG, "$where: empty response body")
            throw Exception("Empty response from server")
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) {
            Log.e(TAG, "$where: expected JSON array, got: ${trimmed.take(200)}")
            throw Exception("Unexpected server response")
        }
        return try {
            JSONArray(trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "$where: JSON parse failed: ${e.message}")
            throw Exception("Could not parse server response")
        }
    }

    /** Sign in with email/password. Returns auth tokens. Throws on failure (caller shows error). */
    fun signIn(email: String, password: String): AuthResult {
        try {
            val body = JSONObject()
            body.put("email", email)
            body.put("password", password)
            val resp = post("/auth/v1/token?grant_type=password", body)
            return parseAuthResult(resp, email)
        } catch (e: Exception) {
            Log.e(TAG, "signIn failed: ${e.message}")
            throw Exception("Sign in failed: ${e.message}")
        }
    }

    /** Sign up with email/password. Returns auth tokens. Throws on failure (caller shows error). */
    fun signUp(email: String, password: String): AuthResult {
        try {
            val body = JSONObject()
            body.put("email", email)
            body.put("password", password)
            val resp = post("/auth/v1/signup", body)
            return parseAuthResult(resp, email)
        } catch (e: Exception) {
            Log.e(TAG, "signUp failed: ${e.message}")
            throw Exception("Sign up failed: ${e.message}")
        }
    }

    /** Refresh an expired access token with a refresh token. Returns new auth tokens. Throws on failure. */
    fun refreshToken(refreshToken: String): AuthResult {
        try {
            val body = JSONObject()
            body.put("refresh_token", refreshToken)
            val resp = post("/auth/v1/token?grant_type=refresh_token", body)
            return parseAuthResult(resp, "")
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken failed: ${e.message}")
            throw Exception("Token refresh failed: ${e.message}")
        }
    }

    private fun parseAuthResult(json: String, email: String): AuthResult {
        try {
            val obj = JSONObject(json)
            val accessToken = obj.optString("access_token", "")
            if (accessToken.isEmpty()) {
                // Supabase returns error JSON like {"error": "...", "msg": "..."} on bad login
                val errMsg = obj.optString("msg",
                    obj.optString("error_description",
                        obj.optString("error", "Unknown auth error")))
                Log.e(TAG, "parseAuthResult: no access_token, error=$errMsg")
                throw Exception(errMsg)
            }
            val userObj = obj.optJSONObject("user")
            return AuthResult(
                accessToken = accessToken,
                refreshToken = obj.optString("refresh_token", ""),
                expiresIn = obj.optLong("expires_in", 3600),
                email = userObj?.optString("email", email) ?: email,
                userId = userObj?.optString("id", "") ?: ""
            )
        } catch (e: Exception) {
            if (e.message?.startsWith("Sign in failed") == true ||
                e.message?.startsWith("Sign up failed") == true) throw e
            Log.e(TAG, "parseAuthResult: ${e.message}")
            throw Exception("Could not parse auth response")
        }
    }

    /** Get writer documents for the user. Never throws - returns empty list on failure. */
    fun getDocuments(token: String): List<DocItem> {
        return try {
            val resp = get("/rest/v1/documents?select=id,title,updated_at&order=updated_at.desc&limit=50", token)
            val arr = safeArray(resp, "getDocuments")
            val items = mutableListOf<DocItem>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    items.add(DocItem(
                        id = id,
                        title = obj.optString("title", "").ifEmpty { "Untitled document" },
                        snippet = "",
                        updatedAt = obj.optString("updated_at", ""),
                        kind = "doc"
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "getDocuments: skipping bad row $i: ${e.message}")
                }
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "getDocuments failed: ${e.message}")
            emptyList()
        }
    }

    /** Get notes for the user. Never throws - returns empty list on failure. */
    fun getNotes(token: String): List<DocItem> {
        return try {
            val resp = get("/rest/v1/notes?select=id,title,text,updated_at&order=updated_at.desc&limit=50", token)
            val arr = safeArray(resp, "getNotes")
            val items = mutableListOf<DocItem>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    val text = obj.optString("text", "")
                    items.add(DocItem(
                        id = id,
                        title = obj.optString("title", "").ifEmpty { "Untitled" },
                        snippet = text.take(120),
                        updatedAt = obj.optString("updated_at", ""),
                        kind = "note"
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "getNotes: skipping bad row $i: ${e.message}")
                }
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "getNotes failed: ${e.message}")
            emptyList()
        }
    }

    /** Get saved PDFs for the user. Never throws - returns empty list on failure. */
    fun getPdfs(token: String): List<DocItem> {
        return try {
            val resp = get("/rest/v1/pdf_documents?select=id,filename,file_size,created_at&order=created_at.desc&limit=50", token)
            val arr = safeArray(resp, "getPdfs")
            val items = mutableListOf<DocItem>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    val size = obj.optLong("file_size", 0)
                    items.add(DocItem(
                        id = id,
                        title = obj.optString("filename", "").ifEmpty { "Untitled PDF" },
                        snippet = formatSize(size),
                        updatedAt = obj.optString("created_at", ""),
                        kind = "pdf"
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "getPdfs: skipping bad row $i: ${e.message}")
                }
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "getPdfs failed: ${e.message}")
            emptyList()
        }
    }

    /** Create a new writer document. Returns the new ID, or "" on failure. */
    fun createDocument(token: String, userId: String): String {
        return try {
            val body = JSONObject()
            body.put("user_id", userId)
            body.put("title", "Untitled document")
            body.put("content_html", "")
            body.put("template", "blank")
            val conn = openConnection("/rest/v1/documents", "POST", token)
            conn.setRequestProperty("Prefer", "return=representation")
            conn.doOutput = true
            try {
                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(body.toString())
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "createDocument: write failed: ${e.message}")
                return ""
            }
            val resp = readResponse(conn)
            val arr = safeArray(resp, "createDocument")
            if (arr.length() == 0) {
                Log.e(TAG, "createDocument: empty representation array")
                return ""
            }
            arr.optJSONObject(0)?.optString("id", "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "createDocument failed: ${e.message}")
            ""
        }
    }

    /** Create a new note. Returns the new ID, or "" on failure. */
    fun createNote(token: String, userId: String): String {
        return try {
            val id = java.util.UUID.randomUUID().toString()
            val body = JSONObject()
            body.put("id", id)
            body.put("user_id", userId)
            body.put("title", "")
            body.put("text", "")
            val conn = openConnection("/rest/v1/notes", "POST", token)
            conn.setRequestProperty("Prefer", "return=representation")
            conn.doOutput = true
            try {
                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(body.toString())
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "createNote: write failed: ${e.message}")
                return ""
            }
            readResponse(conn)
            id
        } catch (e: Exception) {
            Log.e(TAG, "createNote failed: ${e.message}")
            ""
        }
    }

    /** Get a single document with its HTML content. Returns ("","") on failure. */
    fun getDocument(id: String, token: String): Pair<String, String> {
        return try {
            val resp = get("/rest/v1/documents?select=id,title,content_html&id=eq.${encode(id)}&limit=1", token)
            val arr = safeArray(resp, "getDocument")
            if (arr.length() == 0) {
                Log.e(TAG, "getDocument: not found id=$id")
                return Pair("", "")
            }
            val obj = arr.optJSONObject(0) ?: return Pair("", "")
            Pair(
                obj.optString("title", "Untitled document"),
                obj.optString("content_html", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "getDocument failed: ${e.message}")
            Pair("", "")
        }
    }

    /** Update a document's title and HTML content. Never throws. Returns true on success, false on any failure. */
    fun updateDocument(id: String, token: String, title: String, contentHtml: String): Boolean {
        try {
            if (id.isEmpty()) {
                Log.e(TAG, "updateDocument: empty id, skipping")
                return false
            }
            val body = JSONObject()
            body.put("title", title)
            body.put("content_html", contentHtml)
            val encodedId = encode(id)
            val conn = openConnection("/rest/v1/documents?id=eq.$encodedId", "PATCH", token)
            conn.doOutput = true
            try {
                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(body.toString())
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "updateDocument: write failed: ${e.message}")
                return false
            }
            readResponse(conn)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "updateDocument failed: ${e.message}")
            return false
        }
    }

    /** Get a single note with its content. Returns ("","","") on failure. */
    fun getNote(id: String, token: String): Triple<String, String, String> {
        return try {
            val resp = get("/rest/v1/notes?select=id,title,text&id=eq.${encode(id)}&limit=1", token)
            val arr = safeArray(resp, "getNote")
            if (arr.length() == 0) {
                Log.e(TAG, "getNote: not found id=$id")
                return Triple("", "", id)
            }
            val obj = arr.optJSONObject(0) ?: return Triple("", "", id)
            Triple(
                obj.optString("title", "Untitled"),
                obj.optString("text", ""),
                obj.optString("id", id)
            )
        } catch (e: Exception) {
            Log.e(TAG, "getNote failed: ${e.message}")
            Triple("", "", id)
        }
    }

    /** Update a note's title and text. Never throws. Returns true on success, false on any failure. */
    fun updateNote(id: String, token: String, title: String, text: String): Boolean {
        try {
            if (id.isEmpty()) {
                Log.e(TAG, "updateNote: empty id, skipping")
                return false
            }
            val body = JSONObject()
            body.put("title", title)
            body.put("text", text)
            val conn = openConnection("/rest/v1/notes?id=eq.${encode(id)}", "PATCH", token)
            conn.doOutput = true
            try {
                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(body.toString())
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "updateNote: write failed: ${e.message}")
                return false
            }
            readResponse(conn)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "updateNote failed: ${e.message}")
            return false
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun encode(s: String): String {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "encode failed: ${e.message}")
            s
        }
    }
}
