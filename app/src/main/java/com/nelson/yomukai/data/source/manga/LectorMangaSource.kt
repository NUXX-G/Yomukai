package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.HtmlMangaSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.MediaType

class LectorMangaSource(network: NetworkHelper) : HtmlMangaSource(network) {
    override val id = "lectormanga"
    override val name = "LectorManga"
    override val baseUrl = "https://lectormanga.com"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANGA)

    override val defaultType = MediaType.MANGA
    override val latestPath = "/"
    override fun searchUrl(query: String) = "$baseUrl/search?query=${SourceUtils.encode(query)}"

    override val itemSelector = ".card, .manga-item"
    override val chapterSelector = ".chapters a, li.chapter a"
    override val pageImageSelector = ".img-container img, #reader img"
}
