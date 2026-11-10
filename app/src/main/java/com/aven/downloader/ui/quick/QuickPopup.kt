package com.aven.downloader.ui.quick

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aven.downloader.AvenApp
import com.aven.downloader.data.Settings
import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.MediaInfo
import com.aven.downloader.engine.PlaylistEstimator
import com.aven.downloader.engine.PlaylistInfo
import com.aven.downloader.util.Formatters
import kotlinx.coroutines.launch

/** Bottom-sheet style popup shown over whatever app the user came from. */
@Composable
fun QuickPopup(vm: QuickViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val settings by AvenApp.instance.settings.flow.collectAsState(initial = Settings())
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxH),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AHD Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                when (val s = state) {
                    is QuickState.Loading -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        s.hint?.let { h ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AsyncImage(
                                    model = h.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    h.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                            Text("Getting quality options…")
                        }
                        TextButton(onClick = onClose) { Text("Cancel") }
                    }
                    QuickState.NoLink -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("No link found", fontWeight = FontWeight.SemiBold)
                        Text("Copy a video or playlist link first, then open this again. You can also use Share → AHD Hub.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                    }
                    is QuickState.Error -> Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(s.message, fontWeight = FontWeight.SemiBold)
                        s.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.retry() }) { Text("Retry") }
                            OutlinedButton(onClick = onClose) { Text("Close") }
                        }
                    }
                    is QuickState.Video -> VideoSheet(vm, s.info, s.inPlaylist, settings, onClose)
                    is QuickState.Playlist -> PlaylistSheet(vm, s.playlist, settings, onClose)
                    is QuickState.File -> FileSheet(vm, s.file, onClose)
                }
            }
        }
    }
}

private fun sizeText(bytes: Long) = if (bytes > 0) "~" + Formatters.bytes(bytes) else ""

@Composable
private fun ChoiceRow(label: String, size: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (size.isNotEmpty()) Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoSheet(vm: QuickViewModel, info: MediaInfo, inPlaylist: Boolean, settings: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoOpts = remember(info, settings.videoContainer) { FormatPlanner.videoOptions(info, settings.videoContainer) }
    val audioOpts = remember(info) { FormatPlanner.audioOptions(info) }
    val tracks = remember(info) { FormatPlanner.audioTracks(info) }

    var tab by remember(info) { mutableIntStateOf(if (settings.preferAudio && audioOpts.isNotEmpty()) 1 else 0) }
    var videoSel by remember(info) { mutableIntStateOf(FormatPlanner.pickDefault(videoOpts, settings.defaultQuality)) }
    var audioSel by remember(info) { mutableIntStateOf(audioOpts.indexOfFirst { it.audioFormat == settings.audioFormat }.coerceAtLeast(0)) }
    var trackSel by remember(info) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val audioLang = tracks.getOrNull(trackSel)?.takeIf { !it.isDefault }?.lang.orEmpty()

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = info.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(120.dp).size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(info.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(info.uploader, Formatters.duration(info.durationSec.toLong()).ifEmpty { null }).joinToString(" • ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Video") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Audio") })
        }
        if (tracks.isNotEmpty()) {
            Text("Audio language", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.forEachIndexed { i, t -> FilterChip(selected = i == trackSel, onClick = { trackSel = i }, label = { Text(t.label) }) }
            }
        }
        if (tab == 0) {
            videoOpts.forEachIndexed { i, o -> ChoiceRow(o.label, sizeText(o.estimatedSize), i == videoSel) { videoSel = i } }
        } else {
            if (audioOpts.isEmpty()) Text("No audio stream found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            audioOpts.forEachIndexed { i, o -> ChoiceRow(o.label, sizeText(o.estimatedSize), i == audioSel) { audioSel = i } }
        }
        if (inPlaylist) {
            TextButton(onClick = { vm.openPlaylist() }) { Text("This video is part of a playlist — open the whole playlist") }
        }
        val canDownload = !busy && (if (tab == 0) videoOpts.isNotEmpty() else audioOpts.isNotEmpty())
        Button(
            enabled = canDownload,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val spec = if (tab == 0) {
                    val o = videoOpts[videoSel.coerceIn(0, videoOpts.lastIndex)]
                    DownloadSpec(DownloadKind.VIDEO, o.heightCap, settings.videoContainer, settings.audioFormat,
                        "${o.label} · ${settings.videoContainer.uppercase()}", audioLang,
                        pinned = if (audioLang.isEmpty()) o.pinned else "")
                } else {
                    val o = audioOpts[audioSel.coerceIn(0, audioOpts.lastIndex)]
                    DownloadSpec(DownloadKind.AUDIO, 0, settings.videoContainer, o.audioFormat, o.label, audioLang)
                }
                busy = true
                scope.launch {
                    vm.downloadVideo(info, spec)
                    Toast.makeText(context, "Added to downloads – progress is in the notification", Toast.LENGTH_LONG).show()
                    onClose()
                }
            }
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (tab == 0) "Download video" else "Download audio")
        }
    }
}

