package com.claybird.tvhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Opens straight into search. When started with [ACTION_VOICE_SEARCH]
 * (what the star button will be mapped to) it starts listening immediately.
 * Started from the home screen it just shows the keyboard.
 *
 * The on-screen keyboard is drawn by this app, so it never depends on the
 * system keyboard that goes missing on the Streamer. Speech is handled
 * in-app (no system chooser or popup).
 */
class VoiceSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoListen = intent?.action == ACTION_VOICE_SEARCH
        setContent {
            HubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VoiceSearchScreen(autoListen = autoListen)
                }
            }
        }
    }

    companion object {
        const val ACTION_VOICE_SEARCH = "com.claybird.tvhub.VOICE_SEARCH"
    }
}

private val keyRows = listOf(
    "ABCDEFGHIJ",
    "KLMNOPQRST",
    "UVWXYZ0123",
    "456789'&.-",
)

private fun describeSpeechError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error (code $code)."
    SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error (code $code)."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing (code $code)."
    SpeechRecognizer.ERROR_NETWORK -> "Network error (code $code)."
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (code $code)."
    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that (code $code). Try again or type it."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy (code $code)."
    SpeechRecognizer.ERROR_SERVER -> "Speech server error (code $code)."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard (code $code). The mic may not be picking up sound."
    else -> "Speech error (code $code)."
}

@Composable
fun VoiceSearchScreen(autoListen: Boolean) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                status = "Listening... speak now"
            }

            override fun onBeginningOfSpeech() {
                status = "Hearing you..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                level = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                status = "Processing..."
            }

            override fun onError(error: Int) {
                listening = false
                status = describeSpeechError(error)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    query = text
                    status = ""
                } else {
                    status = "Didn't catch that. Try again or type it."
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) query = text
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
            }
        } else {
            null
        }
    }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    val startListening: () -> Unit = {
        if (recognizer == null) {
            status = "No speech recognizer on this device. Use the keyboard."
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            query = ""
            status = "Starting..."
            recognizer.startListening(intent)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            status = "Microphone permission was denied. Allow it under Settings > Apps > Hub > Permissions."
        }
    }

    val listen: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        runCatching { firstKey.requestFocus() }
        if (autoListen && !autoStarted) {
            autoStarted = true
            listen()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("Search", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HubSurface)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = if (query.isEmpty()) "Type or press Speak" else query,
                fontSize = 24.sp,
                color = if (query.isEmpty()) HubMuted else Color.White,
            )
        }
        val levelText = if (listening) "   (mic level ${"%.1f".format(level)})" else ""
        Text(
            text = status + levelText,
            fontSize = 16.sp,
            color = HubMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(10.dp))

        keyRows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { colIndex, ch ->
                    FocusButton(
                        label = ch.toString(),
                        modifier = Modifier.weight(1f).height(52.dp),
                        focusRequester = if (rowIndex == 0 && colIndex == 0) firstKey else null,
                    ) { query += ch }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FocusButton("Speak", Modifier.weight(2f).height(56.dp)) { listen() }
            FocusButton("Space", Modifier.weight(2f).height(56.dp)) { query += " " }
            FocusButton("Delete", Modifier.weight(2f).height(56.dp)) {
                query = query.dropLast(1)
            }
            FocusButton("Clear", Modifier.weight(2f).height(56.dp)) { query = "" }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (query.isBlank()) {
                ""
            } else {
                "Results for \"$query\" will appear here once search is built (Phase 4)."
            },
            fontSize = 18.sp,
            color = HubMuted,
        )
    }
}
