package com.claybird.tvhub

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen player for phone and TV.
 *
 * Remote: Live Up/Down changes channel, OK shows info. On-demand: Left/Right seek 10 seconds, OK pauses.
 * Touch: tap shows controls, swipe up/down changes live channel, double-tap the sides to seek.
 * Phone extras: Go Live button, picture-in-picture, keeps playing (audio) when you leave the app.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var overlay by mutableStateOf(true)
    private var message by mutableStateOf("")
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var playing by mutableStateOf(true)
    private var behindLive by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var isLive = true
    private var altUrl: String? = null
    private var triedAlt = false
    private var hideJob: Job? = null
    private var epgJob: Job? = null
    private var listener: Player.Listener? = null
    // What to remember in "continue watching" for movies and episodes.
    private var histKind = ""
    private var histId = ""
    private var histPl = ""
    private var histIcon = ""
    private var histExt = ""
    private var lastSaved = 0L

    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        isLive = intent.getBooleanExtra(EXTRA_LIVE, true)
        player = PlayerHolder.get(this)
        attachListener()
        if (!isTv) {
            startService(Intent(this, PlaybackService::class.java))
            updatePip()
        }

        if (isLive) {
            if (Repo.zapList.isEmpty() || Repo.api == null) { finish(); return }
            playChannel(Repo.zapIndex, keepFallback = true)
        } else {
            val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
            title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            subtitle = ""
            histKind = intent.getStringExtra(EXTRA_KIND) ?: ""
            histId = intent.getStringExtra(EXTRA_ITEM) ?: ""
            histPl = intent.getStringExtra(EXTRA_PL) ?: Repo.activeId
            histIcon = intent.getStringExtra(EXTRA_ICON) ?: ""
            histExt = intent.getStringExtra(EXTRA_EXT) ?: ""
            val resume = intent.getLongExtra(EXTRA_RESUME, 0L)
            start(url, null, resume)
            if (histKind.isNotEmpty()) saveProgress(push = true)
        }

        setContent {
            HubTheme {
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .pointerInput(isLive) {
                            detectTapGestures(
                                onTap = { if (overlay) { overlay = false } else showOverlay(6000) },
                                onDoubleTap = { pos ->
                                    if (!isLive) {
                                        val delta = if (pos.x < size.width / 2) -10_000L else 10_000L
                                        player.seekTo((player.currentPosition + delta).coerceAtLeast(0))
                                        showOverlay()
                                    }
                                },
                            )
                        }
                        .pointerInput(isLive) {
                            if (isLive) {
                                var total = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { total = 0f },
                                    onDragEnd = {
                                        if (total < -140f) playChannel(Repo.zapIndex + 1)
                                        else if (total > 140f) playChannel(Repo.zapIndex - 1)
                                    },
                                    onVerticalDrag = { _, d -> total += d },
                                )
                            }
                        }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                this.player = this@PlayerActivity.player
                            }
                        },
                    )
                    if (!inPip && (overlay || !playing || message.isNotEmpty())) InfoOverlay()
                }
            }
        }
        showOverlay()

        lifecycleScope.launch {
            while (true) {
                positionMs = player.currentPosition
                durationMs = player.duration.coerceAtLeast(0)
                behindLive = isLive && player.isCurrentMediaItemLive && player.currentLiveOffset > 6000
                if (!isLive && histKind.isNotEmpty() && System.currentTimeMillis() - lastSaved > 15_000) saveProgress(push = false)
                delay(500)
            }
        }
    }

    private fun attachListener() {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val alt = altUrl
                if (alt != null && !triedAlt) {
                    triedAlt = true
                    message = "Trying another stream format..."
                    player.setMediaItem(mediaItem(alt))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    val next = Repo.fallback.pollFirst()
                    if (next != null && isLive) {
                        playFallback(next)
                    } else {
                        message = "Can't play this: ${error.errorCodeName}"
                        showOverlay(8000)
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && message.startsWith("Trying")) message = ""
                if (state == Player.STATE_ENDED && !isLive) finish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying || player.playWhenReady
                updatePip()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
        }
        listener = l
        player.addListener(l)
    }

    private fun mediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Hub").build())
        .build()

    private fun start(url: String, alt: String?, startMs: Long = 0L) {
        altUrl = alt
        triedAlt = false
        message = ""
        if (startMs > 0) player.setMediaItem(mediaItem(url), startMs) else player.setMediaItem(mediaItem(url))
        player.prepare()
        player.playWhenReady = true
        playing = true
    }

    /** The channel that was playing failed; try another one showing the same game. */
    private fun playFallback(c: Channel) {
        val list = Repo.zapList.toMutableList()
        if (Repo.zapIndex in list.indices) list[Repo.zapIndex] = c
        Repo.zapList = list
        val item = Repo.playItemFor(c)
        title = c.name
        subtitle = ""
        start(item.url, item.altUrl)
        message = "That stream failed, trying ${c.name}"
        showOverlay(6000)
    }

    private fun playChannel(index: Int, keepFallback: Boolean = false) {
        if (!keepFallback) Repo.fallback.clear()
        val list = Repo.zapList
        if (list.isEmpty()) return
        val i = ((index % list.size) + list.size) % list.size
        Repo.zapIndex = i
        val c = list[i]
        val item = Repo.playItemFor(c)
        title = c.name
        subtitle = ""
        start(item.url, item.altUrl)
        Repo.recordPlay("Live", Repo.realId(c.id).toString(), c.name, c.icon, "", 0, 0, push = true, playlistId = Repo.playlistIdOf(c.id))
        showOverlay()
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val entries = Repo.epg(c.id)
            val now = System.currentTimeMillis() / 1000
            val cur = entries.firstOrNull { it.start <= now && it.end > now }
            val next = entries.firstOrNull { it.start > now }
            val f = SimpleDateFormat("h:mm a", Locale.US)
            subtitle = listOfNotNull(
                cur?.let { "Now: ${it.title}  (until ${f.format(Date(it.end * 1000))})" },
                next?.let { "Next: ${it.title}  (${f.format(Date(it.start * 1000))})" },
            ).joinToString("    ")
        }
    }

    /** Jump to the live edge; if the stream has no live window, reload the channel (which starts live). */
    private fun goLive() {
        if (player.isCurrentMediaItemLive && player.isCurrentMediaItemDynamic) {
            player.seekToDefaultPosition()
            player.play()
        } else {
            playChannel(Repo.zapIndex, keepFallback = true)
        }
        showOverlay()
    }

    private fun togglePlay() {
        player.playWhenReady = !player.playWhenReady
        playing = player.playWhenReady
        showOverlay()
    }

    private fun showOverlay(stayMs: Long = 4500) {
        overlay = true
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(stayMs)
            overlay = false
        }
    }

    // ---- picture-in-picture (phone) ----

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(autoEnter)
        return b.build()
    }

    private fun pipAllowed() = !isTv && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updatePip() {
        if (!pipAllowed()) return
        runCatching { setPictureInPictureParams(pipParams(player.playWhenReady)) }
    }

    private fun enterPip() {
        if (pipAllowed()) runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.playWhenReady) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ---- overlay ----

    @Composable
    private fun InfoOverlay() {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 40.dp, vertical = 24.dp)
            ) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 17.sp, color = HubMuted)
                if (message.isNotBlank()) Text(message, fontSize = 17.sp, color = HubGreen)
                if (!playing) Text("Paused", fontSize = 18.sp, color = HubGreen)
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 40.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isLive && durationMs > 0) {
                    Text("${fmt(positionMs)} / ${fmt(durationMs)}", fontSize = 16.sp)
                    if (isTv) {
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f)).height(6.dp).background(HubGreen))
                        }
                    } else {
                        Slider(
                            value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                            onValueChange = { player.seekTo((it * durationMs).toLong()); showOverlay(6000) },
                            colors = SliderDefaults.colors(thumbColor = HubGreen, activeTrackColor = HubGreen),
                        )
                    }
                }
                if (!isTv) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isLive) {
                            FocusButton("Prev", Modifier.weight(1f).height(48.dp)) { playChannel(Repo.zapIndex - 1) }
                        } else {
                            FocusButton("-10s", Modifier.weight(1f).height(48.dp)) {
                                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); showOverlay()
                            }
                        }
                        FocusButton(if (playing) "Pause" else "Play", Modifier.weight(1f).height(48.dp)) { togglePlay() }
                        if (isLive) {
                            FocusButton("Next", Modifier.weight(1f).height(48.dp)) { playChannel(Repo.zapIndex + 1) }
                            FocusButton(
                                if (behindLive) "GO LIVE" else "LIVE",
                                Modifier.weight(1f).height(48.dp),
                            ) { goLive() }
                        } else {
                            FocusButton("+10s", Modifier.weight(1f).height(48.dp)) {
                                player.seekTo(player.currentPosition + 10_000); showOverlay()
                            }
                        }
                        if (pipAllowed()) FocusButton("PiP", Modifier.weight(1f).height(48.dp)) { enterPip() }
                    }
                }
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val p = player
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (isLive) playChannel(Repo.zapIndex - 1) else showOverlay()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (isLive) playChannel(Repo.zapIndex + 1) else showOverlay()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive) { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive) { p.seekTo(p.currentPosition + 10_000); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isLive && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { showOverlay(); return true }
                togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; playing = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; playing = false; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveProgress(push: Boolean) {
        if (histKind.isEmpty() || isLive) return
        lastSaved = System.currentTimeMillis()
        val dur = player.duration.coerceAtLeast(0)
        var pos = player.currentPosition
        if (dur > 0 && (pos > dur * 0.95 || dur - pos < 60_000)) pos = 0 // finished: start over next time
        Repo.recordPlay(histKind, histId, title, histIcon, histExt, pos, dur, push, histPl)
    }

    override fun onStop() {
        saveProgress(push = true)
        // TV: stop when the screen is left. Phone: keep playing (audio) in the background.
        if (isTv) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        listener?.let { player.removeListener(it) }
        listener = null
        if (isFinishing) {
            // Left with Back: stop playback and dismiss the media notification.
            player.stop()
            player.clearMediaItems()
            if (!isTv) stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PL = "pl"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_ICON = "icon"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_RESUME = "resume"

        fun live(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_LIVE, true))
        }

        /** [kind] is "Movie" or "Episode" to be remembered in continue watching; empty for one-offs like catch-up. */
        fun vod(
            context: Context, title: String, url: String,
            kind: String = "", itemId: String = "", icon: String = "", ext: String = "", resumeMs: Long = 0L,
            playlistId: String = "",
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_LIVE, false).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ITEM, itemId).putExtra(EXTRA_ICON, icon)
                    .putExtra(EXTRA_EXT, ext).putExtra(EXTRA_RESUME, resumeMs).putExtra(EXTRA_PL, playlistId)
            )
        }
    }
}
