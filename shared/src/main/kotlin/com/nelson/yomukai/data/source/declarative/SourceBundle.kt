package com.nelson.yomukai.data.source.declarative

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import java.io.File
import java.util.Base64

/**
 * All declarative source configurations in one signed file: `bundle.json` plus `bundle.json.sig` (Ed25519 over the
 * exact bytes). A copy ships inside the app and the server; newer signed bundles are fetched from the repository so a
 * source can be fixed without a release. Anything without a valid signature is ignored, whoever serves it.
 */
data class SourceBundle(val version: Long, val configs: List<SourceConfig>, val rejected: List<String>)

object SourceBundles {
    /** The only key whose signatures are accepted. Its private half lives outside the repository. */
    const val PUBLIC_KEY = "njG7Mx1cmkRzM0nYwgCcNVb9McSAUU7jy4d73DTsD3g="

    const val REMOTE_URL = "https://raw.githubusercontent.com/NUXX-G/Yomukai/main/shared/src/main/resources/sources/bundle.json"
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun verify(bundle: ByteArray, signatureBase64: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        Ed25519.verify(Base64.getDecoder().decode(publicKey), bundle, Base64.getDecoder().decode(signatureBase64.trim()))
    }.getOrDefault(false)

    /** The bundle when its signature is valid; each configuration is then checked on its own. */
    fun load(bundle: ByteArray, signatureBase64: String, publicKey: String = PUBLIC_KEY): SourceBundle? {
        if (bundle.size > MAX_BYTES || !verify(bundle, signatureBase64, publicKey)) return null
        return runCatching {
            val root = JsonParser.parseString(String(bundle, Charsets.UTF_8)).asJsonObject
            require(root.get("format")?.asInt == 1)
            val configs = mutableListOf<SourceConfig>()
            val rejected = mutableListOf<String>()
            root.getAsJsonArray("sources")?.forEach { e ->
                val config = runCatching { SourceConfigs.parse(e.asJsonObject) }.getOrNull()
                val problems = config?.let(SourceConfigs::problems) ?: listOf("JSON no válido")
                if (config != null && problems.isEmpty()) configs += config else rejected += "${config?.id ?: "?"}: ${problems.joinToString()}"
            }
            SourceBundle(root.get("version")?.asLong ?: 0, configs.distinctBy { it.id }, rejected)
        }.getOrNull()
    }

    /** The bundle that ships inside the app or the server. */
    fun bundled(): SourceBundle? = runCatching {
        val loader = SourceBundles::class.java.classLoader ?: return null
        val bytes = loader.getResourceAsStream("sources/bundle.json")?.use { it.readBytes() } ?: return null
        val sig = loader.getResourceAsStream("sources/bundle.json.sig")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        load(bytes, sig)
    }.getOrNull()

    /** Builds the unsigned bundle text from the individual files (the signing step lives in scripts/). */
    fun build(sources: List<JsonObject>, version: Long): String {
        val root = JsonObject()
        root.addProperty("format", 1)
        root.addProperty("version", version)
        root.add("sources", com.google.gson.JsonArray().also { a -> sources.sortedBy { it.get("id")?.asString }.forEach(a::add) })
        return com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root) + "\n"
    }
}

/**
 * Keeps the newest valid bundle: the one inside the app/server, one cached on disk, or one just downloaded. A
 * download is accepted only if signed and newer. Used by the server (and its apps through it) and by the Android app.
 */
class SourceBundleUpdater(
    private val network: HttpFetcher,
    private val cacheDir: File,
    private val remoteUrl: String = SourceBundles.REMOTE_URL,
    private val publicKey: String = SourceBundles.PUBLIC_KEY
) {
    private val cacheFile get() = File(cacheDir, "sources-bundle.json")
    private val cacheSig get() = File(cacheDir, "sources-bundle.json.sig")

    /** Bundled or cached, whichever is newer and valid. */
    fun current(): SourceBundle? {
        val cached = runCatching { if (cacheFile.isFile && cacheSig.isFile) SourceBundles.load(cacheFile.readBytes(), cacheSig.readText(), publicKey) else null }.getOrNull()
        val bundled = SourceBundles.bundled()
        return listOfNotNull(cached, bundled).maxByOrNull { it.version }
    }

    /** The newer bundle from [remoteUrl] if there is one, saved for next time; null when nothing changed. */
    suspend fun fetchNewer(currentVersion: Long): SourceBundle? {
        val text = network.getString(remoteUrl, emptyMap(), cloudflareFallback = false)
        val sig = network.getString("$remoteUrl.sig", emptyMap(), cloudflareFallback = false)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bundle = SourceBundles.load(bytes, sig, publicKey) ?: error("El paquete de fuentes descargado no tiene una firma válida")
        if (bundle.version <= currentVersion) return null
        cacheDir.mkdirs()
        File(cacheDir, "sources-bundle.json.partial").apply { writeBytes(bytes) }.renameTo(cacheFile)
        cacheSig.writeText(sig.trim())
        return bundle
    }

    /** The raw signed files, for a server to hand to its apps. */
    fun rawCurrent(): Pair<ByteArray, String>? {
        if (cacheFile.isFile && cacheSig.isFile) {
            val bytes = cacheFile.readBytes()
            val sig = cacheSig.readText()
            val bundled = SourceBundles.bundled()
            val cached = SourceBundles.load(bytes, sig, publicKey)
            if (cached != null && (bundled == null || cached.version >= bundled.version)) return bytes to sig
        }
        val loader = SourceBundles::class.java.classLoader ?: return null
        val bytes = loader.getResourceAsStream("sources/bundle.json")?.use { it.readBytes() } ?: return null
        val sig = loader.getResourceAsStream("sources/bundle.json.sig")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return bytes to sig
    }
}