@Composable
private fun FileSheet(vm: QuickViewModel, info: com.aven.downloader.engine.FileInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember(info) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(info.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            if (info.isVideo) "Google Drive video" else if (info.choices.size > 1) "Google Docs file – choose a format" else "Google Drive file",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        info.choices.forEachIndexed { i, c -> ChoiceRow(c.label, sizeText(c.estimatedSize), i == sel) { sel = i } }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    vm.downloadFile(info, info.choices[sel.coerceIn(0, info.choices.lastIndex)])
                    Toast.makeText(context, "Added to downloads – progress is in the notification", Toast.LENGTH_LONG).show()
                    onClose()
                }
            }
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Download")
        }
    }
}

@Composable
private fun PlaylistSheet(vm: QuickViewModel, pl: PlaylistInfo, settings: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices = PlaylistEstimator.choices
    var choiceIdx by remember(pl) {
        val byAudio = if (settings.preferAudio) choices.indexOfFirst { it.kind == DownloadKind.AUDIO && it.audioFormat == settings.audioFormat } else -1
        val byQ = if (settings.defaultQuality > 0) choices.indexOfFirst { it.kind == DownloadKind.VIDEO && it.heightCap in 1..settings.defaultQuality } else 0
        mutableIntStateOf(if (byAudio >= 0) byAudio else byQ.coerceAtLeast(0))
    }
    val total = pl.entries.size
    var selected by remember(pl) { mutableStateOf(pl.entries.indices.take(10).toSet()) }
    var busy by remember { mutableStateOf(false) }
    val choice = choices[choiceIdx]
    val picked = pl.entries.filterIndexed { i, _ -> i in selected }
    val est = PlaylistEstimator.total(picked, choice)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pl.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("$total videos in this playlist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Quality", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEachIndexed { i, c -> FilterChip(selected = i == choiceIdx, onClick = { choiceIdx = i }, label = { Text(c.label) }) }
        }

        Text("Select videos", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 25).filter { it < total }.forEach { n ->
                FilterChip(selected = false, onClick = { selected = pl.entries.indices.take(n).toSet() }, label = { Text("First $n") })
            }
            FilterChip(selected = false, onClick = { selected = pl.entries.indices.toSet() }, label = { Text("All $total") })
            FilterChip(selected = false, onClick = { selected = emptySet() }, label = { Text("None") })
        }

        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
            itemsIndexed(pl.entries) { i, e ->
                val on = i in selected
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (on) selected - i else selected + i },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - i else selected + i })
                    Text("${i + 1}. ${e.title}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    val b = PlaylistEstimator.bytesFor(e.durationSec, choice)
                    if (b > 0) Text("~" + Formatters.bytes(b), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        val summary = buildString {
            append("${picked.size} of $total selected")
            if (picked.isNotEmpty()) {
                if (est.bytes > 0) {
                    append(" · about ${Formatters.bytes(est.bytes)}")
                    if (est.unknownCount > 0) append(" (+${est.unknownCount} of unknown size)")
                } else append(" · size unknown")
            }
        }
        Text(summary, fontWeight = FontWeight.SemiBold)
        Text("Sizes are rough estimates from video length and typical quality; the real file can be smaller or larger.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            enabled = picked.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    val n = vm.downloadEntries(picked, PlaylistEstimator.toSpec(choice, settings.videoContainer))
                    Toast.makeText(context, "$n videos added – progress is in the notification", Toast.LENGTH_LONG).show()
                    onClose()
                }
            }
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Download ${picked.size} selected")
        }
    }
}
