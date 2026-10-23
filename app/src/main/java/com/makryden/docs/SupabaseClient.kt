package com.makryden.docs

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
 */
object SupabaseClient {

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
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        if (code !in 200..299) {
            throw Exception("HTTP $code: ${sb.toString().take(200)}")
        }
        return sb.toString()
    }

    private fun post(path: String, body: JSONObject, token: String? = null): String {
        val conn = openConnection(path, "POST", token)
        conn.doOutput = true
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        return readResponse(conn)
    }

    private fun get(path: String, token: String): String {
        val conn = openConnection(path, "GET", token)
        return readResponse(conn)
    }

    /** Sign in with email/password. Returns auth tokens. */
    fun signIn(email: String, password: String): AuthResult {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val resp = post("/auth/v1/token?grant_type=password", body)
        return parseAuthResult(resp, email)
    }

    /** Sign up with email/password. Returns auth tokens. */
    fun signUp(email: String, password: String): AuthResult {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val resp = post("/auth/v1/signup", body)
        return parseAuthResult(resp, email)
    }

    private fun parseAuthResult(json: String, email: String): AuthResult {
        val obj = JSONObject(json)
        return AuthResult(
            accessToken = obj.getString("access_token"),
            refreshToken = obj.optString("refresh_token", ""),
            expiresIn = obj.optLong("expires_in", 3600),
            email = obj.optJSONObject("user")?.optString("email", email) ?: email,
            userId = obj.optJSONObject("user")?.optString("id", "") ?: ""
        )
    }

    /** Get writer documents for the user. */
    fun getDocuments(token: String): List<DocItem> {
        val resp = get("/rest/v1/documents?select=id,title,updated_at&order=updated_at.desc&limit=50", token)
        val arr = JSONArray(resp)
        val items = mutableListOf<DocItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(DocItem(
                id = obj.getString("id"),
                title = obj.optString("title", "Untitled document").ifEmpty { "Untitled document" },
                snippet = "",
                updatedAt = obj.optString("updated_at", ""),
                kind = "doc"
            ))
        }
        return items
    }

    /** Get notes for the user. */
    fun getNotes(token: String): List<DocItem> {
        val resp = get("/rest/v1/notes?select=id,title,text,updated_at&order=updated_at.desc&limit=50", token)
        val arr = JSONArray(resp)
        val items = mutableListOf<DocItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val text = obj.optString("text", "")
            items.add(DocItem(
                id = obj.getString("id"),
                title = obj.optString("title", "Untitled").ifEmpty { "Untitled" },
                snippet = text.take(120),
                updatedAt = obj.optString("updated_at", ""),
                kind = "note"
            ))
        }
        return items
    }

    /** Get saved PDFs for the user. */
    fun getPdfs(token: String): List<DocItem> {
        val resp = get("/rest/v1/pdf_documents?select=id,filename,file_size,created_at&order=created_at.desc&limit=50", token)
        val arr = JSONArray(resp)
        val items = mutableListOf<DocItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val size = obj.optLong("file_size", 0)
            items.add(DocItem(
                id = obj.getString("id"),
                title = obj.optString("filename", "Untitled PDF").ifEmpty { "Untitled PDF" },
                snippet = formatSize(size),
                updatedAt = obj.optString("created_at", ""),
                kind = "pdf"
            ))
        }
        return items
    }

    /** Create a new writer document. Returns the new ID. */
    fun createDocument(token: String, userId: String): String {
        val body = JSONObject()
        body.put("user_id", userId)
        body.put("title", "Untitled document")
        body.put("content_html", "")
        body.put("template", "blank")
        val conn = openConnection("/rest/v1/documents", "POST", token)
        conn.setRequestProperty("Prefer", "return=representation")
        conn.doOutput = true
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        val resp = readResponse(conn)
        val arr = JSONArray(resp)
        return arr.getJSONObject(0).getString("id")
    }

    /** Create a new note. Returns the new ID. */
    fun createNote(token: String, userId: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val body = JSONObject()
        body.put("id", id)
        body.put("user_id", userId)
        body.put("title", "")
        body.put("text", "")
        val conn = openConnection("/rest/v1/notes", "POST", token)
        conn.setRequestProperty("Prefer", "return=representation")
        conn.doOutput = true
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        readResponse(conn)
        return id
    }

    /** Get a single document with its HTML content. */
    fun getDocument(id: String, token: String): Pair<String, String> {
        val resp = get("/rest/v1/documents?select=id,title,content_html&id=eq.$id&limit=1", token)
        val arr = JSONArray(resp)
        if (arr.length() == 0) throw Exception("Document not found")
        val obj = arr.getJSONObject(0)
        return Pair(
            obj.optString("title", "Untitled document"),
            obj.optString("content_html", "")
        )
    }

    /** Update a document's title and HTML content. */
    fun updateDocument(id: String, token: String, title: String, contentHtml: String) {
        val body = JSONObject()
        body.put("title", title)
        body.put("content_html", contentHtml)
        val encodedId = encode(id)
        val conn = openConnection("/rest/v1/documents?id=eq.$encodedId", "PATCH", token)
        conn.doOutput = true
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        readResponse(conn)
    }

    /** Get a single note with its content. */
    fun getNote(id: String, token: String): Triple<String, String, String> {
        val resp = get("/rest/v1/notes?select=id,title,text&id=eq.${encode(id)}&limit=1", token)
        val arr = JSONArray(resp)
        if (arr.length() == 0) throw Exception("Note not found")
        val obj = arr.getJSONObject(0)
        return Triple(
            obj.optString("title", "Untitled"),
            obj.optString("text", ""),
            obj.optString("id", id)
        )
    }

    /** Update a note's title and text. */
    fun updateNote(id: String, token: String, title: String, text: String) {
        val body = JSONObject()
        body.put("title", title)
        body.put("text", text)
        val conn = openConnection("/rest/v1/notes?id=eq.${encode(id)}", "PATCH", token)
        conn.doOutput = true
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        readResponse(conn)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
