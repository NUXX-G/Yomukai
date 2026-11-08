package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.nelson.yomukai.data.learning.Dictionary
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager

/**
 * Offline dictionaries: the home worker builds each language's file once ([JobTypes.DICTIONARY]); here it is only
 * kept, looked up (an indexed read) and handed to the app, which then looks words up without a connection.
 */
class Dictionaries(private val s: Services) {
    private fun key(lang: String) = "dict|$lang"

    /** When each language was last asked for: a failing build is not retried on every lookup. */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun file(lang: String): File? = s.jobs.results.get(key(lang))?.first

    /** State of [lang]'s dictionary; asks the worker to build it the first time someone needs it. */
    suspend fun status(user: User, lang: String): String {
        require(lang in Dictionary.LANGUAGES) { "Idioma sin diccionario" }
        if (file(lang) != null) return "ready"
        val worker = s.features.isOn("worker_jobs") && JobTypes.DICTIONARY in s.workers.availability().engines
        if (!worker) return "unavailable"
        val now = System.currentTimeMillis()
        if (!user.isGuest && now - (asked[lang] ?: 0L) > RETRY_MS) {
            asked[lang] = now
            s.jobs.enqueue(JobQueue.Request(JobTypes.DICTIONARY, JsonObject().apply { addProperty("lang", lang) }, JobPriority.BACKGROUND, dedupKey = key(lang), requestedBy = user.id))
        }
        return "building"
    }

    suspend fun lookup(lang: String, word: String): List<Dictionary.Entry>? {
        val file = file(lang) ?: return null
        val key = Dictionary.key(word).take(MAX_WORD).ifEmpty { return emptyList() }
        return withContext(Dispatchers.IO) {
            DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro", java.util.Properties().apply { setProperty("open_mode", "1") }).use { c ->
                c.prepareStatement(Dictionary.LOOKUP).use { st ->
                    fun find(k: String) = run {
                        st.setString(1, k)
                        st.executeQuery().use { r ->
                            buildList {
                                while (r.next()) {
                                    val es = r.getString(5).split(Dictionary.SEPARATOR).filter { it.isNotEmpty() }
                                    add(Dictionary.Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4).split(Dictionary.SEPARATOR), es))
                                }
                            }
                        }
                    }
                    // Chinese and Japanese have no spaces: the longest word the pressed text starts with.
                    Dictionary.candidates(key).firstNotNullOfOrNull { k -> find(k).takeIf { it.isNotEmpty() } }.orEmpty()
                }
            }
        }
    }

    private companion object {
        const val MAX_WORD = 60
        const val RETRY_MS = 3_600_000L
    }
}

fun Route.dictionaryRoutes(s: Services) {
    suspend fun io.ktor.server.application.ApplicationCall.lang(): String? {
        if (!s.features.isOn("learning")) { respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador")); return null }
        return parameters["lang"]?.takeIf { it in Dictionary.LANGUAGES } ?: throw IllegalArgumentException("Idioma sin diccionario")
    }

    get("/dictionary") { call.respond(Dictionary.LANGUAGES.keys) }
    get("/dictionary/{lang}") {
        val lang = call.lang() ?: return@get
        val file = s.dictionaries.file(lang)
        call.respond(mapOf("status" to s.dictionaries.status(call.user, lang), "size" to (file?.length() ?: 0L)))
    }
    get("/dictionary/{lang}/lookup") {
        val lang = call.lang() ?: return@get
        val word = call.request.queryParameters["w"]?.take(200).orEmpty()
        val entries = s.dictionaries.lookup(lang, word) ?: return@get call.respond(mapOf("status" to s.dictionaries.status(call.user, lang), "entries" to emptyList<Any>()))
        call.respond(mapOf("status" to "ready", "entries" to entries))
    }
    // The whole file, for the app to keep (download once per language, then offline).
    get("/dictionary/{lang}/file") {
        val lang = call.lang() ?: return@get
        val file = s.dictionaries.file(lang) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Aún no está listo"))
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "dict-$lang.sqlite").toString())
        call.respondFile(file)
    }
}
