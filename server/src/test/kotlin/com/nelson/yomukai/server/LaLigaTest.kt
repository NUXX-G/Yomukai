package com.nelson.yomukai.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** F2.3: what clients are told during LaLiga's blocks, and who may use the server's relay for what. */
class LaLigaTest {
    private fun config() = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("ykliga").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false", "GUESTS_ENABLED" to "true"
        )
    )

    private fun ApplicationTestBuilder.session(): HttpClient = createClient { install(HttpCookies) }

    @Test
    fun theRelayIsOnlyForAccountsAndOnlyForTheSourcesSites() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session()
        admin.post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"admin","password":"admin-password-1"}""") }

        // Not a source: refused before anything is fetched (no open proxy, nothing at home).
        assertEquals(HttpStatusCode.Forbidden, admin.get("/api/relay?u=" + java.net.URLEncoder.encode("http://127.0.0.1:22/", "UTF-8")).status)
        assertEquals(HttpStatusCode.Forbidden, admin.get("/api/relay?u=" + java.net.URLEncoder.encode("https://evil.example/x", "UTF-8")).status)
        assertEquals(HttpStatusCode.Forbidden, admin.get("/api/relay?u=file%3A%2F%2F%2Fetc%2Fpasswd").status)
        // A guest never gets it.
        val guest = session()
        guest.post("/auth/guest")
        // Refused either way: as a guest (403) or, if the guest limit of this connection was reached, without a session (401).
        assertTrue(guest.get("/api/relay?u=" + java.net.URLEncoder.encode("https://mangadex.org/", "UTF-8")).status in setOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized))
        // A source's own site is allowed (whatever it answers).
        val host = java.net.URI(s.sources.all.first { it.id == "mangadex" }.baseUrl).host
        assertTrue(Relay.allowed(host, s))
        assertTrue(!Relay.allowed("evil.example", s))
        // The state the clients read.
        LaLigaState.set(setOf("104.16.93.114"))
        assertTrue(admin.get("/api/bloqueos").bodyAsText().contains("\"active\":true"))
        LaLigaState.set(emptySet())
    }

    @Test
    fun aBlockIsExplainedAsSuchAndTheProxyAddressIsChecked() {
        LaLigaState.set(setOf("104.16.93.114"))
        assertTrue(sourceErrorText(javax.net.ssl.SSLHandshakeException("PKIX path building failed")).contains("LaLiga"))
        LaLigaState.set(emptySet())
        assertTrue(!sourceErrorText(javax.net.ssl.SSLHandshakeException("PKIX path building failed")).contains("LaLiga"))
        assertNotNull(OutboundProxy.parse("socks5://tor:9050"))
        assertNotNull(OutboundProxy.parse("http://proxy:3128"))
        assertNull(OutboundProxy.parse("ftp://x:21"))
        assertNull(OutboundProxy.parse("socks5://tor"))
        assertTrue(!OutboundProxy.publicHost("localhost"))
    }

    @Test
    fun aUserCannotUseTheRelayWithoutLimit() {
        val now = 1_000_000L
        repeat(600) { assertTrue(Relay.withinRate(42, now)) }
        assertTrue(!Relay.withinRate(42, now))
        assertTrue("a new minute starts again", Relay.withinRate(42, now + 60_000))
    }
}
