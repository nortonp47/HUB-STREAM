package com.claybird.tvhub

import kotlin.math.abs

class GameCard(val game: SchedGame, val streams: List<SportItem>, val importance: Int)

class SportsResult(val cards: List<GameCard>, val leftovers: List<Game>, val diag: String = "")

/**
 * Matches real scheduled games to the channels in your playlist.
 * 1. A channel's guide entry (or the channel name) mentions both teams: strong match.
 * 2. A national network carries the game and the channel is that network: network match.
 * Games with no channel are kept so you can see what you're missing.
 */
object SportsMatcher {
    /** Call letters of local stations to prefer (Huntsville, AL: ABC, CBS, NBC, FOX). */
    private val localStations = listOf("waay", "whnt", "waff", "wzdx")

    private val stop = setOf(
        "state", "university", "college", "the", "and", "city", "united", "saint", "north", "south", "east", "west",
        "fighting", "golden", "team", "football", "basketball", "baseball", "hockey", "soccer", "live", "game",
    )
    private val qualityTokens = setOf("hd", "fhd", "uhd", "sd", "4k", "raw", "hevc", "backup", "us", "usa", "east", "west", "feed", "live", "1080p", "720p", "h265")
    private val norm = Regex("[^a-z0-9]+")

    private fun normalize(s: String): String =
        s.lowercase().replace("&", " and ").replace("+", "plus").replace("'", "").replace(norm, " ").trim()

    private fun words(s: String) = normalize(s).split(' ').filter { it.length >= 4 && it !in stop }

    private fun teamWords(t: Team): Set<String> = (words(t.location) + words(t.name) + words(t.shortName)).toSet()

    private fun teamMatches(paddedText: String, t: Team): Boolean {
        val phrases = listOf(t.displayName, t.location, t.name, t.shortName).map { normalize(it) }.filter { it.length >= 4 }
        return phrases.any { paddedText.contains(" $it ") }
    }

    private class Entry(val channel: Channel, val prog: Prog?, val text: String)

    /** national single-feed networks, by the key a channel name reduces to. */
    private val netAlias = mapOf(
        "espn" to "espn", "espn2" to "espn2", "espnu" to "espnu", "espnews" to "espnews", "espnplus" to "espnplus",
        "abc" to "abc", "cbs" to "cbs", "nbc" to "nbc", "fox" to "fox", "fs1" to "fs1", "fs2" to "fs2",
        "tnt" to "tnt", "tbs" to "tbs", "trutv" to "trutv", "nflnetwork" to "nflnetwork", "nfln" to "nflnetwork",
        "accnetwork" to "acc", "accn" to "acc", "secnetwork" to "sec", "secn" to "sec", "bigtennetwork" to "btn", "btn" to "btn",
        "cbssportsnetwork" to "cbssn", "cbssn" to "cbssn", "usanetwork" to "usa", "peacock" to "peacock",
        "nbatv" to "nbatv", "mlbnetwork" to "mlbnetwork", "nhlnetwork" to "nhlnetwork", "golfchannel" to "golf",
    )
    private val broadcastNets = setOf("abc", "cbs", "nbc", "fox")
    private val notBroadcast = setOf("news", "sports", "sport", "business", "deportes", "movies", "life", "soccer", "weather", "kids")

    private fun networkKey(name: String): String? = netAlias[normalize(name).replace(" ", "")]

    /** Reduces a channel name to the network key it carries, or null. Second value: local call letters matched. */
    private fun channelNetwork(ch: Channel): Pair<String, Boolean>? {
        val tokens = normalize(ch.name.replace(Regex("^[A-Za-z]{2,3}\\s*[|:]\\s*"), "")).split(' ').filter { it.isNotEmpty() && it !in qualityTokens }
        if (tokens.isEmpty()) return null
        val compact = tokens.joinToString("")
        netAlias[compact]?.let { return it to false }
        // ESPN+ event channels: "ESPN+ 03"
        if (tokens[0] == "espnplus") return "espnplus" to false
        // local affiliates: contain exactly the network token and no cable-network words
        for (b in broadcastNets) {
            if (b in tokens && tokens.none { it in notBroadcast }) {
                val local = tokens.any { t -> localStations.any { it == t } }
                return b to local
            }
        }
        return null
    }

