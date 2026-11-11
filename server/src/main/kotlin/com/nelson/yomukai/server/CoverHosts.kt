package com.nelson.yomukai.server

import com.nelson.yomukai.domain.model.MediaItem
import java.util.concurrent.ConcurrentHashMap

/**
 * The hosts each source serves its covers from, as seen in that source's own answers to this server (many sites keep
 * covers on a CDN with another domain). The cover proxy signs those too, and still nothing a client merely claims.
 */
object CoverHosts {
    private val bySource = ConcurrentHashMap<String, MutableSet<String>>()
    private const val MAX_HOSTS_PER_SOURCE = 50

    /** A dotted name ending in letters (so no IPv4 literal, no IPv6, no "localhost"). */
    private val PUBLIC_NAME = Regex("^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")

    fun learn(items: List<MediaItem>) = items.forEach { learn(it.sourceId, it.coverUrl) }

    fun learn(sourceId: String, cover: String) {
        if (sourceId.isBlank() || !cover.startsWith("https://") && !cover.startsWith("http://")) return
        val host = runCatching { java.net.URI(cover).host?.lowercase()?.trimEnd('.') }.getOrNull() ?: return
        // Only host names (never addresses, which a scraped page could point anywhere). Fetches still go through
        // ServerHttp, whose DNS and network guards refuse private, loopback and link-local addresses on every hop.
        if (!PUBLIC_NAME.matches(host)) return
        val set = bySource.getOrPut(sourceId) { ConcurrentHashMap.newKeySet() }
        if (set.size < MAX_HOSTS_PER_SOURCE) set.add(host)
    }

    /** Every image host learnt, of any source. */
    fun known(): Set<String> = bySource.values.flatMapTo(HashSet()) { it }

    fun knows(sourceId: String, host: String): Boolean = bySource[sourceId]?.contains(host) == true

    /** After a restart: the covers already in the works index. */
    suspend fun loadFrom(db: Database) {
        db.tx { query("SELECT source_id, cover FROM index_items WHERE cover <> ''") { getString(1) to getString(2) } }
            .forEach { (source, cover) -> learn(source, cover) }
    }
}
