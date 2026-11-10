package com.aven.downloader.update

import org.json.JSONObject

data class ReleaseInfo(val code: Int, val tag: String, val apkUrl: String, val size: Long)

/** Pure rules of the in-app updater (unit-tested). */
object UpdatePolicy {
    /** A new version may be skipped for at most 2 days; from day 3 the update is mandatory. */
    const val GRACE_MS = 2L * 24 * 60 * 60 * 1000

    fun mustUpdate(current: Int, latest: Int, firstSeenMs: Long, nowMs: Long): Boolean =
        latest > current && firstSeenMs > 0 && nowMs - firstSeenMs >= GRACE_MS

    fun deadline(firstSeenMs: Long): Long = firstSeenMs + GRACE_MS

    /** Release tags look like "build-17". */
    fun codeOfTag(tag: String): Int? = Regex("""(\d+)\s*$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /** Picks the APK that fits this phone: arm64 > v7a > universal. [abis] = Build.SUPPORTED_ABIS. */
    fun pickAsset(names: List<String>, abis: List<String>): Int {
        val apks = names.withIndex().filter { it.value.endsWith(".apk", true) }
        for (abi in abis) apks.firstOrNull { it.value.contains(abi, true) }?.let { return it.index }
        apks.firstOrNull { it.value.contains("universal", true) }?.let { return it.index }
        return apks.firstOrNull()?.index ?: -1
    }

    /** Reads GitHub's "latest release" JSON. */
    fun parseRelease(json: String, abis: List<String>): ReleaseInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = codeOfTag(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
        val i = pickAsset(names, abis)
        if (i < 0) return null
        val a = assets.getJSONObject(i)
        return ReleaseInfo(code, tag, a.optString("browser_download_url"), a.optLong("size", 0L))
    }
}
