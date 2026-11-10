package com.aven.downloader.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Hands a link / navigation request from an Android intent to the Compose UI. */
object IncomingLink {
    data class Event(val text: String?, val openDownloads: Boolean, val nonce: Long = System.nanoTime())

    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events

    fun post(text: String?, openDownloads: Boolean) {
        if (text.isNullOrBlank() && !openDownloads) return
        _events.value = Event(text, openDownloads)
    }

    fun consume() { _events.value = null }
}
