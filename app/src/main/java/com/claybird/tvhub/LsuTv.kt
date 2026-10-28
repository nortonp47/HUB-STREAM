package com.claybird.tvhub

import java.net.HttpURLConnection
import java.net.URL

/**
 * lsufootball.net/tvschedule.htm lists every college football game with every channel carrying it.
 * We read it as plain text and add its channels to ESPN's schedule data for the same game.
 */
object LsuTv {
    private const val URL_TV = "https://www.lsufootball.net/tvschedule.htm"
    private const val MAX_AGE_MS = 15 * 60_000L

    class Line(val teams: List<String>, val networks: List<String>)

    private var cache: Pair<Long, List<Line>>? = null

    /** Shown on the Sports tab: how many games the list gave and how many matched. */
    var note = ""
        private set

    private val timeRe = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?""", RegexOption.IGNORE_CASE)
    private val rankRe = Regex("""^\s*(#\s*\d+|\(\s*\d+\s*\))\s*""")

    private val nets = listOf(
        Regex("""\bESPN\s*\+|espn\+""", RegexOption.IGNORE_CASE) to "ESPN+",
        Regex("""\bESPN2\b""", RegexOption.IGNORE_CASE) to "ESPN2",
        Regex("""\bESPNU\b""", RegexOption.IGNORE_CASE) to "ESPNU",
        Regex("""\bESPN\b(?!\s*[2U+])""", RegexOption.IGNORE_CASE) to "ESPN",
        Regex("""\bFS1\b""", RegexOption.IGNORE_CASE) to "FS1",
        Regex("""\bFOX\b""", RegexOption.IGNORE_CASE) to "FOX",
        Regex("""\bABC\b""", RegexOption.IGNORE_CASE) to "ABC",
        Regex("""\bCBSSN\b|CBS Sports Network""", RegexOption.IGNORE_CASE) to "CBS Sports Network",
        Regex("""\bCBS\b(?!\s*S)""", RegexOption.IGNORE_CASE) to "CBS",
        Regex("""\bNBC\b""", RegexOption.IGNORE_CASE) to "NBC",
        Regex("""\bBTN\b|Big Ten Network""", RegexOption.IGNORE_CASE) to "BTN",
        Regex("""\bACC\s*N(etwork)?\b|\bACCN\b""", RegexOption.IGNORE_CASE) to "ACC Network",
        Regex("""\bSEC\s*N(etwork)?\b|\bSECN\b""", RegexOption.IGNORE_CASE) to "SEC Network",
        Regex("""\bTNT\b""", RegexOption.IGNORE_CASE) to "TNT",
        Regex("""\bUSA(\s*Net(work)?)?\b""", RegexOption.IGNORE_CASE) to "USA Network",
        Regex("""\bPeacock\b""", RegexOption.IGNORE_CASE) to "Peacock",
    )

    private fun toText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<\\s*(br|/p|/tr|/div|/li|/h\\d|/table)[^>]*>"), "\n")
        s = s.replace(Regex("(?i)<\\s*/t[dh]\\s*>"), "  ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&amp;", "&").replace("&nbsp;", " ").replace("&#160;", " ").replace("&#39;", "'").replace("&quot;", "\"")
        return s
    }

    fun parse(html: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in toText(html).lines()) {
            val line = raw.replace(Regex("\\s+"), " ").trim()
            if (line.length < 8) continue
            val t = timeRe.find(line) ?: continue
            val before = line.substring(0, t.range.first).trim()
            val after = line.substring(t.range.last + 1)
            val parts = Regex("""\s+(?:at|vs\.?|@)\s+""", RegexOption.IGNORE_CASE).split(before)
            if (parts.size != 2) continue
            val teams = parts.map { rankRe.replace(it, "").trim() }
            if (teams.any { it.isEmpty() || it.length > 40 }) continue
            val found = LinkedHashSet<String>()
            // "ESPN2 / ESPN Video": only the cell before the slash is the channel; the rest is a video link.
            val cell = after.substringBefore(" / ")
            for ((re, name) in nets) if (re.containsMatchIn(if (name == "ESPN+" || name == "Peacock") after else cell)) found += name
            if (found.isEmpty()) continue
            out += Line(teams, found.toList())
        }
        return out
    }

    private fun load(): List<Line> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.first < MAX_AGE_MS) return it.second }
        val conn = URL(URL_TV).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Hub/1.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(html).also { cache = now to it }
        } finally {
            conn.disconnect()
        }
    }

    private fun flat(s: String): String {
        val plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "").replace("miamiohio", "miamioh")
    }

    private fun same(lineTeam: String, t: Team): Boolean {
        val a = flat(lineTeam)
        if (a.isEmpty()) return false
        return listOf(t.location, t.shortName, t.displayName).map { flat(it) }.filter { it.length >= 3 }
            .any { it == a }
    }

    /** Adds the list's channels to matching college football games. Never throws. */
    fun enrich(games: List<SchedGame>): List<SchedGame> {
        val lines = runCatching { load() }.getOrElse { note = "LSU TV list: unavailable (${it.message})"; return games }
        var matched = 0
        val out = games.map { g ->
            if (g.league.path != "football/college-football" || g.away == null || g.home == null) return@map g
            val l = lines.firstOrNull { (same(it.teams[0], g.away) && same(it.teams[1], g.home)) || (same(it.teams[0], g.home) && same(it.teams[1], g.away)) }
                ?: return@map g
            matched++
            val merged = LinkedHashSet<String>(g.networks)
            merged += l.networks
            SchedGame(g.id, g.league, g.title, g.away, g.home, g.start, g.state, g.detail, merged.toList(), g.spread, g.conference)
        }
        note = "LSU TV list: ${lines.size} games read, $matched matched to the feed" + if (lines.isEmpty()) " (page format not recognized)" else ""
        return out
    }
}
