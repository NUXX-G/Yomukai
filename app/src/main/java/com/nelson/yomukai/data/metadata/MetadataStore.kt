package com.nelson.yomukai.data.metadata

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps [TitleMetadata] on disk for a week, so reopening a title after restarting the app costs no
 * request. Written by hand with Gson's tree API: no reflection, so R8 cannot break it. The newest
 * [MAX_ENTRIES] titles are kept.
 */
class MetadataStore(context: Context) {
    private val file = File(context.filesDir, "metadata_cache_v2.json").also {
        // v1.7.0 saved entries without the MAL score: they are dropped so it gets asked for.
        File(context.filesDir, "metadata_cache.json").delete()
    }
    private val lock = Mutex()
    private var entries: LinkedHashMap<String, Pair<Long, TitleMetadata>>? = null

    private suspend fun loaded(): LinkedHashMap<String, Pair<Long, TitleMetadata>> {
        entries?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) decodeAll(file.readText()) else null }.getOrNull()
        } ?: LinkedHashMap()
        entries = read
        return read
    }

    suspend fun get(key: String, now: Long = System.currentTimeMillis()): TitleMetadata? = lock.withLock {
        loaded()[key]?.takeIf { now - it.first < MAX_AGE_MS }?.second
    }

    suspend fun put(key: String, metadata: TitleMetadata, now: Long = System.currentTimeMillis()) {
        val text = lock.withLock {
            val all = loaded()
            all.remove(key)
            all[key] = now to metadata
            while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
            encodeAll(all)
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 400
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        private fun strings(list: List<String>) = JsonArray().apply { list.forEach { add(it) } }

        fun encode(m: TitleMetadata): JsonObject = JsonObject().apply {
            m.score?.let { add("score", JsonPrimitive(it)) }
            add("scoreSource", JsonPrimitive(m.scoreSource))
            m.malScore?.let { add("malScore", JsonPrimitive(it)) }
            m.officialStatus?.let { add("status", JsonPrimitive(it.name)) }
            add("synopsis", JsonPrimitive(m.synopsis))
            add("genres", strings(m.genres))
            add("inferred", JsonPrimitive(m.inferredGenres))
            add("siteUrl", JsonPrimitive(m.siteUrl))
            add("relations", JsonArray().apply {
                m.relations.forEach { r ->
                    add(JsonObject().apply {
                        add("title", JsonPrimitive(r.title))
                        add("names", strings(r.names))
                        add("cover", JsonPrimitive(r.coverUrl))
                        add("type", JsonPrimitive(r.type.name))
                        add("genres", strings(r.genres))
                        r.relation?.let { add("relation", JsonPrimitive(it)) }
                        add("format", JsonPrimitive(r.format))
                        add("tags", strings(r.tags))
                    })
                }
            })
        }

        fun decode(o: JsonObject): TitleMetadata = TitleMetadata(
            score = o.str("score")?.toFloatOrNull(),
            scoreSource = o.str("scoreSource").orEmpty(),
            malScore = o.str("malScore")?.toFloatOrNull(),
            officialStatus = o.str("status")?.let { name -> PublicationStatus.entries.firstOrNull { it.name == name } },
            synopsis = o.str("synopsis").orEmpty(),
            genres = o.arr("genres").strings(),
            inferredGenres = o.str("inferred") == "true",
            siteUrl = o.str("siteUrl").orEmpty(),
            relations = o.arr("relations").objects().mapNotNull { r ->
                RelatedTitle(
                    title = r.str("title") ?: return@mapNotNull null,
                    names = r.arr("names").strings(),
                    coverUrl = r.str("cover").orEmpty(),
                    type = MediaType.fromName(r.str("type")),
                    genres = r.arr("genres").strings(),
                    relation = r.str("relation"),
                    format = r.str("format").orEmpty(),
                    tags = r.arr("tags").strings()
                )
            }
        )

        fun encodeAll(all: Map<String, Pair<Long, TitleMetadata>>): String = JsonObject().apply {
            all.forEach { (key, value) ->
                add(key, JsonObject().apply {
                    add("at", JsonPrimitive(value.first))
                    add("m", encode(value.second))
                })
            }
        }.toString()

        fun decodeAll(text: String): LinkedHashMap<String, Pair<Long, TitleMetadata>> {
            val result = LinkedHashMap<String, Pair<Long, TitleMetadata>>()
            parseJson(text).asJsonObject.entrySet().forEach { (key, value) ->
                val o = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val at = o.str("at")?.toLongOrNull() ?: return@forEach
                val m = o.obj("m") ?: return@forEach
                result[key] = at to decode(m)
            }
            return result
        }
    }
}
