package com.aven.downloader.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aven.downloader.AvenApp
import com.aven.downloader.BuildConfig
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.update.UpdateUi
import com.aven.downloader.util.Formatters
import java.text.DateFormat
import java.util.Date

/** Welcome popup (first install only) / What's new popup (once after each update), then the update dialogs. */
@Composable
fun AppDialogs() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aven_prefs", Context.MODE_PRIVATE) }
    var intro by rememberSaveable {
        val last = prefs.getInt("last_version_code", -1)
        val cur = BuildConfig.VERSION_CODE
        prefs.edit().putInt("last_version_code", cur).apply()
        mutableStateOf(
            when {
                last == -1 -> "welcome"
                last < cur && WhatsNew.notesFor(cur) != null -> "news"
                else -> null
            }
        )
    }
    val updater = AvenApp.instance.updater
    val update by updater.state.collectAsStateWithLifecycle()

    // The update dialog has priority over the intro popup.
    val blocking = update is UpdateUi.Available || update is UpdateUi.Downloading || update is UpdateUi.Ready ||
        (update as? UpdateUi.Failed)?.info != null
    if (!blocking) when (intro) {
        "welcome" -> InfoDialog("Welcome to AHD Hub", WhatsNew.features) { intro = null }
        "news" -> InfoDialog("What's new", WhatsNew.notesFor(BuildConfig.VERSION_CODE).orEmpty()) { intro = null }
    }

    when (val u = update) {
        is UpdateUi.Available -> {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(u.deadlineMs))
            AlertDialog(
                onDismissRequest = { if (!u.force) updater.later() },
                properties = DialogProperties(dismissOnBackPress = !u.force, dismissOnClickOutside = !u.force),
                title = { Text(if (u.force) "Update required" else "Update available") },
                text = {
                    Text(
                        if (u.force) "This version has expired. Update now to keep using AHD Hub." +
                            (if (u.info.size > 0) "\n\nDownload size: ${Formatters.bytes(u.info.size)}" else "")
                        else "A new version is ready" + (if (u.info.size > 0) " (${Formatters.bytes(u.info.size)})" else "") +
                            ". You can update later, but only until $date."
                    )
                },
                confirmButton = { Button(onClick = { updater.download(u.info) }) { Text("Update now") } },
                dismissButton = { if (!u.force) TextButton(onClick = { updater.later() }) { Text("Later") } }
            )
        }
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Downloading update…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u.info.size > 0) LinearProgressIndicator(progress = { (u.done.toFloat() / u.info.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(Formatters.bytes(u.done) + if (u.info.size > 0) " / " + Formatters.bytes(u.info.size) else "")
                }
            },
            confirmButton = {}
        )
        is UpdateUi.Ready -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Ready to install") },
            text = { Text("Tap Install. If Android asks, allow AHD Hub to install apps, then come back and tap Install again.") },
            confirmButton = { Button(onClick = { updater.install(u.file) }) { Text("Install") } }
        )
        is UpdateUi.Failed -> if (u.info != null) AlertDialog(
            onDismissRequest = { if (!u.force) updater.later() },
            properties = DialogProperties(dismissOnBackPress = !u.force, dismissOnClickOutside = !u.force),
            title = { Text("Update problem") },
            text = { Text(u.message) },
            confirmButton = { Button(onClick = { updater.download(u.info) }) { Text("Try again") } },
            dismissButton = { if (!u.force) TextButton(onClick = { updater.later() }) { Text("Later") } }
        )
        else -> Unit
    }
}

/** A small card-like popup with a cross; tapping outside closes it too. */
@Composable
private fun InfoDialog(title: String, lines: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, color = Gold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(22.dp)) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp)) }
            }
        },
        confirmButton = {}
    )
}
