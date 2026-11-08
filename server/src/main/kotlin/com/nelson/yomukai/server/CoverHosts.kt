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

    fun learn(items: List<MediaItem>) = items.forEach { learn(it.sourceId, it.coverUrl) }

    fun learn(sourceId: String, cover: String) {
        if (sourceId.isBlank() || !cover.startsWith("https://") && !cover.startsWith("http://")) return
        val host = runCatching { java.net.URI(cover).host?.lowercase() }.getOrNull() ?: return
        val set = bySource.getOrPut(sourceId) { ConcurrentHashMap.newKeySet() }
        if (set.size < MAX_HOSTS_PER_SOURCE) set.add(host)
    }

    fun knows(sourceId: String, host: String): Boolean = bySource[sourceId]?.contains(host) == true

    /** After a restart: the covers already in the works index. */
    suspend fun loadFrom(db: Database) {
        db.tx { query("SELECT source_id, cover FROM index_items WHERE cover <> ''") { getString(1) to getString(2) } }
            .forEach { (source, cover) -> learn(source, cover) }
    }
}
