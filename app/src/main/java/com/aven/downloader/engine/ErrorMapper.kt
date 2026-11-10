package com.aven.downloader.engine

data class FriendlyError(val summary: String, val details: String)

/** Turns raw yt-dlp error text into something readable. The raw text is always kept as details. */
object ErrorMapper {
    fun map(raw: String?): FriendlyError {
        val text = raw?.trim().orEmpty()
        val l = text.lowercase()
        val summary = when {
            text.startsWith("DL:") -> text.removePrefix("DL:").trim()
            "confirm you" in l && "not a bot" in l ->
                "YouTube is asking for bot verification (it often blocks shared mobile/VPN networks). Try another network (Wi-Fi or mobile data), wait a few minutes, or log in under Settings → Login cookies."
            ("drive" in l || "googledrive" in l) && ("quota" in l || "too many users" in l) ->
                "Google Drive says this file has been downloaded too many times (quota exceeded). Try again later, or make a copy of it in your own Drive."
            ("drive.google" in l || "googledrive" in l || "google drive" in l) &&
                ("sign in" in l || "login" in l || "log in" in l || "permission" in l || "access denied" in l ||
                    "403" in l || "404" in l || "not found" in l || "cannot access" in l || "unable to download" in l) ->
                "Couldn't open this Google Drive link. The file or folder must be shared with \"Anyone with the link\", or log in under Settings → Login cookies → Google Drive."
            "private video" in l -> "This video is private."
            "drm" in l -> "This video is DRM-protected and can't be downloaded."
            "login required" in l || "log in" in l || "sign in" in l || "cookies" in l && "facebook" in l ->
                "This video needs a login (private, age-restricted or members-only). Log in under Settings → Login cookies, or import a cookies.txt file."
            "video unavailable" in l || "not available" in l && "format" !in l ||
                "has been removed" in l || "this content isn't available" in l ->
                "This video is unavailable (removed, private, or restricted in your region)."
            "requested format is not available" in l ->
                "That quality isn't available for this video. Pick another option."
            "unsupported url" in l -> "This link isn't supported."
            "http error 403" in l -> "The server refused the download (403). AHD Hub already tried the other YouTube modes. Update yt-dlp in Settings, or try another network / Login cookies."
            "http error 429" in l || "too many requests" in l ->
                "Too many requests (429). Wait a bit and try again."
            "no space left" in l -> "Not enough storage space on the device."
            "unable to download" in l && ("resolve" in l || "timed out" in l || "unreachable" in l) ||
                "network is unreachable" in l || "timed out" in l || "name or service not known" in l ||
                "temporary failure in name resolution" in l ->
                "Network problem. Check your connection and retry."
            "ffmpeg" in l && ("not found" in l || "not installed" in l) ->
                "FFmpeg is missing, so merging/conversion failed."
            text.isEmpty() -> "Something went wrong (no error message was returned)."
            else -> firstErrorLine(text) ?: "Download failed."
        }
        return FriendlyError(summary, text.ifEmpty { summary })
    }

    private fun firstErrorLine(text: String): String? =
        text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")?.trim()
            ?.replace(Regex("""\[[^\]]+]\s*[\w-]+:\s*"""), "")
            ?.take(220)
            ?.ifEmpty { null }
}
