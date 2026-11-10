package com.aven.downloader.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aven.downloader.AvenApp
import com.aven.downloader.data.DownloadEntity
import com.aven.downloader.download.DownloadManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val aven = application as AvenApp
    private val manager = aven.downloads

    val items: StateFlow<List<DownloadEntity>> = aven.database.downloads().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val summary: StateFlow<DownloadManager.Summary> = manager.summary
    val frags: StateFlow<Map<Long, DownloadManager.FragProgress>> = manager.frags

    fun pause(id: Long) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { manager.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { manager.retry(id) }
    fun remove(id: Long, deleteFile: Boolean) = viewModelScope.launch { manager.remove(id, deleteFile) }
}
