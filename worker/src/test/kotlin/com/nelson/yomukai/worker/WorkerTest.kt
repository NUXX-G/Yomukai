package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import com.nelson.yomukai.server.Config
import com.nelson.yomukai.server.JobQueue
import com.nelson.yomukai.server.JobStatus
import com.nelson.yomukai.server.JobTypes
import com.nelson.yomukai.server.Services
import com.nelson.yomukai.server.yomukai
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkerTest {
    @Test
    fun configNeedsUrlAndTokenAndRefusesPlainHttpOnTheInternet() {
        val ok = WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "https://yomukai.example.com/", "WORKER_TOKEN" to "ykw_abc"))
        assertEquals("https://yomukai.example.com", ok.serverUrl)
        assertTrue(ok.concurrency >= 1)
        assertTrue(runCatching { WorkerConfig.fromEnv(mapOf("WORKER_TOKEN" to "ykw_abc")) }.isFailure)
        assertTrue(runCatching { WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "https://x.com")) }.isFailure)
        assertTrue(runCatching { WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "https://x.com", "WORKER_TOKEN" to "otro")) }.isFailure)
        assertTrue(runCatching { WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "http://x.com", "WORKER_TOKEN" to "ykw_a")) }.isFailure)
        WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "http://100.101.2.3:8080", "WORKER_TOKEN" to "ykw_a"))
    }

    @Test
    fun privateHosts() {
        listOf("http://100.64.0.1", "http://vps.tail1234.ts.net:8080", "http://192.168.1.10", "http://10.0.0.2", "http://localhost:8080", "http://172.20.0.1")
            .forEach { assertTrue(it, WorkerConfig.isPrivateHost(it)) }
        assertTrue("docker service name", WorkerConfig.isPrivateHost("http://yomukai:8080"))
        listOf("http://8.8.8.8", "http://example.com", "http://100.128.0.1", "http://172.32.0.1").forEach { assertFalse(it, WorkerConfig.isPrivateHost(it)) }
    }

    @Test
    fun machineDescription() {
        assertEquals(15_923L, Capabilities.parseMemInfo("MemTotal:       16305764 kB\nMemFree: 1 kB"))
        assertEquals("NVIDIA GeForce RTX 5060", Capabilities.parseNvidiaSmi("NVIDIA GeForce RTX 5060, 8151 MiB\n"))
        assertNull(Capabilities.parseNvidiaSmi("NVIDIA-SMI has failed because it couldn't communicate with the NVIDIA driver"))
        assertEquals(1, Capabilities.defaultConcurrency(cpus = 2, ramMb = 4_000))
        assertEquals(4, Capabilities.defaultConcurrency(cpus = 16, ramMb = 32_000))
    }

    @Test
    fun busyWhenLoadedOrPausedByHand() {
        assertFalse(LoadMonitor.isBusy(1.0, 4, 0.85, paused = false))
        assertTrue(LoadMonitor.isBusy(3.6, 4, 0.85, paused = false))
        assertTrue(LoadMonitor.isBusy(0.0, 4, 0.85, paused = true))
        assertFalse("unknown load: not busy", LoadMonitor.isBusy(null, 4, 0.85, paused = false))
    }

    @Test
    fun idleEnginesAreUnloaded() {
        var now = 0L
        var created = 0
        var closed = 0
        val res = IdleResource("modelo", idleMs = 1_000, clock = { now }) { created++; AutoCloseable { closed++ } }
        res.use { }
        res.use { }
        assertEquals(1, created)
        now = 999
        res.unloadIfIdle()
        assertTrue(res.isLoaded())
        now = 2_000
        res.unloadIfIdle()
        assertFalse(res.isLoaded())
        assertEquals(1, closed)
        res.use { }
        assertEquals(2, created)
    }

    @Test
    fun backupsKeepTheNewest() {
        val dir = Files.createTempDirectory("ykwb").toFile()
        (1..20).forEach { File(dir, "yomukai-202611%02d-040000.db.gz".format(it)).writeText("x") }
        File(dir, "otra.txt").writeText("x")
        Worker.pruneBackups(dir, keep = 14)
        assertEquals(15, dir.listFiles()!!.size)
        assertTrue(File(dir, "yomukai-20261120-040000.db.gz").exists())
        assertFalse(File(dir, "yomukai-20261106-040000.db.gz").exists())
    }

    @Test
    fun aJobGoesFromTheVpsToTheWorkerAndBack() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val data = Files.createTempDirectory("ykwe2e").toFile()
        val config = Config.fromEnv(mapOf("DATA_DIR" to data.path, "ADMIN_PASSWORD" to "admin-password-1", "BACKUPS_ENABLED" to "false", "PORT" to "0"))
        val services = Services(config)
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { yomukai(config, services) }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val (_, token) = services.workers.create("casa")
            val job = services.jobs.enqueue(JobQueue.Request(JobTypes.ECHO, JsonObject().apply { addProperty("text", "hola") }))

            val workerConfig = WorkerConfig.fromEnv(mapOf("YOMUKAI_URL" to "http://127.0.0.1:$port", "WORKER_TOKEN" to token, "WORKER_WORK_DIR" to File(data, "work").path))
            val vps = VpsClient(workerConfig.serverUrl, workerConfig.token)
            val worker = Worker(workerConfig, listOf(EchoHandler()), vps, LoadMonitor(0.85, null))
            vps.hello(Capabilities.describe(listOf(EchoHandler()), busy = false, concurrency = 1))
            val claimed = vps.claim()!!
            assertEquals(job.id, claimed.id)
            worker.process(claimed)

            val done = services.jobs.get(job.id)!!
            assertEquals(JobStatus.DONE, done.status)
            val (file, type) = services.jobs.results.get(done.resultKey!!)!!
            assertEquals("application/json", type)
            assertEquals("""{"text":"hola"}""", file.readText())
            assertTrue("temporary output removed", File(data, "work").listFiles().orEmpty().isEmpty())
            assertNull("nothing else to do", vps.claim())

            // A worker without permission gets no database copy.
            assertTrue(runCatching { vps.pullBackup(File(data, "nas")) }.isFailure)
        } finally {
            server.stop(100, 1_000)
        }
    }
}
