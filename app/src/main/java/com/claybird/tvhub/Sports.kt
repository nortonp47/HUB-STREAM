package com.claybird.tvhub

enum class Sport(val label: String, words: List<String>) {
    Soccer("Soccer", listOf(
        "premier league", "epl", "la liga", "laliga", "serie a", "bundesliga", "ligue 1", "mls", "champions league",
        "europa league", "conference league", "uefa", "fifa", "world cup", "soccer", "fa cup", "carabao", "efl",
        "liga mx", "concacaf", "usmnt", "uswnt", "nwsl", "eredivisie", "scottish premiership", "a-league", "copa america",
        "copa libertadores", "club world cup", "futbol", "gold cup",
    )),
    Football("Football", listOf(
        "nfl", "ncaaf", "college football", "cfb", "football", "redzone", "ufl", "cfl", "xfl", "bowl game",
        "high school football", "arena football", "sec ", "big ten", "acc ",
    )),
    Basketball("Basketball", listOf(
        "nba", "wnba", "ncaab", "college basketball", "basketball", "march madness", "g league", "euroleague",
    )),
    Baseball("Baseball", listOf("mlb", "baseball", "world series", "milb", "npb", "kbo", "little league")),
    Hockey("Hockey", listOf("nhl", "hockey", "ahl", "khl")),
    Motorsport("Motorsport", listOf(
        "nascar", "formula 1", "formula one", "f1", "indycar", "motogp", "motocross", "supercross", "grand prix",
        "imsa", "wrc", "nhra", "drag racing", "racing", "rally",
    )),
    Combat("Combat", listOf(
        "ufc", "mma", "boxing", "bellator", "pfl", "wwe", "aew", "wrestling", "fight night", "kickboxing", "ppv",
    )),
    Golf("Golf", listOf("pga", "golf", "lpga", "liv golf", "ryder cup", "masters", "dp world")),
    Tennis("Tennis", listOf("tennis", "atp", "wta", "wimbledon", "us open", "french open", "roland garros", "australian open")),
    Cricket("Cricket", listOf("cricket", "ipl", "t20", "ashes", "test match", "bbl")),
    Rugby("Rugby", listOf("rugby", "six nations", "super rugby", "nrl", "afl")),
    Other("Other", listOf(
        "lacrosse", "volleyball", "softball", "olympic", "cycling", "tour de france", "darts", "snooker", "curling",
        "track and field", "swimming", "gymnastics", "skiing", "table tennis", "handball", "pickleball", "bowling",
    ));

    val regex: Regex = Regex("\\b(" + words.joinToString("|") { Regex.escape(it.trim()) } + ")\\b", RegexOption.IGNORE_CASE)
}

class SportItem(val channel: Channel, val prog: Prog, val sport: Sport, val live: Boolean, val category: String)

/**
 * Finds live and upcoming sports in the guide. This is a keyword heuristic over the program title,
 * description, channel name and category, so it will occasionally miss or misfile something.
 */
object Sports {
    private val replay = Regex(
        "\\b(replay|re-air|rerun|re-run|encore|highlights?|recap|classic|best of|top plays|archive|condensed|rewind|delayed|tape delay|full game replay|rewatch)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val studio = Regex(
        "\\b(sportscenter|first take|get up|pardon the interruption|around the horn|countdown|pregame|postgame|analysis|podcast|talk|news|insider|mailbag|gameday|kickoff show)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(title: String, desc: String, channelName: String, category: String): Sport? {
        if (title.isBlank()) return null
        if (replay.containsMatchIn(title) || replay.containsMatchIn(desc.take(120))) return null
        if (studio.containsMatchIn(title)) return null
        val text = "$title $desc $channelName $category"
        // Title first: a title that names a sport or league beats the channel's category.
        for (s in Sport.values()) if (s.regex.containsMatchIn(title)) return s
        for (s in Sport.values()) if (s.regex.containsMatchIn(text)) return s
        return null
    }

    /** Everything airing now, plus what starts within [horizonSec]. */
    fun build(now: Long, horizonSec: Long): List<SportItem> {
        val catNames = HashMap<String, String>()
        for (c in Repo.liveCategories) catNames[c.id] = c.name
        val out = ArrayList<SportItem>()
        for (ch in Repo.live) {
            val progs = Epg.forChannel(ch) ?: continue
            val cat = catNames[ch.categoryId].orEmpty()
            for (p in progs) {
                if (p.end <= now) continue
                if (p.start > now + horizonSec) break
                val live = p.start <= now
                val sport = classify(p.title, p.desc, ch.name, cat) ?: continue
                out += SportItem(ch, p, sport, live, cat)
            }
        }
        return out
    }
}

/** One game, with every channel showing it, best first. */
class Game(val sport: Sport, val title: String, val live: Boolean, val start: Long, val end: Long, val streams: List<SportItem>)

object Games {
    private val trailingTags = Regex("\\(.*?\\)|\\[.*?\\]")
    private val leadTags = Regex("^(live|new|ppv)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val hd = Regex("\\b(hd|fhd|uhd|4k|1080p?|hevc)\\b", RegexOption.IGNORE_CASE)
    private val sd = Regex("\\b(sd|480p?|360p?)\\b", RegexOption.IGNORE_CASE)
    private val us = Regex("(^|[^a-z])(us|usa|united states|america)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val otherRegion = Regex(
        "(^|[^a-z])(uk|gb|ca|au|nz|ie|es|fr|de|it|pt|br|ar|mx|nl|se|no|dk|pl|tr|in|pk|latino|spanish|arabic|portugal|india)([^a-z]|$)",
        RegexOption.IGNORE_CASE,
    )

    private fun key(item: SportItem): String {
        var t = item.prog.title.replace(trailingTags, " ").replace(leadTags, "").lowercase()
        t = t.replace(nonAlnum, " ").trim()
        return "${item.sport.name}|$t|${item.prog.start / 1800}"
    }

    /** Higher is better. A guess from the channel name and category (no stream testing). */
    fun score(item: SportItem): Int {
        val text = "${item.category} ${item.channel.name}"
        var s = 0
        if (us.containsMatchIn(text)) s += 20
        if (otherRegion.containsMatchIn(text)) s -= 30
        if (hd.containsMatchIn(item.channel.name)) s += 10
        if (sd.containsMatchIn(item.channel.name)) s -= 10
        if (Repo.isFavorite(Kind.Live, item.channel.id)) s += 5
        return s
    }

    fun group(items: List<SportItem>): List<Game> =
        items.groupBy { key(it) }.values.map { list ->
            val ranked = list.sortedByDescending { score(it) }
            val first = ranked.first()
            Game(first.sport, first.prog.title, first.live, first.prog.start, first.prog.end, ranked)
        }
}
