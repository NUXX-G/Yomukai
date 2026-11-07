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

    @Volatile
    private var official: com.nelson.yomukai.data.source.declarative.SourceBundle? = null

    /** JSON sources from other signed repositories (each with its own key), never replacing an official one. */
    @Volatile
    private var extraConfigs: List<com.nelson.yomukai.data.source.declarative.SourceConfig> = emptyList()

    /** Where each declarative source came from: "oficial" or the extra repository's host. */
    @Volatile
    var origins: Map<String, String> = emptyMap()
        private set

    /**
     * Adds the sources of extra signed repositories ([byRepo]: repository host → its verified configurations). An id
     * already taken (by the code or by the official bundle) is skipped and reported, so nothing official can be
     * replaced from outside. Returns the ids rejected that way.
     */
    fun applyExtra(byRepo: Map<String, List<com.nelson.yomukai.data.source.declarative.SourceConfig>>): List<String> {
        val taken = kotlinSources.map { it.id }.toMutableSet().apply {
            addAll(official?.configs.orEmpty().map { it.id })
            addAll(extra.map { it.id })
            addAll(RESERVED_IDS)
        }
        val accepted = mutableListOf<com.nelson.yomukai.data.source.declarative.SourceConfig>()
        val rejected = mutableListOf<String>()
        val origin = mutableMapOf<String, String>()
        for ((repo, configs) in byRepo) {
            for (c in configs) {
            if (!taken.add(c.id)) rejected += "${c.id} ($repo)" else { accepted += c; origin[c.id] = repo }
        }
        }
        extraConfigs = accepted
        origins = origin
        val base = official ?: com.nelson.yomukai.data.source.declarative.SourceBundle(bundleVersion, emptyList(), emptyList())
        apply(base)
        return rejected
    }

    /**
     * Sources the admin took out of the catalogue: gone from every list and search for everyone (a title already in
     * a library still opens). Put back with the same switch.
     */
    @Volatile
    var hidden: Set<String> = emptySet()

    /** Sources only one host has (the server's Jellyfin): put first, and replaced as a whole when they change. */
    @Volatile
    private var extra: List<CatalogueSource> = emptyList()

    /** Version of the configuration bundle in use (0 = none). */
    @Volatile
    var bundleVersion: Long = 0
        private set

    @Volatile
    var all: List<CatalogueSource> = kotlinSources
        private set

    @Volatile
    private var byId: Map<String, CatalogueSource> = all.associateBy { it.id }

    val defaultEnabledIds: Set<String> get() = all.filter { it.enabledByDefault && it.id !in hidden }.map { it.id }.toSet()

    init {
        bundle?.let(::apply)
    }

    /**
     * Uses [bundle]'s sources from now on. A configuration with the id of a Kotlin source replaces it (that is how a
     * source moves to a JSON file); the order of the Kotlin list is kept, new ones go at the end.
     */
    fun apply(bundle: com.nelson.yomukai.data.source.declarative.SourceBundle) {
        official = bundle
        val all = bundle.configs + extraConfigs.filter { e -> bundle.configs.none { it.id == e.id } && kotlinSources.none { it.id == e.id } }
        val made = all.map { com.nelson.yomukai.data.source.declarative.declarativeSource(it, network) { id, from, to -> onDomainChange(id, from, to) } }
        val byConfig = made.associateBy { it.id }
        declarative = made
        rebuild(byConfig)
        bundleVersion = bundle.version
    }

    fun setExtra(sources: List<CatalogueSource>) {
        if (sources.map { it.id } == extra.map { it.id }) return
        extra = sources
        rebuild(declarative.associateBy { it.id })
    }

    private fun rebuild(byConfig: Map<String, CatalogueSource>) {
        // Host-only sources (the server's Jellyfin) can never be covered by a JSON source with the same id.
        val hostIds = extra.map { it.id }.toSet()
        val merged = extra + kotlinSources.map { byConfig[it.id] ?: it }.filter { it.id !in hostIds } +
            declarative.filter { m -> kotlinSources.none { it.id == m.id } && m.id !in hostIds }
        all = merged
        byId = merged.associateBy { it.id }
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
        val off = hidden
        return all.filter { it.id in ids && it.id !in broken && it.id !in off }
    }

    fun require(id: String): CatalogueSource = byId[id] ?: error("Fuente desconocida: $id")

    companion object {
        /** Ids only this program may use, whether or not their source is running now (the server's Jellyfin). */
        val RESERVED_IDS = setOf("jellyfin")
    }
}
