package com.nelson.yomukai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.YomukaiApplication
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.ReadingStatus
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Syncs against a real Yomukai server on the computer running the emulator (10.0.2.2:8099, user admin,
 * password prueba123). Only with `-e live true`; plays "the web" with plain HTTP calls.
 */
@RunWith(AndroidJUnit4::class)
class SyncLiveTest {
    private val server = "http://10.0.2.2:8099"
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val stamp = System.currentTimeMillis().toString()

    private fun webSession(): String {
        val body = """{"name":"admin","password":"prueba123"}""".toRequestBody(json)
        http.newCall(Request.Builder().url("$server/auth/login").post(body).build()).execute().use { r ->
            return r.headers("Set-Cookie").first { it.startsWith("yk_session=") }.substringBefore(';').substringAfter('=')
        }
    }

    private fun web(session: String, method: String, path: String, body: String? = null): String {
        val builder = Request.Builder().url("$server$path").header("Cookie", "yk_session=$session")
        builder.method(method, body?.toRequestBody(json))
        http.newCall(builder.build()).execute().use { r -> return r.body!!.string() }
    }

    @Test
    fun phoneAndWebStayTheSame() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("live", args.getString("live") == "true")
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YomukaiApplication).container
        val sync = container.serverSync
        val repo = container.libraryRepository
        val db = container.database
        sync.disconnect()

        // Something on the phone before connecting.
        val phoneTitle = "phone-$stamp"
        db.mediaDao().upsert(MediaEntity("mangadex", phoneTitle, "Del móvil", "", "MANGA", "", "", "", ""))
        repo.setInLibrary("mangadex", phoneTitle, true)
        db.chapterDao().upsertAll(listOf(ChapterEntity("mangadex", "c-$stamp", phoneTitle, "Cap. 1", 1f, "u", "", 0)))
        repo.setChaptersRead("mangadex", listOf("c-$stamp"), true)
        val chapter = db.chapterDao().get("mangadex", "c-$stamp")!!
        repo.toggleBookmark(chapter, 4)
        val catId = repo.createCategory("Cat-$stamp")
        repo.setCategories("mangadex", phoneTitle, setOf(catId))

        assertNull("connects", sync.connect("$server/", "admin", "prueba123"))
        val session = webSession()

        // The web sees the phone's things.
        val library = JsonParser.parseString(web(session, "GET", "/api/library")).asJsonArray.map { it.asJsonObject }
        val mine = library.first { it["mediaId"].asString == phoneTitle }
        assertEquals("PLAN_TO_READ", mine["readingStatus"].asString)
        assertEquals("Cat-$stamp", mine["categories"].asJsonArray[0].asString)
        val progress = JsonParser.parseString(web(session, "GET", "/api/progress?s=mangadex&id=$phoneTitle")).asJsonArray
        assertTrue(progress.first()!!.asJsonObject["read"].asBoolean)
        val marks = JsonParser.parseString(web(session, "GET", "/api/bookmarks?s=mangadex&id=$phoneTitle")).asJsonArray
        assertEquals(4, marks.first()!!.asJsonObject["position"].asInt)

        // The web changes things; the phone gets them.
        val webTitle = "web-$stamp"
        web(session, "PUT", "/api/library", JsonObject().apply {
            addProperty("sourceId", "mangadex"); addProperty("mediaId", webTitle); addProperty("title", "De la web")
            addProperty("cover", ""); addProperty("type", "MANHWA"); addProperty("status", ""); addProperty("author", "")
            addProperty("genres", ""); addProperty("addedAt", 0); addProperty("readingStatus", "COMPLETED")
            add("categories", JsonArray())
        }.toString())
        web(session, "POST", "/api/progress", """{"rows":[{"sourceId":"mangadex","chapterId":"c-$stamp","mediaId":"$phoneTitle","read":false,"page":7,"total":20,"fraction":0,"updatedAt":${System.currentTimeMillis() + 1000}}]}""")
        web(session, "PUT", "/api/settings", """{"exclusions":{"global":["Gore-$stamp"],"perSource":{}}}""")
        assertTrue(sync.sync().isSuccess)
        val fromWeb = db.mediaDao().get("mangadex", webTitle)!!
        assertTrue(fromWeb.inLibrary)
        assertEquals(ReadingStatus.COMPLETED.name, fromWeb.readingStatus)
        val synced = db.chapterDao().get("mangadex", "c-$stamp")!!
        assertFalse(synced.read)
        assertEquals(7, synced.lastPageRead)
        assertTrue("Gore-$stamp" in container.preferences.exclusions.value.global)

        // Deleting on the phone deletes on the web.
        repo.setInLibrary("mangadex", phoneTitle, false)
        repo.deleteCategory(catId)
        assertTrue(sync.sync().isSuccess)
        val after = JsonParser.parseString(web(session, "GET", "/api/library")).asJsonArray.map { it.asJsonObject["mediaId"].asString }
        assertFalse(phoneTitle in after)
        val cats = JsonParser.parseString(web(session, "GET", "/api/categories")).asJsonArray.map { it.asJsonObject["name"].asString }
        assertFalse("Cat-$stamp" in cats)

        // Deleting on the web deletes on the phone.
        web(session, "DELETE", "/api/library?s=mangadex&id=$webTitle")
        assertTrue(sync.sync().isSuccess)
        assertFalse(db.mediaDao().get("mangadex", webTitle)!!.inLibrary)

        sync.disconnect()
    }
}
