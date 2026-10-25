package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.HtmlMangaSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.MediaType

class SurtrScansSource(network: NetworkHelper) : HtmlMangaSource(network) {
    override val id = "surtrscans"
    override val name = "SurtrScans"
    override val baseUrl = "https://surtrscans.com"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANHWA, MediaType.MANGA)

    override val defaultType = MediaType.MANHWA
    override val latestPath = "/"
    override fun searchUrl(query: String) = "$baseUrl/?s=${SourceUtils.encode(query)}"

    override val itemSelector = ".utao, .bsx"
    override val chapterSelector = "#chapterlist li, .eplister li"
    override val chapterTitleSelector = ".chapternum"
    override val pageImageSelector = "#readerarea img"
}
