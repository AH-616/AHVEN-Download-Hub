package com.aven.downloader.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aven.downloader.engine.EngineState
import com.aven.downloader.ui.theme.Gold

@Composable
fun HomeScreen(vm: HomeViewModel, padding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val loading = state is FetchState.Loading

    Column(
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AHD", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Gold)
            Text(" Hub", fontSize = 34.sp, fontWeight = FontWeight.Light)
        }
        Text(
            "Paste a link, preview it, then choose what to download. Even download your Google Drive videos, zip files and documents here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SupportedSites()

        when (val e = engine) {
            EngineState.Initializing -> StatusCard(
                "Preparing the downloader engine… the first launch takes a few seconds.", spinner = true
            )
            is EngineState.Failed -> StatusCard(
                "The downloader engine failed to start: ${e.message}", error = true, action = "Retry", onAction = vm::retryEngine
            )
            EngineState.Ready -> Unit
        }

        OutlinedTextField(
            value = vm.input,
            onValueChange = vm::onInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video or playlist link") },
            maxLines = 3,
            trailingIcon = {
                if (vm.input.isNotEmpty()) {
                    IconButton(onClick = { vm.onInput("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (!loading) vm.submit() })
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                // Clipboard is read only when the user taps Paste. Nothing opens automatically.
                val text = clipboard.getText()?.text
                if (text.isNullOrBlank()) Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                else vm.onInput(text)
            }) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Paste")
            }
            Button(
                onClick = { vm.submit() },
                enabled = vm.input.isNotBlank() && !loading && engine !is EngineState.Failed,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Preview & download")
            }
        }

        when (val s = state) {
            FetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("Reading video information…", modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelLoading) { Text("Cancel") }
            }
            is FetchState.Error -> ErrorCard(s.message, s.details)
            else -> Unit
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tips", fontWeight = FontWeight.SemiBold, color = Gold)
                Text("• In any app, tap Share and choose AHD Hub: a small popup opens, pick quality and download. Or copy a link and use the \"Download link\" Quick Settings tile.", style = MaterialTheme.typography.bodyMedium)
                Text("• Google Drive: paste a file or folder link that is shared with \"Anyone with the link\". For private files, log in under Settings → Login cookies → Google Drive.", style = MaterialTheme.typography.bodyMedium)
                Text("• Only public videos you're allowed to save will work. Private or login-only videos can't be downloaded.", style = MaterialTheme.typography.bodyMedium)
                Text("• If downloads suddenly stop working, update the engine in Settings.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    text: String,
    spinner: Boolean = false,
    error: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (spinner) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ErrorCard(message: String, details: String?) {
    var showDetails by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (!details.isNullOrBlank() && details != message) {
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Show details") }
                if (showDetails) Text(details.take(1500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

private class SiteChip(val label: String, val color: androidx.compose.ui.graphics.Color)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SupportedSites() {
    val sites = listOf(
        SiteChip("YouTube", androidx.compose.ui.graphics.Color(0xFFFFC857)),
        SiteChip("Facebook", androidx.compose.ui.graphics.Color(0xFF64B5F6)),
        SiteChip("Instagram", androidx.compose.ui.graphics.Color(0xFFCE93D8)),
        SiteChip("TikTok", androidx.compose.ui.graphics.Color(0xFF4DD0E1)),
        SiteChip("Google Drive · videos, zip, docs", androidx.compose.ui.graphics.Color(0xFF81C784)),
        SiteChip("+1000 more sites", androidx.compose.ui.graphics.Color(0xFFB0BEC5))
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Works with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sites.forEach { c ->
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = c.color.copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, c.color.copy(alpha = 0.7f))
                ) {
                    Text(
                        c.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = c.color,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
