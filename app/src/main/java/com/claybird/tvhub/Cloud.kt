package com.claybird.tvhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * HUB account: sign in on any device and your playlists and favorites come with you.
 * Backed by a Supabase project; every row is locked to the signed-in user.
 * The key below is the project's public (publishable) key, which is meant to ship inside apps.
 */
object Cloud {
    private const val URL_BASE = "https://hovbbyirispeqkpgrznv.supabase.co"
    private const val KEY = "sb_publishable_jPgz8DY03Efnra8ShNlFtQ_KhS9l42L"
    private const val PREFS = "hub_account"

    private lateinit var appContext: Context
    var email by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("")
    private var access = ""
    private var refresh = ""
    private var expiresAt = 0L

    val signedIn: Boolean get() = email != null && refresh.isNotEmpty()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        access = p.getString("access", "") ?: ""
        refresh = p.getString("refresh", "") ?: ""
        expiresAt = p.getLong("expires", 0)
        email = p.getString("email", null)
    }

    private fun save() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("access", access).putString("refresh", refresh)
            .putLong("expires", expiresAt).putString("email", email).apply()
    }

    fun signOut() {
        access = ""; refresh = ""; expiresAt = 0; email = null
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---- HTTP ----

    private fun call(method: String, path: String, body: String? = null, bearer: String? = null, prefer: String? = null): String {
        val conn = URL(URL_BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("apikey", KEY)
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.setRequestProperty("Content-Type", "application/json")
        if (prefer != null) conn.setRequestProperty("Prefer", prefer)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                val out = ByteArrayOutputStream(); s.copyTo(out); out.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    val j = JSONObject(text)
                    j.optString("msg").ifBlank { j.optString("error_description") }
                        .ifBlank { j.optString("message") }.ifBlank { j.optString("error") }
                }.getOrDefault("")
                throw XtreamException(msg.ifBlank { "Server answered HTTP $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun adoptSession(json: JSONObject, mail: String?) {
        access = json.getString("access_token")
        refresh = json.getString("refresh_token")
        expiresAt = System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)
        if (mail != null) email = mail
        save()
    }

    /** Returns a message to show. Throws on failure. */
    suspend fun signIn(mail: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", mail).put("password", password).toString()
        val json = JSONObject(call("POST", "/auth/v1/token?grant_type=password", body))
        adoptSession(json, mail)
    }

    /** Returns true if signed in right away, false if the email must be confirmed first. */
    suspend fun signUp(mail: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", mail).put("password", password).toString()
        val json = JSONObject(call("POST", "/auth/v1/signup", body))
        if (json.has("access_token")) {
            adoptSession(json, mail)
            true
        } else false
    }

    private fun token(): String {
        if (refresh.isEmpty()) throw XtreamException("Not signed in")
        if (access.isNotEmpty() && System.currentTimeMillis() / 1000 < expiresAt - 60) return access
        val body = JSONObject().put("refresh_token", refresh).toString()
        val json = JSONObject(call("POST", "/auth/v1/token?grant_type=refresh_token", body))
        adoptSession(json, null)
        return access
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- data ----

    fun pullPlaylists(): List<Playlist> {
        val arr = JSONArray(call("GET", "/rest/v1/playlists?select=id,name,server,username,password,position&order=position.asc,created_at.asc", bearer = token()))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Playlist(o.getString("id"), o.getString("name"), o.getString("server"), o.getString("username"), o.getString("password"))
        }
    }

    fun pushPlaylists(list: List<Playlist>) {
        if (list.isEmpty()) return
        val arr = JSONArray()
        list.forEachIndexed { i, p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("server", p.server)
                .put("username", p.username).put("password", p.password).put("position", i))
        }
        call("POST", "/rest/v1/playlists?on_conflict=id", arr.toString(), token(), "resolution=merge-duplicates,return=minimal")
    }

    fun deletePlaylist(id: String) {
        call("DELETE", "/rest/v1/playlists?id=eq.${enc(id)}", bearer = token())
    }

    fun pullFavorites(): Set<String> {
        val arr = JSONArray(call("GET", "/rest/v1/favorites?select=key&limit=10000", bearer = token()))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("key") }.toHashSet()
    }

    fun pushFavorites(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val arr = JSONArray()
        keys.forEach { arr.put(JSONObject().put("key", it)) }
        call("POST", "/rest/v1/favorites?on_conflict=user_id,key", arr.toString(), token(), "resolution=merge-duplicates,return=minimal")
    }

    fun pullHistory(): List<HistoryItem> {
        val arr = JSONArray(call("GET", "/rest/v1/history?select=*&order=updated_at.desc&limit=300", bearer = token()))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(
                o.getString("key"), o.optString("playlist_id"), o.getString("kind"), o.getString("item_id"),
                o.getString("title"), o.optString("icon"), o.optString("ext"),
                o.optLong("position_ms"), o.optLong("duration_ms"),
                runCatching { java.time.OffsetDateTime.parse(o.getString("updated_at")).toInstant().toEpochMilli() }.getOrDefault(0L),
            )
        }
    }

    fun pushHistory(items: Collection<HistoryItem>) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.forEach { h ->
            arr.put(JSONObject().put("key", h.key).put("kind", h.kind).put("item_id", h.itemId).put("title", h.title)
                .put("icon", h.icon).put("ext", h.ext).put("position_ms", h.positionMs).put("duration_ms", h.durationMs)
                .put("playlist_id", h.playlistId).put("updated_at", java.time.Instant.ofEpochMilli(h.updatedAt).toString()))
        }
        call("POST", "/rest/v1/history?on_conflict=user_id,key", arr.toString(), token(), "resolution=merge-duplicates,return=minimal")
    }

    fun pullCuration(): List<Pair<String, String>> {
        val arr = JSONArray(call("GET", "/rest/v1/curation?select=playlist_id,data&limit=100", bearer = token()))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("playlist_id") to o.getString("data") } }
    }

    fun pushCuration(playlistId: String, data: String) {
        val body = JSONObject().put("playlist_id", playlistId).put("data", data).put("updated_at", java.time.Instant.now().toString()).toString()
        call("POST", "/rest/v1/curation?on_conflict=user_id,playlist_id", "[$body]", token(), "resolution=merge-duplicates,return=minimal")
    }

    fun deleteFavorite(key: String) {
        call("DELETE", "/rest/v1/favorites?key=eq.${enc(key)}", bearer = token())
    }
}
