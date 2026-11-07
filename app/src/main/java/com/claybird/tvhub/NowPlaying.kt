package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.delay

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Phone controls for whatever is playing, including playback started from the car:
 * pause, skip back and forward, and a seek bar. To change what is playing, pick something else in the app.
 */
@Composable
fun NowPlayingBar() {
    val context = LocalContext.current
    val player = remember { PlayerHolder.get(context) }
    var title by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    fun refresh() {
        val item: MediaItem? = player.currentMediaItem
        val meta: MediaMetadata = player.mediaMetadata
        active = item != null && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
        title = (meta.title ?: item?.mediaMetadata?.title ?: "").toString()
        playing = player.isPlaying || player.playWhenReady
        live = player.isCurrentMediaItemLive || player.duration == androidx.media3.common.C.TIME_UNSET
        dur = if (player.duration > 0) player.duration else 0L
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) { refresh() }
        }
        player.addListener(l)
        refresh()
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(active, open) {
        while (active && open) {
            if (!dragging) pos = player.currentPosition
            delay(500)
        }
    }

    if (!active) return

    Column(modifier = Modifier.fillMaxWidth().background(HubSurface).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).clickable { open = !open }) {
                Text(title.ifBlank { "Playing" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (open) "Tap to shrink" else "Now playing - tap for controls", fontSize = 11.sp, color = HubMuted)
            }
            FocusButton(if (playing) "Pause" else "Play", Modifier.width(88.dp).height(40.dp), textSize = 14.sp) {
                player.playWhenReady = !player.playWhenReady
            }
        }
        if (open) {
            if (!live && dur > 0) {
                Slider(
                    value = pos.coerceIn(0, dur).toFloat(), valueRange = 0f..dur.toFloat(),
                    onValueChange = { dragging = true; pos = it.toLong() },
                    onValueChangeFinished = { player.seekTo(pos); dragging = false },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(pos), fontSize = 11.sp, color = HubMuted)
                    Text(clock(dur), fontSize = 11.sp, color = HubMuted)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusButton("-30s", Modifier.weight(1f).height(40.dp), textSize = 13.sp) { player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)) }
                    FocusButton("-10s", Modifier.weight(1f).height(40.dp), textSize = 13.sp) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                    FocusButton("+10s", Modifier.weight(1f).height(40.dp), textSize = 13.sp) { player.seekTo(player.currentPosition + 10_000) }
                    FocusButton("+30s", Modifier.weight(1f).height(40.dp), textSize = 13.sp) { player.seekTo(player.currentPosition + 30_000) }
                }
            } else {
                Text("Live: use the tabs below to switch channels.", fontSize = 12.sp, color = HubMuted, modifier = Modifier.padding(vertical = 4.dp))
            }
            FocusButton("Stop playing", Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp), textSize = 13.sp) {
                player.stop(); player.clearMediaItems(); CarBridge.release(); open = false
            }
        }
    }
}
