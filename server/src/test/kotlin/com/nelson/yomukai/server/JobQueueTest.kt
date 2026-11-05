package com.nelson.yomukai.server

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobQueueTest {
    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("ykjobs").toFile()
    private val db = Database(File(dir, "t.db"))
    private val results = ResultCache(db, File(dir, "results"), maxBytes = 100)
    private val queue = JobQueue(db, results) { now }
    private val registry = WorkerRegistry(db) { now }

    private fun payload(text: String = "x") = JsonObject().apply { addProperty("text", text) }

    private suspend fun worker(name: String, engines: List<String>, gpu: String? = null): WorkerInfo {
        val (info, _) = registry.create(name)
        registry.hello(info.id, JsonObject().apply {
            add("engines", com.google.gson.JsonArray().also { a -> engines.forEach(a::add) })
            gpu?.let { addProperty("gpu", it) }
        }, "1.0")
        return registry.get(info.id)!!
    }

    @Test
    fun sameWorkJoinsTheLiveJobAndRaisesItsPriority() = runBlocking {
        val a = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload(), JobPriority.BACKGROUND, dedupKey = "tts:cap1"))
        val b = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload(), JobPriority.INTERACTIVE, dedupKey = "tts:cap1"))
        assertEquals(a.id, b.id)
        assertEquals(JobPriority.INTERACTIVE, b.priority)
    }

    @Test
    fun urgentJobsFirstAndOnlyWhatTheWorkerCanDo() = runBlocking {
        queue.enqueue(JobQueue.Request(JobTypes.OCR, payload("ocr"), JobPriority.INTERACTIVE))
        val background = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload("bg"), JobPriority.BACKGROUND))
        now += 1
        val urgent = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload("now"), JobPriority.INTERACTIVE))
        val w = worker("casa", listOf(JobTypes.TTS))
        assertEquals(urgent.id, queue.claim(w, gpuWorkerOnline = false)!!.id)
        assertEquals(background.id, queue.claim(w, gpuWorkerOnline = false)!!.id)
        assertNull("cannot do OCR", queue.claim(w, gpuWorkerOnline = false))
    }

    @Test
    fun gpuJobsWaitForTheGpuWorkerForAWhile() = runBlocking {
        val job = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload()))
        val cpu = worker("casa", listOf(JobTypes.TTS))
        val gpu = worker("portatil", listOf(JobTypes.TTS), gpu = "RTX 5060")
        assertNull("left for the GPU worker", queue.claim(cpu, gpuWorkerOnline = true))
        now += JobQueue.GPU_GRACE_MS
        assertEquals("the GPU worker took too long: the CPU one does it", job.id, queue.claim(cpu, gpuWorkerOnline = true)!!.id)
        val other = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload("2")))
        assertEquals(other.id, queue.claim(gpu, gpuWorkerOnline = true)!!.id)
    }

    @Test
    fun lostWorkersGiveTheJobBackAndFailuresRetryWithAPause() = runBlocking {
        val job = queue.enqueue(JobQueue.Request(JobTypes.OCR, payload(), maxAttempts = 2))
        val w = worker("casa", listOf(JobTypes.OCR))
        queue.claim(w, false)
        now += JobQueue.LEASE_MS + 1
        queue.sweep()
        assertEquals(JobStatus.QUEUED, queue.get(job.id)!!.status)

        queue.claim(w, false)
        val failed = queue.fail(job.id, w.id, "sin memoria")
        assertEquals("second attempt was the last", JobStatus.FAILED, failed.status)

        val retry = queue.enqueue(JobQueue.Request(JobTypes.OCR, payload("b")))
        queue.claim(w, false)
        assertEquals(JobStatus.QUEUED, queue.fail(retry.id, w.id, "red").status)
        assertNull("waits its back-off", queue.claim(w, false))
        now += JobQueue.backoff(1)
        assertEquals(retry.id, queue.claim(w, false)!!.id)
    }

    @Test
    fun expiredJobsAreDropped() = runBlocking {
        val job = queue.enqueue(JobQueue.Request(JobTypes.OCR, payload(), ttlMs = 1_000))
        now += 1_000
        queue.sweep()
        assertEquals(JobStatus.EXPIRED, queue.get(job.id)!!.status)
    }

    @Test
    fun resultsAreSharedAndTheCacheForgetsTheOldest() = runBlocking {
        val w = worker("casa", listOf(JobTypes.TTS))
        val first = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload(), dedupKey = "a"))
        queue.claim(w, false)
        queue.complete(first.id, w.id, ByteArray(60).inputStream(), "audio/ogg")
        assertTrue(results.has("a"))
        now += 10
        val second = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload(), dedupKey = "b"))
        queue.claim(w, false)
        queue.complete(second.id, w.id, ByteArray(60).inputStream(), "audio/ogg")
        assertFalse("over 100 bytes: the least recently used went", results.has("a"))
        val (file, type) = results.get("b")!!
        assertEquals(60L, file.length())
        assertEquals("audio/ogg", type)
    }

    @Test
    fun aWorkerCannotTouchAnotherWorkersJob() = runBlocking {
        val job = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload()))
        val a = worker("casa", listOf(JobTypes.TTS))
        val b = worker("portatil", listOf(JobTypes.TTS))
        queue.claim(a, false)
        assertFalse(queue.heartbeat(job.id, b.id))
        runCatching { queue.complete(job.id, b.id, ByteArray(1).inputStream(), "audio/ogg") }.also { assertTrue(it.isFailure) }
        runCatching { queue.fail(job.id, b.id, "x") }.also { assertTrue(it.isFailure) }
        assertTrue(queue.heartbeat(job.id, a.id))
    }

    @Test
    fun oversizedResultsAreRefused() = runBlocking {
        val w = worker("casa", listOf(JobTypes.TTS))
        val job = queue.enqueue(JobQueue.Request(JobTypes.TTS, payload()))
        queue.claim(w, false)
        val r = runCatching { queue.complete(job.id, w.id, ByteArray(50).inputStream(), "audio/ogg", limit = 10) }
        assertTrue(r.isFailure)
        assertEquals(JobStatus.RUNNING, queue.get(job.id)!!.status)
        assertNotNull(dir.resolve("results").listFiles())
        assertTrue("no partial file left", dir.resolve("results").listFiles()!!.none { it.name.endsWith(".partial") })
    }

    @Test
    fun tokensAreCheckedRevokedAndSanitised() = runBlocking {
        val (info, token) = registry.create("casa")
        assertEquals(info.id, registry.authenticate(token)!!.id)
        assertNull(registry.authenticate("ykw_falso"))
        assertNull(registry.authenticate(null))
        registry.revoke(info.id)
        assertNull(registry.authenticate(token))
        val clean = WorkerRegistry.sanitize(com.google.gson.JsonParser.parseString(
            """{"engines":["tts","rm -rf","ocr"],"cpus":99999,"gpu":"RTX","evil":{"a":1}}"""
        ).asJsonObject)
        assertEquals(listOf("tts", "ocr"), clean.getAsJsonArray("engines").map { it.asString })
        assertEquals(1024, clean.get("cpus").asInt)
        assertFalse(clean.has("evil"))
    }

    @Test
    fun unknownJobTypesAreRefused() = runBlocking {
        assertTrue(runCatching { queue.enqueue(JobQueue.Request("borrar_todo", payload())) }.isFailure)
    }
}
