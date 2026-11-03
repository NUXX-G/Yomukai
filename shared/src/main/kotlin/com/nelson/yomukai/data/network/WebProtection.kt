package com.nelson.yomukai.data.network

import org.jsoup.Jsoup

/** Recognizes anti-bot pages and cleans browser-rendered answers. Pure: used by the app and the server. */
object WebProtection {
    /** A real mobile Chrome: several sites reject bare clients. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    /** A challenge in progress. The `challenge-platform` script alone proves nothing: Cloudflare adds it to normal pages too. */
    fun isCloudflareChallenge(html: String): Boolean {
        val head = html.take(6000)
        return "cf-browser-verification" in head || "_cf_chl_opt" in head ||
            "<title>Just a moment" in head || "<title>Un momento" in head
    }

    /** A browser shows JSON or plain text wrapped in `<pre>`; the callers want the original text. */
    fun unwrapPlainText(html: String): String {
        val body = Jsoup.parse(html).body()
        val only = body.children().singleOrNull()
        return if (only?.tagName() == "pre" && body.ownText().isBlank()) only.wholeText() else html
    }

    /** Recognizes the block pages of common anti-bot/firewall products; null when the page looks real. */
    fun detectProtection(code: Int, body: String, cfMitigated: String? = null): String? {
        val head = body.take(6000)
        return when {
            cfMitigated != null || isCloudflareChallenge(body) -> "Cloudflare"
            "Attention Required! | Cloudflare" in head || "Sorry, you have been blocked" in head -> "Cloudflare"
            "Tu acceso a este sitio ha sido limitado" in head ||
                "Your access to this site has been limited" in head -> "Wordfence"
            "DDoS-Guard" in head && code in setOf(403, 503) -> "DDoS-Guard"
            code == 403 && body.length < 2000 && "<html" in body.lowercase() -> "acceso denegado"
            else -> null
        }
    }
}
