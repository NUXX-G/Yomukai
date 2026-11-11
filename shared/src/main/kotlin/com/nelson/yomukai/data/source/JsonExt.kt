package com.nelson.yomukai.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Null-safe helpers so JSON parsing of loosely-typed APIs never throws on a missing field. */
internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonArray?.objects(): List<JsonObject> =
    this?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

internal fun JsonArray?.strings(): List<String> =
    this?.filter { it.isJsonPrimitive }?.map { it.asString.trim() }.orEmpty()

/**
 * Parses an API answer. A web page instead of data (anti-bot check, error page, moved site) becomes a clear message
 * instead of Gson's «MalformedJsonException … setStrictness(LENIENT)», which used to reach the user as it was.
 */
internal fun parseJson(text: String): JsonElement {
    val clean = text.removePrefix("﻿").removePrefix(")]}'").trimStart()
    if (clean.startsWith("<")) throw NotJsonException(clean)
    return try {
        JsonParser.parseString(clean)
    } catch (e: com.google.gson.JsonParseException) {
        throw NotJsonException(clean, e)
    }
}

/** The site answered with something that is not the data it promises (usually a web page). */
class NotJsonException(body: String, cause: Throwable? = null) : java.io.IOException(describe(body), cause) {
    private companion object {
        fun describe(body: String): String = when {
            com.nelson.yomukai.data.network.WebProtection.detectProtection(200, body) != null ->
                "La web pide una comprobación anti-bot y no ha dejado pasar; se reintentará por otra vía"
            body.startsWith("<") -> "La web respondió con una página en vez de datos (puede estar caída o haber cambiado)"
            body.isBlank() -> "La web respondió vacía"
            else -> "La web respondió con datos ilegibles"
        }
    }
}

private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** Extracts props.pageProps from a Next.js server-rendered page. */
internal fun nextPageProps(html: String): JsonObject {
    val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: error("La página no contiene __NEXT_DATA__")
    return parseJson(json).asJsonObject.obj("props")?.obj("pageProps") ?: error("pageProps ausente")
}

internal fun formatEpochSeconds(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return ""
    return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(seconds * 1000))
}

internal fun formatIsoDate(iso: String?): String = iso?.take(10)?.split("-")
    ?.takeIf { it.size == 3 }?.reversed()?.joinToString("/").orEmpty()
