package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.anime.AnimeAv1Source
import com.nelson.yomukai.data.source.anime.AnimeFlvSource
import com.nelson.yomukai.data.source.anime.AnitakuSource
import com.nelson.yomukai.data.source.manga.AsuraScansSource
import com.nelson.yomukai.data.source.manga.FlameComicsSource
import com.nelson.yomukai.data.source.manga.LectorTmoSource
import com.nelson.yomukai.data.source.manga.MangaDexSource
import com.nelson.yomukai.data.source.manga.MegaBananaSource
import com.nelson.yomukai.data.source.manga.NovelCoolNovelSource
import com.nelson.yomukai.data.source.manga.NovelCoolSource
import com.nelson.yomukai.data.source.manga.OlympusScansSource
import com.nelson.yomukai.data.source.manga.YskComicsSource
import com.nelson.yomukai.data.source.manga.ZonaTmoSource
import com.nelson.yomukai.data.source.novel.SkyNovelsSource

/**
 * Every source: the Kotlin ones (special logic) and the declarative ones from the signed configuration bundle
 * (see declarative/SourceConfig.kt), which can be replaced at run time when a newer bundle arrives.
 */
class SourceManager(
    private val network: HttpFetcher,
    bundle: com.nelson.yomukai.data.source.declarative.SourceBundle? = com.nelson.yomukai.data.source.declarative.SourceBundles.bundled(),
    /** Told when a declarative source follows its site to a new domain: (source, old, new). */
    var onDomainChange: (String, String, String) -> Unit = { _, _, _ -> }
) {

    private val kotlinSources: List<CatalogueSource> = listOf(
        // Manga / manhwa
        MangaDexSource(network),
        MangaDexSource(network, com.nelson.yomukai.data.source.manga.MangaDexProfile.ENGLISH),
        MangaDexSource(network, com.nelson.yomukai.data.source.manga.MangaDexProfile.CHINESE),
        OlympusScansSource(network),
        AsuraScansSource(network),
        FlameComicsSource(network),
        LectorTmoSource(network),
        ZonaTmoSource(network),
        NovelCoolSource(network, "es"),
        NovelCoolSource(network, "en"),
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

    @Volatile
    private var declarative: List<CatalogueSource> = emptyList()

    /** Version of the configuration bundle in use (0 = none). */
    @Volatile
    var bundleVersion: Long = 0
        private set

    @Volatile
    var all: List<CatalogueSource> = kotlinSources
        private set

    @Volatile
    private var byId: Map<String, CatalogueSource> = all.associateBy { it.id }

    val defaultEnabledIds: Set<String> get() = all.filter { it.enabledByDefault }.map { it.id }.toSet()

    init {
        bundle?.let(::apply)
    }

    /**
     * Uses [bundle]'s sources from now on. A configuration with the id of a Kotlin source replaces it (that is how a
     * source moves to a JSON file); the order of the Kotlin list is kept, new ones go at the end.
     */
    fun apply(bundle: com.nelson.yomukai.data.source.declarative.SourceBundle) {
        val made = bundle.configs.map { com.nelson.yomukai.data.source.declarative.declarativeSource(it, network) { id, from, to -> onDomainChange(id, from, to) } }
        val byConfig = made.associateBy { it.id }
        val merged = kotlinSources.map { byConfig[it.id] ?: it } + made.filter { m -> kotlinSources.none { it.id == m.id } }
        declarative = made
        all = merged
        byId = merged.associateBy { it.id }
        bundleVersion = bundle.version
    }

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
