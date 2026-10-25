package com.claybird.tvhub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** One line in a browse list, whatever it is (channel, movie or show). */
class Row3(
    val id: Int,
    val title: String,
    val icon: String,
    val subtitle: String,
    val ref: Any,
)

private const val ALL = "__all"
private const val FAV = "__fav"

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
private fun clock(sec: Long) = timeFmt.format(Date(sec * 1000))

/**
 * Categories on the left, items in the middle, details on the right.
 * Focusing a category switches the list. OK plays or opens, press and hold OK to favorite.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    kind: Kind,
    startOnFavorites: Boolean,
    onPlay: (List<Row3>, Int) -> Unit,
    onOpenShow: (Show) -> Unit,
) {
    if (!Repo.loaded) {
        Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
            Text(kind.label, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Spacer(Modifier.height(16.dp))
            Text(Repo.status.ifBlank { "Loading..." }, fontSize = 20.sp, color = HubMuted)
        }
        return
    }

    val categories = when (kind) {
        Kind.Live -> Repo.liveCategories
        Kind.Movies -> Repo.movieCategories
        Kind.Series -> Repo.showCategories
    }
    var selected by remember { mutableStateOf(if (startOnFavorites) FAV else ALL) }
    var focusedRow by remember { mutableStateOf<Row3?>(null) }
    val version = Repo.favoritesVersion

    val sortKey = "${BrowseSort.mode}|${BrowseSort.minRating}|${BrowseSort.decade}"
    val rows: List<Row3> = remember(kind, selected, version, Repo.loaded, sortKey) {
        when (kind) {
            Kind.Live -> Repo.live.asSequence()
                .filter { selected == ALL || (selected == FAV && Repo.isFavorite(kind, it.id)) || it.categoryId == selected }
                .map { Row3(it.id, it.name, it.icon, "", it) }.toList()
            Kind.Movies -> BrowseSort.movies(Repo.movies.filter { selected == ALL || (selected == FAV && Repo.isFavorite(kind, it.id)) || it.categoryId == selected })
                .map { Row3(it.id, it.name, it.icon, BrowseSort.subtitle(it.rating, it.year), it) }
            Kind.Series -> BrowseSort.shows(Repo.shows.filter { selected == ALL || (selected == FAV && Repo.isFavorite(kind, it.id)) || it.categoryId == selected })
                .map { Row3(it.id, it.name, it.icon, BrowseSort.subtitle(it.rating, it.year), it) }
        }
    }

    val firstCat = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstCat.requestFocus() } }

    Row(modifier = Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 16.dp)) {
        // ---- categories ----
        Column(modifier = Modifier.width(270.dp).fillMaxHeight()) {
            Text(kind.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Spacer(Modifier.height(8.dp))
            val cats = listOf(Category(FAV, "Favorites"), Category(ALL, "All")) + categories
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(cats, key = { it.id }) { cat ->
                    ListEntry(
                        title = cat.name,
                        selected = selected == cat.id,
                        modifier = Modifier.then(
                            if (cat.id == (if (startOnFavorites) FAV else ALL)) Modifier.focusRequester(firstCat) else Modifier
                        ),
                        onFocus = { selected = cat.id },
                        onClick = { selected = cat.id },
                    )
                }
            }
        }
        Spacer(Modifier.width(20.dp))

        // ---- items ----
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (kind != Kind.Live) SortBar()
            Text(
                text = "${rows.size} ${if (rows.size == 1) "item" else "items"}",
                fontSize = 16.sp, color = HubMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            if (rows.isEmpty()) {
                Text(
                    if (selected == FAV) "No favorites yet. Press and hold OK on anything to add it." else "Nothing here.",
                    fontSize = 18.sp, color = HubMuted,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.id }) { row ->
                    val fav = Repo.isFavorite(kind, row.id)
                    ListEntry(
                        title = row.title,
                        icon = row.icon,
                        star = fav,
                        selected = false,
                        onFocus = { focusedRow = row },
                        onLongClick = { Repo.toggleFavorite(kind, row.id) },
                        onClick = {
                            if (kind == Kind.Series) onOpenShow(row.ref as Show)
                            else onPlay(rows, rows.indexOf(row))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(20.dp))

        // ---- details ----
        DetailPane(kind, focusedRow, modifier = Modifier.width(380.dp).fillMaxHeight())
    }
}

@Composable
private fun DetailPane(kind: Kind, row: Row3?, modifier: Modifier) {
    Column(modifier = modifier.padding(top = 10.dp)) {
        if (row == null) {
            Text("Select something to see details.", fontSize = 16.sp, color = HubMuted)
            return
        }
        if (row.icon.isNotBlank()) {
            AsyncImage(
                model = row.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(if (kind == Kind.Live) 90.dp else 200.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(row.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        when (val ref = row.ref) {
            is Channel -> {
                var entries by remember(ref.id) { mutableStateOf<List<EpgEntry>?>(null) }
                LaunchedEffect(ref.id) {
                    entries = null
                    delay(300) // don't hit the server while the cursor is flying past
                    entries = Repo.epg(ref.id)
                }
                val now = System.currentTimeMillis() / 1000
                val list = entries
                when {
                    list == null -> Text("Loading guide...", fontSize = 16.sp, color = HubMuted)
                    list.isEmpty() -> Text("No guide data for this channel.", fontSize = 16.sp, color = HubMuted)
                    else -> {
                        val upcoming = list.filter { it.end > now }
                        upcoming.take(3).forEachIndexed { i, e ->
                            val label = if (e.start <= now) "Now" else "Next"
                            Text("$label  ${clock(e.start)} - ${clock(e.end)}", fontSize = 14.sp, color = HubGreen)
                            Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            if (i == 0 && e.description.isNotBlank()) {
                                Text(e.description, fontSize = 14.sp, color = HubMuted, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            is Show -> if (ref.plot.isNotBlank()) Text(ref.plot, fontSize = 15.sp, color = HubMuted, maxLines = 12, overflow = TextOverflow.Ellipsis)
            is Movie -> if (row.subtitle.isNotBlank()) Text(row.subtitle, fontSize = 16.sp, color = HubMuted)
        }
    }
}

/** A focusable list line. Focus highlights it; [onFocus] reports it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListEntry(
    title: String,
    modifier: Modifier = Modifier,
    icon: String = "",
    star: Boolean = false,
    selected: Boolean = false,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(shape)
            .background(if (focused) HubGreen else if (selected) HubSurface else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon.isNotBlank()) {
            AsyncImage(
                model = icon, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            fontSize = 19.sp,
            color = if (focused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (star) Text("*", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (focused) Color.Black else HubGreen)
    }
}

/** Seasons and episodes of one show. */
@Composable
fun EpisodesScreen(show: Show, onPlayEpisode: (Show, Episode) -> Unit) {
    var episodes by remember(show.id) { mutableStateOf<List<Episode>?>(null) }
    var error by remember(show.id) { mutableStateOf("") }
    LaunchedEffect(show.id) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Repo.seriesEpisodes(show.id) }
        }.onSuccess { episodes = it }.onFailure { error = it.message ?: "Couldn't load episodes"; episodes = emptyList() }
    }
    val first = remember { FocusRequester() }
    var focusedEp by remember { mutableStateOf<Episode?>(null) }
    LaunchedEffect(episodes) { if (!episodes.isNullOrEmpty()) runCatching { first.requestFocus() } }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(show.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            val list = episodes
            if (list == null) Text("Loading episodes...", fontSize = 18.sp, color = HubMuted)
            else if (list.isEmpty()) Text(error.ifBlank { "No episodes found." }, fontSize = 18.sp, color = HubMuted)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.id }) { ep ->
                    ListEntry(
                        title = "S${ep.season}  E${ep.number}   ${ep.title}",
                        modifier = if (ep === list.first()) Modifier.focusRequester(first) else Modifier,
                        onFocus = { focusedEp = ep },
                        onClick = { onPlayEpisode(show, ep) },
                    )
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(modifier = Modifier.width(380.dp)) {
            if (show.icon.isNotBlank()) {
                AsyncImage(model = show.icon, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(220.dp))
                Spacer(Modifier.height(10.dp))
            }
            val plot = focusedEp?.plot?.ifBlank { null } ?: show.plot
            if (plot.isNotBlank()) Text(plot, fontSize = 15.sp, color = HubMuted, maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
    }
}
