package com.claybird.tvhub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A remote-friendly keyboard drawn by the app (the system keyboard is unreliable on the Streamer). */
@Composable
fun OnScreenKeyboard(
    onChar: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    firstKey: FocusRequester? = null,
) {
    var shift by remember { mutableStateOf(false) }
    val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm._:", "/@&'?=#%+~")
    rows.forEachIndexed { r, row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEachIndexed { c, ch ->
                val shown = if (shift) ch.uppercaseChar() else ch
                FocusButton(
                    label = shown.toString(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    focusRequester = if (r == 0 && c == 0) firstKey else null,
                ) { onChar(shown.toString()) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusButton(if (shift) "ABC ON" else "abc", Modifier.weight(2f).height(52.dp)) { shift = !shift }
        FocusButton("Space", Modifier.weight(3f).height(52.dp)) { onChar(" ") }
        FocusButton("Delete", Modifier.weight(2f).height(52.dp)) { onDelete() }
        FocusButton("Clear", Modifier.weight(2f).height(52.dp)) { onClear() }
    }
}

/** First-run login to the Xtream panel. Entered here, stored only on this device. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    var server by remember { mutableStateOf(Repo.creds?.server ?: "http://") }
    var user by remember { mutableStateOf(Repo.creds?.username ?: "") }
    var pass by remember { mutableStateOf(Repo.creds?.password ?: "") }
    var active by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    val labels = listOf("Server URL", "Username", "Password")
    val values = listOf(server, user, pass)
    fun update(f: (String) -> String) {
        when (active) {
            0 -> server = f(server)
            1 -> user = f(user)
            else -> pass = f(pass)
        }
    }

    val connect: () -> Unit = {
        if (!busy) {
            busy = true
            message = "Checking login..."
            scope.launch {
                val c = Credentials(server.trim(), user.trim(), pass.trim())
                val result = withContext(Dispatchers.IO) {
                    runCatching { XtreamApi(c).authenticate() }
                }
                result.onSuccess {
                    Repo.saveLogin(c)
                    message = "Connected: $it"
                    onDone()
                }.onFailure {
                    message = it.message ?: "Couldn't connect"
                }
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("Connect your IPTV service", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Text(
            "Choose a field, then type with the keyboard below. Stored only on this device.",
            fontSize = 16.sp, color = HubMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                Column(
                    modifier = Modifier
                        .weight(if (i == 0) 1.6f else 1f)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape)
                        .background(HubSurface)
                        .then(
                            if (active == i) Modifier.border(3.dp, HubGreen, shape)
                            else if (focused) Modifier.border(3.dp, Color.White, shape)
                            else Modifier
                        )
                        .clickable { active = i }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = HubMuted)
                    Text(
                        text = values[i].ifEmpty { " " },
                        fontSize = 20.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OnScreenKeyboard(
            onChar = { ch -> update { it + ch } },
            onDelete = { update { it.dropLast(1) } },
            onClear = { update { "" } },
            firstKey = firstKey,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton(
                label = if (busy) "Connecting..." else "Connect",
                modifier = Modifier.width(220.dp).height(56.dp),
            ) { connect() }
            Text(message, fontSize = 16.sp, color = HubMuted)
        }
    }
}

/** Account info, refresh and log out. */
@Composable
fun SettingsScreen(onLoggedOut: () -> Unit) {
    var info by remember { mutableStateOf("Checking account...") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        info = withContext(Dispatchers.IO) {
            runCatching { Repo.api!!.authenticate() }.getOrElse { it.message ?: "Couldn't reach the server" }
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Spacer(Modifier.height(16.dp))
        Text("Server: ${Repo.creds?.server ?: ""}", fontSize = 18.sp)
        Text("User: ${Repo.creds?.username ?: ""}", fontSize = 18.sp)
        Text("Account: $info", fontSize = 18.sp, color = HubMuted)
        Text(Repo.status, fontSize = 16.sp, color = HubMuted)
        Spacer(Modifier.height(24.dp))
        FocusButton(
            label = if (busy) "Refreshing..." else "Refresh channel lists now",
            modifier = Modifier.width(380.dp).height(56.dp),
            focusRequester = first,
        ) {
            if (!busy) {
                busy = true
                scope.launch { Repo.refresh(force = true); busy = false }
            }
        }
        Spacer(Modifier.height(12.dp))
        FocusButton("Log out", Modifier.width(380.dp).height(56.dp)) {
            Repo.logout()
            onLoggedOut()
        }
    }
}
