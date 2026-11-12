package com.aven.downloader.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aven.downloader.ui.theme.GlassBackground
import com.aven.downloader.ui.theme.GlassCard
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.util.Formatters
import kotlinx.coroutines.launch

/** IDM/ADM-style Grabber: scan the open page, pick file types, tick files, ADD or DOWNLOAD. */
@Composable
fun GrabberScreen(onBack: () -> Unit, onDownloads: () -> Unit) {
    val vm: GrabberViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typeDialog by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { if (vm.items.isEmpty()) vm.grab() }

    val list = vm.visible()
    val (count, bytes) = vm.totalSelected()

    GlassBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Grabber", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        BrowserHolder.url.ifBlank { "No page open" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { typeDialog = true }) { Text("File types", color = Gold) }
            }

            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = list.isNotEmpty() && count == list.size,
                            onCheckedChange = { vm.selectAll(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Gold)
                        )
                        Text("Select All", Modifier.weight(1f))
                        Checkbox(checked = vm.fullNames, onCheckedChange = { vm.fullNames = it }, colors = CheckboxDefaults.colors(checkedColor = Gold))
                        Text("Show full file name")
                    }
                    Text("Total selected: $count / ${list.size}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Total size(Approx): " + if (bytes > 0) Formatters.bytes(bytes) else "—",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Link depth: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(0, 1, 2).forEach { l ->
                            TextButton(onClick = { vm.level = l }) {
                                Text(
                                    if (l == 0) "This page" else "Level $l",
                                    color = if (vm.level == l) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (vm.level == l) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (list.isEmpty()) item {
                    Text(
                        if (vm.grabbing) "Looking for files…" else "No files of the selected types were found. Play the video / scroll the page first, " +
                            "then press GRAB, or choose more types.",
                        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(list, key = { it.url }) { f ->
                    GrabRow(f, vm.sizes[f.url], f.url in vm.selected, vm.fullNames) { vm.toggleSelect(f.url) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f), enabled = count > 0,
                    onClick = {
                        scope.launch {
                            val n = vm.enqueueSelected()
                            Toast.makeText(context, "$n added to Downloads", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("ADD") }
                Button(
                    modifier = Modifier.weight(1f), enabled = count > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = MaterialTheme.colorScheme.onPrimary),
                    onClick = { scope.launch { vm.enqueueSelected(); onDownloads() } }
                ) { Text("DOWNLOAD") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { if (vm.grabbing) vm.stop() else vm.grab() }
                ) { Text(if (vm.grabbing) "STOP" else "GRAB") }
            }
        }
    }

    if (typeDialog) TypeDialog(vm, onClose = { typeDialog = false })
}

@Composable
private fun GrabRow(f: Found, size: Long?, checked: Boolean, full: Boolean, onToggle: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onToggle) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (f.type == "image") {
                    AsyncImage(model = f.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else Text(f.type.take(4).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Gold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (size == null) "…" else if (size > 0) Formatters.bytes(size) else if (f.type == "hls") "Stream" else "Unknown size",
                    style = MaterialTheme.typography.bodySmall, color = Gold
                )
                Text(f.name, maxLines = if (full) 6 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = Gold))
        }
    }
}

@Composable
private fun TypeDialog(vm: GrabberViewModel, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(vm.types) }
    var custom by remember { mutableStateOf(vm.customExt) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Select file types to grab") },
        text = {
            Column {
                GrabType.values().forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (t in sel) sel - t else sel + t },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = t in sel, onCheckedChange = { sel = if (it) sel + t else sel - t }, colors = CheckboxDefaults.colors(checkedColor = Gold))
                        Text(t.label)
                    }
                }
                if (GrabType.CUSTOM in sel) {
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it }, singleLine = true,
                        label = { Text("Extensions, e.g. iso, mkv, epub") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setTypes(sel, custom); onClose() }) { Text("OK", color = Gold) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
