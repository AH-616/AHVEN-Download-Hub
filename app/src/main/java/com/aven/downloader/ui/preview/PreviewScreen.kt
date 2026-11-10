package com.aven.downloader.ui.preview

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.aven.downloader.data.Settings
import com.aven.downloader.engine.AudioOption
import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.PreviewSource
import com.aven.downloader.engine.VideoOption
import com.aven.downloader.ui.home.FetchState
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    loaded: FetchState.Loaded,
    settings: Settings,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (DownloadSpec) -> Unit
) {
    val info = loaded.info
    val videoOpts = remember(info, settings.videoContainer) { FormatPlanner.videoOptions(info, settings.videoContainer) }
    val audioOpts = remember(info) { FormatPlanner.audioOptions(info) }

    val tracks = remember(info) { FormatPlanner.audioTracks(info) }
    var trackSel by rememberSaveable(info.sourceUrl) { mutableIntStateOf(0) }
    val audioLang = tracks.getOrNull(trackSel)?.takeIf { !it.isDefault }?.lang.orEmpty()

    var tab by rememberSaveable(info.sourceUrl) { mutableIntStateOf(if (settings.preferAudio && audioOpts.isNotEmpty()) 1 else 0) }
    var videoSel by rememberSaveable(info.sourceUrl) {
        mutableIntStateOf(FormatPlanner.pickDefault(videoOpts, settings.defaultQuality))
    }
    var audioSel by rememberSaveable(info.sourceUrl) {
        mutableIntStateOf(audioOpts.indexOfFirst { it.audioFormat == settings.audioFormat }.coerceAtLeast(0))
    }
    var playing by rememberSaveable(info.sourceUrl) { mutableStateOf(false) }
    var playError by remember(loaded.version) { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Preview & download") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                val canDownload = if (tab == 0) videoOpts.isNotEmpty() else audioOpts.isNotEmpty()
                Button(
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
                        onDownload(spec)
                    },
                    enabled = canDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (tab == 0) "Download video" else "Download audio")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Player / thumbnail ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (playing && loaded.preview != null && playError == null) {
                    PreviewPlayer(
                        source = loaded.preview,
                        key = loaded.version,
                        onFailure = { playError = it },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = info.thumbnail,
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (loaded.preview != null && playError == null) {
                        IconButton(
                            onClick = { playing = true },
                            modifier = Modifier
                                .size(64.dp)
                                .background(Gold, CircleShape)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play preview", tint = Color(0xFF1A1400), modifier = Modifier.size(36.dp))
                        }
                    }
                }
                if (loaded.refreshing) CircularProgressIndicator()
            }

            when {
                loaded.preview == null -> Note(
                    "In-app preview isn't available for this video (no directly playable stream). You can still download it."
                )
                playError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Note("The preview couldn't play. The stream link may have expired or isn't supported. You can still download.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { playError = null; playing = false; onRefresh() },
                            enabled = !loaded.refreshing
                        ) { Text("Get a fresh link & retry") }
                    }
                    Text(playError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            loaded.refreshError?.let { Note("Couldn't refresh the preview: $it") }

            // ---- Title block ----
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    info.uploader,
                    Formatters.duration(info.durationSec.toLong()).ifEmpty { null },
                    info.extractor
                ).joinToString(" • ")
                if (meta.isNotEmpty()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }

            // ---- Format selection ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Video") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Audio") })
            }
            if (tracks.isNotEmpty()) {
                Text("Audio language", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.forEachIndexed { i, t ->
                        FilterChip(selected = i == trackSel, onClick = { trackSel = i }, label = { Text(t.label) })
                    }
                }
            }
            if (tab == 0) {
                videoOpts.forEachIndexed { i, o -> OptionRow(o.label, sizeText(o.estimatedSize), i == videoSel) { videoSel = i } }
                Text(
                    "Container: ${settings.videoContainer.uppercase()} (change in Settings). Only qualities that exist for this video are listed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (audioOpts.isEmpty()) Note("No audio stream was found for this video.")
                audioOpts.forEachIndexed { i, o -> OptionRow(o.label, sizeText(o.estimatedSize), i == audioSel) { audioSel = i } }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun sizeText(bytes: Long) = if (bytes > 0) "~" + Formatters.bytes(bytes) else ""

@Composable
private fun Note(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionRow(label: String, size: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Text(label, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (size.isNotEmpty()) Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

// ------------------------------------------------------------------
// In-app player (Media3 ExoPlayer). Plays a remote stream URL BEFORE downloading.
// ------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun PreviewPlayer(source: PreviewSource, key: Int, onFailure: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(source, key) { buildPlayer(context, source) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onFailure("${error.errorCodeName}: ${error.message ?: ""}".trim())
            }
        }
        exo.addListener(listener)
        exo.prepare()
        exo.playWhenReady = true
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = true
            }
        },
        update = { it.player = exo }
    )
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, source: PreviewSource): ExoPlayer {
    val headers = when (source) {
        is PreviewSource.Single -> source.headers
        is PreviewSource.Merged -> source.headers
    }
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    val factory = DefaultMediaSourceFactory(http)
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
    when (source) {
        is PreviewSource.Single -> {
            val item = MediaItem.Builder().setUri(source.url).apply {
                if (source.hls) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            player.setMediaItem(item)
        }
        is PreviewSource.Merged -> {
            val video = factory.createMediaSource(MediaItem.fromUri(source.videoUrl))
            val audio = factory.createMediaSource(MediaItem.fromUri(source.audioUrl))
            player.setMediaSource(MergingMediaSource(video, audio))
        }
    }
    return player
}
