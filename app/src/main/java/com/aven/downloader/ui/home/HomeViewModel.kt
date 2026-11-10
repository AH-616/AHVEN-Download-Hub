package com.aven.downloader.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aven.downloader.AvenApp
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.EngineState
import com.aven.downloader.engine.Fetched
import com.aven.downloader.engine.ErrorMapper
import com.aven.downloader.engine.MediaInfo
import com.aven.downloader.engine.PreviewPlanner
import com.aven.downloader.engine.PreviewSource
import com.aven.downloader.util.UrlParser
import com.aven.downloader.util.UrlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface FetchState {
    data object Idle : FetchState
    data object Loading : FetchState
    /** A playlist link: the selection popup handles it. */
    data class OpenPopup(val url: String) : FetchState
    data class Error(val message: String, val details: String?) : FetchState
    data class Loaded(
        val info: MediaInfo,
        val preview: PreviewSource?,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
        val version: Int = 0
    ) : FetchState
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val aven = application as AvenApp

    var input by mutableStateOf("")
        private set

    private val _state = MutableStateFlow<FetchState>(FetchState.Idle)
    val state: StateFlow<FetchState> = _state

    val engineState: StateFlow<EngineState> = aven.engine.state

    private var job: Job? = null

    fun onInput(text: String) {
        input = text
        if (_state.value is FetchState.Error) _state.value = FetchState.Idle
    }

    /** Called when the user taps "Preview & download", or when a link is shared to the app. */
    fun submit(text: String = input) {
        when (val r = UrlParser.parse(text)) {
            is UrlResult.Unsupported -> {
                input = text
                _state.value = FetchState.Error(r.message, null)
            }
            is UrlResult.Supported -> {
                input = r.url
                load(r.url)
            }
        }
    }

    private fun load(url: String) {
        job?.cancel()
        _state.value = FetchState.Loading
        job = viewModelScope.launch {
            try {
                _state.value = when (val f = aven.engine.fetch(url)) {
                    is Fetched.Single -> FetchState.Loaded(f.info, PreviewPlanner.choose(f.info))
                    is Fetched.Playlist -> FetchState.OpenPopup(url)
                    is Fetched.File -> FetchState.OpenPopup(url)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val e = ErrorMapper.map(t.message)
                _state.value = FetchState.Error(e.summary, e.details)
            }
        }
    }

    fun cancelLoading() {
        job?.cancel()
        _state.value = FetchState.Idle
    }

    fun dismissPreview() {
        job?.cancel()
        _state.value = FetchState.Idle
    }

    /** Stream links expire. Re-extracts the video to get fresh preview URLs. */
    fun refreshPreview() {
        val current = _state.value as? FetchState.Loaded ?: return
        _state.value = current.copy(refreshing = true, refreshError = null)
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val info = aven.engine.fetchInfo(current.info.sourceUrl)
                _state.value = FetchState.Loaded(info, PreviewPlanner.choose(info), version = current.version + 1)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = current.copy(refreshing = false, refreshError = ErrorMapper.map(t.message).summary)
            }
        }
    }

    fun download(spec: DownloadSpec, onQueued: () -> Unit) {
        val loaded = _state.value as? FetchState.Loaded ?: return
        viewModelScope.launch {
            aven.downloads.enqueue(loaded.info, spec)
            _state.value = FetchState.Idle
            input = ""
            onQueued()
        }
    }

    fun retryEngine() = aven.engine.retryInit(aven.appScope)
}