    fun build(now: Long, games: List<SchedGame>, items: List<SportItem>): SportsResult {
        if (games.isEmpty()) return SportsResult(emptyList(), Games.group(items), "Game feed returned nothing (offline or blocked), using guide keywords only.")
        val horizon = now + 8 * 3600
        val relevant = games.filter { (it.live || it.start in (now - 3600)..horizon) }

        // Words that appear in team names today, so we only index guide entries that could matter.
        val teamWordSet = HashSet<String>()
        for (g in relevant) { g.away?.let { teamWordSet += teamWords(it) }; g.home?.let { teamWordSet += teamWords(it) } }

        val index = HashMap<String, MutableList<Entry>>()
        val netChannels = HashMap<String, MutableList<Pair<Channel, Boolean>>>()
        for (ch in Repo.live) {
            val nameText = normalize(ch.name)
            val nameWords = nameText.split(' ').filter { it.length >= 4 && it in teamWordSet }
            if (nameWords.isNotEmpty()) {
                val e = Entry(ch, null, nameText)
                for (w in nameWords.toSet()) index.getOrPut(w) { ArrayList() }.add(e)
            }
            val progs = Epg.forChannel(ch)
            if (progs != null) {
                for (p in progs) {
                    if (p.end <= now - 3600) continue
                    if (p.start > horizon + 4 * 3600) break
                    val text = normalize(p.title + " " + p.desc + " " + ch.name)
                    val ws = text.split(' ').filter { it.length >= 4 && it in teamWordSet }
                    if (ws.isEmpty()) continue
                    val e = Entry(ch, p, text)
                    for (w in ws.toSet()) index.getOrPut(w) { ArrayList() }.add(e)
                }
            }
            channelNetwork(ch)?.let { (key, local) -> netChannels.getOrPut(key) { ArrayList() }.add(ch to local) }
        }

        val catNames = HashMap<String, String>()
        for (c in Repo.liveCategories) catNames[c.id] = c.name

        // How many games claim each national network at about the same time (ambiguity check).
        fun overlaps(a: SchedGame, b: SchedGame) = abs(a.start - b.start) < 3 * 3600
        val claims = HashMap<SchedGame, List<String>>()
        for (g in relevant) claims[g] = g.networks.mapNotNull { networkKey(it) }

        val cards = ArrayList<GameCard>()
        val ambiguous = HashMap<String, Int>()
        val matchedChannels = HashSet<Int>()
        for (g in relevant) {
            val found = LinkedHashMap<Int, SportItem>() // by channel id
            val strong = HashSet<Int>()
            val a = g.away
            val h = g.home
            if (a != null && h != null) {
                val seen = HashSet<Entry>()
                for (w in teamWords(a)) for (e in index[w].orEmpty()) {
                    if (!seen.add(e)) continue
                    if (e.prog != null && !(e.prog.start < g.start + 4 * 3600 && e.prog.end > g.start - 1800)) continue
                    val padded = " ${e.text} "
                    if (teamMatches(padded, a) && teamMatches(padded, h)) {
                        val prog = e.prog ?: Prog(g.start, g.start + 3 * 3600, g.title, "")
                        found.getOrPut(e.channel.id) { SportItem(e.channel, prog, g.league.sport, g.live, catNames[e.channel.categoryId].orEmpty()) }
                        strong += e.channel.id
                    }
                }
            } else {
                // Events without teams (UFC card, a race): match the event name against guide entries.
                val key = words(g.title).take(4)
                if (key.size >= 2) {
                    val seen = HashSet<Int>()
                    for (ch in Repo.live) {
                        val progs = Epg.forChannel(ch) ?: continue
                        for (p in progs) {
                            if (!(p.start < g.start + 4 * 3600 && p.end > g.start - 1800)) continue
                            val t = " ${normalize(p.title + " " + ch.name)} "
                            if (key.all { t.contains(" $it ") } && seen.add(ch.id)) {
                                found[ch.id] = SportItem(ch, p, g.league.sport, g.live, catNames[ch.categoryId].orEmpty())
                                strong += ch.id
                            }
                        }
                    }
                }
            }
            // National network channels.
            for (key in claims[g].orEmpty().distinct()) {
                if (key == "espnplus" || key == "peacock") continue // only matched through team names above
                // Two games on the same network at once: can't tell which, so only team evidence counts.
                val rivals = relevant.count { it !== g && overlaps(it, g) && claims[it]?.contains(key) == true }
                if (rivals > 0) { ambiguous.merge(key, 1, Int::plus); continue }
                for ((ch, _) in netChannels[key].orEmpty()) {
                    if (found.containsKey(ch.id)) continue
                    val prog = Prog(g.start, g.start + 3 * 3600, g.title, "")
                    found[ch.id] = SportItem(ch, prog, g.league.sport, g.live, catNames[ch.categoryId].orEmpty())
                }
            }
            val ranked = found.values.sortedByDescending { item ->
                var s = Games.score(item)
                if (item.channel.id in strong) s += 15
                channelNetwork(item.channel)?.let { (_, local) -> if (local) s += 25 }
                s
            }
            ranked.forEach { matchedChannels += it.channel.id }
            cards += GameCard(g, ranked, importance(g))
        }
        // Games the feed doesn't cover (cricket, rugby, ...), from the guide keywords.
        val leftovers = Games.group(items.filter { it.channel.id !in matchedChannels && it.sport !in coveredSports })
        val live = relevant.count { it.live }
        val withStreams = cards.count { it.streams.isNotEmpty() }
        val diag = "Feed: ${games.size} games ($live live now). Found channels for $withStreams of ${cards.size}." +
            (if (ambiguous.isNotEmpty()) " Same-time games on one network, skipped: " + ambiguous.entries.joinToString { "${it.key} x${it.value}" } else "")
        return SportsResult(cards, leftovers, diag)
    }

    private val coveredSports: Set<Sport> = LEAGUES.map { it.sport }.toSet()

    /** How much a game matters, for ordering. */
    fun importance(g: SchedGame): Int {
        var s = g.league.weight
        val ranks = listOfNotNull(g.away?.rank, g.home?.rank).filter { it in 1..25 }
        if (ranks.isNotEmpty()) s += 20 + (26 - ranks.min())
        if (ranks.size == 2) s += 25
        g.spread?.let { if (abs(it) <= 3.0) s += 8 }
        if (g.conference) s += 3
        if (g.live) {
            val a = g.away?.score?.toIntOrNull()
            val h = g.home?.score?.toIntOrNull()
            if (a != null && h != null) {
                val close = when (g.league.sport) {
                    Sport.Football -> 8; Sport.Basketball -> 6; Sport.Baseball -> 2; Sport.Hockey -> 1; Sport.Soccer -> 1; else -> 0
                }
                if (close > 0 && abs(a - h) <= close) s += 15
            }
        }
        return s
    }
}
