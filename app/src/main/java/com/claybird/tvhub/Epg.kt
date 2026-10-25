package com.claybird.tvhub

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class Prog(val start: Long, val end: Long, val title: String, val desc: String)

/**
 * The program guide. Downloads the provider's XMLTV file, keeps only what is airing from a couple
 * of hours ago to two days ahead for channels you actually have, and caches that compactly.
 */
object Epg {
    private lateinit var appContext: Context
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    private var data: Map<String, Array<Prog>> = emptyMap()
    /** Each playlist's guide, by playlist id. [data] merges them, keyed by "slot|epgId". */
    private val parts = HashMap<String, Map<String, Array<Prog>>>()

    private fun rebuild() {
        val m = HashMap<String, Array<Prog>>()
        Repo.enabled.forEachIndexed { slot, pl -> parts[pl.id]?.forEach { (k, v) -> m["$slot|$k"] = v } }
        data = m
        ready = m.isNotEmpty()
        version++
    }

    /** Shown on screen. */
    var status by mutableStateOf("")
    /** Bumps whenever new guide data arrives, so screens redraw. */
    var version by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    private var loading = false

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun forChannel(c: Channel): Array<Prog>? =
        if (c.epgId.isBlank()) null else data["${c.id / Repo.SLOT}|${c.epgId.lowercase()}"]

    fun current(c: Channel, now: Long): Prog? =
        forChannel(c)?.firstOrNull { it.start <= now && it.end > now }

    private fun cacheFile(id: String) = File(appContext.filesDir, "epg_$id.bin.gz")

    fun clear(deleteFile: Boolean = true) {
        if (deleteFile) Repo.playlists.forEach { cacheFile(it.id).delete() }
        parts.clear()
        data = emptyMap()
        ready = false
        status = ""
        version++
    }

    suspend fun load(force: Boolean) {
        if (!Repo.loaded) return
        // One load at a time; a switch of playlists clears everything and starts again.
        if (loading) return
        loading = true
        try {
            for (pl in Repo.enabled) {
                val channels = Repo.rawFor(pl.id)?.lv ?: continue
                val api = Repo.apiForPlaylist(pl.id) ?: continue
                val id = pl.id
                withContext(Dispatchers.IO) {
                    val f = cacheFile(id)
                    val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
                    if (!force && fresh && readCache(f, id)) return@withContext
                    try {
                        if (Repo.isEnabled(id)) status = "Guide: downloading ${pl.name} (the first time can take a minute)..."
                        download(f, id, api, channels)
                    } catch (e: Throwable) {
                        val msg = if (e is OutOfMemoryError) "not enough memory" else e.message
                        if (f.exists() && readCache(f, id)) {
                            if (Repo.isEnabled(id)) status = "Guide: couldn't refresh (${pl.name}: $msg); using saved copy"
                        } else if (Repo.isEnabled(id)) {
                            status = "Guide: couldn't load (${pl.name}: $msg)"
                        }
                    }
                }
            }
        } finally {
            loading = false
        }
    }

    private fun download(cache: File, id: String, api: XtreamApi, channels: List<Channel>) {
        val wanted = HashSet<String>()
        for (c in channels) if (c.epgId.isNotBlank()) wanted.add(c.epgId.lowercase())
        val now = System.currentTimeMillis() / 1000
        val minT = now - 12 * 3600
        val maxT = now + 48 * 3600

        val conn = api.openXmltv()
        val result = HashMap<String, ArrayList<Prog>>()
        try {
            if (conn.responseCode != 200) throw XtreamException("Guide server answered HTTP ${conn.responseCode}")
            val raw = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            val parser = Xml.newPullParser()
            parser.setInput(BufferedInputStream(raw, 64 * 1024), null)
            var chan = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var keep = false
            var count = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            chan = (parser.getAttributeValue(null, "channel") ?: "").lowercase()
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                            desc = ""
                            keep = chan in wanted && stop > minT && start < maxT && start > 0
                        }
                        "title" -> if (keep) { if (title.isEmpty()) title = parser.nextText().trim() }
                        "desc" -> if (keep) desc = parser.nextText().trim().take(240)
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme" && keep) {
                    result.getOrPut(chan) { ArrayList() }.add(Prog(start, stop, title, desc))
                    keep = false
                    count++
                    if (count % 20000 == 0 && Repo.isEnabled(id)) status = "Guide: reading... $count shows"
                }
                event = parser.next()
            }
        } finally {
            conn.disconnect()
        }
        val built = HashMap<String, Array<Prog>>(result.size * 2)
        for ((k, v) in result) built[k] = v.sortedBy { it.start }.toTypedArray()
        writeCache(cache, built)
        // The playlist may have been switched while this downloaded: keep the file, don't show it.
        if (!Repo.isEnabled(id)) return
        parts[id] = built
        rebuild()
        status = if (data.isEmpty()) "Guide: the provider sent no guide data for your channels"
        else "Guide ready: ${data.size} channels"
    }

    private fun writeCache(f: File, map: Map<String, Array<Prog>>) {
        runCatching {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(f.outputStream()), 64 * 1024)).use { out ->
                out.writeInt(map.size)
                for ((k, arr) in map) {
                    out.writeUTF(k)
                    out.writeInt(arr.size)
                    for (p in arr) {
                        out.writeLong(p.start); out.writeLong(p.end)
                        out.writeUTF(p.title.take(300)); out.writeUTF(p.desc)
                    }
                }
            }
        }.onFailure { f.delete() }
    }

    private fun readCache(f: File, id: String): Boolean = try {
        val map = HashMap<String, Array<Prog>>()
        DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream()), 64 * 1024)).use { inp ->
            val n = inp.readInt()
            repeat(n) {
                val k = inp.readUTF()
                val m = inp.readInt()
                map[k] = Array(m) { Prog(inp.readLong(), inp.readLong(), inp.readUTF(), inp.readUTF()) }
            }
        }
        if (Repo.isEnabled(id)) {
            parts[id] = map
            rebuild()
            status = "Guide ready: ${data.size} channels"
        }
        map.isNotEmpty()
    } catch (e: Throwable) {
        f.delete()
        false
    }

    /** XMLTV time "20261024120000 +0000" to epoch seconds. */
    private fun parseTime(s: String?): Long {
        if (s == null || s.length < 14) return 0
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = s.substring(12, 14).toInt()
            var offset = 0L
            val rest = s.substring(14).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val oh = rest.substring(1, 3).toInt()
                val om = rest.substring(3, 5).toInt()
                offset = (oh * 3600L + om * 60L) * (if (rest[0] == '-') -1 else 1)
            }
            daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + se - offset
        } catch (e: Exception) {
            0
        }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
}
