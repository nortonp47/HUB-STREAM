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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

class Field(val label: String, val secret: Boolean = false, val type: KeyboardType = KeyboardType.Text)

/**
 * Text entry for both devices. Phone: normal text fields with the system keyboard.
 * TV: choose a field with the remote, then type on the app's own keyboard.
 */
@Composable
fun FormFields(isTv: Boolean, fields: List<Field>, values: SnapshotStateList<String>, firstKey: FocusRequester?) {
    if (!isTv) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEachIndexed { i, f ->
                OutlinedTextField(
                    value = values[i], onValueChange = { values[i] = it }, singleLine = true,
                    label = { Text(f.label) }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (f.secret) KeyboardType.Password else f.type),
                )
            }
        }
        return
    }
    var active by remember { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        fields.forEachIndexed { i, f ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .weight(if (f.type == KeyboardType.Uri) 1.6f else 1f)
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
                Text(f.label, fontSize = 13.sp, color = HubMuted)
                Text(values[i].ifEmpty { " " }, fontSize = 20.sp, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    OnScreenKeyboard(
        onChar = { ch -> values[active] = values[active] + ch },
        onDelete = { values[active] = values[active].dropLast(1) },
        onClear = { values[active] = "" },
        firstKey = firstKey,
    )
}

/** Add a playlist: the provider's Xtream server, username and password. Stored on this device (and your account if signed in). */
@Composable
fun SetupScreen(isTv: Boolean, onDone: () -> Unit, onAccount: (() -> Unit)?) {
    val fields = remember {
        listOf(Field("Name (optional)"), Field("Server URL", type = KeyboardType.Uri), Field("Username"), Field("Password", secret = !isTv))
    }
    val values = remember { mutableStateListOf("", "http://", "", "") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { firstKey.requestFocus() } }

    val connect: () -> Unit = {
        if (!busy) {
            busy = true
            message = "Checking login..."
            scope.launch {
                val c = Credentials(values[1].trim(), values[2].trim(), values[3].trim())
                val result = withContext(Dispatchers.IO) { runCatching { XtreamApi(c).authenticate() } }
                result.onSuccess {
                    Repo.addPlaylist(values[0].trim(), c)
                    message = "Connected: $it"
                    onDone()
                }.onFailure { message = it.message ?: "Couldn't connect" }
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (isTv) 48.dp else 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState())) {
        Text(if (Repo.hasLogin) "Add a playlist" else "Connect your IPTV service", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Spacer(Modifier.height(10.dp))
        FormFields(isTv, fields, values, firstKey)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton(if (busy) "Connecting..." else "Connect", Modifier.width(200.dp).height(54.dp)) { connect() }
            if (onAccount != null && !Repo.hasLogin) {
                FocusButton("Sign in to HUB account", Modifier.width(280.dp).height(54.dp)) { onAccount() }
            }
        }
        Text(message, fontSize = 15.sp, color = HubMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Sign in or create a HUB account so playlists and favorites follow you to every device. */
@Composable
fun AccountScreen(isTv: Boolean, onDone: () -> Unit) {
    val fields = remember { listOf(Field("Email", type = KeyboardType.Email), Field("Password", secret = !isTv)) }
    val values = remember { mutableStateListOf("", "") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { firstKey.requestFocus() } }

    fun run(create: Boolean) {
        if (busy) return
        val mail = values[0].trim()
        val pw = values[1]
        if (mail.isBlank() || pw.length < 6) { message = "Enter your email and a password of at least 6 characters."; return }
        busy = true
        message = if (create) "Creating account..." else "Signing in..."
        scope.launch {
            try {
                if (create) {
                    val signedIn = Cloud.signUp(mail, pw)
                    if (!signedIn) { message = "Account created. Check your email to confirm it, then come back and press Sign in."; busy = false; return@launch }
                } else {
                    Cloud.signIn(mail, pw)
                }
                message = "Signed in. Syncing..."
                message = Repo.syncWithAccount()
                onDone()
            } catch (e: Exception) {
                message = e.message ?: "Couldn't sign in"
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (isTv) 48.dp else 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState())) {
        Text("HUB account", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Text("Your playlists and favorites follow you to every device you sign in on.", fontSize = 15.sp, color = HubMuted)
        Spacer(Modifier.height(10.dp))
        FormFields(isTv, fields, values, firstKey)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusButton(if (busy) "Working..." else "Sign in", Modifier.width(200.dp).height(54.dp)) { run(false) }
            FocusButton("Create account", Modifier.width(240.dp).height(54.dp)) { run(true) }
        }
        Text(message, fontSize = 15.sp, color = HubMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Account, playlists, refresh. */
@Composable
fun SettingsScreen(isTv: Boolean, onOpen: (Screen) -> Unit) {
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val active = Repo.active
    LaunchedEffect(Repo.activeId) {
        if (isTv) runCatching { first.requestFocus() }
        info = "Checking account..."
        info = withContext(Dispatchers.IO) {
            runCatching { Repo.api!!.authenticate() }.getOrElse { it.message ?: "Couldn't reach the server" }
        }
    }
    val pad = if (isTv) 48.dp else 20.dp
    Column(modifier = Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Spacer(Modifier.height(14.dp))

        Text("HUB account", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (Cloud.signedIn) {
            Text("Signed in as ${Cloud.email}", fontSize = 16.sp, color = HubMuted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton(if (busy) "Syncing..." else "Sync now", Modifier.width(180.dp).height(50.dp), focusRequester = first) {
                    if (!busy) { busy = true; scope.launch { syncMsg = Repo.syncWithAccount(); busy = false } }
                }
                FocusButton("Sign out", Modifier.width(180.dp).height(50.dp)) { Cloud.signOut(); syncMsg = "Signed out. Playlists stay on this device." }
            }
            if (syncMsg.isNotBlank()) Text(syncMsg, fontSize = 14.sp, color = HubMuted)
        } else {
            Text("Not signed in. Sign in to keep your playlists and favorites on all your devices.", fontSize = 15.sp, color = HubMuted)
            Spacer(Modifier.height(6.dp))
            FocusButton("Sign in or create account", Modifier.width(300.dp).height(50.dp), focusRequester = first) { onOpen(Screen.Account) }
        }

        Spacer(Modifier.height(22.dp))
        Text("Playlists", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Repo.playlists.forEach { pl ->
            val isActive = pl.id == Repo.activeId
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pl.name + if (isActive) "   (in use)" else "", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        color = if (isActive) HubGreen else Color.White)
                    Text("${pl.username} @ ${pl.server}", fontSize = 13.sp, color = HubMuted, maxLines = 1)
                }
                if (!isActive) FocusButton("Use", Modifier.width(90.dp).height(46.dp)) { Repo.selectPlaylist(pl.id) }
                FocusButton("Remove", Modifier.width(110.dp).height(46.dp)) { Repo.removePlaylist(pl.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        FocusButton("Add another playlist", Modifier.width(300.dp).height(50.dp)) { onOpen(Screen.Setup) }
        if (active != null) {
            Spacer(Modifier.height(6.dp))
            Text("In use: ${active.name}. $info", fontSize = 14.sp, color = HubMuted)
            Text(Repo.status, fontSize = 14.sp, color = HubMuted)
            Spacer(Modifier.height(10.dp))
            FocusButton(if (busy) "Refreshing..." else "Refresh channel lists now", Modifier.width(340.dp).height(50.dp)) {
                if (!busy) { busy = true; scope.launch { Repo.refresh(force = true); Epg.load(force = true); busy = false } }
            }
        }
    }
}
