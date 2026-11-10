package com.aven.downloader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

data class SavedFile(val uri: Uri, val displayName: String, val sizeBytes: Long, val skipped: Boolean)

/**
 * Copies a finished download from app-private temp storage into its final location:
 *  - custom folder chosen with the system folder picker (Storage Access Framework), or
 *  - Download/AHD Hub (MediaStore on Android 10+, plain file on Android 8-9).
 */
class StorageSaver(private val context: Context) {

    /** Every download goes into a sub-folder by file type, inside the chosen (or default) folder. */
    enum class Category(val folder: String) {
        VIDEOS("Videos"), MUSIC("Music"), PHOTOS("Photos"), DOCUMENTS("Documents"),
        COMPRESSED("Compressed"), PROGRAMS("Programs"), SUBTITLES("Subtitles"), TORRENTS("Torrents"), OTHERS("Others")
    }

    fun save(src: File, settings: Settings): SavedFile {
        val name = src.name
        val mime = mimeFor(name)
        val custom = settings.customFolderUri
        val cat = categoryFor(name)
        return when {
            custom != null -> saveToTree(src, name, mime, Uri.parse(custom), settings.duplicateMode, cat)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> saveToMediaStore(src, name, mime, settings.duplicateMode, cat)
            else -> saveLegacy(src, name, settings.duplicateMode, cat)
        }
    }

    // ---------- Storage Access Framework (custom folder) ----------
    private fun saveToTree(src: File, name: String, mime: String, tree: Uri, mode: DuplicateMode, cat: Category): SavedFile {
        val root = DocumentFile.fromTreeUri(context, tree)
        if (root == null || !root.exists() || !root.canWrite()) {
            throw IOException("The chosen download folder is no longer accessible. Pick it again in Settings.")
        }
        val dir = subDir(root, cat.folder)
        var target = name
        val existing = dir.findFile(name)
        if (existing != null) {
            when (mode) {
                DuplicateMode.SKIP -> return SavedFile(existing.uri, name, existing.length(), true)
                DuplicateMode.OVERWRITE -> existing.delete()
                DuplicateMode.RENAME -> target = uniqueName(name) { dir.findFile(it) != null }
            }
        }
        val doc = dir.createFile(mime, target)
            ?: throw IOException("Couldn't create the file in the chosen folder.")
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Couldn't write to the chosen folder.")
        return SavedFile(doc.uri, doc.name ?: target, src.length(), false)
    }

    // ---------- MediaStore (Android 10+) ----------
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(src: File, name: String, mime: String, mode: DuplicateMode, cat: Category): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/AHD Hub/${cat.folder}"

        fun find(displayName: String): Uri? {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(displayName, "$relative/"),
                null
            )?.use { c ->
                if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
            }
            return null
        }

        var target = name
        val existing = find(name)
        if (existing != null) {
            when (mode) {
                DuplicateMode.SKIP -> return SavedFile(existing, name, sizeOf(existing), true)
                DuplicateMode.OVERWRITE -> runCatching { resolver.delete(existing, null, null) }
                DuplicateMode.RENAME -> target = uniqueName(name) { find(it) != null }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, target)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Couldn't create the file in Downloads/AHD Hub.")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write to Downloads/AHD Hub.")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return SavedFile(uri, target, src.length(), false)
    }

    // ---------- Android 8-9 (needs WRITE_EXTERNAL_STORAGE) ----------
    @Suppress("DEPRECATION")
    private fun saveLegacy(src: File, name: String, mode: DuplicateMode, cat: Category): SavedFile {
        val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AHD Hub"), cat.folder)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Couldn't create Download/AHD Hub. Allow storage permission for AHD Hub and retry.")
        }
        var target = File(dir, name)
        if (target.exists()) {
            when (mode) {
                DuplicateMode.SKIP -> return SavedFile(Uri.fromFile(target), name, target.length(), true)
                DuplicateMode.OVERWRITE -> target.delete()
                DuplicateMode.RENAME -> target = File(dir, uniqueName(name) { File(dir, it).exists() })
            }
        }
        src.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return SavedFile(Uri.fromFile(target), target.name, target.length(), false)
    }

    // ---------- helpers ----------
    private fun sizeOf(uri: Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    } catch (ignored: Exception) { 0L }

    companion object {
        private val extMap = mapOf(
            Category.VIDEOS to setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "ts", "wmv", "mpg", "mpeg"),
            Category.MUSIC to setOf("mp3", "m4a", "opus", "aac", "flac", "wav", "ogg", "wma", "amr"),
            Category.PHOTOS to setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg"),
            Category.DOCUMENTS to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "rtf", "odt", "ods", "odp", "json", "xml", "html"),
            Category.COMPRESSED to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"),
            Category.PROGRAMS to setOf("apk", "xapk", "apks", "aab", "exe", "msi", "dmg", "deb"),
            Category.SUBTITLES to setOf("srt", "vtt", "ass", "ssa", "sub", "lrc"),
            Category.TORRENTS to setOf("torrent")
        )

        fun categoryFor(fileName: String): Category {
            val e = fileName.substringAfterLast('.', "").lowercase()
            return extMap.entries.firstOrNull { e in it.value }?.key ?: Category.OTHERS
        }

        private val subDirLock = Any()

        private fun subDir(root: DocumentFile, name: String): DocumentFile = synchronized(subDirLock) {
            root.findFile(name)?.takeIf { it.isDirectory }
                ?: root.createDirectory(name)
                ?: throw IOException("Couldn't create the \"$name\" folder in the chosen folder.")
        }

        /** Creates all type folders (empty) inside a freshly chosen folder. */
        fun createCategoryFolders(context: Context, tree: Uri) {
            val root = DocumentFile.fromTreeUri(context, tree) ?: return
            if (!root.canWrite()) return
            Category.values().forEach { runCatching { subDir(root, it.folder) } }
        }

        fun mimeFor(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus", "ogg" -> "audio/ogg"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }

        fun uniqueName(name: String, exists: (String) -> Boolean): String {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (!exists(candidate)) return candidate
                n++
            }
        }

        /** Does the saved file still exist? */
        fun exists(context: Context, uriString: String?): Boolean {
            if (uriString.isNullOrEmpty()) return false
            val uri = Uri.parse(uriString)
            return try {
                if (uri.scheme == "file") File(uri.path!!).exists()
                else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (ignored: Exception) { false }
        }

        /** A content:// URI another app can open (file:// is not allowed to leave the app). */
        fun shareableUri(context: Context, uriString: String): Uri {
            val uri = Uri.parse(uriString)
            return if (uri.scheme == "file") {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(uri.path!!))
            } else uri
        }

        fun deleteFile(context: Context, uriString: String?): Boolean {
            if (uriString.isNullOrEmpty()) return false
            val uri = Uri.parse(uriString)
            return try {
                when {
                    uri.scheme == "file" -> File(uri.path!!).delete()
                    DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(context.contentResolver, uri)
                    else -> context.contentResolver.delete(uri, null, null) > 0
                }
            } catch (ignored: Exception) { false }
        }
    }
}
