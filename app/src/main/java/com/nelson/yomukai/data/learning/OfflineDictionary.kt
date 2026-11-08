package com.nelson.yomukai.data.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.nelson.yomukai.data.sync.ServerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The offline dictionaries on this device: the file the home worker built (kaikki.org, see [Dictionary]), downloaded
 * once per language from the connected server. Without the file, the server is asked instead.
 */
class OfflineDictionary(private val context: Context, private val sync: ServerSync) {
    private fun file(lang: String) = File(context.filesDir, "dict/$lang.sqlite")

    fun isDownloaded(lang: String) = file(lang).isFile

    /** Downloads [lang]'s file from the server; false when the server does not have it yet. */
    suspend fun download(lang: String): Boolean {
        require(lang in Dictionary.LANGUAGES) { "Idioma sin diccionario" }
        return sync.download("/api/dictionary/$lang/file", file(lang), MAX_BYTES)
    }

    fun delete(lang: String) = file(lang).delete()

    /** Meanings of [word]; status is "ready", "building" (the worker is making it) or "unavailable". */
    suspend fun lookup(lang: String, word: String): Pair<String, List<Dictionary.Entry>> = withContext(Dispatchers.IO) {
        val key = Dictionary.key(word)
        if (lang !in Dictionary.LANGUAGES || key.isEmpty()) return@withContext "unavailable" to emptyList()
        val local = file(lang)
        if (local.isFile) {
            val found = runCatching {
                SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    Dictionary.candidates(key).firstNotNullOfOrNull { k ->
                        db.rawQuery(Dictionary.LOOKUP, arrayOf(k)).use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    val es = c.getString(4).split(Dictionary.SEPARATOR).filter { it.isNotEmpty() }
                                    add(Dictionary.Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3).split(Dictionary.SEPARATOR), es))
                                }
                            }
                        }.takeIf { it.isNotEmpty() }
                    }.orEmpty()
                }
            }
            found.getOrNull()?.let { return@withContext "ready" to it }
        }
        val remote = runCatching {
            val enc = java.net.URLEncoder.encode(word.take(MAX_WORD), Charsets.UTF_8)
            sync.api("GET", "/api/dictionary/$lang/lookup?w=$enc")?.asJsonObject
        }.getOrNull() ?: return@withContext "unavailable" to emptyList()
        val entries = remote.getAsJsonArray("entries")?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            fun l(k: String) = o.getAsJsonArray(k)?.mapNotNull { it.takeIf { x -> x.isJsonPrimitive }?.asString }.orEmpty()
            Dictionary.Entry(s("word"), s("pos"), s("pron"), l("glosses"), l("es"))
        }.orEmpty()
        (remote.get("status")?.asString ?: "unavailable") to entries
    }

    private companion object {
        const val MAX_BYTES = 400L * 1024 * 1024
        const val MAX_WORD = 200
    }
}
