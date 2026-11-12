package com.aven.downloader.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aven.downloader.AvenApp
import com.aven.downloader.data.Settings
import com.aven.downloader.data.StorageSaver
import com.aven.downloader.ui.theme.GlassCard
import com.aven.downloader.ui.theme.GlassColors
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.ui.theme.glassBorder
import com.aven.downloader.util.Formatters
import com.aven.downloader.util.UrlParser
import kotlinx.coroutines.launch

private val shortcuts = listOf(
    "YouTube" to "https://m.youtube.com",
    "Facebook" to "https://m.facebook.com",
    "Instagram" to "https://www.instagram.com",
    "TikTok" to "https://www.tiktok.com",
    "Google" to "https://www.google.com",
    "Archive.org" to "https://archive.org",
    "GitHub" to "https://github.com",
    "Wikipedia" to "https://www.wikipedia.org"
)

/** IDM-style browser tab: address bar, page, floating download button, found-files sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    padding: PaddingValues,
    onSupportedVideo: (String) -> Unit,
    onOpenGrabber: () -> Unit,
    onQueued: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AvenApp.instance.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editorFor by remember { mutableStateOf<Found?>(null) }

    val url = BrowserHolder.url
    LaunchedEffect(url, editing) { if (!editing) address = url }

    // another screen asked the browser to open a link ("Refresh link in browser", shortcuts)
    val pending = BrowserHolder.pendingLoad
    LaunchedEffect(pending) {
        pending?.let { BrowserHolder.open(it); BrowserHolder.pendingLoad = null }
    }
    // the page itself started a download
    val pd = BrowserHolder.pendingDownload
    LaunchedEffect(pd) {
        if (pd != null) {
            BrowserHolder.pendingDownload = null
            if (settings.showDownloadEditor) editorFor = pd
            else { BrowserHolder.enqueue(pd, pd.name, 0, 0); onQueued() }
        }
    }

    fun startDownload(f: Found) {
        val target = BrowserHolder.refreshTarget
        if (target != null) {
            BrowserHolder.refreshTarget = null
            scope.launch {
                AvenApp.instance.downloads.replaceLink(target, BrowserHolder.sourceFor(f, f.name), f.type == "hls")
                onQueued()
            }
            return
        }
        if (settings.showDownloadEditor) editorFor = f
        else scope.launch { BrowserHolder.enqueue(f, f.name, 0, 0); onQueued() }
    }

    DisposableEffect(Unit) { onDispose { BrowserHolder.detach() } }
    BackHandler(enabled = BrowserHolder.customView != null || BrowserHolder.canBack) {
        if (BrowserHolder.customView != null) BrowserHolder.hideCustom() else BrowserHolder.back()
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        // ---- address bar ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { BrowserHolder.back() }, enabled = BrowserHolder.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            IconButton(onClick = { BrowserHolder.forward() }, enabled = BrowserHolder.canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
            }
            TextField(
                value = address,
                onValueChange = { address = it; editing = true },
                singleLine = true,
                placeholder = { Text("Search or type a link") },
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = GlassColors.fillStrong,
                    unfocusedContainerColor = GlassColors.fill,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { editing = false; BrowserHolder.open(address) }),
                modifier = Modifier.weight(1f).height(52.dp)
            )
            IconButton(onClick = {
                if (BrowserHolder.progress in 1..99) BrowserHolder.stop() else BrowserHolder.reload()
            }) {
                Icon(if (BrowserHolder.progress in 1..99) Icons.Filled.Close else Icons.Filled.Refresh, "Reload")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Grabber (scan this page)") }, onClick = { menu = false; onOpenGrabber() })
                    DropdownMenuItem(
                        text = { Text(if (BrowserHolder.desktop) "Desktop site: on" else "Desktop site: off") },
                        onClick = { menu = false; BrowserHolder.setDesktop(!BrowserHolder.desktop) }
                    )
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                        menu = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("link", BrowserHolder.url))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Start page") }, onClick = { menu = false; BrowserHolder.goHome() })
                    DropdownMenuItem(text = { Text("Clear browsing data") }, onClick = {
                        menu = false; BrowserHolder.clearBrowsingData()
                        Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
        if (BrowserHolder.progress in 1..99) {
            LinearProgressIndicator(
                progress = { BrowserHolder.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = Gold, trackColor = Color.Transparent
            )
        } else Spacer(Modifier.height(2.dp))

        BrowserHolder.refreshTarget?.let {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(Gold.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Refresh link: open the page, play the file, then pick it from the \"found\" button.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { BrowserHolder.refreshTarget = null }) { Text("Cancel") }
            }
        }
        // ---- page ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    BrowserHolder.attach(ctx).also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                update = { BrowserHolder.attach(it.context) }
            )
            if (url.isBlank() || url == "about:blank") StartPage(onOpen = { BrowserHolder.open(it) })

            // floating download button / supported-site button
            val supported = UrlParser.isKnownVideoHost(url) &&
                (UrlParser.isYoutubeSingleVideo(url) || !UrlParser.isYoutube(url))
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (supported) {
                    PillButton("Download video", Icons.Filled.Download) { onSupportedVideo(url) }
                }
                if (BrowserHolder.found.isNotEmpty()) {
                    PillButton("${BrowserHolder.found.size} found", Icons.Filled.Download) { showSheet = true }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = MaterialTheme.colorScheme.surface) {
            FoundList(
                items = BrowserHolder.found.toList(),
                onPick = { showSheet = false; startDownload(it) },
                onGrabber = { showSheet = false; onOpenGrabber() }
            )
        }
    }

    editorFor?.let { f ->
        DownloadEditorDialog(
            found = f,
            defaultParts = settings.parts,
            onDismiss = { editorFor = null },
            onConfirm = { name, parts, size ->
                editorFor = null
                scope.launch { BrowserHolder.enqueue(f, name, parts, size); onQueued() }
            }
        )
    }
}

@Composable
private fun PillButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Gold)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StartPage(onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Browse & download", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Open any site. When it plays a video, audio or offers a file, a download button appears. " +
                "Menu → Grabber lists every file on a page.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        shortcuts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (name, u) ->
                    GlassCard(Modifier.weight(1f), onClick = { onOpen(u) }) {
                        Text(name, Modifier.padding(18.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundList(items: List<Found>, onPick: (Found) -> Unit, onGrabber: () -> Unit) {
    val sizes = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(items.map { it.url }) {
        items.take(12).forEach { f ->
            if (sizes[f.url] == null) sizes[f.url] = BrowserHolder.probeSize(f)
        }
    }
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
        Text("Found on this page", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.url }) { f ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { onPick(f) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        val sz = sizes[f.url]
                        Text(
                            f.type.uppercase() + if (sz != null && sz > 0) " · " + Formatters.bytes(sz) else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        TextButton(onClick = onGrabber) { Text("Open Grabber for all files", color = Gold) }
    }
}

/** IDM-style "Download file info" window. Shown only when Settings → Download editor is on. */
@Composable
fun DownloadEditorDialog(
    found: Found,
    defaultParts: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, parts: Int, size: Long) -> Unit
) {
    var name by remember { mutableStateOf(found.name) }
    var parts by remember { mutableFloatStateOf(defaultParts.coerceIn(1, 16).toFloat()) }
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(found.url) {
        val p = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aven.downloader.net.Net.probe(found.url, BrowserHolder.sourceFor(found, name).headers())
            }
        } catch (e: Exception) { null }
        size = p?.size ?: -1L
        if (p != null && found.name.isBlank()) name = p.fileName
    }
    val category = StorageSaver.categoryFor(name)
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.fillMaxWidth(), strong = true) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download file info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File name") }, modifier = Modifier.fillMaxWidth())
                Text(found.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Size: " + when { size == null -> "checking…"; size!! > 0 -> Formatters.bytes(size!!); else -> "unknown" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Save to: ${category.folder}", style = MaterialTheme.typography.bodyMedium)
                if (found.type != "hls") {
                    Text("Connections: ${parts.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = parts, onValueChange = { parts = it }, valueRange = 1f..16f, steps = 14)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onConfirm(name.ifBlank { found.name }, parts.toInt(), (size ?: 0L).coerceAtLeast(0)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text("Download") }
                }
            }
        }
    }
}
