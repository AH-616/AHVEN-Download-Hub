package com.aven.downloader.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aven.downloader.AvenApp
import com.aven.downloader.data.DuplicateMode
import com.aven.downloader.data.Settings
import com.aven.downloader.engine.EngineState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val aven = application as AvenApp
    private val repo = aven.settings

    val settings: StateFlow<Settings> = repo.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    val engineState: StateFlow<EngineState> = aven.engine.state

    var updateMessage by mutableStateOf<String?>(null)
        private set
    var updating by mutableStateOf(false)
        private set
    var ytDlpVersion by mutableStateOf("…")
        private set

    /** Bumped when login cookies change, so the Settings rows redraw. */
    var cookieTick by mutableIntStateOf(0)
        private set
    var cookieMessage by mutableStateOf<String?>(null)
        private set

    fun refreshCookies() { cookieTick++ }

    fun importCookies(text: String) {
        val ok = aven.cookies.importText(text)
        cookieMessage = if (ok) "Cookies imported." else "That file doesn't look like a cookies.txt (Netscape format)."
        cookieTick++
    }

    fun clearCookies() {
        aven.cookies.clearAll()
        cookieMessage = "All saved logins were removed."
        cookieTick++
    }

    init {
        viewModelScope.launch {
            aven.engine.state.collect { if (it is EngineState.Ready) ytDlpVersion = aven.engine.ytDlpVersion() }
        }
    }

    fun setQuality(v: Int) = viewModelScope.launch { repo.setDefaultQuality(v) }
    fun setContainer(v: String) = viewModelScope.launch { repo.setVideoContainer(v) }
    fun setAudioFormat(v: String) = viewModelScope.launch { repo.setAudioFormat(v) }
    fun setPreferAudio(v: Boolean) = viewModelScope.launch { repo.setPreferAudio(v) }
    fun setFolder(uri: String?) = viewModelScope.launch {
        repo.setCustomFolder(uri)
        if (uri != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.aven.downloader.data.StorageSaver.createCategoryFolders(aven, android.net.Uri.parse(uri))
        }
    }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { repo.setWifiOnly(v) }
    fun setConcurrent(v: Int) = viewModelScope.launch { repo.setMaxConcurrent(v) }
    fun setDuplicate(v: DuplicateMode) = viewModelScope.launch { repo.setDuplicateMode(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { repo.setNotifications(v) }
    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { repo.setAutoUpdate(v) }
    fun setUpdateChannel(v: String) = viewModelScope.launch { repo.setUpdateChannel(v) }
    fun setParts(v: Int) = viewModelScope.launch { repo.setParts(v) }
    fun setAutoDetectClipboard(v: Boolean) = viewModelScope.launch { repo.setAutoDetectClipboard(v) }
    fun setForceIpv4(v: Boolean) = viewModelScope.launch { repo.setForceIpv4(v) }

    fun clearCompleted() = viewModelScope.launch { aven.downloads.clearCompleted() }
    fun clearFailed() = viewModelScope.launch { aven.downloads.clearFailed() }

    fun updateYtDlp() {
        if (updating) return
        updating = true
        updateMessage = null
        viewModelScope.launch {
            updateMessage = try {
                aven.engine.updateYtDlp(settings.value.updateChannel).also { ytDlpVersion = aven.engine.ytDlpVersion() }
            } catch (t: Throwable) {
                "Couldn't update right now. Check your internet connection and try again."
            } finally {
                updating = false
            }
        }
    }
}
