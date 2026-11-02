package com.nelson.yomukai.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import java.io.File

/**
 * Last titles seen per source, so Explore shows something at once while the network catches up and
 * keeps showing it when a site is down. The JSON is built by hand because model classes are not
 * kept by R8.
 */
class HomeCache(context: Context) {
    private val file = File(context.filesDir, "home_cache.json")

    fun load(): Map<String, List<MediaItem>> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JsonParser.parseString(file.readText()).asJsonObject
        root.entrySet().associate { (sourceId, items) ->
            sourceId to items.asJsonArray.map { element ->
                val item = element.asJsonObject
                MediaItem(
                    id = item.get("id").asString,
                    title = item.get("title").asString,
                    coverUrl = item.get("cover").asString,
                    type = MediaType.fromName(item.get("type").asString),
                    status = item.get("status").asString,
                    description = "",
                    sourceId = sourceId
                )
            }
        }
    }.getOrElse { e ->
        Log.w(TAG, "Caché de Explorar ilegible, se ignora", e)
        emptyMap()
    }

    fun save(sections: Map<String, List<MediaItem>>) {
        val root = JsonObject()
        sections.forEach { (sourceId, items) ->
            val array = JsonArray()
            items.take(MAX_PER_SOURCE).forEach { item ->
                array.add(
                    JsonObject().apply {
                        addProperty("id", item.id)
                        addProperty("title", item.title)
                        addProperty("cover", item.coverUrl)
                        addProperty("type", item.type.name)
                        addProperty("status", item.status)
                    }
                )
            }
            root.add(sourceId, array)
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            check(tmp.renameTo(file)) { "No se pudo reemplazar la caché" }
        }.onFailure { Log.w(TAG, "No se pudo guardar la caché de Explorar", it) }
    }

    private companion object {
        const val TAG = "YomukaiHome"
        const val MAX_PER_SOURCE = 40
    }
}
