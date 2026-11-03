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

internal fun parseJson(text: String): JsonElement = JsonParser.parseString(text)

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
