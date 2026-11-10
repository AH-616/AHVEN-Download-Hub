package com.aven.downloader.ui.quick

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aven.downloader.AvenApp
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.ErrorMapper
import com.aven.downloader.engine.Fetched
import com.aven.downloader.engine.FileChoice
import com.aven.downloader.engine.FileInfo
import com.aven.downloader.engine.MediaInfo
import com.aven.downloader.engine.PlaylistEntry
import com.aven.downloader.engine.PlaylistInfo
import com.aven.downloader.engine.QuickHint
import com.aven.downloader.engine.QuickHintFetcher
import com.aven.downloader.util.UrlParser
import com.aven.downloader.util.UrlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface QuickState {
    /** [hint] = title and picture shown while the quality list is still being read. */
    data class Loading(val hint: QuickHint? = null) : QuickState
    data object NoLink : QuickState
    data class Error(val message: String, val details: String?) : QuickState
    /** [inPlaylist] = the link also points into a playlist, so "open the whole playlist" is offered. */
    data class Video(val info: MediaInfo, val inPlaylist: Boolean) : QuickState
    data class Playlist(val playlist: PlaylistInfo) : QuickState
    data class File(val file: FileInfo) : QuickState
}

/** State of the small download popup (Share menu / clipboard / playlist link). */
class QuickViewModel(application: Application) : AndroidViewModel(application) {
    private val aven = application as AvenApp

    private val _state = MutableStateFlow<QuickState>(QuickState.Loading())
    val state: StateFlow<QuickState> = _state

    private var job: Job? = null
    private var hintJob: Job? = null
    private var currentUrl: String? = null

    fun start(text: String?) {
        when (val r = UrlParser.parse(text)) {
            is UrlResult.Unsupported -> _state.value = QuickState.Error(r.message, null)
            is UrlResult.Supported -> load(r.url, false)
        }
    }

    fun noLink() { _state.value = QuickState.NoLink }

    fun retry() { currentUrl?.let { load(it, false) } }

    fun openPlaylist() { currentUrl?.let { load(it, true) } }

    fun cancelLoading() {
        job?.cancel()
        hintJob?.cancel()
        _state.value = QuickState.NoLink
    }

    private fun load(url: String, forcePlaylist: Boolean) {
        job?.cancel()
        currentUrl = url
        _state.value = QuickState.Loading()
        hintJob?.cancel()   // a late hint of the previous link must never show on this one
        if (!forcePlaylist && UrlParser.isYoutubeSingleVideo(url)) {
            hintJob = viewModelScope.launch {
                val h = QuickHintFetcher.youtube(url) ?: return@launch
                // Only while the full result hasn't arrived yet and the user is still on this link.
                if (currentUrl == url && _state.value is QuickState.Loading) _state.value = QuickState.Loading(h)
            }
        }
        job = viewModelScope.launch {
            try {
                _state.value = when (val f = aven.engine.fetch(url, forcePlaylist)) {
                    is Fetched.Single -> QuickState.Video(f.info, UrlParser.playlistUrlOf(url) != null)
                    is Fetched.Playlist -> QuickState.Playlist(f.playlist)
                    is Fetched.File -> QuickState.File(f.file)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val e = ErrorMapper.map(t.message)
                _state.value = QuickState.Error(e.summary, e.details)
            }
        }
    }

    suspend fun downloadVideo(info: MediaInfo, spec: DownloadSpec) {
        aven.downloads.enqueue(info, spec)
    }

    suspend fun downloadFile(info: FileInfo, choice: FileChoice) {
        aven.downloads.enqueueFile(info, choice)
    }

    suspend fun downloadEntries(entries: List<PlaylistEntry>, spec: DownloadSpec): Int =
        aven.downloads.enqueueAll(entries, spec)
}
