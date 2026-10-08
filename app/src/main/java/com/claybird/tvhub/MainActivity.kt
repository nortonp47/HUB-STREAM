package com.claybird.tvhub

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
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
        setContent {
            HubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
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

private enum class Screen { Home, Diagnostics }

@Composable
fun HubApp() {
    var screen by remember { mutableStateOf(Screen.Home) }
    val context = LocalContext.current

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (screen) {
        Screen.Home -> HomeScreen(
            onSearch = { context.startActivity(Intent(context, VoiceSearchActivity::class.java)) },
            onDiagnostics = { screen = Screen.Diagnostics },
        )
        Screen.Diagnostics -> DiagnosticsScreen()
    }
}

@Composable
fun HomeScreen(onSearch: () -> Unit, onDiagnostics: () -> Unit) {
    var note by remember { mutableStateOf("") }
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTile.requestFocus() } }

    val tiles = listOf<Pair<String, () -> Unit>>(
        "Search" to onSearch,
        "Live TV" to { note = "Live TV arrives in Phase 1." },
        "Guide" to { note = "The cable-style guide arrives in Phase 2." },
        "Movies & Shows" to { note = "On-demand arrives in Phase 1." },
        "Favorites" to { note = "Favorites arrive in Phase 2." },
        "Add-ons" to { note = "Stremio add-ons arrive in Phase 5." },
        "Diagnostics" to onDiagnostics,
        "Settings" to { note = "Settings arrive in Phase 6." },
    )

    Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Text(
            text = "Hub",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = HubGreen,
        )
        Text(
            text = "Live TV, guide and streaming in one place",
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

        Spacer(Modifier.height(12.dp))
        Text(text = note, fontSize = 18.sp, color = HubMuted)
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
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { !it.isVirtual }
            .map { it.name }
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
