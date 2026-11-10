package com.aven.downloader.util

import java.util.Locale

object Formatters {
    fun bytes(value: Long): String {
        if (value <= 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${value} B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSec: Long): String = if (bytesPerSec <= 0) "" else bytes(bytesPerSec) + "/s"

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
