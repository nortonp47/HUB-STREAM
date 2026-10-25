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
        if (c.epgId.isBlank()) null else data[c.epgId.lowercase()]

    fun current(c: Channel, now: Long): Prog? =
        forChannel(c)?.firstOrNull { it.start <= now && it.end > now }

    private fun cacheFile() = File(appContext.filesDir, "epg_${Repo.activeId}.bin.gz")

    fun clear(deleteFile: Boolean = true) {
        data = emptyMap()
        ready = false
        status = ""
        if (deleteFile) cacheFile().delete()
        version++
    }

    suspend fun load(force: Boolean) {
        if (loading || Repo.api == null || !Repo.loaded) return
        loading = true
        try {
            withContext(Dispatchers.IO) {
                val f = cacheFile()
                val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
                if (!force && fresh && readCache(f)) return@withContext
                try {
                    status = "Guide: downloading (the first time can take a minute)..."
                    download(f)
                } catch (e: Exception) {
                    if (f.exists() && readCache(f)) {
                        status = "Guide: couldn't refresh (${e.message}); using saved copy"
                    } else {
                        status = "Guide: couldn't load (${e.message})"
                    }
                }
            }
        } finally {
            loading = false
        }
    }

    private fun download(cache: File) {
        val api = Repo.api ?: return
        val wanted = HashSet<String>()
        for (c in Repo.live) if (c.epgId.isNotBlank()) wanted.add(c.epgId.lowercase())
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
                    if (count % 20000 == 0) status = "Guide: reading... $count shows"
                }
                event = parser.next()
            }
        } finally {
            conn.disconnect()
        }
        val built = HashMap<String, Array<Prog>>(result.size * 2)
        for ((k, v) in result) built[k] = v.sortedBy { it.start }.toTypedArray()
        data = built
        ready = built.isNotEmpty()
        version++
        status = if (built.isEmpty()) "Guide: the provider sent no guide data for your channels"
        else "Guide ready: ${built.size} channels"
        writeCache(cache, built)
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

    private fun readCache(f: File): Boolean = try {
        val map = HashMap<String, Array<Prog>>()
        DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream()), 64 * 1024)).use { inp ->
            val n = inp.readInt()
            repeat(n) {
                val k = inp.readUTF()
                val m = inp.readInt()
                map[k] = Array(m) { Prog(inp.readLong(), inp.readLong(), inp.readUTF(), inp.readUTF()) }
            }
        }
        data = map
        ready = map.isNotEmpty()
        version++
        status = "Guide ready: ${map.size} channels"
        map.isNotEmpty()
    } catch (e: Exception) {
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
