package com.claybird.tvhub

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

data class Credentials(val server: String, val username: String, val password: String)

data class Category(val id: String, val name: String)

data class Channel(
    val id: Int,
    val name: String,
    val categoryId: String,
    val icon: String,
    val epgId: String,
    val archive: Boolean,
)

data class Movie(
    val id: Int,
    val name: String,
    val categoryId: String,
    val icon: String,
    val ext: String,
    val rating: String,
    val added: Long,
)

data class Show(
    val id: Int,
    val name: String,
    val categoryId: String,
    val icon: String,
    val rating: String,
    val plot: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String,
)

data class EpgEntry(val title: String, val start: Long, val end: Long, val description: String)

class XtreamException(message: String) : Exception(message)

/** Talks to an Xtream Codes panel (player_api.php) and builds stream URLs. */
class XtreamApi(private val creds: Credentials) {

    private val base: String = run {
        var s = creds.server.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s.trimEnd('/')
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(action: String?, extra: String = ""): String {
        val a = if (action == null) "" else "&action=$action"
        return "$base/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}$a$extra"
    }

    fun liveUrl(id: Int) = "$base/live/${enc(creds.username)}/${enc(creds.password)}/$id.m3u8"
    fun liveTsUrl(id: Int) = "$base/live/${enc(creds.username)}/${enc(creds.password)}/$id.ts"
    fun movieUrl(id: Int, ext: String) =
        "$base/movie/${enc(creds.username)}/${enc(creds.password)}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) =
        "$base/series/${enc(creds.username)}/${enc(creds.password)}/$id.${ext.ifBlank { "mp4" }}"

    /** Opens the provider's full XMLTV guide. The caller reads and closes it. */
    fun openXmltv(): HttpURLConnection {
        val conn = URL("$base/xmltv.php?username=${enc(creds.username)}&password=${enc(creds.password)}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        return conn
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        try {
            val code = conn.responseCode
            if (code != 200) throw XtreamException("Server answered HTTP $code")
            val raw = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            val out = ByteArrayOutputStream()
            raw.use { it.copyTo(out) }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    /** Raw JSON text for an API action, so callers can cache it. */
    fun raw(action: String?, extra: String = ""): String = fetch(apiUrl(action, extra))

    /** Checks the login. Returns a short description like "Active until 2026-12-01, 2 connections". */
    fun authenticate(): String {
        val text = raw(null)
        val json = try { JSONObject(text) } catch (e: Exception) { throw XtreamException("Not an Xtream server (unexpected reply)") }
        val user = json.optJSONObject("user_info") ?: throw XtreamException("Login failed: no user info in the reply")
        if (user.optInt("auth", 0) != 1) throw XtreamException("Login rejected: wrong username or password")
        val status = user.optString("status", "")
        val exp = user.optString("exp_date", "")
        val maxConn = user.optString("max_connections", "")
        val expText = exp.toLongOrNull()?.let { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(it * 1000)) }
        return buildString {
            append(status.ifBlank { "Logged in" })
            if (expText != null) append(", expires $expText")
            if (maxConn.isNotBlank()) append(", $maxConn connection(s)")
        }
    }

    fun shortEpg(streamId: Int): List<EpgEntry> {
        val text = fetch(apiUrl("get_short_epg", "&stream_id=$streamId&limit=4"))
        val arr = JSONObject(text).optJSONArray("epg_listings") ?: return emptyList()
        val list = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull() ?: continue
            val end = o.optString("stop_timestamp").toLongOrNull() ?: continue
            list += EpgEntry(decode(o.optString("title")), start, end, decode(o.optString("description")))
        }
        return list
    }

    fun seriesEpisodes(seriesId: Int): List<Episode> {
        val json = JSONObject(fetch(apiUrl("get_series_info", "&series_id=$seriesId")))
        val eps = json.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (key in eps.keys()) {
            val arr = eps.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val info = o.optJSONObject("info")
                out += Episode(
                    id = o.optString("id"),
                    season = o.optString("season", key).toIntOrNull() ?: key.toIntOrNull() ?: 0,
                    number = o.optString("episode_num").toIntOrNull() ?: (i + 1),
                    title = o.optString("title").ifBlank { "Episode ${i + 1}" },
                    ext = o.optString("container_extension", "mp4"),
                    plot = info?.optString("plot").orEmpty(),
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    companion object {
        private fun decode(s: String): String = try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            s
        }

        private fun arrayOf(text: String): JSONArray = try { JSONArray(text) } catch (e: Exception) { JSONArray() }

        fun parseCategories(text: String): List<Category> {
            val arr = arrayOf(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Category(o.optString("category_id"), o.optString("category_name").trim())
            }
        }

        fun parseChannels(text: String): List<Channel> {
            val arr = arrayOf(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Channel(
                    id = o.optInt("stream_id"),
                    name = o.optString("name").trim(),
                    categoryId = o.optString("category_id"),
                    icon = o.optString("stream_icon"),
                    epgId = o.optString("epg_channel_id"),
                    archive = o.optInt("tv_archive", 0) == 1,
                )
            }
        }

        fun parseMovies(text: String): List<Movie> {
            val arr = arrayOf(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Movie(
                    id = o.optInt("stream_id"),
                    name = o.optString("name").trim(),
                    categoryId = o.optString("category_id"),
                    icon = o.optString("stream_icon"),
                    ext = o.optString("container_extension", "mp4"),
                    rating = o.optString("rating"),
                    added = o.optString("added").toLongOrNull() ?: 0L,
                )
            }
        }

        fun parseShows(text: String): List<Show> {
            val arr = arrayOf(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Show(
                    id = o.optInt("series_id"),
                    name = o.optString("name").trim(),
                    categoryId = o.optString("category_id"),
                    icon = o.optString("cover"),
                    rating = o.optString("rating"),
                    plot = o.optString("plot"),
                )
            }
        }
    }
}
