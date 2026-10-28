package com.claybird.tvhub

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

class League(val path: String, val label: String, val sport: Sport, val weight: Int)

val LEAGUES = listOf(
    League("football/nfl", "NFL", Sport.Football, 50),
    League("football/college-football", "College Football", Sport.Football, 45),
    League("basketball/nba", "NBA", Sport.Basketball, 40),
    League("basketball/mens-college-basketball", "College Basketball", Sport.Basketball, 25),
    League("basketball/womens-college-basketball", "Women's College Basketball", Sport.Basketball, 12),
    League("basketball/wnba", "WNBA", Sport.Basketball, 15),
    League("baseball/mlb", "MLB", Sport.Baseball, 30),
    League("hockey/nhl", "NHL", Sport.Hockey, 28),
    League("soccer/eng.1", "Premier League", Sport.Soccer, 40),
    League("soccer/uefa.champions", "Champions League", Sport.Soccer, 45),
    League("soccer/usa.1", "MLS", Sport.Soccer, 22),
    League("soccer/esp.1", "La Liga", Sport.Soccer, 28),
    League("soccer/ger.1", "Bundesliga", Sport.Soccer, 26),
    League("soccer/ita.1", "Serie A", Sport.Soccer, 26),
    League("soccer/fra.1", "Ligue 1", Sport.Soccer, 20),
    League("soccer/uefa.europa", "Europa League", Sport.Soccer, 28),
    League("soccer/mex.1", "Liga MX", Sport.Soccer, 18),
    League("mma/ufc", "UFC", Sport.Combat, 35),
    League("racing/f1", "Formula 1", Sport.Motorsport, 32),
    League("racing/nascar-premier", "NASCAR", Sport.Motorsport, 26),
    League("golf/pga", "PGA Tour", Sport.Golf, 22),
)

class Team(val displayName: String, val shortName: String, val location: String, val name: String, val abbr: String,
           val rank: Int, val score: String)

/** A real scheduled game from the sports scoreboard feed. */
class SchedGame(
    val id: String,
    val league: League,
    val title: String,
    val away: Team?,
    val home: Team?,
    val start: Long,
    val state: String, // pre, in, post
    val detail: String,
    val networks: List<String>,
    val spread: Double?,
    val conference: Boolean,
) {
    val live: Boolean get() = state == "in"
}

/**
 * Today's and nearby games from ESPN's public scoreboard feed (unofficial, undocumented, could change).
 * Used only to know what games exist, when, and on which networks; the video comes from your playlist.
 */
object ScheduleFeed {
    private var cache: Pair<Long, List<SchedGame>>? = null
    private const val MAX_AGE_MS = 3 * 60_000L

    suspend fun games(force: Boolean = false): List<SchedGame> {
        val now = System.currentTimeMillis()
        cache?.let { if (!force && now - it.first < MAX_AGE_MS) return it.second }
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val today = LocalDate.now()
        // One request per league per day: a date range is rejected by some leagues.
        val days = listOf(-1L, 0L, 1L).map { today.plusDays(it).format(fmt) }
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val lastError = java.util.concurrent.atomic.AtomicReference("")
        val all = coroutineScope {
            LEAGUES.flatMap { l -> days.map { d -> l to d } }.map { (l, d) ->
                async(Dispatchers.IO) {
                    runCatching { fetch(l, d) }.getOrElse { e -> errors.incrementAndGet(); lastError.set("${l.label}: ${e.message}"); emptyList() }
                }
            }.awaitAll()
        }.flatten().distinctBy { it.league.path + it.id }
        failures = errors.get()
        lastFailure = lastError.get()
        val enriched = withContext(Dispatchers.IO) { LsuTv.enrich(all) }
        cache = now to enriched
        return enriched
    }

    /** How many league-day requests failed on the last load, and the last reason. */
    var failures = 0
        private set
    var lastFailure = ""
        private set

    private fun fetch(l: League, range: String): List<SchedGame> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard?dates=$range&limit=300"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.use { s -> val o = ByteArrayOutputStream(); s.copyTo(o); o.toString("UTF-8") }
            val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SchedGame>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                parse(l, ev)?.let { out += it }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(l: League, ev: JSONObject): SchedGame? {
        val start = runCatching { OffsetDateTime.parse(ev.getString("date")).toEpochSecond() }.getOrNull() ?: return null
        val type = ev.optJSONObject("status")?.optJSONObject("type")
        val state = type?.optString("state").orEmpty()
        if (state == "post") return null
        val detail = type?.optString("shortDetail").orEmpty()
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
        var away: Team? = null
        var home: Team? = null
        val competitors = comp?.optJSONArray("competitors")
        if (competitors != null) {
            for (i in 0 until competitors.length()) {
                val c = competitors.optJSONObject(i) ?: continue
                val t = c.optJSONObject("team") ?: continue // athletes (golf, racing) have no team
                val rank = c.optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
                val team = Team(
                    t.optString("displayName"), t.optString("shortDisplayName"), t.optString("location"),
                    t.optString("name"), t.optString("abbreviation"), rank, c.optString("score"),
                )
                if (c.optString("homeAway") == "home") home = team else away = team
            }
        }
        val nets = LinkedHashSet<String>()
        comp?.optJSONArray("broadcasts")?.let { b ->
            for (i in 0 until b.length()) {
                val names = b.optJSONObject(i)?.optJSONArray("names") ?: continue
                for (j in 0 until names.length()) nets += names.optString(j)
            }
        }
        val spread = comp?.optJSONArray("odds")?.optJSONObject(0)?.let { if (it.has("spread")) it.optDouble("spread") else null }
        return SchedGame(
            id = ev.optString("id"), league = l,
            title = ev.optString("name").ifBlank { ev.optString("shortName") },
            away = away, home = home, start = start, state = state, detail = detail,
            networks = nets.toList(), spread = spread?.takeIf { !it.isNaN() },
            conference = comp?.optBoolean("conferenceCompetition", false) ?: false,
        )
    }
}
