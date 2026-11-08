package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import com.nelson.yomukai.data.learning.Dictionary
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.DriverManager

class DictionaryHandlerTest {
    @Test
    fun aDumpBecomesASmallIndexedFile() = runBlocking {
        val sample = javaClass.getResource("/kaikki_sample.jsonl")!!.readBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> ex.sendResponseHeaders(200, sample.size.toLong()); ex.responseBody.use { it.write(sample) } }
            start()
        }
        try {
            val handler = DictionaryHandler { lang -> if (lang in Dictionary.LANGUAGES) "http://127.0.0.1:${server.address.port}/$lang.jsonl" else null }
            val dir = Files.createTempDirectory("ykdict").toFile()
            val out = handler.run(ClaimedJob(1, "dictionary", JsonObject().apply { addProperty("lang", "en") }, 1), JobContext(dir, okhttp3.OkHttpClient()))
            assertTrue(out.file.length() < sample.size)
            DriverManager.getConnection("jdbc:sqlite:${out.file.absolutePath}").use { c ->
                c.prepareStatement(Dictionary.LOOKUP).use { st ->
                    st.setString(1, Dictionary.key("Dictionary"))
                    st.executeQuery().use { r ->
                        val pos = mutableListOf<String>()
                        while (r.next()) pos += r.getString(2)
                        assertEquals(listOf("noun", "verb"), pos.sorted())
                    }
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = PermanentJobFailure::class)
    fun unknownLanguagesAreRefused(): Unit = runBlocking {
        DictionaryHandler().run(ClaimedJob(1, "dictionary", JsonObject().apply { addProperty("lang", "xx") }, 1), JobContext(Files.createTempDirectory("ykd").toFile(), okhttp3.OkHttpClient()))
    }
}
