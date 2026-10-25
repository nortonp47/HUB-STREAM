package com.claybird.tvhub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Records remote-control key presses so we can see what the star button sends. */
object KeyLog {
    var capturing = false
    val entries = mutableStateListOf<String>()

    fun add(line: String) {
        entries.add(0, line)
        while (entries.size > 12) entries.removeAt(entries.lastIndex)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phone: the media controls are a notification, so ask for permission once (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            HubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HubApp()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyLog.capturing && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val device = event.device?.name ?: "unknown device"
            KeyLog.add(
                KeyEvent.keyCodeToString(event.keyCode) +
                    "  (code ${event.keyCode}, scan ${event.scanCode}, $device)"
            )
        }
        return super.dispatchKeyEvent(event)
    }
}

sealed class Screen {
    object Home : Screen()
    object Setup : Screen()
    object Settings : Screen()
    object Account : Screen()
    object Diagnostics : Screen()
    object Guide : Screen()
    object Sports : Screen()
    class Browse(val kind: Kind, val favorites: Boolean = false) : Screen()
    class Episodes(val show: Show, val back: Screen) : Screen()
}

@Composable
fun HubApp() {
    val context = LocalContext.current
    Repo.init(context)
    Cloud.init(context)
    Epg.init(context)
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val start: Screen = if (isTv) Screen.Home else Screen.Guide
    var screen by remember { mutableStateOf<Screen>(if (Repo.hasLogin) start else Screen.Setup) }

    // Download (or read from cache) the channel lists whenever there is a login and nothing loaded yet.
    LaunchedEffect(Repo.activeId, Repo.loaded) {
        if (Repo.hasLogin && !Repo.loaded) Repo.refresh(force = false)
    }
    // Then the program guide, in the background.
    LaunchedEffect(Repo.activeId, Repo.loaded) {
        if (Repo.loaded) Epg.load(force = false)
    }
    // Signed in: pull playlists and favorites from the account when the app opens.
    LaunchedEffect(Cloud.email) {
        if (Cloud.signedIn) {
            Repo.syncWithAccount()
            if (screen == Screen.Setup && Repo.hasLogin) screen = start
        }
    }
    // Removing the last playlist sends you back to setup.
    LaunchedEffect(Repo.hasLogin) {
        if (!Repo.hasLogin && screen != Screen.Account) screen = Screen.Setup
    }

    BackHandler(enabled = screen != start && !((screen == Screen.Setup || screen == Screen.Account) && !Repo.hasLogin && screen == Screen.Setup)) {
        screen = when (val s = screen) {
            is Screen.Episodes -> s.back
            else -> start
        }
    }

    val watchLive: (List<Channel>, Int) -> Unit = { list, index ->
        Repo.zapList = list
        Repo.zapIndex = index
        PlayerActivity.live(context)
    }

    val body: @Composable () -> Unit = {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onSearch = { context.startActivity(Intent(context, VoiceSearchActivity::class.java)) },
                onOpen = { screen = it },
            )
            Screen.Setup -> SetupScreen(isTv, onDone = { screen = start }, onAccount = { screen = Screen.Account })
            Screen.Account -> AccountScreen(isTv, onDone = { screen = if (Repo.hasLogin) start else Screen.Setup })
            Screen.Settings -> SettingsScreen(isTv, onOpen = { screen = it })
            Screen.Diagnostics -> DiagnosticsScreen()
            Screen.Guide -> GuideScreen(isTv = isTv, onWatch = watchLive)
            Screen.Sports -> SportsScreen(isTv = isTv, onWatch = { lists, itemIndex, streamIndex ->
                // One channel per game goes in the zap list; the next best become automatic fallbacks.
                val chosen = lists[itemIndex][streamIndex]
                Repo.zapList = lists.mapIndexed { i, l -> if (i == itemIndex) chosen else l.first() }
                Repo.zapIndex = itemIndex
                Repo.fallback.clear()
                lists[itemIndex].filter { it.id != chosen.id }.take(2).forEach { Repo.fallback.add(it) }
                PlayerActivity.live(context)
            })
            is Screen.Browse -> {
                val play: (List<Row3>, Int) -> Unit = { rows, index ->
                    when (s.kind) {
                        Kind.Live -> watchLive(rows.map { it.ref as Channel }, index)
                        Kind.Movies -> {
                            val m = rows[index].ref as Movie
                            PlayerActivity.vod(context, m.name, Repo.api!!.movieUrl(m.id, m.ext))
                        }
                        Kind.Series -> {}
                    }
                }
                val open: (Show) -> Unit = { screen = Screen.Episodes(it, s) }
                if (isTv) BrowseScreen(s.kind, s.favorites, play, open)
                else PhoneBrowseScreen(s.kind, s.favorites, play, open)
            }
            is Screen.Episodes -> {
                val playEp: (Show, Episode) -> Unit = { show, ep ->
                    PlayerActivity.vod(context, "${show.name}  S${ep.season}E${ep.number}", Repo.api!!.episodeUrl(ep.id, ep.ext))
                }
                if (isTv) EpisodesScreen(s.show, playEp) else PhoneEpisodesScreen(s.show, playEp)
            }
        }
    }

    if (isTv || screen == Screen.Setup || screen == Screen.Account) {
        body()
    } else {
        // Phone: tabs along the bottom.
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { body() }
            val current = screen
            val tabs = listOf(
                Triple("Guide", Screen.Guide, current == Screen.Guide),
                Triple("Live", Screen.Browse(Kind.Live), current is Screen.Browse && current.kind == Kind.Live),
                Triple("Sports", Screen.Sports, current == Screen.Sports),
                Triple("Movies", Screen.Browse(Kind.Movies), current is Screen.Browse && current.kind == Kind.Movies),
                Triple("Shows", Screen.Browse(Kind.Series),
                    (current is Screen.Browse && current.kind == Kind.Series) || current is Screen.Episodes),
                Triple("More", Screen.Settings, current == Screen.Settings),
            )
            Row(modifier = Modifier.fillMaxWidth().background(HubSurface)) {
                tabs.forEach { (label, target, on) ->
                    Box(
                        modifier = Modifier.weight(1f).height(56.dp).clickable { screen = target },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) HubGreen else Color.White, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onSearch: () -> Unit, onOpen: (Screen) -> Unit) {
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTile.requestFocus() } }

    val tiles = listOf<Pair<String, () -> Unit>>(
        "Guide" to { onOpen(Screen.Guide) },
        "Sports Now" to { onOpen(Screen.Sports) },
        "Live TV" to { onOpen(Screen.Browse(Kind.Live)) },
        "Movies" to { onOpen(Screen.Browse(Kind.Movies)) },
        "Shows" to { onOpen(Screen.Browse(Kind.Series)) },
        "Search" to onSearch,
        "Favorite Channels" to { onOpen(Screen.Browse(Kind.Live, favorites = true)) },
        "Favorite Movies" to { onOpen(Screen.Browse(Kind.Movies, favorites = true)) },
        "Favorite Shows" to { onOpen(Screen.Browse(Kind.Series, favorites = true)) },
        "Settings" to { onOpen(Screen.Settings) },
        "Diagnostics" to { onOpen(Screen.Diagnostics) },
    )

    Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Text(
            text = "Hub",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = HubGreen,
        )
        Text(
            text = Repo.status.ifBlank { "Live TV, movies and shows in one place" },
            fontSize = 18.sp,
            color = HubMuted,
        )
        Spacer(Modifier.height(36.dp))

        tiles.chunked(4).forEachIndexed { rowIndex, rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowTiles.forEachIndexed { colIndex, tile ->
                    FocusButton(
                        label = tile.first,
                        modifier = Modifier.width(210.dp).height(110.dp),
                        focusRequester = if (rowIndex == 0 && colIndex == 0) firstTile else null,
                        textSize = 22.sp,
                        onClick = tile.second,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tip: press and hold OK on any channel, movie or show to favorite it.",
            fontSize = 16.sp,
            color = HubMuted,
        )
    }
}

private fun audioTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    else -> "type $type"
}

