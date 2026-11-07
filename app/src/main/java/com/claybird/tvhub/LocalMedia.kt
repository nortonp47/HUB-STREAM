package com.claybird.tvhub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LocalEntry(val docId: String, val name: String, val isDir: Boolean)

private val mediaExt = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "webm", "wmv", "mpg", "mpeg", "3gp", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

/** Your own video and audio files: pick a folder once, then browse and play what's inside. */
@Composable
fun LocalScreen(isTv: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hub", Context.MODE_PRIVATE) }
    var tree by remember { mutableStateOf(prefs.getString("local_tree", null)) }
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var entries by remember { mutableStateOf<List<LocalEntry>>(emptyList()) }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString("local_tree", uri.toString()).apply()
            tree = uri.toString(); stack = emptyList()
        }
    }

    LaunchedEffect(tree, stack) {
        val t = tree ?: return@LaunchedEffect
        msg = ""
        entries = withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(t)
                val docId = stack.lastOrNull()?.first ?: DocumentsContract.getTreeDocumentId(treeUri)
                val child = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val out = ArrayList<LocalEntry>()
                ctx.contentResolver.query(
                    child,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (dir || mime.startsWith("video/") || mime.startsWith("audio/") || ext in mediaExt) out += LocalEntry(c.getString(0), name, dir)
                    }
                }
                out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            }.getOrElse { msg = "Couldn't read that folder. Choose it again."; emptyList() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("My media", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HubGreen)
        Text(
            if (tree == null) "Choose a folder on this device (or a USB drive). Anything playable inside it shows up here."
            else "Folder: " + Uri.decode(tree!!).substringAfterLast(':') + stack.joinToString("") { "/" + it.second },
            fontSize = 13.sp, color = HubMuted, modifier = Modifier.padding(vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton(if (tree == null) "Choose folder" else "Change folder", Modifier.weight(1f).height(46.dp), textSize = 15.sp) {
                try { picker.launch(null) } catch (e: ActivityNotFoundException) { msg = "This device has no file picker. Plug in a keyboard-friendly file manager or use a phone." }
            }
            if (stack.isNotEmpty()) {
                Spacer8()
                FocusButton("Up", Modifier.width(90.dp).height(46.dp), textSize = 15.sp) { stack = stack.dropLast(1) }
            }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 14.sp, color = HubMuted, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (tree != null && entries.isEmpty() && msg.isBlank()) item { Text("Nothing playable here.", color = HubMuted, modifier = Modifier.padding(8.dp)) }
            items(entries, key = { it.docId }) { e ->
                var focused by remember { mutableStateOf(false) }
                Text(
                    (if (e.isDir) "📁  " else "▶  ") + e.name,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (focused) Color.Black else Color.White,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (focused) HubGreen else HubSurface)
                        .clickable {
                            if (e.isDir) stack = stack + (e.docId to e.name)
                            else {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), e.docId)
                                PlayerActivity.vod(ctx, e.name.substringBeforeLast('.'), uri.toString())
                            }
                        }.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
