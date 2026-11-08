package com.nelson.yomukai.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DomainAliasesTest {
    @Test
    fun onlyPlainHostNamesAreAccepted() {
        assertEquals("zonatmo.org", DomainAliases.host(" https://ZonaTMO.org/library?x=1 "))
        listOf("127.0.0.1", "localhost", "a b.com", "x", "http://", "evil.com:8080").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { DomainAliases.host(bad) }
        }
    }

    private fun ApplicationTestBuilder.session(): HttpClient = createClient { install(HttpCookies) }

    private suspend fun HttpClient.login(name: String, password: String) {
        repeat(50) {
            val r = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return
            delay(100)
        }
        error("no se pudo entrar como $name")
    }

    @Test
    fun theAdminMovesASourceToItsNewDomain() = testApplication {
        val c = Config.fromEnv(
            mapOf(
                "DATA_DIR" to Files.createTempDirectory("ykdomains").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
                "BACKUPS_ENABLED" to "false"
            )
        )
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { contentType(ContentType.Application.Json); setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }
        val body = """{"from":"lectortmo.com","to":"https://zonatmo.org/"}"""
        assertEquals(HttpStatusCode.Forbidden, ana.put("/api/admin/domains") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.OK, admin.put("/api/admin/domains") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(mapOf("lectortmo.com" to "zonatmo.org"), s.http.hostAliases)
        assertEquals(HttpStatusCode.BadRequest, admin.put("/api/admin/domains") { contentType(ContentType.Application.Json); setBody("""{"from":"a.com","to":"10.0.0.1"}""") }.status)
        admin.delete("/api/admin/domains/lectortmo.com")
        assertTrue(s.http.hostAliases.isEmpty())
    }
}
