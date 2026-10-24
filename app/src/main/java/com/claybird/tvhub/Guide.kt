package com.claybird.tvhub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val clockFmt = SimpleDateFormat("h:mm a", Locale.US)
private val dayFmt = SimpleDateFormat("EEE h:mm a", Locale.US)
fun clockText(sec: Long): String = clockFmt.format(Date(sec * 1000))
fun dayClockText(sec: Long): String = dayFmt.format(Date(sec * 1000))

private const val G_FAV = "__fav"
private const val G_ALL = "__all"
private const val G_SPORTS = "__sports"

/** A chip row used for categories and sports. */
@Composable
fun ChipRow(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        items(items, key = { it.first }) { (id, label) ->
            var focused by remember { mutableStateOf(false) }
            val on = selected == id
            Box(
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) HubGreen else HubSurface)
                    .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(label, fontSize = 15.sp, color = if (on) Color.Black else Color.White, maxLines = 1) }
        }
    }
}

/**
 * Cable-style guide: channels down the side, time across the top, everything scrolls together.
 * Phone: tap a show for details, tap the channel name to watch. TV: OK on a show watches it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(isTv: Boolean, startOnFavorites: Boolean = false, onWatch: (List<Channel>, Int) -> Unit) {
    if (!Repo.loaded) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Guide", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Text(Repo.status.ifBlank { "Loading..." }, fontSize = 17.sp, color = HubMuted)
        }
        return
    }

    val dpPerMin = if (isTv) 5f else 4f
    val nameWidth: Dp = if (isTv) 190.dp else 92.dp
    val rowHeight: Dp = if (isTv) 64.dp else 58.dp

    var selected by remember { mutableStateOf(if (startOnFavorites) G_FAV else G_ALL) }
    var query by remember { mutableStateOf("") }
    var pickedChannel by remember { mutableStateOf<Channel?>(null) }
    var pickedProg by remember { mutableStateOf<Prog?>(null) }
    val epgVersion = Epg.version
    val favVersion = Repo.favoritesVersion

    // The window the grid covers: from an hour ago to 29 hours ahead, on half-hour marks.
    val nowSec = remember { System.currentTimeMillis() / 1000 }
    val winStart = remember { (nowSec / 1800) * 1800 - 3600 }
    val winEnd = winStart + 30 * 3600
    val totalWidth = ((winEnd - winStart) / 60f * dpPerMin).dp
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val minutes = (nowSec - winStart) / 60f - 20f
        hScroll.scrollTo(with(density) { (minutes * dpPerMin).dp.toPx() }.toInt().coerceAtLeast(0))
    }

    val sportsIds: Set<Int> = remember(epgVersion, selected) {
        if (selected != G_SPORTS) emptySet()
        else Sports.build(System.currentTimeMillis() / 1000, 0).filter { it.live }.map { it.channel.id }.toHashSet()
    }

    val channels: List<Channel> = remember(selected, query, epgVersion, favVersion, sportsIds) {
        val q = query.trim()
        Repo.live.filter { c ->
            val inCat = when (selected) {
                G_ALL -> Epg.forChannel(c) != null || !Epg.ready
                G_FAV -> Repo.isFavorite(Kind.Live, c.id)
                G_SPORTS -> c.id in sportsIds
                else -> c.categoryId == selected
            }
            inCat && (q.isEmpty() || c.name.contains(q, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 12.dp)) {
            Text("Guide", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Spacer(Modifier.width(14.dp))
            Text(Epg.status, fontSize = 13.sp, color = HubMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!isTv) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find a channel") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val chips = listOf(G_FAV to "Favorites", G_SPORTS to "Sports now", G_ALL to "All") +
            Repo.liveCategories.map { it.id to it.name }
        ChipRow(chips, selected) { selected = it }

        // Details of the show you picked.
        val pc = pickedChannel
        val pp = pickedProg
        if (pc != null && pp != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(HubSurface).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pp.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pc.name}   ${clockText(pp.start)} - ${clockText(pp.end)}", fontSize = 13.sp, color = HubGreen, maxLines = 1)
                    if (pp.desc.isNotBlank()) Text(pp.desc, fontSize = 13.sp, color = HubMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!isTv) {
                    Spacer(Modifier.width(10.dp))
                    FocusButton("Watch", Modifier.width(100.dp).height(46.dp)) {
                        onWatch(channels, channels.indexOf(pc).coerceAtLeast(0))
                    }
                }
            }
        }

        // Time header, scrolls with the grid.
        Row {
            Spacer(Modifier.width(nameWidth))
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Row(Modifier.width(totalWidth)) {
                    var t = winStart
                    while (t < winEnd) {
                        Box(Modifier.width((30 * dpPerMin).dp).padding(vertical = 6.dp, horizontal = 4.dp)) {
                            Text(clockText(t), fontSize = 13.sp, color = HubMuted, maxLines = 1)
                        }
                        t += 1800
                    }
                }
            }
        }

        if (channels.isEmpty()) {
            Text(
                when {
                    selected == G_FAV -> "No favorite channels yet. Touch and hold a channel name to add one."
                    selected == G_SPORTS -> "No live sports found right now."
                    else -> "No channels here."
                },
                fontSize = 16.sp, color = HubMuted, modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(channels, key = { it.id }) { ch ->
                val fav = Repo.isFavorite(Kind.Live, ch.id)
                Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                    // Channel cell
                    var cellFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .width(nameWidth)
                            .height(rowHeight)
                            .onFocusChanged { cellFocused = it.isFocused }
                            .background(if (cellFocused) HubGreen else HubSurface)
                            .combinedClickable(
                                onClick = { onWatch(channels, channels.indexOf(ch)) },
                                onLongClick = { Repo.toggleFavorite(Kind.Live, ch.id) },
                            )
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isTv && ch.icon.isNotBlank()) {
                            AsyncImage(model = ch.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            (if (fav) "* " else "") + ch.name,
                            fontSize = if (isTv) 15.sp else 12.sp,
                            color = if (cellFocused) Color.Black else Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Programs
                    Box(Modifier.weight(1f).height(rowHeight).horizontalScroll(hScroll)) {
                        Row(Modifier.width(totalWidth).height(rowHeight)) {
                            val progs = Epg.forChannel(ch)
                            if (progs == null || progs.isEmpty()) {
                                Box(Modifier.width((300 * dpPerMin).dp.coerceAtMost(totalWidth)).height(rowHeight).padding(2.dp)
                                    .background(Color(0xFF1A1F2A)).padding(10.dp)) {
                                    Text(if (Epg.ready) "No guide data" else "Guide loading...", fontSize = 13.sp, color = HubMuted)
                                }
                            } else {
                                var cursor = winStart
                                for (p in progs) {
                                    val s = maxOf(p.start, cursor)
                                    val e = minOf(p.end, winEnd)
                                    if (e <= s) continue
                                    if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60f * dpPerMin).dp))
                                    cursor = e
                                    val isNow = p.start <= nowSec && p.end > nowSec
                                    var focused by remember(ch.id, p.start) { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .width(((e - s) / 60f * dpPerMin).dp)
                                            .height(rowHeight)
                                            .padding(1.dp)
                                            .onFocusChanged {
                                                focused = it.isFocused
                                                if (it.isFocused) { pickedChannel = ch; pickedProg = p }
                                            }
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (focused) HubGreen else if (isNow) Color(0xFF3A4254) else Color(0xFF232836))
                                            .clickable {
                                                pickedChannel = ch; pickedProg = p
                                                if (isTv) onWatch(channels, channels.indexOf(ch))
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(p.title, fontSize = if (isTv) 16.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.Medium)
                                        Text(clockText(p.start), fontSize = 11.sp, maxLines = 1,
                                            color = if (focused) Color.Black else HubMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Live sports now (and later today), one row per game, filterable by sport. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SportsScreen(isTv: Boolean, onWatchGame: (List<Game>, Int, Int) -> Unit) {
    var items by remember { mutableStateOf<List<Game>?>(null) }
    var mode by remember { mutableStateOf("live") }
    var sport by remember { mutableStateOf("all") }
    val epgVersion = Epg.version

    LaunchedEffect(epgVersion, Repo.loaded) {
        if (!Repo.loaded) return@LaunchedEffect
        while (true) {
            items = withContext(Dispatchers.Default) {
                Games.group(Sports.build(System.currentTimeMillis() / 1000, 8 * 3600))
            }
            delay(60_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Sports", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        val all = items
        if (all == null) {
            Text(if (Epg.ready) "Looking for games..." else Epg.status.ifBlank { "Waiting for the guide..." },
                fontSize = 16.sp, color = HubMuted, modifier = Modifier.padding(16.dp))
            return
        }
        ChipRow(listOf("live" to "Live now (${all.count { it.live }})", "later" to "Later today"), mode) { mode = it }
        val inMode = all.filter { (mode == "live") == it.live }
        val counts = Sport.values().associateWith { s -> inMode.count { it.sport == s } }
        ChipRow(
            listOf("all" to "All sports (${inMode.size})") +
                Sport.values().filter { (counts[it] ?: 0) > 0 }.map { it.name to "${it.label} (${counts[it]})" },
            sport,
        ) { sport = it }

        val shown = inMode
            .filter { sport == "all" || it.sport.name == sport }
            .sortedWith(if (mode == "live") compareBy({ it.sport.ordinal }, { it.end }) else compareBy({ it.start }))
        if (shown.isEmpty()) {
            Text(
                if (mode == "live") "No live games found in the guide right now." else "Nothing starting in the next 8 hours.",
                fontSize = 16.sp, color = HubMuted, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { "${it.sport.name}:${it.title}:${it.start}" }) { game ->
                var focused by remember { mutableStateOf(false) }
                val now = System.currentTimeMillis() / 1000
                val best = game.streams.first()
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .background(if (focused) HubGreen else Color.Transparent)
                            .combinedClickable(
                                onClick = { onWatchGame(shown, shown.indexOf(game), 0) },
                                onLongClick = { Repo.toggleFavorite(Kind.Live, best.channel.id) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (best.channel.icon.isNotBlank()) {
                            AsyncImage(model = best.channel.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val fg = if (focused) Color.Black else Color.White
                            Text(game.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${game.sport.label}   " + if (game.live)
                                    "Live until ${clockText(game.end)}  (${((game.end - now) / 60).coerceAtLeast(0)} min left)"
                                else "Starts ${dayClockText(game.start)}",
                                fontSize = 13.sp, color = if (focused) Color.Black else HubGreen,
                            )
                        }
                    }
                    // The best few channels showing it; the first is the one a plain tap plays.
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        val top = game.streams.take(3)
                        items(top.size) { i ->
                            val label = top[i].channel.name.replace(Regex("^[A-Za-z]{2,3}\\s*[|:]\\s*"), "")
                            var f by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .onFocusChanged { f = it.isFocused }
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (f) HubGreen else if (i == 0) Color(0xFF2F5A43) else HubSurface)
                                    .clickable { onWatchGame(shown, shown.indexOf(game), i) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(label + if (i == 0) "  (best)" else "", fontSize = 13.sp, maxLines = 1,
                                    color = if (f) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
