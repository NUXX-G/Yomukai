package com.nelson.yomukai.server

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.data.source.declarative.DeclarativeSource
import com.nelson.yomukai.data.source.declarative.SourceBundles
import com.nelson.yomukai.data.source.declarative.SourceConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.slf4j.LoggerFactory

/**
 * The admin's catalogue of sources, like plugins but safe: a source is either code in this repository (reviewed, comes
 * with releases) or a JSON description signed with an Ed25519 key — the official one, or the key of an extra
 * repository the admin chose to trust ([Repo], from SOURCE_REPOS). Nothing downloaded is ever run as code, and an
 * extra repository can only add sources, never replace an official one.
 *
 * The admin can take any source out of the catalogue for everyone (and put it back) with one switch.
 */
class SourceCatalog(
    private val db: Database,
    private val sources: SourceManager,
    private val http: HttpFetcher,
    private val repos: List<Repo>,
    private val health: SourceHealthStore
) {
    /** An extra signed repository: the bundle's address (its signature at the same address + ".sig") and its key. */
    data class Repo(val url: String, val publicKey: String) {
        val host: String get() = url.substringAfter("://").substringBefore('/')
    }

    @Volatile
    var lastRejected: List<String> = emptyList()
        private set

    suspend fun loadHidden() {
        sources.hidden = db.tx { query("SELECT id FROM source_settings WHERE hidden = 1") { getString(1) } }.toSet()
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        require(sources.get(id) != null) { "Fuente desconocida" }
        db.tx {
            update(
                "INSERT INTO source_settings(id, hidden, updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET hidden = excluded.hidden, updated_at = excluded.updated_at",
                id, hidden, System.currentTimeMillis()
            )
        }
        loadHidden()
    }

    /** Fetches every extra repository; one that fails or whose signature does not match is skipped (and reported). */
    suspend fun refreshExtra() {
        if (repos.isEmpty()) return
        val problems = mutableListOf<String>()
        val byRepo = linkedMapOf<String, List<SourceConfig>>()
        for (repo in repos) {
            runCatching {
                val text = http.getString(repo.url, emptyMap(), cloudflareFallback = false)
                val sig = http.getString(repo.url + ".sig", emptyMap(), cloudflareFallback = false)
                val bundle = SourceBundles.load(text.toByteArray(Charsets.UTF_8), sig, repo.publicKey) ?: error("firma no válida")
                byRepo[repo.host] = bundle.configs
                problems += bundle.rejected.map { "${repo.host}: $it" }
            }.onFailure { problems += "${repo.host}: ${it.message}" }
        }
        problems += sources.applyExtra(byRepo).map { "id ya usado: $it" }
        lastRejected = problems
        if (problems.isNotEmpty()) log.warn("Repositorios de fuentes: {}", problems)
    }

    /** Everything the admin panel shows for each source. */
    suspend fun list(): List<Map<String, Any?>> {
        val healthById = health.all().associateBy { it.sourceId }
        val official = sources.bundleVersion
        return sources.all.map { s ->
            val declarative = s is DeclarativeSource
            val origin = sources.origins[s.id]
            mapOf(
                "id" to s.id, "name" to s.name, "language" to s.language, "types" to s.supportedTypes.map { it.name },
                "kind" to when (s) { is AnimeSource -> "anime"; is NovelSource -> "novel"; else -> "manga" },
                "format" to if (declarative) "json" else "código",
                "origin" to (origin ?: if (s.id == JellyfinSource.ID) "tu NAS" else "oficial"),
                "version" to if (declarative && origin == null) official else null,
                "hidden" to (s.id in sources.hidden),
                "health" to healthById[s.id]?.let { mapOf("status" to it.status.name, "summary" to it.summary, "at" to it.checkedAt) },
                "degraded" to sources.degraded[s.id]
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SourceCatalog::class.java)

        /** "https://host/bundle.json#BASE64KEY, …": only https, only well-formed keys. */
        fun parseRepos(raw: String?): List<Repo> = raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { entry ->
            val url = entry.substringBefore('#').trim()
            val key = entry.substringAfter('#', "").trim()
            val keyOk = runCatching { java.util.Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)
            if (url.startsWith("https://") && url.length < 500 && keyOk) Repo(url, key) else null
        }
    }
}

/** Admin: the catalogue of sources (installed or taken out for everyone, origin, version, health). */
fun Route.sourceCatalogAdminRoutes(s: Services) {
    get("/sources/catalog") {
        call.respond(mapOf("sources" to s.sourceCatalog.list(), "rejected" to s.sourceCatalog.lastRejected, "officialVersion" to s.sources.bundleVersion))
    }
    put("/sources/{id}") {
        val body = call.jsonBody(512)
        s.sourceCatalog.setHidden(call.parameters["id"].orEmpty().take(60), body.get("hidden")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    post("/sources/refresh") {
        runCatching { s.bundles.fetchNewer(s.sources.bundleVersion) }.getOrNull()?.let(s.sources::apply)
        s.sourceCatalog.refreshExtra()
        call.respond(mapOf("officialVersion" to s.sources.bundleVersion, "rejected" to s.sourceCatalog.lastRejected))
    }
}
