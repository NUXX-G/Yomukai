package com.nelson.yomukai.data.source.declarative

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Base64

class DeclarativeSourceTest {
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource(name)) { "falta $name" }.readText()
    private object NoNetwork : HttpFetcher {
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean): String = throw IOException("sin red")
        override suspend fun postString(url: String, headers: Map<String, String>): String = throw IOException("sin red")
        override suspend fun postJson(url: String, body: String): String = throw IOException("sin red")
        override suspend fun checkImage(url: String, headers: Map<String, String>): String = throw IOException("sin red")
    }

    private val bundle = checkNotNull(SourceBundles.bundled()) { "el paquete de fuentes incluido no se pudo verificar" }
    private val marmota = declarativeSource(bundle.configs.first { it.id == "marmota" }, NoNetwork) as DeclarativeSource
    private fun doc(name: String, base: String = "https://marmota.me/") = Jsoup.parse(fixture(name), base)

    // ---------- The signed bundle ----------

    @Test
    fun theBundledConfigurationIsSignedAndMatchesTheFiles() {
        assertTrue(bundle.rejected.isEmpty())
        val files = File("../sources").listFiles { f -> f.name.endsWith(".json") }!!.map { JsonParser.parseString(it.readText()).asJsonObject.get("id").asString }.toSet()
        assertEquals("regenerate with: java scripts/SignSources.java", files, bundle.configs.map { it.id }.toSet())
    }

    @Test
    fun anyChangeBreaksTheSignature() {
        val loader = javaClass.classLoader!!
        val bytes = loader.getResourceAsStream("sources/bundle.json")!!.readBytes()
        val sig = loader.getResourceAsStream("sources/bundle.json.sig")!!.readBytes().toString(Charsets.UTF_8)
        assertNotNull(SourceBundles.load(bytes, sig))
        val tampered = String(bytes).replace("https://marmota.me", "https://evil.example").toByteArray()
        assertNull(SourceBundles.load(tampered, sig))
        assertNull(SourceBundles.load(bytes, Base64.getEncoder().encodeToString(ByteArray(64))))
        assertNull("another key", SourceBundles.load(bytes, sig, Base64.getEncoder().encodeToString(ByteArray(32) { 9 })))
    }

    @Test
    fun badConfigsAreRejectedOneByOne() {
        val bad = SourceConfigs.parse(JsonParser.parseString("""{"id":"x y","name":"","engine":"php","baseUrl":"http://a.com/ruta"}""").asJsonObject)
        val problems = SourceConfigs.problems(bad)
        assertTrue(problems.toString(), problems.size >= 4)
        val cookie = SourceConfigs.parse(JsonParser.parseString("""{"id":"ok","name":"Ok","engine":"madara","baseUrl":"https://a.com","headers":{"Cookie":"x"}}""").asJsonObject)
        assertTrue(SourceConfigs.problems(cookie).any { it.contains("cabeceras") })
    }

    @Test
    fun theSourceManagerUsesTheJsonVersion() {
        val sm = SourceManager(NoNetwork)
        val s = sm.require("marmota")
        assertTrue(s is DeclarativeMangaSource)
        assertEquals(bundle.version, sm.bundleVersion)
        assertTrue("marmota" in sm.defaultEnabledIds)
    }

    // ---------- Madara (Marmota): the same checks the Kotlin version had ----------

    @Test
    fun madaraHomeCards() {
        val items = marmota.parseList(marmota.config.latest!!, fixture("marmota_home.html"), doc("marmota_home.html"))
        assertTrue(items.size >= 40)
        assertTrue(items.all { it.type == MediaType.COMIC && it.title.isNotBlank() && it.id.contains("/comic/") })
        assertTrue(items.all { it.coverUrl.startsWith("https://") && "dflazy" !in it.coverUrl })
    }

    @Test
    fun madaraSearchWithAlternativeTitles() {
        val items = marmota.parseList(marmota.config.search!!, fixture("marmota_search.html"), doc("marmota_search.html"))
        val impostor = items.first { it.id == "https://marmota.me/comic/batman-the-imposter-tpb-2021/" }
        assertTrue(impostor.altTitles.any { it.startsWith("Batman: El Impostor") })
    }

    @Test
    fun madaraDetailsAndChapters() {
        val base = MediaItem("https://marmota.me/comic/batman-v4-2025/", "Batman", "", MediaType.COMIC, "", "", "marmota")
        val d = marmota.parseDetails(fixture("marmota_detail.html"), doc("marmota_detail.html", base.id), base)
        assertEquals("OnGoing", d.status)
        assertTrue(d.description.isNotBlank())
        val chapters = marmota.parseChapters(fixture("marmota_chapters_ajax.html"), doc("marmota_chapters_ajax.html", base.id), base.id)
        assertEquals(13, chapters.size)
        assertEquals("https://marmota.me/comic/batman-v4-2025/batman-vol-4-13/", chapters.first().url)
        assertEquals(13f, chapters.first().chapterNumber)
    }

    @Test
    fun madaraPagesAreCleanHttps() {
        val pages = marmota.parsePages(fixture("marmota_chapter.html"), doc("marmota_chapter.html"))
        assertEquals(33, pages.size)
        assertTrue(pages.all { it.startsWith("https://marmota.me/wp-content/uploads/") && ' ' !in it })
    }

    @Test
    fun madaraCatalogueUrl() {
        assertEquals(
            "https://marmota.me/?s=&post_type=wp-manga&m_orderby=views&paged=2&status%5B%5D=end&genre%5B%5D=dc-comics",
            marmota.catalogueUrl(marmota.config.browse!!, 2, mapOf(FilterKeys.SORT to setOf("views"), FilterKeys.STATUS to setOf("end"), FilterKeys.GENRE to setOf("dc-comics")))
        )
    }

    // ---------- The fallback chain ----------

    @Test
    fun whenTheSelectorBreaksTheHeuristicFindsThePages() {
        // The site renamed its reader container: the configured selector matches nothing.
        val broken = declarativeSource(bundle.configs.first { it.id == "marmota" }.copy(pages = PagesRule(image = ".lector-nuevo img")), NoNetwork) as DeclarativeSource
        val pages = broken.parsePages(fixture("marmota_chapter.html"), doc("marmota_chapter.html"))
        assertTrue("found ${pages.size}", pages.size >= 30)
        assertTrue(pages.all { it.startsWith("https://") })
    }

    @Test
    fun imagesEmbeddedInScriptsAreFound() {
        val html = """<html><body><div id=app></div><script>window.__DATA__={"pages":["https:\/\/cdn.x.com\/ch\/1.webp","https:\/\/cdn.x.com\/ch\/2.webp","https:\/\/cdn.x.com\/ch\/3.webp"]""" +
            ""","avatar":"https:\/\/cdn.x.com\/u\/me.png"}</script></body></html>"""
        val src = declarativeSource(SourceConfig(id = "x", name = "X", engine = "html", baseUrl = "https://x.com", latest = ListRule("{base}/")), NoNetwork) as DeclarativeSource
        assertEquals(listOf("https://cdn.x.com/ch/1.webp", "https://cdn.x.com/ch/2.webp", "https://cdn.x.com/ch/3.webp"), src.parsePages(html, Jsoup.parse(html)))
    }

    @Test
    fun detailsFallBackToLdJsonAndOgTags() {
        val html = """<html><head><meta property="og:image" content="https://x.com/c.jpg"><meta property="og:description" content="Desde og">
            <script type="application/ld+json">{"@type":"Book","name":"Desde ld+json","description":"Sinopsis ld"}</script></head><body></body></html>"""
        val src = declarativeSource(SourceConfig(id = "x", name = "X", engine = "html", baseUrl = "https://x.com", latest = ListRule("{base}/"),
            details = DetailsRule(title = ".no-existe")), NoNetwork) as DeclarativeSource
        val d = src.parseDetails(html, Jsoup.parse(html), MediaItem("https://x.com/a", "Viejo", "", MediaType.MANGA, "", "", "x"))
        assertEquals("Desde ld+json", d.title)
        assertEquals("Sinopsis ld", d.description)
        assertEquals("https://x.com/c.jpg", d.coverUrl)
    }

    // ---------- Next.js (Flame) ----------

    @Test
    fun nextJsDataDrivesDetailsAndChapters() {
        val flame = declarativeSource(
            SourceConfig(
                id = "flame_json", name = "Flame", engine = "nextjs", baseUrl = "https://flamecomics.xyz", types = listOf("MANHWA"),
                latest = ListRule("{base}/"),
                details = DetailsRule(json = "props.pageProps.series", map = mapOf("title" to "title", "status" to "status")),
                chapters = ChaptersRule(
                    json = "props.pageProps.chapters[*]",
                    map = mapOf("url" to "{base}/series/{series_id}/{token}", "number" to "chapter", "title" to "title")
                )
            ), NoNetwork
        ) as DeclarativeSource
        val html = fixture("flame_series.html")
        val page = Jsoup.parse(html, "https://flamecomics.xyz/series/2")
        val d = flame.parseDetails(html, page, MediaItem("https://flamecomics.xyz/series/2", "?", "", MediaType.MANHWA, "", "", "flame_json"))
        assertEquals("Youngest Son of the Renowned Swordsmanship Clan", d.title)
        val chapters = flame.parseChapters(html, page, d.id)
        assertTrue(chapters.size > 100)
        assertTrue(chapters.all { it.url.startsWith("https://flamecomics.xyz/series/") && it.chapterNumber > 0 })
    }

    // ---------- SvelteKit (AnimeAV1) ----------

    @Test
    fun svelteKitDataIsDecoded() {
        val data = Extract.svelteKitData(fixture("av1_catalog_data.json"))!!
        val results = Extract.path(data, "results[*]")
        assertEquals(20, results.size)
        assertEquals("Battle Spirits [Re]: Zekkai no Kuu", Extract.string(results.first(), "title"))
        val src = declarativeSource(
            SourceConfig(
                id = "av1_json", name = "AV1", engine = "sveltekit", baseUrl = "https://animeav1.com",
                latest = ListRule(
                    url = "{base}/catalogo/__data.json", json = "results[*]",
                    map = mapOf("title" to "title", "url" to "{base}/media/{slug}", "cover" to "https://cdn.animeav1.com/covers/{id}.jpg")
                )
            ), NoNetwork
        ) as DeclarativeSource
        val items = src.parseList(src.config.latest!!, fixture("av1_catalog_data.json"), Jsoup.parse(""))
        assertEquals(20, items.size)
        assertTrue(items.first().id.startsWith("https://animeav1.com/media/"))
        assertEquals("https://cdn.animeav1.com/covers/4479.jpg", items.first().coverUrl)
    }

    @Test
    fun devalueHandlesSpecialsAndSharedValues() {
        val flat = JsonParser.parseString("""[{"a":1,"b":1,"c":2,"d":-1},"x",[3,4],5,"y"]""").asJsonArray
        val out = Extract.devalue(flat).asJsonObject
        assertEquals("x", out.get("a").asString)
        assertEquals("x", out.get("b").asString)
        assertEquals(5, out.getAsJsonArray("c")[0].asInt)
        assertTrue(out.get("d").isJsonNull)
    }

    // ---------- Domains ----------

    @Test
    fun aMovedSiteIsFollowedAndOldLinksStillWork() = runBlocking {
        val moves = mutableListOf<String>()
        val net = object : HttpFetcher by NoNetwork {
            override suspend fun getStringWithUrl(url: String, headers: Map<String, String>): Pair<String, String> =
                fixture("marmota_home.html") to url.replace("https://marmota.me", "https://marmota.net")
        }
        val config = bundle.configs.first { it.id == "marmota" }.copy(domains = listOf("https://marmota.me"))
        val src = declarativeSource(config, net) { id, from, to -> moves += "$id $from $to" } as DeclarativeSource
        assertTrue(src.fetchLatestUpdates(1).getOrThrow().isNotEmpty())
        assertEquals(listOf("marmota https://marmota.me https://marmota.net"), moves)
        assertEquals("https://marmota.net", src.baseUrl)
        assertEquals("https://marmota.net/comic/x/", src.rebase("https://marmota.me/comic/x/"))
    }

    @Test
    fun aDeadDomainFallsBackToTheNextOne() = runBlocking {
        val asked = mutableListOf<String>()
        val net = object : HttpFetcher by NoNetwork {
            override suspend fun getStringWithUrl(url: String, headers: Map<String, String>): Pair<String, String> {
                asked += url
                if (url.startsWith("https://muerto.com")) throw IOException("no responde")
                return fixture("marmota_home.html") to url
            }
        }
        val config = bundle.configs.first { it.id == "marmota" }.copy(baseUrl = "https://muerto.com", domains = listOf("https://muerto.com", "https://marmota.me"))
        val src = declarativeSource(config, net) as DeclarativeSource
        assertTrue(src.fetchLatestUpdates(1).getOrThrow().isNotEmpty())
        assertEquals(listOf("https://muerto.com/", "https://marmota.me/"), asked)
        assertEquals("https://marmota.me", src.baseUrl)
        assertFalse(src.baseUrl.contains("muerto"))
    }
}
