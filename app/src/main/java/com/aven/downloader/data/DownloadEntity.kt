package com.aven.downloader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationSec: Int = 0,
    val kind: DownloadKind,
    val heightCap: Int = 0,
    val container: String = "mp4",
    val audioFormat: String = "mp3",
    val formatLabel: String = "",
    @ColumnInfo(defaultValue = "''") val audioLang: String = "",
    @ColumnInfo(defaultValue = "''") val pinned: String = "",
    @ColumnInfo(defaultValue = "''") val fileSource: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,          // 0..100, of the stream currently downloading
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = 0,
    val statusDetail: String? = null,  // e.g. "Downloading stream 1 of 2", "Merging…"
    val outputUri: String? = null,
    val outputName: String? = null,
    val sizeBytes: Long = 0,
    val errorSummary: String? = null,
    val errorDetails: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0
) {
    @Ignore
    fun toSpec() = DownloadSpec(kind, heightCap, container, audioFormat, formatLabel, audioLang, pinned, fileSource)
    @get:Ignore
    val isActive get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED
}
