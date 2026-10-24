package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Touch layouts for the phone. The TV layouts (Browse.kt, Setup.kt) are built for a remote.

@Composable
fun PhoneHomeScreen(onOpen: (Screen) -> Unit) {
    val tiles = listOf(
        "Live TV" to Screen.Browse(Kind.Live),
        "Movies" to Screen.Browse(Kind.Movies),
        "Shows" to Screen.Browse(Kind.Series),
        "Settings" to Screen.Settings,
    )
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Hub", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Text(
            Repo.status.ifBlank { "Live TV, movies and shows" },
            fontSize = 15.sp, color = HubMuted,
        )
        Spacer(Modifier.height(24.dp))
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (label, target) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(HubSurface)
                            .clickable { onOpen(target) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("Tip: touch and hold anything to favorite it.", fontSize = 14.sp, color = HubMuted)
    }
}

private const val ALL = "__all"
private const val FAV = "__fav"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneBrowseScreen(
    kind: Kind,
    startOnFavorites: Boolean,
    onPlay: (List<Row3>, Int) -> Unit,
    onOpenShow: (Show) -> Unit,
) {
    if (!Repo.loaded) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(kind.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Spacer(Modifier.height(12.dp))
            Text(Repo.status.ifBlank { "Loading..." }, fontSize = 17.sp, color = HubMuted)
        }
        return
    }
    val categories = when (kind) {
        Kind.Live -> Repo.liveCategories
        Kind.Movies -> Repo.movieCategories
        Kind.Series -> Repo.showCategories
    }
    var selected by remember { mutableStateOf(if (startOnFavorites) FAV else ALL) }
    var query by remember { mutableStateOf("") }
    val version = Repo.favoritesVersion

    val rows: List<Row3> = remember(kind, selected, query, version, Repo.loaded) {
        val q = query.trim()
        fun ok(name: String, cat: String, id: Int) =
            (selected == ALL || (selected == FAV && Repo.isFavorite(kind, id)) || cat == selected) &&
                (q.isEmpty() || name.contains(q, ignoreCase = true))
        when (kind) {
            Kind.Live -> Repo.live.filter { ok(it.name, it.categoryId, it.id) }.map { Row3(it.id, it.name, it.icon, "", it) }
            Kind.Movies -> Repo.movies.filter { ok(it.name, it.categoryId, it.id) }.map { Row3(it.id, it.name, it.icon, "", it) }
            Kind.Series -> Repo.shows.filter { ok(it.name, it.categoryId, it.id) }.map { Row3(it.id, it.name, it.icon, "", it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text(kind.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search ${kind.label.lowercase()}") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val cats = listOf(Category(FAV, "Favorites"), Category(ALL, "All")) + categories
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(cats, key = { it.id }) { cat ->
                val on = selected == cat.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) HubGreen else HubSurface)
                        .clickable { selected = cat.id }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) { Text(cat.name, fontSize = 15.sp, color = if (on) Color.Black else Color.White, maxLines = 1) }
            }
        }
        if (rows.isEmpty()) {
            Text(
                if (selected == FAV) "No favorites yet. Touch and hold anything to add it." else "Nothing here.",
                fontSize = 16.sp, color = HubMuted, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                val fav = Repo.isFavorite(kind, row.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (kind == Kind.Series) onOpenShow(row.ref as Show)
                                else onPlay(rows, rows.indexOf(row))
                            },
                            onLongClick = { Repo.toggleFavorite(kind, row.id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.icon.isNotBlank()) {
                        AsyncImage(
                            model = row.icon, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.size(if (kind == Kind.Live) 44.dp else 56.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(row.title, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (fav) "★" else "☆", fontSize = 26.sp, color = if (fav) HubGreen else HubMuted,
                        modifier = Modifier.clickable { Repo.toggleFavorite(kind, row.id) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneEpisodesScreen(show: Show, onPlayEpisode: (Show, Episode) -> Unit) {
    var episodes by remember(show.id) { mutableStateOf<List<Episode>?>(null) }
    var error by remember(show.id) { mutableStateOf("") }
    LaunchedEffect(show.id) {
        runCatching { withContext(Dispatchers.IO) { Repo.api!!.seriesEpisodes(show.id) } }
            .onSuccess { episodes = it }
            .onFailure { error = it.message ?: "Couldn't load episodes"; episodes = emptyList() }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(show.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
                if (show.plot.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(show.plot, fontSize = 14.sp, color = HubMuted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val list = episodes
        if (list == null) item { Text("Loading episodes...", color = HubMuted, modifier = Modifier.padding(16.dp)) }
        else if (list.isEmpty()) item { Text(error.ifBlank { "No episodes found." }, color = HubMuted, modifier = Modifier.padding(16.dp)) }
        else items(list, key = { it.id }) { ep ->
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onPlayEpisode(show, ep) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("S${ep.season}  E${ep.number}   ${ep.title}", fontSize = 17.sp)
                if (ep.plot.isNotBlank()) Text(ep.plot, fontSize = 13.sp, color = HubMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
