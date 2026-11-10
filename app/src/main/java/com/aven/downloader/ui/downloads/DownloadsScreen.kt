package com.aven.downloader.ui.downloads

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.aven.downloader.download.DownloadManager
import com.aven.downloader.ui.theme.GoldSoft
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aven.downloader.data.DownloadEntity
import com.aven.downloader.data.DownloadStatus
import com.aven.downloader.data.StorageSaver
import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.ui.theme.ErrorRed
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.ui.theme.SuccessGreen
import com.aven.downloader.util.Formatters

@Composable
fun DownloadsScreen(padding: PaddingValues, vm: DownloadsViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val frags by vm.frags.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val active = items.filter { it.isActive }.sortedBy { it.createdAt }
    val history = items.filter { !it.isActive }
    val list = if (tab == 0) active else history

    var detailsFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var removeFor by remember { mutableStateOf<DownloadEntity?>(null) }

    Column(Modifier.padding(padding).fillMaxSize()) {
        Text(
            "Downloads",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
        )
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, contentColor = Gold) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Queue (${active.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History (${history.size})") })
        }
        summary.blocked?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) { Text(it, modifier = Modifier.padding(12.dp)) }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) "No active downloads." else "No finished downloads yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.id }) { e ->
                    DownloadCard(
                        e = e,
                        frag = frags[e.id],
                        onPause = { vm.pause(e.id) },
                        onResume = { vm.resume(e.id) },
                        onCancel = { vm.cancel(e.id) },
                        onRetry = { vm.retry(e.id) },
                        onDetails = { detailsFor = e },
                        onRemove = { removeFor = e }
                    )
                }
            }
        }
    }

    detailsFor?.let { e ->
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            confirmButton = { TextButton(onClick = { detailsFor = null }) { Text("Close") } },
            title = { Text("Error details") },
            text = { Text((e.errorDetails ?: e.errorSummary ?: "No details").take(3000), style = MaterialTheme.typography.bodySmall) }
        )
    }
    removeFor?.let { e ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Remove from list") },
            text = {
                Text(
                    if (e.status == DownloadStatus.COMPLETED)
                        "Remove \"${e.title}\" from history. You can also delete the downloaded file."
                    else "Remove \"${e.title}\" from the list?"
                )
            },
            confirmButton = {
                Row {
                    if (e.status == DownloadStatus.COMPLETED) {
                        TextButton(onClick = { vm.remove(e.id, true); removeFor = null }) {
                            Text("Delete file too", color = ErrorRed)
                        }
                    }
                    TextButton(onClick = { vm.remove(e.id, false); removeFor = null }) { Text("Remove") }
                }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DownloadCard(
    e: DownloadEntity,
    frag: DownloadManager.FragProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val expandable = e.status == DownloadStatus.RUNNING || e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.QUEUED
    var expanded by rememberSaveable(e.id) { mutableStateOf(false) }
    Card(
        modifier = when {
            e.status == DownloadStatus.COMPLETED -> Modifier.clickable { openWith(context, e) }
            expandable -> Modifier.clickable { expanded = !expanded }
            else -> Modifier
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = e.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(Modifier)
                )
                Column(Modifier.weight(1f)) {
                    Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        (when (e.kind) { DownloadKind.AUDIO -> "Audio"; DownloadKind.FILE -> "File"; else -> "Video" }) + " • " + e.formatLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (expandable) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (e.status == DownloadStatus.RUNNING) {
                val indeterminate = e.statusDetail?.endsWith("…") == true || (e.progress <= 0f && e.totalBytes == 0L)
                if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { (e.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else if (e.status == DownloadStatus.PAUSED && e.progress > 0f) {
                LinearProgressIndicator(progress = { (e.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outline)
            }

            val (text, color) = statusText(e)
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)

            if (expandable && expanded) DownloadDetails(e, frag)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (e.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                        TextButton(onClick = onPause) { Icon(Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Pause") }
                        TextButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Cancel") }
                    }
                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Resume") }
                        TextButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Cancel") }
                    }
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Retry") }
                        TextButton(onClick = onDetails) { Icon(Icons.Filled.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Details") }
                        TextButton(onClick = onRemove) { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Remove") }
                    }
                    DownloadStatus.CANCELED -> {
                        TextButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Retry") }
                        TextButton(onClick = onRemove) { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Remove") }
                    }
                    DownloadStatus.COMPLETED -> {
                        TextButton(onClick = { openWith(context, e) }) {
                            Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Open with…")
                        }
                        Spacer(Modifier.weight(1f))
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Remove / delete file…") }, onClick = { menu = false; onRemove() })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadDetails(e: DownloadEntity, frag: DownloadManager.FragProgress?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val blockCells = frag?.cells
        if (frag != null && blockCells != null && e.status == DownloadStatus.RUNNING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blocks", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${frag.done} of ${frag.total} done · ${frag.parallel} connections", style = MaterialTheme.typography.bodySmall, color = Gold)
            }
            BlockGrid(blockCells)
            frag.conns.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (c.block >= 0) "Connection ${c.index} · block ${c.block + 1}" else "Connection ${c.index} · idle",
                        style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f)
                    )
                    if (c.block >= 0) Text(
                        Formatters.bytes(c.done) + " done · " + Formatters.bytes(c.left) + " left · " + Formatters.speed(c.speed),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else if (frag != null && e.status == DownloadStatus.RUNNING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pieces", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${frag.done} of ${frag.total} done · ${frag.parallel} at a time", style = MaterialTheme.typography.bodySmall, color = Gold)
            }
            FragmentGrid(frag)
        } else {
            Text(
                if (e.status == DownloadStatus.RUNNING)
                    "This stream is one continuous file (or is still starting), so there are no pieces to show."
                else "Pieces are shown while the download is running.",
                style = MaterialTheme.typography.bodySmall, color = muted
            )
        }
        val done = if (e.totalBytes > 0 && e.progress > 0f)
            Formatters.bytes((e.totalBytes * (e.progress / 100f)).toLong()) + " / " + Formatters.bytes(e.totalBytes) else null
        DetailRow("Downloaded", done)
        DetailRow("Speed", Formatters.speed(e.speedBps).takeIf { it.isNotEmpty() })
        DetailRow("Time left", if (e.etaSec > 0) Formatters.duration(e.etaSec) else null)
        DetailRow("Step", e.statusDetail)
        DetailRow("Quality", e.formatLabel.takeIf { it.isNotBlank() })
        DetailRow("Format ids", e.pinned.takeIf { it.isNotBlank() })
        DetailRow("Source", runCatching { Uri.parse(e.url).host }.getOrNull())
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One box per block of a file (Google Drive downloads). A box fills up from the left as its block is downloaded. */
@Composable
private fun BlockGrid(cells: FloatArray) {
    val cols = 10
    val rows = (cells.size + cols - 1) / cols
    val idle = MaterialTheme.colorScheme.outlineVariant
    val cellH = 16.dp
    val gap = 4.dp
    Canvas(Modifier.fillMaxWidth().height(cellH * rows + gap * (rows - 1))) {
        val g = gap.toPx()
        val ch = cellH.toPx()
        val cw = (size.width - g * (cols - 1)) / cols
        for (i in cells.indices) {
            val x = (i % cols) * (cw + g)
            val y = (i / cols) * (ch + g)
            val f = cells[i]
            drawRoundRect(idle, Offset(x, y), Size(cw, ch), CornerRadius(4.dp.toPx()))
            if (f > 0f) drawRoundRect(if (f >= 0.999f) Gold else GoldSoft, Offset(x, y), Size(cw * f, ch), CornerRadius(4.dp.toPx()))
        }
    }
}

/** One small square per piece (long videos are grouped, at most 120 squares). Gold = done, light = downloading now. */
@Composable
private fun FragmentGrid(frag: DownloadManager.FragProgress) {
    val cols = 20
    val cells = minOf(frag.total, 120).coerceAtLeast(1)
    val rows = (cells + cols - 1) / cols
    val doneCells = (frag.done.toLong() * cells / frag.total).toInt().coerceIn(0, cells)
    val activeCells = if (doneCells >= cells) 0
    else minOf(maxOf(1, frag.parallel * cells / frag.total), cells - doneCells)
    val idle = MaterialTheme.colorScheme.outlineVariant
    val cellH = 10.dp
    val gap = 3.dp
    Canvas(Modifier.fillMaxWidth().height(cellH * rows + gap * (rows - 1))) {
        val g = gap.toPx()
        val ch = cellH.toPx()
        val cw = (size.width - g * (cols - 1)) / cols
        for (i in 0 until cells) {
            val r = i / cols
            val c = i % cols
            val color = when {
                i < doneCells -> Gold
                i < doneCells + activeCells -> GoldSoft
                else -> idle
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(c * (cw + g), r * (ch + g)),
                size = Size(cw, ch),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
    }
}

@Composable
private fun statusText(e: DownloadEntity): Pair<String, Color> {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (e.status) {
        DownloadStatus.RUNNING -> {
            val parts = mutableListOf<String>()
            e.statusDetail?.let { parts += it }
            if (e.progress > 0f && e.statusDetail?.endsWith("…") != true) parts += "${e.progress.toInt()}%"
            if (e.totalBytes > 0 && e.statusDetail?.endsWith("…") != true) parts += Formatters.bytes(e.totalBytes)
            Formatters.speed(e.speedBps).takeIf { it.isNotEmpty() }?.let { parts += it }
            if (e.etaSec > 0) parts += "ETA " + Formatters.duration(e.etaSec)
            parts.joinToString(" • ").ifEmpty { "Starting…" } to muted
        }
        DownloadStatus.QUEUED -> (e.statusDetail ?: "Queued") to muted
        DownloadStatus.PAUSED -> (listOfNotNull(e.statusDetail ?: "Paused", if (e.progress > 0) "${e.progress.toInt()}%" else null)
            .joinToString(" • ")) to muted
        DownloadStatus.COMPLETED -> (listOfNotNull(
            "Completed",
            Formatters.bytes(e.sizeBytes).takeIf { e.sizeBytes > 0 },
            e.outputName,
            e.statusDetail
        ).joinToString(" • ")) to SuccessGreen
        DownloadStatus.FAILED -> ("Failed: " + (e.errorSummary ?: "unknown error")) to ErrorRed
        DownloadStatus.CANCELED -> "Canceled" to muted
    }
}

/** Opens a finished download with the system app chooser (VLC, MX Player, ...). No built-in player. */
private fun openWith(context: Context, e: DownloadEntity) {
    val uriString = e.outputUri
    if (!StorageSaver.exists(context, uriString)) {
        Toast.makeText(context, "File not found. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
        return
    }
    val uri = StorageSaver.shareableUri(context, uriString!!)
    val mime = StorageSaver.mimeFor(e.outputName ?: "")
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(view, "Open with").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(chooser)
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, "No app found that can open this file.", Toast.LENGTH_LONG).show()
    }
}
