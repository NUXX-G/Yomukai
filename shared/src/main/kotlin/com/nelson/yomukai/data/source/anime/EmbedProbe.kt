package com.nelson.yomukai.data.source.anime

/**
 * What a video host's player page says about itself, read before offering it (F2, F3): a deleted video, a page that
 * refuses to play inside the app, and — most useful — the stream hidden in its (often packed) JavaScript, so the
 * episode plays in our own player instead of a host's iframe full of redirects. Pure: tested with real pages.
 */
object EmbedProbe {
    /** The host says the video is gone («WE ARE SORRY · We can't find the video», «File was deleted»…). */
    fun isDead(html: String): Boolean {
        val text = html.take(MAX_SCAN).lowercase()
        return DEAD.any { it in text }
    }

    /** The page only plays outside a sandboxed iframe («This video is not available due to sandboxed iframe!»). */
    fun refusesSandbox(html: String): Boolean = "sandboxed iframe" in html.take(MAX_SCAN).lowercase()

    /** A direct stream in the page or in its packed scripts; HLS master playlists first. Null when none. */
    fun findStream(html: String): String? {
        val texts = listOf(html) + PACKED.findAll(html).mapNotNull { unpack(it.value) }
        val found = texts.flatMap { text ->
            STREAM.findAll(text.replace("\\/", "/")).map { it.value.trimEnd('\\') }.filterNot { url -> NOISE.any { it in url.lowercase() } }
        }.map { if (it.startsWith("//")) "https:$it" else it }.distinct()
        return found.firstOrNull { ".m3u8" in it && "master" in it } ?: found.firstOrNull { ".m3u8" in it } ?: found.firstOrNull()
    }

    /** Dean Edwards' packer: `eval(function(p,a,c,k,e,d){…}('payload',base,count,'w|o|r|d|s'.split('|')…))`. */
    fun unpack(script: String): String? {
        val m = PACKER_ARGS.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val base = m.groupValues[2].toIntOrNull()?.takeIf { it in 2..BASE62 } ?: return null
        val words = m.groupValues[4].split('|')
        return WORD.replace(payload) { w ->
            val index = decode(w.value, base)
            words.getOrNull(index ?: -1)?.takeIf { it.isNotEmpty() } ?: w.value
        }
    }

    private fun decode(token: String, base: Int): Int? {
        var n = 0L
        for (c in token) {
            val d = DIGITS.indexOf(c).takeIf { it in 0 until base } ?: return null
            n = n * base + d
            if (n > Int.MAX_VALUE) return null
        }
        return n.toInt()
    }

    /**
     * Video hosts the anime sources embed. The server may open these through FlareSolverr when they show a bot check
     * (besides the sources' own sites): only these, so nobody can use it to browse anywhere else.
     */
    val VIDEO_HOSTS = setOf(
        "streamwish.to", "streamwish.com", "swdyu.com", "wishembed.pro", "awish.pro", "dwish.pro", "strwish.com",
        "filemoon.sx", "filemoon.to", "filemoon.in", "moonplayer.lat", "vidhide.com", "vidhidepro.com", "vidhidevip.com",
        "voe.sx", "dood.la", "dood.wf", "dood.yt", "doodstream.com", "d0000d.com", "ds2play.com", "streamtape.com",
        "mixdrop.co", "mixdrop.ag", "mp4upload.com", "yourupload.com", "ok.ru", "uqload.io", "uqload.to", "vidmoly.to",
        "vidmoly.me", "lulustream.com", "luluvdo.com", "streamhide.to", "playerwish.com", "hqq.tv", "netu.tv",
        "mega.nz", "sendvid.com", "burstcloud.co", "upstream.to", "listeamed.net", "vidguard.to", "bembed.net",
        "savefiles.com", "streamsb.net", "embedsito.com", "fembed.com", "player.zilla-networks.com",
    )

    private const val MAX_SCAN = 200_000
    private const val BASE62 = 62
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val DEAD = listOf(
        "we can't find the video", "we can’t find the video", "file was deleted", "file not found", "video not found",
        "this video has been removed", "file is no longer available", "the video you are looking for is not found",
        "video no encontrado", "el archivo ha sido eliminado", "this video doesn't exist", "video is unavailable",
    )
    private val PACKED = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\.split\('\|'\)[^)]*\)\)""", RegexOption.DOT_MATCHES_ALL)
    private val PACKER_ARGS = Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val WORD = Regex("""\b\w+\b""")
    private val STREAM = Regex("""(?:https?:)?//[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""")
    private val NOISE = listOf("thumb", "preview", "sprite", "poster", "/ads/", "vast", "trailer")
}
