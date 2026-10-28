package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.anime.AnimeAv1Source
import com.nelson.yomukai.data.source.anime.AnimeFlvSource
import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.FlameComicsSource
import com.nelson.yomukai.data.source.manga.LectorMangaSource
import com.nelson.yomukai.data.source.manga.LectorTmoSource
import com.nelson.yomukai.data.source.manga.MangaDexSource
import com.nelson.yomukai.data.source.manga.OlympusScansSource
import com.nelson.yomukai.data.source.manga.SurtrScansSource
import com.nelson.yomukai.data.source.novel.RoyalRoadSource
import com.nelson.yomukai.data.source.novel.SkyNovelsSource
import com.nelson.yomukai.data.source.novel.TuNovelaLigeraSource

class SourceManager(network: NetworkHelper) {

    val all: List<CatalogueSource> = listOf(
        // Manga / manhwa
        MangaDexSource(network),
        OlympusScansSource(network),
        AsuraScansSource(network),
        FlameComicsSource(network),
        LectorTmoSource(network),
        LectorMangaSource(network),
        SurtrScansSource(network),
        // Novels
        RoyalRoadSource(network),
        SkyNovelsSource(network),
        TuNovelaLigeraSource(network),
        // Anime
        AnimeAv1Source(network),
        AnimeFlvSource(network)
    )

    private val byId = all.associateBy { it.id }

    val defaultEnabledIds: Set<String> = all.filterNot { it.isExperimental }.map { it.id }.toSet()

    fun get(id: String): CatalogueSource? = byId[id]

    /** [enabledIds] null means "user never customised the list". */
    fun enabled(enabledIds: Set<String>?): List<CatalogueSource> {
        val ids = enabledIds ?: defaultEnabledIds
        return all.filter { it.id in ids }
    }

    fun require(id: String): CatalogueSource = byId[id] ?: error("Fuente desconocida: $id")
}
