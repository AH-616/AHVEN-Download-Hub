package com.aven.downloader.ui.settings

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import com.aven.downloader.BuildConfig
import com.aven.downloader.update.UpdateUi
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aven.downloader.AvenApp
import com.aven.downloader.data.CookieSites
import com.aven.downloader.ui.CookieLoginActivity
import com.aven.downloader.data.DuplicateMode
import com.aven.downloader.engine.EngineState
import com.aven.downloader.ui.theme.Gold
import com.aven.downloader.ui.theme.GlassCard
import androidx.compose.material3.OutlinedTextField

private enum class Dialog { QUALITY, CONTAINER, AUDIO, DUPLICATE, CLEAR_COMPLETED, CLEAR_FAILED, FOLDER, CHANNEL, CLEAR_COOKIES, DOH, THEME, CLEAR_CACHE, DOH_CUSTOM }

@Composable
fun SettingsScreen(padding: PaddingValues, vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                vm.setFolder(uri.toString())
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't use that folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                vm.importCookies(text)
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read that file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshCookies()
        onPauseOrDispose { }
    }

    Column(
        Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, start = 4.dp))

        Section("Appearance") {
            ValueRow("Theme", when (s.themeMode) { "light" -> "Light"; "dark" -> "Dark"; else -> "System" }, "Liquid-glass look in dark or light.") { dialog = Dialog.THEME }
        }

        Section("Quality & format") {
            ValueRow("Default video quality", qualityLabel(s.defaultQuality), "Used to pre-select a quality; falls back to the closest available.") { dialog = Dialog.QUALITY }
            ValueRow("Audio format", if (s.audioFormat == "opus") "OPUS (WebM)" else "M4A", "Used when you download audio only. (MP3 needs FFmpeg, which was removed to keep the app small and fast.)") { dialog = Dialog.AUDIO }
            SwitchRow("Open on Audio tab", "Start the format screen on Audio instead of Video.", s.preferAudio) { vm.setPreferAudio(it) }
        }

        Section("Storage") {
            val folderName = s.customFolderUri?.let { u ->
                runCatching { DocumentFile.fromTreeUri(context, Uri.parse(u))?.name }.getOrNull() ?: "Custom folder"
            } ?: "Downloads/AHD Hub"
            ValueRow("Save location", folderName, "Finished files are saved here. Partial downloads stay in app storage until done.") { dialog = Dialog.FOLDER }
            ValueRow("If a file already exists", s.duplicateMode.label, null) { dialog = Dialog.DUPLICATE }
        }

        Section("Browser") {
            SwitchRow("Block ads", "Blocks known ad, pop-up and tracker hosts in the in-app browser.", s.blockAds) { vm.setBlockAds(it) }
            SwitchRow("Download editor", "Show the \"Download file info\" window (name, size, folder, connections) before a browser download. Off = starts at once.", s.showDownloadEditor) { vm.setShowDownloadEditor(it) }
        }

        Section("DNS over HTTPS") {
            ValueRow(
                "Secure DNS", when (s.dohMode) { "cloudflare" -> "Cloudflare 1.1.1.1"; "google" -> "Google 8.8.8.8"; "adguard" -> "AdGuard"; "custom" -> "Custom"; else -> "Off" },
                "Encrypts the app's own lookups (downloads, YouTube fetch, probes). The in-app web pages and yt-dlp still use the system DNS."
            ) { dialog = Dialog.DOH }
            if (s.dohMode == "custom") ValueRow("Custom DoH address", s.dohCustom.ifBlank { "Not set" }, "e.g. https://dns.example.com/dns-query") { dialog = Dialog.DOH_CUSTOM }
        }

        Section("Data management") {
            LaunchedEffect(Unit) { vm.refreshCacheSize() }
            ValueRow("Clear cache", vm.cacheText, "Deletes temporary files, image and browser cache. Your downloads and unfinished downloads are kept.") { dialog = Dialog.CLEAR_CACHE }
        }

        Section("Network & queue") {
            SwitchRow("Wi-Fi only", "Off = Wi-Fi and mobile data. On = downloads wait for Wi-Fi (and pause when Wi-Fi drops).", s.wifiOnly) { vm.setWifiOnly(it) }
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Maximum concurrent downloads: ${s.maxConcurrent}")
                Slider(
                    value = s.maxConcurrent.toFloat(),
                    onValueChange = { vm.setConcurrent(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Text("How many downloads run at the same time. The rest wait in the queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var partsDraft by remember(s.parts) { mutableStateOf(s.parts.toFloat()) }
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Number of parts: ${partsDraft.toInt()}")
                Slider(
                    value = partsDraft,
                    onValueChange = { partsDraft = it },
                    onValueChangeFinished = { vm.setParts(partsDraft.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14
                )
                Text(
                    "How many parts one download is split into and fetched at the same time (1 = no splitting). More parts can be faster; some sites slow down or block when it is too high. Default: 4.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SwitchRow("Auto-detect copied link", "When the app opens, read a copied video link and start looking at it. (Android itself may show a small \"pasted from clipboard\" notice.)", s.autoDetectClipboard) { vm.setAutoDetectClipboard(it) }
        }

        Section("YouTube & updates") {
            SwitchRow("Auto-update yt-dlp", "Checks once a day when the app starts. YouTube changes often, so this keeps downloads working.", s.autoUpdateYtDlp) { vm.setAutoUpdate(it) }
            ValueRow("Update channel", if (s.updateChannel == "nightly") "Nightly" else "Stable", "Nightly gets YouTube fixes sooner but can be less stable.") { dialog = Dialog.CHANNEL }
            SwitchRow("Force IPv4", "Can fix some YouTube 403 / 429 errors on mobile networks.", s.forceIpv4) { vm.setForceIpv4(it) }
        }

        Section("Login cookies (optional)") {
            Text(
                "Log in to a site inside the app so yt-dlp can use your own session. This can fix \"Sign in to confirm you're not a bot\" and allows age-restricted or members-only videos you can already watch. " +
                    "Use a spare account if you can: sites may limit accounts that are used for downloading. Cookies stay on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val tick = vm.cookieTick
            CookieSites.all.forEach { site ->
                val loggedIn = tick >= 0 && AvenApp.instance.cookies.hasSite(site.id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(site.label)
                        Text(if (loggedIn) "Logged in" else "Not logged in", style = MaterialTheme.typography.bodySmall, color = if (loggedIn) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, CookieLoginActivity::class.java).putExtra(CookieLoginActivity.EXTRA_SITE, site.id))
                    }) { Text(if (loggedIn) "Log in again" else "Log in") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { cookiePicker.launch(arrayOf("text/plain", "*/*")) }) { Text("Import cookies.txt") }
                TextButton(onClick = { dialog = Dialog.CLEAR_COOKIES }) { Text("Remove all logins") }
            }
            if (tick >= 0 && AvenApp.instance.cookies.hasImported()) Text("A cookies.txt file is imported.", style = MaterialTheme.typography.bodySmall, color = Gold)
            vm.cookieMessage?.let { Text(it, color = Gold) }
            Text(
                "Google (YouTube and Drive) may refuse sign-in inside an app. If that happens, export cookies.txt from a desktop browser (a \"cookies.txt\" extension) and use Import.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Notifications") {
            SwitchRow("Completion notifications", "Notify when a download finishes or fails. (While downloading, Android always shows a progress notification.)", s.notifications) { vm.setNotifications(it) }
        }

        Section("History") {
            ValueRow("Clear completed downloads", "", "Removes the list entries only. Your files are kept.") { dialog = Dialog.CLEAR_COMPLETED }
            ValueRow("Clear failed & canceled", "", "Also deletes their partial data.") { dialog = Dialog.CLEAR_FAILED }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
            HalfCard("Diagnostics", Modifier.weight(1f).fillMaxHeight()) {
                val engineText = when (val e = engine) {
                    EngineState.Initializing -> "Starting…"
                    EngineState.Ready -> "Ready"
                    is EngineState.Failed -> "Failed: ${e.message}"
                }
                Text("Engine: $engineText")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::updateYtDlp, enabled = !vm.updating && engine is EngineState.Ready) { Text("Update engine") }
                    if (vm.updating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
                }
                vm.updateMessage?.let { Text(it, color = Gold) }
            }
            HalfCard("Engine version", Modifier.weight(1f).fillMaxHeight()) {
                Text(vm.ytDlpVersion, fontWeight = FontWeight.SemiBold)
                Text(
                    versionDate(vm.ytDlpVersion)?.let { "Updated $it" } ?: "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val updateUi by AvenApp.instance.updater.state.collectAsStateWithLifecycle()
        Section("App update") {
            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { AvenApp.instance.updater.check(true) },
                    enabled = updateUi !is UpdateUi.Checking
                ) { Text("Check for updates") }
                if (updateUi is UpdateUi.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
            }
            when (val u = updateUi) {
                UpdateUi.UpToDate -> Text("You have the latest version.", color = Gold)
                UpdateUi.NotConfigured -> Text("Updates are not set up in this build.", color = Gold)
                is UpdateUi.Failed -> { if (u.info == null) Text(u.message, color = Gold) }
                else -> Unit
            }
        }

        var aboutOpen by rememberSaveable { mutableStateOf(false) }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { aboutOpen = !aboutOpen },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("About", color = Gold, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (aboutOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                if (aboutOpen) {
                    Text("AHVEN Download Hub uses NewPipe Extractor and yt-dlp (via youtubedl-android) and is released under GPL-3.0. Download only content you're allowed to save.", style = MaterialTheme.typography.bodySmall)
                    Text("Made by AH", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Contact: ahvenstudio@gmail.com",
                        color = Gold,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ahvenstudio@gmail.com"))
                                        .putExtra(Intent.EXTRA_SUBJECT, "AHD Hub ${BuildConfig.VERSION_NAME}")
                                )
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ---------- dialogs ----------
    when (dialog) {
        Dialog.QUALITY -> ChoiceDialog(
            "Default video quality",
            listOf(0 to "Best available", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"),
            s.defaultQuality, { vm.setQuality(it) }, { dialog = null }
        )
        Dialog.CONTAINER -> dialog = null
        Dialog.THEME -> ChoiceDialog(
            "Theme", listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark"),
            s.themeMode, { vm.setThemeMode(it) }, { dialog = null }
        )
        Dialog.DOH -> ChoiceDialog(
            "DNS over HTTPS",
            listOf("off" to "Off (system DNS)", "cloudflare" to "Cloudflare (1.1.1.1)", "google" to "Google (8.8.8.8)", "adguard" to "AdGuard", "custom" to "Custom…"),
            s.dohMode, { vm.setDohMode(it); if (it == "custom") dialog = Dialog.DOH_CUSTOM }, { if (dialog == Dialog.DOH) dialog = null }
        )
        Dialog.DOH_CUSTOM -> {
            var text by remember { mutableStateOf(s.dohCustom) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Custom DoH address") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("https://dns.example.com/dns-query") }) },
                confirmButton = { TextButton(onClick = { vm.setDohCustom(text); dialog = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
            )
        }
        Dialog.CLEAR_CACHE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Clear cache?") },
            text = { Text("Deletes temporary files, image cache and browser cache. Downloads and unfinished downloads are not touched.") },
            confirmButton = { TextButton(onClick = { vm.clearCache(false); dialog = null }) { Text("Clear cache") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.clearCache(true); dialog = null }) { Text("Also logins & history") }
                    TextButton(onClick = { dialog = null }) { Text("Cancel") }
                }
            }
        )
        Dialog.AUDIO -> ChoiceDialog(
            "Audio format", listOf("m4a" to "M4A (original quality)", "opus" to "Opus (WebM audio)"),
            s.audioFormat, { vm.setAudioFormat(it) }, { dialog = null }
        )
        Dialog.DUPLICATE -> ChoiceDialog(
            "If a file already exists", DuplicateMode.entries.map { it to it.label },
            s.duplicateMode, { vm.setDuplicate(it) }, { dialog = null }
        )
        Dialog.FOLDER -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Save location") },
            text = { Text("Choose where finished downloads are saved.") },
            confirmButton = { TextButton(onClick = { dialog = null; folderPicker.launch(null) }) { Text("Choose folder…") } },
            dismissButton = {
                Row {
                    if (s.customFolderUri != null) TextButton(onClick = { vm.setFolder(null); dialog = null }) { Text("Use Downloads/AHD Hub") }
                    TextButton(onClick = { dialog = null }) { Text("Cancel") }
                }
            }
        )
        Dialog.CHANNEL -> ChoiceDialog(
            "Update channel", listOf("stable" to "Stable", "nightly" to "Nightly (newest fixes)"),
            s.updateChannel, { vm.setUpdateChannel(it) }, { dialog = null }
        )
        Dialog.CLEAR_COOKIES -> ConfirmDialog("Remove all logins?", "Saved login cookies and imported cookies.txt are deleted from this phone.",
            { vm.clearCookies() }, { dialog = null })
        Dialog.CLEAR_COMPLETED -> ConfirmDialog("Clear completed downloads?", "The list entries are removed. Downloaded files are kept.",
            { vm.clearCompleted() }, { dialog = null })
        Dialog.CLEAR_FAILED -> ConfirmDialog("Clear failed & canceled?", "These entries and their partial data are removed.",
            { vm.clearFailed() }, { dialog = null })
        null -> Unit
    }
}

private fun qualityLabel(q: Int) = if (q == 0) "Best available" else "${q}p"

/** "2026.10.26" or "2026.10.26.123456" -> "26 Oct 2026"; null when the version has no date. */
private fun versionDate(v: String): String? {
    val m = Regex("""^(\d{4})\.(\d{1,2})\.(\d{1,2})""").find(v) ?: return null
    return try {
        java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
    } catch (e: Exception) { null }
}

@Composable
private fun HalfCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    GlassCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Gold, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Gold, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, hint: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            if (value.isNotEmpty()) Text(value, color = Gold)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(value); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
