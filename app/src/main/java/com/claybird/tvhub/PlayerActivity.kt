package com.claybird.tvhub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen player. Live: Up/Down changes channel, OK shows info. On-demand: Left/Right
 * seek 10 seconds, OK pauses, Up/Down show the progress bar. Back leaves.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var overlay by mutableStateOf(true)
    private var message by mutableStateOf("")
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var paused by mutableStateOf(false)
    private var isLive = true
    private var altUrl: String? = null
    private var triedAlt = false
    private var hideJob: Job? = null
    private var epgJob: Job? = null

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
        val exo = buildPlayer()
        player = exo

        if (isLive) {
            if (Repo.zapList.isEmpty() || Repo.api == null) { finish(); return }
            playChannel(Repo.zapIndex)
        } else {
            val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
            title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            subtitle = ""
            start(url)
        }

        setContent {
            HubTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                this.player = exo
                            }
                        },
                    )
                    if (overlay || paused || message.isNotEmpty()) InfoOverlay()
                }
            }
        }
        showOverlay()

        if (!isLive) {
            lifecycleScope.launch {
                while (true) {
                    player?.let { positionMs = it.currentPosition; durationMs = it.duration.coerceAtLeast(0) }
                    delay(500)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun InfoOverlay() {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 20.sp, color = HubMuted)
                if (message.isNotBlank()) Text(message, fontSize = 18.sp, color = HubGreen)
                if (paused) Text("Paused", fontSize = 20.sp, color = HubGreen)
            }
            if (!isLive && durationMs > 0) {
                Column(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 48.dp, vertical = 28.dp)
                ) {
                    Text("${fmt(positionMs)} / ${fmt(durationMs)}", fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f)).height(6.dp).background(HubGreen))
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

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Hub/1.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true
            )
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val alt = altUrl
                if (alt != null && !triedAlt) {
                    triedAlt = true
                    message = "Trying another stream format..."
                    exo.setMediaItem(MediaItem.fromUri(alt))
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    message = "Can't play this: ${error.errorCodeName}"
                    showOverlay(stayMs = 8000)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && message.startsWith("Trying")) message = ""
                if (state == Player.STATE_ENDED && !isLive) finish()
            }
        })
        return exo
    }

    private fun start(url: String, alt: String? = null) {
        altUrl = alt
        triedAlt = false
        message = ""
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        paused = false
    }

    private fun playChannel(index: Int) {
        val list = Repo.zapList
        if (list.isEmpty()) return
        val i = ((index % list.size) + list.size) % list.size
        Repo.zapIndex = i
        val c = list[i]
        val item = Repo.playItemFor(c)
        title = c.name
        subtitle = ""
        start(item.url, item.altUrl)
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

    private fun showOverlay(stayMs: Long = 4500) {
        overlay = true
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(stayMs)
            overlay = false
            if (!paused) message = if (message.startsWith("Can't")) message else ""
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val p = player ?: return super.dispatchKeyEvent(event)
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
                paused = !p.playWhenReady
                p.playWhenReady = !p.playWhenReady
                paused = !p.playWhenReady
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; paused = false; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; paused = true; return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun live(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_LIVE, true))
        }

        fun vod(context: Context, title: String, url: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_LIVE, false).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_URL, url)
            )
        }
    }
}
