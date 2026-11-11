package com.nelson.yomukai.worker

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nelson.yomukai.server.Config
import com.nelson.yomukai.server.CoverJobs
import com.nelson.yomukai.server.Job
import com.nelson.yomukai.server.JobStatus
import com.nelson.yomukai.server.JobTypes
import com.nelson.yomukai.server.Services
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.random.Random

/** F2.2, the whole round trip: the worker makes the covers at home, the VPS keeps only what it asked for. */
class CoversHandlerTest {
    /** A big noisy image, so shrinking it is worth it (small ones stay as they are). */
    private fun bigPng(): ByteArray {
        val img = BufferedImage(1400, 2000, BufferedImage.TYPE_INT_RGB)
        val r = Random(7)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, r.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun coversMadeAtHomeEndUpInTheVpsCacheAndJunkDoesNot() = runBlocking {
        val png = bigPng()
        val images = mapOf(
            "https://s4.anilist.co/cover/1.png" to (png to "image/png"),
            // A site that answers a page instead of an image: never stored.
            "https://evil.example/cover.jpg" to ("<html><script>alert(1)</script></html>".toByteArray() to "image/jpeg")
        )
        val payload = JsonObject().apply {
            add("images", JsonArray().also { arr ->
                images.keys.forEach { u -> arr.add(JsonObject().apply { addProperty("u", u); add("w", JsonArray().also { it.add(240); it.add(720) }) }) }
            })
        }
        val dir = Files.createTempDirectory("ykcov").toFile()
        val out = CoversHandler { url, _, _ -> images[url] }.run(ClaimedJob(1, "covers", payload, 1), JobContext(dir, okhttp3.OkHttpClient()))

        val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykcovs").toString(), "BACKUPS_ENABLED" to "false")))
        s.jobs.results.put("covers-result", out.file.inputStream(), "application/zip", 64L * 1024 * 1024)
        val job = Job(1, JobTypes.COVERS, 10, payload, null, JobStatus.DONE, 1, 3, 1, null, false, 0, 0, Long.MAX_VALUE, null, "covers-result")
        CoverJobs(s).finished(job)

        assertTrue(s.proxy.hasImage("https://s4.anilist.co/cover/1.png"))
        assertTrue(s.proxy.hasImage("https://s4.anilist.co/cover/1.png", 240))
        assertTrue(s.proxy.hasImage("https://s4.anilist.co/cover/1.png", 720))
        assertFalse("a page posing as an image is not kept", s.proxy.hasImage("https://evil.example/cover.jpg"))
        assertFalse("the result is removed once used", s.jobs.results.has("covers-result"))
    }
}
