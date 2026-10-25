package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One title from a Stremio catalog. */
class MetaItem(
    val id: String, val type: String, val name: String, val poster: String, val rating: Double,
    val year: Int, val genres: List<String>, val description: String,
)

/** Stremio add-on protocol, read side: GET <base>/catalog/<type>/<id>[/<extra>].json. */
object Stremio {
    const val CINEMETA = "https://v3-cinemeta.strem.io"

    val movieGenres = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
        "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western",
    )

    private fun getJson(url: String, timeout: Int = 25000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = timeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Reads an add-on's manifest. Returns its name, or throws. [resources] says whether it can give streams. */
    fun manifest(url: String): Pair<String, Boolean> {
        val m = getJson(url)
        val res = m.optJSONArray("resources")
        var streams = false
        if (res != null) for (i in 0 until res.length()) {
            val r = res.opt(i)
            if (r == "stream" || (r is JSONObject && r.optString("name") == "stream")) streams = true
        }
        return m.optString("name", "Add-on") to streams
    }

    /** Asks one add-on for streams of a movie ("tt123") or an episode ("tt123:1:2"). */
    fun streams(addon: Addon, type: String, id: String): List<StreamOpt> {
        val arr = getJson("${addon.base}/stream/$type/${URLEncoder.encode(id, "UTF-8")}.json", 45000).optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (!u.startsWith("http")) return@mapNotNull null
            val title = o.optString("title").ifBlank { o.optString("description") }
            StreamOpt(addon.name, o.optString("name").replace("\n", " ").trim(), title.trim(), u)
        }
    }

    fun videos(type: String, id: String): List<Video> {
        val arr = getJson("$CINEMETA/meta/$type/$id.json").optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val season = o.optInt("season", 0)
            val ep = o.optInt("episode", o.optInt("number", 0))
            if (season < 1 || ep < 1) null else Video(o.optString("id"), season, ep, o.optString("name").ifBlank { o.optString("title") })
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    fun catalog(base: String, type: String, catalog: String, genre: String?, skip: Int): List<MetaItem> {
        val extras = ArrayList<String>()
        if (!genre.isNullOrEmpty()) extras += "genre=" + URLEncoder.encode(genre, "UTF-8").replace("+", "%20")
        if (skip > 0) extras += "skip=$skip"
        val path = "/catalog/$type/$catalog" + (if (extras.isEmpty()) "" else "/" + extras.joinToString("&")) + ".json"
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Hub/1.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("metas") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("imdb_id").ifBlank { o.optString("id") }
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val g = o.optJSONArray("genres") ?: o.optJSONArray("genre")
                MetaItem(
                    id = id, type = type, name = name, poster = o.optString("poster"),
                    rating = o.optString("imdbRating").toDoubleOrNull() ?: 0.0,
                    year = Regex("(19|20)\\d{2}").find(o.optString("releaseInfo").ifBlank { o.optString("year") })?.value?.toInt() ?: 0,
                    genres = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it) },
                    description = o.optString("description"),
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** An installed Stremio add-on (its manifest.json link). */
class Addon(val url: String, val name: String) {
    val base: String get() = url.substringBefore("/manifest.json").trimEnd('/')
}

/** A playable stream an add-on returned (only direct links; torrents without a debrid link can't play here). */
class StreamOpt(val addon: String, val name: String, val detail: String, val url: String)

class Video(val id: String, val season: Int, val episode: Int, val title: String)

object AddonStore {
    var addons by mutableStateOf<List<Addon>>(emptyList())
        private set
    private lateinit var ctx: android.content.Context

    fun init(c: android.content.Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        runCatching {
            val arr = JSONArray(ctx.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).getString("addons", "[]"))
            addons = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Addon(o.getString("u"), o.optString("n")) } }
        }
    }

    private fun save() {
        val arr = JSONArray()
        addons.forEach { arr.put(JSONObject().put("u", it.url).put("n", it.name)) }
        ctx.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).edit().putString("addons", arr.toString()).apply()
    }

    fun add(a: Addon) {
        addons = addons.filter { it.url != a.url } + a
        save()
    }

    fun remove(url: String) {
        addons = addons.filter { it.url != url }
        save()
    }

    /** Replace with the account's list (called after sync). */
    fun replace(list: List<Addon>) {
        addons = list
        save()
    }
}

/** Kept outside the screen so choices and results survive going into a title and back. */
object DiscoverState {
    var type by mutableStateOf("movie")
    var catalog by mutableStateOf("imdbRating")  // imdbRating = top rated, top = popular, year = by year
    var genre by mutableStateOf("")
    var year by mutableStateOf(2026)
    var items by mutableStateOf<List<MetaItem>>(emptyList())
    var key = ""
}

private val norm = Regex("[^a-z0-9]+")
private fun titleKey(s: String) = s.lowercase().replace(Regex("\\((19|20)\\d{2}\\)"), " ").replace(Regex("[-–]\\s*(19|20)\\d{2}\\s*$"), " ")
    .replace("&", " and ").replace(norm, " ").trim()

