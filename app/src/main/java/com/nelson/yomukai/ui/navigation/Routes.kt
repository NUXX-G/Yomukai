package com.nelson.yomukai.ui.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val SEARCH = "search?query={query}"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val BROWSE = "browse/{sourceId}"
    const val DETAILS = "details/{sourceId}/{mediaId}"
    const val MANGA_READER = "manga_reader/{sourceId}/{mediaId}/{chapterId}"
    const val NOVEL_READER = "novel_reader/{sourceId}/{mediaId}/{chapterId}"
    const val ANIME_PLAYER = "anime_player?url={url}&title={title}&referer={referer}&sourceId={sourceId}&mediaId={mediaId}&chapterId={chapterId}&server={server}"

    const val WEB_CAPTURE = "web_capture?url={url}"
    const val QUICK_READER = "quick_reader"

    const val ARG_SOURCE = "sourceId"
    const val ARG_MEDIA = "mediaId"
    const val ARG_CHAPTER = "chapterId"
    const val ARG_QUERY = "query"

    private fun enc(value: String) = Uri.encode(value)

    fun search(query: String = "") = "search?query=${enc(query)}"
    fun browse(sourceId: String) = "browse/${enc(sourceId)}"
    fun details(sourceId: String, mediaId: String) = "details/${enc(sourceId)}/${enc(mediaId)}"
    fun mangaReader(sourceId: String, mediaId: String, chapterId: String) =
        "manga_reader/${enc(sourceId)}/${enc(mediaId)}/${enc(chapterId)}"
    fun novelReader(sourceId: String, mediaId: String, chapterId: String) =
        "novel_reader/${enc(sourceId)}/${enc(mediaId)}/${enc(chapterId)}"
    fun webCapture(url: String = "") = "web_capture?url=${enc(url)}"
    fun animePlayer(
        url: String, title: String, referer: String = "",
        sourceId: String = "", mediaId: String = "", chapterId: String = "", server: String = ""
    ) =
        "anime_player?url=${enc(url)}&title=${enc(title)}&referer=${enc(referer)}" +
            "&sourceId=${enc(sourceId)}&mediaId=${enc(mediaId)}&chapterId=${enc(chapterId)}&server=${enc(server)}"
}
