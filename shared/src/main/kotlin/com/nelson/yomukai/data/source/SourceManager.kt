package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.anime.AnimeAv1Source
import com.nelson.yomukai.data.source.anime.AnimeFlvSource
import com.nelson.yomukai.data.source.anime.AnitakuSource
import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.FlameComicsSource
import com.nelson.yomukai.data.source.manga.LectorTmoSource
import com.nelson.yomukai.data.source.manga.MangaDexSource
import com.nelson.yomukai.data.source.manga.MarmotaComicsSource
import com.nelson.yomukai.data.source.manga.MegaBananaSource
import com.nelson.yomukai.data.source.manga.NovelCoolNovelSource
import com.nelson.yomukai.data.source.manga.NovelCoolSource
import com.nelson.yomukai.data.source.manga.OlympusScansSource
import com.nelson.yomukai.data.source.manga.YskComicsSource
import com.nelson.yomukai.data.source.manga.ZonaTmoSource
import com.nelson.yomukai.data.source.novel.SkyNovelsSource

class SourceManager(network: HttpFetcher) {

    val all: List<CatalogueSource> = listOf(
        // Manga / manhwa
        MangaDexSource(network),
        MangaDexSource(network, com.nelson.yomukai.data.source.manga.MangaDexProfile.CHINESE),
        OlympusScansSource(network),
        AsuraScansSource(network),
        FlameComicsSource(network),
        LectorTmoSource(network),
        ZonaTmoSource(network),
        NovelCoolSource(network, "es"),
        NovelCoolSource(network, "en"),
        MarmotaComicsSource(network),
        MegaBananaSource(network),
        YskComicsSource(network),
        // Novels
        SkyNovelsSource(network),
        NovelCoolNovelSource(network),
        // Anime
        AnimeAv1Source(network),
        AnimeFlvSource(network),
        AnitakuSource(network)
    )

    private val byId = all.associateBy { it.id }

    val defaultEnabledIds: Set<String> = all.filter { it.enabledByDefault }.map { it.id }.toSet()

    fun get(id: String): CatalogueSource? = byId[id]

    /** Registrable domains of the given sources (own site plus CDNs/players), used for per-source Tor exclusion. */
    fun domainsOf(ids: Set<String>): Set<String> =
        com.nelson.yomukai.data.network.TorRouting.domainsOf(
            ids.mapNotNull { byId[it] }.flatMap { listOf(it.baseUrl) + it.extraHosts + (it.imageHeaders["Referer"] ?: "") }
                .filter { it.isNotBlank() }
        )

    /**
     * Sources the canary found broken, with why: hidden from every list (home, search, catalogues) until they work
     * again, instead of showing errors or garbage. Titles already in a library still open.
     */
    @Volatile
    var degraded: Map<String, String> = emptyMap()

    /** [enabledIds] null means "user never customised the list". Broken sources are left out. */
    fun enabled(enabledIds: Set<String>?): List<CatalogueSource> {
        val ids = enabledIds ?: defaultEnabledIds
        val broken = degraded
        return all.filter { it.id in ids && it.id !in broken }
    }

    fun require(id: String): CatalogueSource = byId[id] ?: error("Fuente desconocida: $id")
}
