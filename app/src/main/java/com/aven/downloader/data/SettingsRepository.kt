package com.aven.downloader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aven_settings")

enum class DuplicateMode(val label: String) {
    RENAME("Keep both (rename new file)"),
    OVERWRITE("Replace the existing file"),
    SKIP("Skip (don't download again)")
}

data class Settings(
    val defaultQuality: Int = 0,               // 0 = best, otherwise max height
    val videoContainer: String = "mp4",        // mp4 | mkv
    val audioFormat: String = "mp3",           // mp3 | m4a
    val preferAudio: Boolean = false,          // which tab opens first on the format screen
    val customFolderUri: String? = null,       // null = Download/AHD Hub
    val wifiOnly: Boolean = false,
    val maxConcurrent: Int = 2,                // 1..5
    val duplicateMode: DuplicateMode = DuplicateMode.RENAME,
    val notifications: Boolean = true,
    val autoUpdateYtDlp: Boolean = true,       // refresh yt-dlp at most once a day
    val updateChannel: String = "stable",      // stable | nightly
    val forceIpv4: Boolean = true,             // helps with some YouTube 403/429 cases
    val lastUpdateCheck: Long = 0L,
    val parts: Int = 4,                        // 1..16 parallel parts of one download
    val autoDetectClipboard: Boolean = true    // read a copied link when the app opens
) {
    /** How many segments / connections of one download run at the same time. */
    val fragments: Int get() = parts.coerceIn(1, 16)
}

class SettingsRepository(private val context: Context) {
    private object K {
        val quality = intPreferencesKey("default_quality")
        val container = stringPreferencesKey("video_container")
        val audio = stringPreferencesKey("audio_format")
        val preferAudio = booleanPreferencesKey("prefer_audio")
        val folder = stringPreferencesKey("custom_folder")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val concurrent = intPreferencesKey("max_concurrent")
        val duplicate = stringPreferencesKey("duplicate_mode")
        val notifications = booleanPreferencesKey("notifications")
        val autoUpdate = booleanPreferencesKey("auto_update_ytdlp")
        val channel = stringPreferencesKey("update_channel")
        val ipv4 = booleanPreferencesKey("force_ipv4")
        val lastUpdate = longPreferencesKey("last_update_check")
        val parts = intPreferencesKey("parts")
        val clip = booleanPreferencesKey("auto_clipboard")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultQuality = p[K.quality] ?: 0,
            videoContainer = p[K.container] ?: "mp4",
            audioFormat = p[K.audio] ?: "mp3",
            preferAudio = p[K.preferAudio] ?: false,
            customFolderUri = p[K.folder],
            wifiOnly = p[K.wifiOnly] ?: false,
            maxConcurrent = (p[K.concurrent] ?: 2).coerceIn(1, 5),
            duplicateMode = runCatching { DuplicateMode.valueOf(p[K.duplicate] ?: "RENAME") }
                .getOrDefault(DuplicateMode.RENAME),
            notifications = p[K.notifications] ?: true,
            autoUpdateYtDlp = p[K.autoUpdate] ?: true,
            updateChannel = p[K.channel] ?: "stable",
            forceIpv4 = p[K.ipv4] ?: true,
            lastUpdateCheck = p[K.lastUpdate] ?: 0L,
            parts = (p[K.parts] ?: 4).coerceIn(1, 16),
            autoDetectClipboard = p[K.clip] ?: true
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setDefaultQuality(v: Int) = edit { it[K.quality] = v }
    suspend fun setVideoContainer(v: String) = edit { it[K.container] = v }
    suspend fun setAudioFormat(v: String) = edit { it[K.audio] = v }
    suspend fun setPreferAudio(v: Boolean) = edit { it[K.preferAudio] = v }
    suspend fun setCustomFolder(uri: String?) = edit { if (uri == null) it.remove(K.folder) else it[K.folder] = uri }
    suspend fun setWifiOnly(v: Boolean) = edit { it[K.wifiOnly] = v }
    suspend fun setMaxConcurrent(v: Int) = edit { it[K.concurrent] = v.coerceIn(1, 5) }
    suspend fun setDuplicateMode(v: DuplicateMode) = edit { it[K.duplicate] = v.name }
    suspend fun setNotifications(v: Boolean) = edit { it[K.notifications] = v }
    suspend fun setAutoUpdate(v: Boolean) = edit { it[K.autoUpdate] = v }
    suspend fun setUpdateChannel(v: String) = edit { it[K.channel] = v }
    suspend fun setForceIpv4(v: Boolean) = edit { it[K.ipv4] = v }
    suspend fun setParts(v: Int) = edit { it[K.parts] = v.coerceIn(1, 16) }
    suspend fun setAutoDetectClipboard(v: Boolean) = edit { it[K.clip] = v }
    suspend fun setLastUpdateCheck(v: Long) = edit { it[K.lastUpdate] = v }
}
