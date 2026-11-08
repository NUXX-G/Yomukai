package com.nelson.yomukai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Episodes some sites only offer inside their own player (an embed): shown full screen on a page of their own, since
 * the app's pages are cross-origin isolated (for the in-browser voices) and cannot frame other sites. The page only
 * frames addresses the server signed itself (from a source's episode list), in a sandbox: the player runs, but it
 * can neither open pop-ups nor move this tab elsewhere. Headers for this path: see [EMBED_PATH] in securityHeaders.
 */
fun Route.embedPage(proxy: MediaProxy) {
    get(EMBED_PATH) {
        val url = call.request.queryParameters["u"].orEmpty()
        val key = call.request.queryParameters["k"].orEmpty()
        if (!url.startsWith("https://") || !proxy.embedSigned(url, key)) {
            return@get call.respondText("Enlace no válido", status = HttpStatusCode.Forbidden)
        }
        val back = safeBack(call.request.queryParameters["back"])
        call.respondText(embedHtml(url, back), ContentType.Text.Html)
    }
}

/** A path of this site to go back to; anything else (other sites, "//x", tabs or spaces browsers would drop) is "/". */
fun safeBack(raw: String?): String = raw?.takeIf { it.length <= MAX_BACK && BACK_PATH.matches(it) && !it.startsWith("//") } ?: "/"

private val BACK_PATH = Regex("""^/[A-Za-z0-9/_\-.%?=&]*$""")
private const val MAX_BACK = 500

private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

fun embedHtml(url: String, back: String): String = """<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="referrer" content="origin"><title>Yomukai</title>
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden}
iframe{position:fixed;inset:0;width:100%;height:100%;border:0}
a{position:fixed;top:calc(env(safe-area-inset-top) + 10px);left:10px;z-index:1;padding:8px 14px;border-radius:999px;background:#000a;color:#fff;
font:600 14px system-ui,sans-serif;text-decoration:none;opacity:.55}
a:hover,a:focus{opacity:1}
</style></head><body>
<a href="${attr(back)}">← Volver</a>
<iframe src="${attr(url)}" sandbox="allow-scripts allow-same-origin allow-presentation" allow="fullscreen; autoplay; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="origin"></iframe>
</body></html>
"""

const val EMBED_PATH = "/ver"

/** This page's own policy: it frames one https player and runs nothing itself. */
const val EMBED_CONTENT_SECURITY_POLICY =
    "default-src 'none'; frame-src https:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
const val EMBED_PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), fullscreen=*, picture-in-picture=*, autoplay=*, encrypted-media=*"
