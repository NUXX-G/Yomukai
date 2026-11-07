package com.nelson.yomukai.data.update

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher

/** A newer version published on GitHub: its number and the page with the APK. */
data class AvailableUpdate(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Whether a newer app is out, from the repository's latest GitHub release (only the public API, no account). The app
 * shows a quiet notice and the user installs it by hand: nothing is downloaded or installed on its own.
 */
object AppUpdates {
    const val LATEST = "https://api.github.com/repos/NUXX-G/Yomukai/releases/latest"

    /** "1.13.0" < "2.0.0" < "2.0.1"; a leading "v" and anything after "-" are ignored. Unreadable parts count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Reads GitHub's answer: the update when newer than [current], else null. Only github.com links are kept. */
    fun parse(json: String, current: String): AvailableUpdate? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        if (o.get("draft")?.asBoolean == true || o.get("prerelease")?.asBoolean == true) return null
        val tag = o.get("tag_name")?.asString ?: return null
        val page = o.get("html_url")?.asString?.takeIf { it.startsWith("https://github.com/") } ?: return null
        val apk = o.getAsJsonArray("assets")?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
            ?.get("browser_download_url")?.asString?.takeIf { it.startsWith("https://github.com/") }
        if (isNewer(tag, current)) AvailableUpdate(tag.removePrefix("v"), page, apk) else null
    }.getOrNull()

    suspend fun check(network: HttpFetcher, current: String): AvailableUpdate? =
        parse(network.getString(LATEST, mapOf("Accept" to "application/vnd.github+json"), cloudflareFallback = false), current)
}