/** Browse popular, top-rated (IMDb) and new titles; watch the ones already on your playlists. */
@Composable
fun DiscoverScreen(isTv: Boolean, onPlayMovie: (Movie, Long) -> Unit, onOpenShow: (Show) -> Unit, onPlayUrl: (String, String) -> Unit) {
    val st = DiscoverState
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<MetaItem?>(null) }
    var more by remember { mutableStateOf(0) }
    var finding by remember { mutableStateOf<MetaItem?>(null) }
    finding?.let { StreamPicker(it, onPlay = onPlayUrl, onClose = { finding = null }); return }

    LaunchedEffect(st.type, st.catalog, st.genre, st.year, more) {
        val key = "${st.type}|${st.catalog}|${if (st.catalog == "year") st.year.toString() else st.genre}"
        val append = more > 0 && key == st.key
        if (!append && key == st.key && st.items.isNotEmpty()) return@LaunchedEffect
        loading = true; error = ""
        val genreParam = if (st.catalog == "year") st.year.toString() else st.genre.ifEmpty { null }
        runCatching {
            withContext(Dispatchers.IO) { Stremio.catalog(Stremio.CINEMETA, st.type, st.catalog, genreParam, if (append) st.items.size else 0) }
        }.onSuccess { got ->
            st.items = if (append) (st.items + got).distinctBy { it.id } else got
            st.key = key
        }.onFailure { error = "Couldn't load: ${it.message}" }
        loading = false
    }

    // Titles that are also on your playlists.
    val movieIndex = remember(Repo.movies) { Repo.movies.groupBy { titleKey(it.name) } }
    val showIndex = remember(Repo.shows) { Repo.shows.groupBy { titleKey(it.name) } }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Discover", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        ChipRow(listOf("movie" to "Movies", "series" to "Shows"), st.type) { st.type = it; picked = null; more = 0 }
        ChipRow(listOf("imdbRating" to "Top rated (IMDb)", "top" to "Popular", "year" to "By year"), st.catalog) { st.catalog = it; picked = null; more = 0 }
        if (st.catalog == "year") {
            ChipRow((2026 downTo 1990).map { it.toString() to it.toString() }, st.year.toString()) { st.year = it.toInt(); more = 0 }
        } else {
            ChipRow(listOf("" to "All genres") + Stremio.movieGenres.map { it to it }, st.genre) { st.genre = it; more = 0 }
        }

        val p = picked
        if (p != null) {
            val key = titleKey(p.name)
            val movieMatch = if (p.type == "movie") movieIndex[key]?.firstOrNull { m -> m.year == 0 || p.year == 0 || kotlin.math.abs(m.year - p.year) <= 1 } else null
            val showMatch = if (p.type == "series") showIndex[key]?.firstOrNull { s -> s.year == 0 || p.year == 0 || kotlin.math.abs(s.year - p.year) <= 1 } else null
            Column(modifier = Modifier.fillMaxWidth().background(HubSurface).padding(12.dp)) {
                Text(p.name + if (p.year > 0) "  (${p.year})" else "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text((if (p.rating > 0) "IMDb %.1f   ".format(p.rating) else "") + p.genres.take(3).joinToString(", "), fontSize = 13.sp, color = HubGreen)
                if (p.description.isNotBlank()) Text(p.description, fontSize = 13.sp, color = HubMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (movieMatch != null) FocusButton("Watch (your playlist)", Modifier.weight(1f).height(46.dp), textSize = 15.sp) { onPlayMovie(movieMatch, 0L) }
                    else if (showMatch != null) FocusButton("Open (your playlist)", Modifier.weight(1f).height(46.dp), textSize = 15.sp) { onOpenShow(showMatch) }
                    else Text("Not on your playlists.", fontSize = 13.sp, color = HubMuted, modifier = Modifier.weight(1f))
                    FocusButton("Find streams", Modifier.weight(1f).height(46.dp), textSize = 15.sp) { finding = p }
                    FocusButton("Close", Modifier.width(90.dp).height(46.dp), textSize = 15.sp) { picked = null }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, fontSize = 14.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(16.dp))
        if (loading && st.items.isEmpty()) Text("Loading...", fontSize = 16.sp, color = HubMuted, modifier = Modifier.padding(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(st.items, key = { it.id }) { it ->
                var focused by remember { mutableStateOf(false) }
                val onMine = (if (it.type == "movie") movieIndex[titleKey(it.name)] else showIndex[titleKey(it.name)]) != null
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (focused) HubGreen else Color.Transparent)
                        .clickable { picked = it }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = it.poster, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 48.dp, height = 70.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (focused) Color.Black else Color.White)
                        Text(listOfNotNull(if (it.rating > 0) "IMDb %.1f".format(it.rating) else null, if (it.year > 0) it.year.toString() else null,
                            if (onMine) "On your playlist" else null).joinToString("   "),
                            fontSize = 13.sp, color = if (focused) Color.Black else if (onMine) HubGreen else HubMuted)
                    }
                }
            }
            if (st.items.isNotEmpty() && st.catalog != "year") item {
                FocusButton(if (loading) "Loading..." else "Load more", Modifier.fillMaxWidth().padding(16.dp).height(48.dp)) { if (!loading) more++ }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}


/** Lists streams from your add-ons for a title (and, for shows, lets you pick the episode). */
@Composable
fun StreamPicker(item: MetaItem, onPlay: (String, String) -> Unit, onClose: () -> Unit) {
    var videos by remember(item.id) { mutableStateOf<List<Video>?>(if (item.type == "series") null else emptyList()) }
    var season by remember(item.id) { mutableStateOf(1) }
    var chosenId by remember(item.id) { mutableStateOf(if (item.type == "series") "" else item.id) }
    var results by remember(item.id) { mutableStateOf<List<StreamOpt>?>(null) }
    var status by remember(item.id) { mutableStateOf("") }
    val addons = AddonStore.addons

    LaunchedEffect(item.id) {
        if (item.type == "series") {
            videos = runCatching { withContext(Dispatchers.IO) { Stremio.videos("series", item.id) } }.getOrDefault(emptyList())
            season = videos?.firstOrNull()?.season ?: 1
        }
    }
    LaunchedEffect(chosenId, addons) {
        if (chosenId.isEmpty()) return@LaunchedEffect
        results = null; status = "Asking ${addons.size} add-on(s)..."
        val all = ArrayList<StreamOpt>()
        val errors = ArrayList<String>()
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                addons.map { a -> async { runCatching { Stremio.streams(a, item.type, chosenId) }.onFailure { errors += "${a.name}: ${it.message}" }.getOrDefault(emptyList()) } }
                    .forEach { all += it.await() }
            }
        }
        results = all
        status = if (all.isEmpty()) (if (errors.isNotEmpty()) errors.joinToString("; ") else "No playable streams found.") else "${all.size} streams"
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
            FocusButton("Back", Modifier.width(90.dp).height(44.dp), textSize = 15.sp) { onClose() }
        }
        if (addons.isEmpty()) {
            Text("No add-ons yet. Open More, then Add-ons, and paste an add-on link.", fontSize = 15.sp, color = HubMuted)
            return@Column
        }
        if (item.type == "series") {
            val v = videos
            if (v == null) { Text("Loading episodes...", color = HubMuted); return@Column }
            val seasons = v.map { it.season }.distinct()
            ChipRow(seasons.map { it.toString() to "Season $it" }, season.toString()) { season = it.toInt(); chosenId = "" ; results = null }
            if (chosenId.isEmpty()) {
                LazyColumn {
                    items(v.filter { it.season == season }, key = { it.id + it.episode }) { e ->
                        Text("E${e.episode}  ${e.title}", fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { chosenId = "${item.id}:${e.season}:${e.episode}" }.padding(vertical = 12.dp))
                    }
                }
                return@Column
            }
        }
        Text(status, fontSize = 13.sp, color = HubMuted, modifier = Modifier.padding(vertical = 4.dp))
        val list = results.orEmpty()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list.size) { i ->
                val r = list[i]
                var focused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (focused) HubGreen else HubSurface)
                        .clickable { onPlay(item.name, r.url) }.padding(10.dp),
                ) {
                    Text(r.name.ifBlank { r.addon }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (focused) Color.Black else Color.White, maxLines = 1)
                    Text(r.detail, fontSize = 12.sp, color = if (focused) Color.Black else HubMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Install add-ons by pasting their manifest link (from the add-on's configure page). */
@Composable
fun AddonsScreen(isTv: Boolean) {
    var link by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Add-ons", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Text("Stremio add-ons that give streams, for example AIOStreams, MediaFusion or Comet set up with your debrid key. Open the add-on's configure page, choose TorBox, paste your key, then copy the install link (it ends in manifest.json) and paste it here. Links with your key are saved to your HUB account.",
            fontSize = 13.sp, color = HubMuted, modifier = Modifier.padding(vertical = 6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = link, onValueChange = { link = it.trim() }, singleLine = true,
            placeholder = { Text("https://.../manifest.json  (or stremio://...)") }, modifier = Modifier.fillMaxWidth(),
        )
        FocusButton(if (busy) "Checking..." else "Add add-on", Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
            if (busy || link.isBlank()) return@FocusButton
            busy = true; msg = ""
            val url = link.replace("stremio://", "https://")
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Stremio.manifest(url) } }
                    .onSuccess { (name, hasStreams) ->
                        val a = Addon(url, name)
                        AddonStore.add(a)
                        Repo.pushAddonsBackground()
                        msg = "Added $name." + if (!hasStreams) " (It doesn't provide streams, so it won't be used for playback.)" else ""
                        link = ""
                    }
                    .onFailure { msg = "Couldn't read that link: ${it.message}" }
                busy = false
            }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 14.sp, color = HubMuted, modifier = Modifier.padding(vertical = 6.dp))
        Spacer(Modifier.height(10.dp))
        Text("Installed (${AddonStore.addons.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(AddonStore.addons, key = { it.url }) { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(a.name, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    FocusButton("Remove", Modifier.width(110.dp).height(42.dp), textSize = 14.sp) {
                        AddonStore.remove(a.url); Repo.pushAddonsBackground(removed = a.url)
                    }
                }
            }
        }
    }
}
