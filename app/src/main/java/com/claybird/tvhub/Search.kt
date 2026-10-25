package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Lives outside the screen so the query and results are still there when you come back from playback. */
object SearchState {
    var query by mutableStateOf("")
}

private sealed class Hit(val label: String, val title: String, val sub: String)
private class LiveHit(val ch: Channel, sub: String) : Hit("LIVE", ch.name, sub)
private class MovieHit(val m: Movie) : Hit("MOVIE", m.name, m.rating)
private class ShowHit(val s: Show) : Hit("SHOW", s.name, s.rating)

private class Results(val live: List<Hit>, val movies: List<Hit>, val shows: List<Hit>) {
    val all get() = live + movies + shows
}

private fun matches(name: String, tokens: List<String>) = tokens.all { name.contains(it, ignoreCase = true) }

private fun runSearch(q: String): Results {
    val tokens = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
    val now = System.currentTimeMillis() / 1000
    val live = Repo.live.filter { matches(it.name, tokens) }.take(40).map { c ->
        val p = Epg.current(c, now)
        LiveHit(c, p?.title ?: "")
    }
    // Also find channels by what's on right now (e.g. a team name).
    val byProgram = if (tokens.joinToString("").length >= 3) {
        Repo.live.asSequence().filter { c -> live.none { (it as LiveHit).ch.id == c.id } }.mapNotNull { c ->
            val p = Epg.current(c, now)
            if (p != null && matches(p.title, tokens)) LiveHit(c, "Now: ${p.title}") else null
        }.take(30).toList()
    } else emptyList()
    val movies = Repo.movies.filter { matches(it.name, tokens) }.take(60).map { MovieHit(it) }
    val shows = Repo.shows.filter { matches(it.name, tokens) }.take(60).map { ShowHit(it) }
    return Results(live + byProgram, movies, shows)
}

/** One search across live channels (by name or what's on now), movies and shows. Empty: continue watching and recents. */
@Composable
fun SearchScreen(
    isTv: Boolean,
    onLive: (List<Channel>, Int) -> Unit,
    onMovie: (Movie, Long) -> Unit,
    onShow: (Show) -> Unit,
    onResume: (HistoryItem) -> Unit,
) {
    var results by remember { mutableStateOf(Results(emptyList(), emptyList(), emptyList())) }
    val query = SearchState.query
    LaunchedEffect(query, Repo.loaded, Epg.version) {
        if (!Repo.loaded) return@LaunchedEffect
        delay(if (isTv) 0 else 200)
        results = withContext(Dispatchers.Default) { runSearch(query) }
    }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { firstKey.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Search", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        if (isTv) {
            Text(if (query.isEmpty()) "Type with the keyboard below" else query, fontSize = 20.sp,
                color = if (query.isEmpty()) HubMuted else androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(vertical = 6.dp))
        } else {
            OutlinedTextField(
                value = query, onValueChange = { SearchState.query = it }, singleLine = true,
                placeholder = { Text("Channels, movies, shows, teams") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        val hits = results.all
        val liveList = results.live.map { (it as LiveHit).ch }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (query.isBlank()) {
                val cont = Repo.historyFor("Movie", "Episode").filter { it.positionMs > 15_000 }.take(8)
                val recents = Repo.historyFor("Live").take(12)
                if (cont.isNotEmpty()) {
                    item { Header("Continue watching") }
                    items(cont, key = { it.key }) { h ->
                        Line("RESUME", h.title, "at ${timeText(h.positionMs)}" + if (h.durationMs > 0) " of ${timeText(h.durationMs)}" else "") { onResume(h) }
                    }
                }
                if (recents.isNotEmpty()) {
                    item { Header("Recent channels") }
                    items(recents, key = { it.key }) { h -> Line("LIVE", h.title, "") { onResume(h) } }
                }
                if (cont.isEmpty() && recents.isEmpty()) {
                    item { Text("Nothing watched yet. Search above, or open the Guide.", color = HubMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp)) }
                }
            } else if (hits.isEmpty()) {
                item { Text("No matches.", color = HubMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp)) }
            } else {
                if (results.live.isNotEmpty()) {
                    item { Header("Live channels") }
                    items(results.live, key = { "l" + (it as LiveHit).ch.id }) { h ->
                        Line(h.label, h.title, h.sub) { onLive(liveList, liveList.indexOf((h as LiveHit).ch).coerceAtLeast(0)) }
                    }
                }
                if (results.movies.isNotEmpty()) {
                    item { Header("Movies") }
                    items(results.movies, key = { "m" + (it as MovieHit).m.id }) { h ->
                        Line(h.label, h.title, h.sub) { onMovie((h as MovieHit).m, 0L) }
                    }
                }
                if (results.shows.isNotEmpty()) {
                    item { Header("Shows") }
                    items(results.shows, key = { "s" + (it as ShowHit).s.id }) { h ->
                        Line(h.label, h.title, h.sub) { onShow((h as ShowHit).s) }
                    }
                }
            }
        }
        if (isTv) {
            Column(modifier = Modifier.heightIn(max = 330.dp)) {
                OnScreenKeyboard(
                    onChar = { SearchState.query += it },
                    onDelete = { SearchState.query = SearchState.query.dropLast(1) },
                    onClear = { SearchState.query = "" },
                    firstKey = firstKey,
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HubGreen, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Line(tag: String, title: String, sub: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .background(if (focused) HubGreen else HubSurface)
            .androidxFocus { focused = it }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tag, fontSize = 11.sp, color = if (focused) androidx.compose.ui.graphics.Color.Black else HubMuted, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) androidx.compose.ui.graphics.Color.Black else HubMuted)
        }
    }
    Spacer(Modifier.padding(0.dp))
}

private fun Modifier.androidxFocus(set: (Boolean) -> Unit): Modifier =
    this.then(androidx.compose.ui.Modifier.onFocusChanged { set(it.isFocused) })

private fun timeText(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}