/**
 * Records from one audio source for a few seconds and returns the loudest
 * sample seen (0..32767) plus a short description, or -1 on failure.
 */
private fun peakLevel(source: Int, seconds: Int): Pair<Int, String> {
    val rate = 16000
    val minBuf = AudioRecord.getMinBufferSize(
        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuf <= 0) return -1 to "unsupported format"
    val record = try {
        AudioRecord(
            source, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
    } catch (e: Exception) {
        return -1 to "could not open: ${e.message}"
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        return -1 to "not initialized"
    }
    val buf = ShortArray(rate / 10)
    var peak = 0
    var samples = 0
    var device = "unknown input"
    try {
        record.startRecording()
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            val n = record.read(buf, 0, buf.size)
            if (n < 0) return -1 to "read error $n"
            samples += n
            for (i in 0 until n) {
                val v = abs(buf[i].toInt())
                if (v > peak) peak = v
            }
            record.routedDevice?.let { device = "${audioTypeName(it.type)}: ${it.productName}" }
        }
    } catch (e: Exception) {
        return -1 to "error: ${e.message}"
    } finally {
        runCatching { record.stop() }
        record.release()
    }
    return peak to "$samples samples from $device"
}

/**
 * Answers the open questions about this particular Streamer:
 * what key the star button sends, whether a speech recognizer exists,
 * and which microphones apps are allowed to see.
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        KeyLog.capturing = true
        onDispose { KeyLog.capturing = false }
    }

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    @Suppress("DEPRECATION")
    val recognizerHandlers = remember {
        context.packageManager
            .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            .size
    }
    val audioInputs = remember {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { "${audioTypeName(it.type)}: ${it.productName}" }
    }
    val inputDevices = remember {
        InputDevice.getDeviceIds()
            .toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> !device.isVirtual }
            .map { device -> device.name }
    }

    var micResult by remember { mutableStateOf("") }
    var micBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val micFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { micFocus.requestFocus() } }

    val runMicTest: () -> Unit = {
        micBusy = true
        scope.launch {
            val lines = mutableListOf<String>()
            val sources = listOf(
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            )
            for ((label, source) in sources) {
                micResult = (lines + "Recording $label for 3 seconds. Talk now...").joinToString("\n")
                val (peak, detail) = withContext(Dispatchers.Default) { peakLevel(source, 3) }
                lines += if (peak < 0) {
                    "$label: failed ($detail)"
                } else {
                    "$label: peak $peak of 32767 ($detail)"
                }
            }
            lines += "A peak above about 500 means the mic heard you; near 0 means silence."
            micResult = lines.joinToString("\n")
            micBusy = false
        }
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runMicTest() else micResult = "Microphone permission was denied."
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Diagnostics", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HubGreen)
            Spacer(Modifier.height(16.dp))
            Text("Speech recognizer available: $recognizerAvailable", fontSize = 18.sp)
            Text("Apps that can handle speech requests: $recognizerHandlers", fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Text("Microphones apps can see:", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (audioInputs.isEmpty()) {
                Text("  none", fontSize = 16.sp, color = HubMuted)
            } else {
                audioInputs.forEach { Text("  $it", fontSize = 16.sp, color = HubMuted) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Input devices:", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            inputDevices.forEach { Text("  $it", fontSize = 16.sp, color = HubMuted) }
            Spacer(Modifier.height(16.dp))
            FocusButton(
                label = if (micBusy) "Testing..." else "Mic test",
                modifier = Modifier.fillMaxWidth().height(52.dp),
                focusRequester = micFocus,
                textSize = 18.sp,
            ) {
                if (!micBusy) {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) runMicTest() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            if (micResult.isNotEmpty()) {
                Text(micResult, fontSize = 16.sp, color = HubMuted)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text("Press buttons on the remote", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                "Try the star button, voice button and any others. If a button never shows up here, the system keeps it for itself.",
                fontSize = 16.sp,
                color = HubMuted,
            )
            Spacer(Modifier.height(16.dp))
            KeyLog.entries.forEach { Text(it, fontSize = 16.sp, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
