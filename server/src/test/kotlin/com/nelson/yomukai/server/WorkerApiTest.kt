package com.nelson.yomukai.server

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The worker API end to end: tokens, who may see which job, and a job going round trip. */
class WorkerApiTest {
    private fun config() = Config.fromEnv(
        mapOf("DATA_DIR" to Files.createTempDirectory("ykapi").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1", "BACKUPS_ENABLED" to "false", "GUESTS_ENABLED" to "true")
    )

    private suspend fun ApplicationTestBuilder.login(name: String, password: String): HttpClient {
        val client = createClient { install(HttpCookies) }
        repeat(50) {
            val r = client.post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return client
            delay(100) // the admin is created right after start-up
        }
        error("no se pudo entrar como $name")
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun workerFlowAndAuthorization() = testApplication {
        val config = config()
        val services = Services(config)
        application { yomukai(config, services) }

        val admin = login("admin", "admin-password-1")
        val friend = admin.post("/api/admin/users") { contentType(ContentType.Application.Json); setBody("""{"name":"ana","password":"ana-password-1"}""") }
        assertEquals(HttpStatusCode.OK, friend.status)
        val ana = login("ana", "ana-password-1")

        // Only admins manage workers.
        assertEquals(HttpStatusCode.Forbidden, ana.post("/api/admin/workers") { setBody("""{"name":"x"}""") }.status)
        val created = json(admin.post("/api/admin/workers") { setBody("""{"name":"casa"}""") }.bodyAsText())
        val token = created.get("token").asString
        assertTrue(token.startsWith("ykw_"))

        // No token, a wrong token, or a user's session: no worker API.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/worker/claim").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/worker/claim") { header("Authorization", "Bearer ykw_nada") }.status)
        assertEquals(HttpStatusCode.Unauthorized, ana.post("/worker/claim").status)

        suspend fun worker(path: String, body: String = "{}") = client.post(path) { header("Authorization", "Bearer $token"); setBody(body) }
        assertEquals(HttpStatusCode.OK, worker("/worker/hello", """{"version":"1","capabilities":{"engines":["echo","tts"],"cpus":4}}""").status)
        assertEquals("nothing queued", HttpStatusCode.NoContent, worker("/worker/claim").status)

        val features = json(ana.get("/api/features").bodyAsText())
        assertTrue(features.getAsJsonObject("worker").get("online").asBoolean)
        assertTrue(features.getAsJsonObject("available").get("tts").asBoolean)

        // The admin's test job goes round trip.
        val jobId = json(admin.post("/api/admin/jobs/echo").bodyAsText()).get("id").asLong
        val claimed = json(worker("/worker/claim").bodyAsText())
        assertEquals(jobId, claimed.get("id").asLong)
        assertEquals("echo", claimed.get("type").asString)
        val done = client.put("/worker/jobs/$jobId/result") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody("""{"text":"hola"}""")
        }
        assertEquals(HttpStatusCode.OK, done.status)

        // Someone else's job does not exist for a user; its owner (the admin) gets the result.
        assertEquals(HttpStatusCode.NotFound, ana.get("/api/jobs/$jobId").status)
        assertEquals(HttpStatusCode.NotFound, ana.get("/api/jobs/$jobId/result").status)
        assertEquals("""{"text":"hola"}""", admin.get("/api/jobs/$jobId/result").bodyAsText())

        // Database copies only for workers the admin allowed.
        val wid = created.get("id").asLong
        assertEquals(HttpStatusCode.Forbidden, client.get("/worker/backups/latest") { header("Authorization", "Bearer $token") }.status)
        admin.post("/api/admin/workers/$wid/backup") { setBody("""{"allowed":true}""") }
        assertEquals("allowed, but no copy made yet", HttpStatusCode.NoContent, client.get("/worker/backups/latest") { header("Authorization", "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Forbidden, ana.post("/api/admin/workers/$wid/backup") { setBody("""{"allowed":true}""") }.status)

        // A revoked token stops working at once.
        val revoke = admin.request("/api/admin/workers/${created.get("id").asLong}") { method = io.ktor.http.HttpMethod.Delete }
        assertEquals(HttpStatusCode.OK, revoke.status)
        assertEquals(HttpStatusCode.Unauthorized, worker("/worker/claim").status)

        // Turning the feature off hides the worker from everyone.
        admin.put("/api/admin/features/worker_jobs") { setBody("""{"enabled":false}""") }
        assertTrue(!json(ana.get("/api/features").bodyAsText()).getAsJsonObject("available").get("tts").asBoolean)
        assertEquals(HttpStatusCode.Forbidden, ana.put("/api/admin/features/worker_jobs") { setBody("""{"enabled":true}""") }.status)
    }

    @Test
    fun guestsAndAnonymousCannotSeeTheAdminStatus() = testApplication {
        val config = config()
        application { yomukai(config, Services(config)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/features").status)
        val guest = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, guest.post("/auth/guest").status)
        assertEquals(HttpStatusCode.Forbidden, guest.get("/api/admin/status").status)
        assertEquals(HttpStatusCode.OK, guest.get("/api/features").status)
    }
}
