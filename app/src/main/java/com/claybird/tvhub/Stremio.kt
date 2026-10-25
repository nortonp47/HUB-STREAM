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
fun DiscoverScreen(isTv: Boolean, onPlayMovie: (Movie, Long) -> Unit, onOpenShow: (Show) -> Unit) {
    val st = DiscoverState
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<MetaItem?>(null) }
    var more by remember { mutableStateOf(0) }

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
                    else Text("Not on your playlists. Stremio add-ons for streams are coming next.", fontSize = 13.sp, color = HubMuted, modifier = Modifier.weight(1f))
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
