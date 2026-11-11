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
    fun findStream(page: String): String? {
        val html = page.take(MAX_SCAN)
        streamtape(html)?.let { return it }
        val texts = listOf(html) + PACKED.findAll(html).take(MAX_PACKED).mapNotNull { unpack(it.value) }
        val found = texts.flatMap { text ->
            STREAM.findAll(text.replace("\\/", "/")).map { it.value.trimEnd('\\') }
                .filterNot { url -> NOISE.any { it in url.lowercase() } || PLAYER_PAGE.containsMatchIn(url) }
        }.map { if (it.startsWith("//")) "https:$it" else it }.distinct()
        return found.firstOrNull { ".m3u8" in it && "master" in it } ?: found.firstOrNull { ".m3u8" in it } ?: found.firstOrNull()
    }

    /** Dean Edwards' packer: `eval(function(p,a,c,k,e,d){…}('payload',base,count,'w|o|r|d|s'.split('|')…))`. */
    fun unpack(script: String): String? {
        if (script.length > MAX_PACKED_SIZE) return null
        val m = PACKER_ARGS.find(script) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val base = m.groupValues[2].toIntOrNull()?.takeIf { it in 2..BASE62 } ?: return null
        val words = m.groupValues[4].split('|').takeIf { it.size <= MAX_WORDS } ?: return null
        // Built by hand with a size limit: a crafted dictionary cannot blow a small script up into a huge text.
        val out = StringBuilder()
        var last = 0
        for (w in WORD.findAll(payload)) {
            out.append(payload, last, w.range.first)
            val word = decode(w.value, base)?.let { words.getOrNull(it) }?.takeIf { it.isNotEmpty() && it.length <= MAX_WORD }
            out.append(word ?: w.value)
            last = w.range.last + 1
            if (out.length > MAX_UNPACKED) return null
        }
        return out.append(payload, last, payload.length).toString()
    }

    /** Streamtape writes its link in pieces: `'//streamtape.c' + ('xcdom/get_video?…').substring(2).substring(1)`. */
    fun streamtape(html: String): String? {
        val m = STREAMTAPE.find(html) ?: return null
        var tail = m.groupValues[2]
        SUBSTRING.findAll(m.groupValues[3]).forEach { cut -> tail = tail.drop(cut.groupValues[1].toInt()) }
        return "https:" + m.groupValues[1] + tail
    }

    /** DoodStream and its many domains (they all redirect to the current one). */
    fun isDood(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.")
        return DOOD_HOSTS.any { host == it } || host.startsWith("dood") || host.startsWith("d0000d") || host.startsWith("d000d")
    }

    /** Dood's player page: the `/pass_md5/…` path and the token its `makePlay()` appends. Null when not there. */
    fun doodPass(html: String): Pair<String, String>? {
        val path = DOOD_PASS.find(html)?.value ?: return null
        val token = DOOD_TOKEN.find(html)?.groupValues?.get(1) ?: path.substringAfterLast('/')
        return path to token
    }

    /** The stream Dood's page builds: its base, ten random letters, the token and the time (as `makePlay()` does). */
    fun doodStream(base: String, token: String, now: Long = System.currentTimeMillis()): String? {
        val clean = base.trim().takeIf { it.startsWith("https://") && it.length < MAX_WORD * 4 } ?: return null
        val letters = (1..DOOD_RANDOM).map { DOOD_ALPHABET.random() }.joinToString("")
        return "$clean$letters?token=$token&expiry=$now"
    }

    /** Hosts whose player only answers on a mirror (Streamwish's main domains load an obfuscated redirect page). */
    fun mirrorFor(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.")
        return if (host in STREAMWISH_FAMILY) url.replaceFirst(Regex("//[^/]+"), "//$STREAMWISH_MIRROR") else url
    }

    /** File lockers (download pages, not players): never offered to play. */
    fun isDownloadOnly(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.")
        return DOWNLOAD_ONLY.any { host == it || host.endsWith(".$it") }
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
        "savefiles.com", "streamsb.net", "embedsito.com", "fembed.com", "player.zilla-networks.com", "hgcloud.to", "luluvdo.com",
        "mixdrop.top", "dsvplay.com", "hglink.to", "dhcplay.com", "playmogo.com", "dood.li", "do7go.com", "swdyu.com",
    )

    private const val MAX_SCAN = 200_000
    private const val MAX_PACKED = 5
    private const val MAX_PACKED_SIZE = 150_000
    private const val MAX_WORDS = 5_000
    private const val MAX_WORD = 200
    private const val MAX_UNPACKED = 1_000_000
    private const val STREAMWISH_MIRROR = "swdyu.com"
    private val STREAMWISH_FAMILY = setOf(
        "hgcloud.to", "streamwish.to", "streamwish.com", "strwish.com", "dhcplay.com", "hglink.to", "playerwish.com", "hlswish.com",
    )
    private val DOOD_HOSTS = setOf("dsvplay.com", "playmogo.com", "do7go.com", "ds2play.com", "ds2video.com", "dooood.com", "vidply.com")
    private val DOOD_PASS = Regex("""/pass_md5/[A-Za-z0-9_\-/]{10,200}""")
    private val DOOD_TOKEN = Regex("""[?&]token=([A-Za-z0-9]{6,64})""")
    private const val DOOD_RANDOM = 10
    private const val DOOD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val DOWNLOAD_ONLY = setOf("1fichier.com", "mediafire.com", "uptobox.com", "zippyshare.com")
    private val PLAYER_PAGE = Regex("""//[^/\s"']+/(?:e|embed|v|d)/[^/\s"'?]+(?:/[^/\s"'?]*)?\.(?:mp4|m3u8)""")
    private val STREAMTAPE = Regex("""robotlink'\)\.innerHTML\s*=\s*'([^']{1,200})'\s*\+\s*\('([^']{1,400})'\)((?:\.substring\(\d+\)){1,3})""")
    private val SUBSTRING = Regex("""\.substring\((\d+)\)""")
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
