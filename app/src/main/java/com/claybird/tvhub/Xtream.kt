package com.claybird.tvhub

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
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
    val year: Int = 0,
)

data class Show(
    val id: Int,
    val name: String,
    val categoryId: String,
    val icon: String,
    val rating: String,
    val plot: String,
    val year: Int = 0,
    val added: Long = 0L,
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
    /** Catch-up (timeshift) link for a past show on a channel that keeps an archive. */
    fun catchupUrl(streamId: Int, startSec: Long, durationMin: Int, zoneId: String?): String {
        val zone = runCatching { java.time.ZoneId.of(zoneId ?: "UTC") }.getOrDefault(java.time.ZoneOffset.UTC)
        val t = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
            .format(java.time.Instant.ofEpochSecond(startSec).atZone(zone))
        return "$base/timeshift/${enc(creds.username)}/${enc(creds.password)}/$durationMin/$t/$streamId.m3u8"
    }

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

    private fun <T> open(url: String, read: (InputStream) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        try {
            val code = conn.responseCode
            if (code != 200) throw XtreamException("Server answered HTTP $code")
            val raw = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            return raw.use(read)
        } finally {
            conn.disconnect()
        }
    }

    /** For small replies only (login, EPG snippets, one series). Catalogs go through [download]. */
    private fun fetch(url: String): String = open(url) { input ->
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        out.toString("UTF-8")
    }

    /** Raw JSON text for a small API action. */
    fun raw(action: String?, extra: String = ""): String = fetch(apiUrl(action, extra))

    /**
     * Streams an API action's reply straight into [to] without holding it in memory. Catalogs
     * (every channel/movie/show) can be well over 100 MB on big providers. [to] is only replaced
     * once the whole reply has arrived, so a dropped connection leaves the old copy intact.
     */
    fun download(action: String, to: File) {
        val tmp = File(to.path + ".part")
        try {
            open(apiUrl(action)) { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(to)) throw XtreamException("Couldn't save ${to.name}")
        } finally {
            tmp.delete()
        }
    }

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
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    companion object {
        private fun decode(s: String): String = try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            s
        }

        /**
         * Reads a top-level JSON array of objects one item at a time, so only the results stay in
         * memory, never the whole reply. Each object is handed over as its plain fields in text form
         * (nested objects/arrays are skipped); the map is reused, so [make] must not keep it.
         * Anything other than an array (an error object, an empty reply) gives an empty list.
         */
        private fun <T> readObjects(file: File, make: (Map<String, String>) -> T?): List<T> {
            val out = ArrayList<T>()
            try {
                JsonReader(file.bufferedReader()).use { r ->
                    r.isLenient = true
                    if (r.peek() != JsonToken.BEGIN_ARRAY) return out
                    r.beginArray()
                    val fields = HashMap<String, String>()
                    while (r.hasNext()) {
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                        fields.clear()
                        r.beginObject()
                        while (r.hasNext()) {
                            val name = r.nextName()
                            when (r.peek()) {
                                JsonToken.STRING, JsonToken.NUMBER -> fields[name] = r.nextString()
                                JsonToken.BOOLEAN -> fields[name] = r.nextBoolean().toString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        make(fields)?.let { out += it }
                    }
                }
            } catch (e: Exception) {
                // Malformed or cut short: keep what was read so far.
            }
            return out
        }

        private fun Map<String, String>.str(key: String, fallback: String = "") = this[key] ?: fallback
        private fun Map<String, String>.int(key: String) =
            this[key]?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: 0

        private val yearRx = Regex("(?:\\(|[-\u2013]\\s*|\\b)((?:19|20)\\d{2})\\)?\\s*$|\\(((?:19|20)\\d{2})\\)")
        /** A release year from a field like "2007-06-18" or from the title: "Heartland (2007)" or "28 Days - 2000". 0 if unknown. */
        internal fun yearOf(name: String, field: String): Int {
            Regex("^((?:19|20)\\d{2})").find(field.trim())?.let { return it.groupValues[1].toInt() }
            yearRx.find(name.trim())?.let { m -> return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: 0 }
            return 0
        }

        fun parseCategories(file: File): List<Category> =
            readObjects(file) { o -> Category(o.str("category_id"), o.str("category_name").trim()) }
                .distinctBy { it.id }

        fun parseChannels(file: File): List<Channel> =
            readObjects(file) { o ->
                Channel(
                    id = o.int("stream_id"),
                    name = o.str("name").trim(),
                    categoryId = o.str("category_id"),
                    icon = o.str("stream_icon"),
                    epgId = o.str("epg_channel_id"),
                    archive = o.int("tv_archive") == 1,
                )
            }.distinctBy { it.id }

        fun parseMovies(file: File): List<Movie> =
            readObjects(file) { o ->
                Movie(
                    id = o.int("stream_id"),
                    name = o.str("name").trim(),
                    categoryId = o.str("category_id"),
                    icon = o.str("stream_icon"),
                    ext = o.str("container_extension", "mp4"),
                    rating = o.str("rating"),
                    added = o.str("added").toLongOrNull() ?: 0L,
                    year = yearOf(o.str("name"), o.str("year")),
                )
            }.distinctBy { it.id }

        fun parseShows(file: File): List<Show> =
            readObjects(file) { o ->
                Show(
                    id = o.int("series_id"),
                    name = o.str("name").trim(),
                    categoryId = o.str("category_id"),
                    icon = o.str("cover"),
                    rating = o.str("rating"),
                    plot = o.str("plot"),
                    year = yearOf(o.str("name"), o.str("releaseDate").ifBlank { o.str("release_date") }.ifBlank { o.str("year") }),
                    added = o.str("last_modified").toLongOrNull() ?: 0L,
                )
            }.distinctBy { it.id }
    }
}
