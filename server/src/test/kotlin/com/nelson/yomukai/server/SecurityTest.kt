package com.nelson.yomukai.server

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecurityTest {
    // ---------- Pure pieces ----------

    @Test
    fun totpMatchesRfc6238() {
        // RFC 6238 appendix B, SHA-1, 8 digits, secret "12345678901234567890".
        val secret = "12345678901234567890".toByteArray()
        assertEquals("94287082", Totp.code(secret, Totp.step(59_000), digits = 8))
        assertEquals("07081804", Totp.code(secret, Totp.step(1_111_111_109_000), digits = 8))
        assertEquals("14050471", Totp.code(secret, Totp.step(1_111_111_111_000), digits = 8))
        assertEquals("89005924", Totp.code(secret, Totp.step(1_234_567_890_000), digits = 8))
        assertEquals("69279037", Totp.code(secret, Totp.step(2_000_000_000_000), digits = 8))
    }

    @Test
    fun totpAcceptsOneStepOfDriftAndNeverTheSameCodeTwice() {
        val secret = Totp.newSecret()
        val bytes = Totp.decodeBase32(secret)!!
        val now = 1_800_000_000_000L
        val step = Totp.step(now)
        assertEquals(step, Totp.verify(secret, Totp.code(bytes, step), now))
        assertEquals(step - 1, Totp.verify(secret, Totp.code(bytes, step - 1), now))
        assertNull("two steps old", Totp.verify(secret, Totp.code(bytes, step - 2), now))
        assertNull("already used", Totp.verify(secret, Totp.code(bytes, step), now, lastUsedStep = step))
        assertNull(Totp.verify(secret, "12", now))
        assertTrue(Totp.uri(secret, "ana").startsWith("otpauth://totp/Yomukai:ana?secret=$secret"))
    }

    @Test
    fun base32RoundTrip() {
        val bytes = ByteArray(20) { it.toByte() }
        assertTrue(bytes.contentEquals(Totp.decodeBase32(Totp.base32(bytes))))
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32("12345678901234567890".toByteArray()))
        assertNull(Totp.decodeBase32("no válido!"))
    }

    @Test
    fun secretBoxEncryptsAndRejectsTampering() {
        val box = SecretBox("clave-del-servidor".toByteArray(), "totp")
        val sealed = box.seal("JBSWY3DPEHPK3PXP")
        assertNotEquals(sealed, box.seal("JBSWY3DPEHPK3PXP"))
        assertEquals("JBSWY3DPEHPK3PXP", box.open(sealed))
        assertNull("another purpose has another key", SecretBox("clave-del-servidor".toByteArray(), "jellyfin").open(sealed))
        assertNull(box.open(sealed.dropLast(2) + "AA"))
        assertNull(box.open("basura"))
    }

    @Test
    fun loginLocksGrowLongerEachTime() {
        assertEquals(LoginThrottle.LOCK_MS, LoginThrottle.lockFor(0))
        assertEquals(LoginThrottle.LOCK_MS * 2, LoginThrottle.lockFor(1))
        assertEquals(LoginThrottle.LOCK_MS * 4, LoginThrottle.lockFor(2))
        assertEquals(LoginThrottle.MAX_LOCK_MS, LoginThrottle.lockFor(30))

        val t = LoginThrottle()
        var now = 1_000_000L
        repeat(LoginThrottle.MAX_FAILURES) { t.failed(listOf("ip:1"), now) }
        assertEquals(LoginThrottle.LOCK_MS, t.waitFor(listOf("ip:1"), now))
        now += LoginThrottle.LOCK_MS
        repeat(LoginThrottle.MAX_FAILURES) { t.failed(listOf("ip:1"), now) }
        assertEquals("second lock doubles", LoginThrottle.LOCK_MS * 2, t.waitFor(listOf("ip:1"), now))
    }

    @Test
    fun devices() {
        assertEquals("Chrome en Windows", deviceOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
        assertEquals("Safari en iOS", deviceOf("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1"))
        assertEquals("App de Android", deviceOf("okhttp/4.12.0"))
        assertEquals("Desconocido", deviceOf(null))
    }

    @Test
    fun recoveryCodes() {
        val codes = RecoveryCodes.generate()
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { it.matches(Regex("[a-z2-9]{5}-[a-z2-9]{5}")) })
        assertEquals(RecoveryCodes.hash(codes[0]), RecoveryCodes.hash(codes[0].uppercase().replace("-", " ")))
    }

    // ---------- Over HTTP ----------

    private fun config(extra: Map<String, String> = emptyMap()) = Config.fromEnv(
        mapOf(
            "DATA_DIR" to Files.createTempDirectory("yksec").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
            "BACKUPS_ENABLED" to "false", "GUESTS_ENABLED" to "true"
        ) + extra
    )

    private suspend fun ApplicationTestBuilder.session(): HttpClient = createClient { install(HttpCookies) }

    private suspend fun HttpClient.login(name: String, password: String): HttpResponse {
        var last: HttpResponse? = null
        repeat(50) {
            last = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (last!!.status != HttpStatusCode.Unauthorized || name != "admin") return last!!
            delay(100) // the admin is created right after start-up
        }
        return last!!
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun headersAreStrict() = testApplication {
        val c = config()
        application { yomukai(c, Services(c)) }
        val r = client.get("/auth/me")
        assertTrue(r.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(r.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", r.headers["Referrer-Policy"])
        assertEquals("DENY", r.headers["X-Frame-Options"])
        assertTrue(r.headers["Permissions-Policy"]!!.contains("camera=()"))
        assertNull("no HSTS over plain HTTP", r.headers["Strict-Transport-Security"])
        val cookie = session().login("admin", "admin-password-1").headers["Set-Cookie"]!!
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"))
    }

    @Test
    fun usersOnlyEverSeeTheirOwnThings() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        listOf("ana", "beto").forEach { admin.post("/api/admin/users") { setBody("""{"name":"$it","password":"$it-password-1"}""") } }
        val ana = session().also { assertEquals(HttpStatusCode.OK, it.login("ana", "ana-password-1").status) }
        val beto = session().also { it.login("beto", "beto-password-1") }

        val row = """{"sourceId":"s","mediaId":"m","title":"Secreta","cover":"","type":"MANGA","status":"","author":"","genres":"","addedAt":1,"readingStatus":"NONE"}"""
        assertEquals(HttpStatusCode.OK, ana.put("/api/library") { contentType(ContentType.Application.Json); setBody(row) }.status)
        assertTrue(ana.get("/api/library").bodyAsText().contains("Secreta"))
        assertFalse(beto.get("/api/library").bodyAsText().contains("Secreta"))
        assertFalse(beto.get("/api/history").bodyAsText().contains("Secreta"))

        // Beto cannot close Ana's session, even knowing its id.
        val anaSession = JsonParser.parseString(ana.get("/api/sessions").bodyAsText()).asJsonArray.first().asJsonObject.get("id").asLong
        assertEquals(HttpStatusCode.NotFound, beto.request("/api/sessions/$anaSession") { method = HttpMethod.Delete }.status)
        assertEquals(HttpStatusCode.OK, ana.get("/api/library").status)

        // Nobody but admins reaches the admin API.
        listOf("/api/admin/users", "/api/admin/audit", "/api/admin/invites", "/api/admin/workers", "/api/admin/status").forEach {
            assertEquals(it, HttpStatusCode.Forbidden, beto.get(it).status)
        }
        // And nobody without a session reaches anything.
        listOf("/api/library", "/api/history", "/api/sessions", "/api/settings", "/api/features", "/api/sources", "/api/2fa").forEach {
            assertEquals(it, HttpStatusCode.Unauthorized, client.get(it).status)
        }
    }

    @Test
    fun guestsAreKeptToTheBasics() = testApplication {
        val c = config()
        application { yomukai(c, Services(c)) }
        val guest = session()
        assertEquals(HttpStatusCode.OK, guest.post("/auth/guest").status)
        assertEquals(HttpStatusCode.Forbidden, guest.get("/api/admin/users").status)
        assertEquals(HttpStatusCode.BadRequest, guest.post("/api/2fa/setup").status)
        assertNotEquals(HttpStatusCode.OK, guest.get("/api/export").status)
    }

    @Test
    fun twoStepLoginWithRecoveryCodes() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        admin.post("/api/admin/users") { setBody("""{"name":"ana","password":"ana-password-1"}""") }
        val ana = session().also { it.login("ana", "ana-password-1") }

        val setup = json(ana.post("/api/2fa/setup").bodyAsText())
        val secret = setup.get("secret").asString
        assertEquals(HttpStatusCode.BadRequest, ana.post("/api/2fa/enable") { setBody("""{"code":"000000"}""") }.status)
        val code = Totp.code(Totp.decodeBase32(secret)!!, Totp.step(System.currentTimeMillis()))
        val enabled = json(ana.post("/api/2fa/enable") { setBody("""{"code":"$code"}""") }.bodyAsText())
        val recovery = enabled.getAsJsonArray("recoveryCodes").map { it.asString }
        assertEquals(10, recovery.size)
        assertTrue(json(ana.get("/api/2fa").bodyAsText()).get("enabled").asBoolean)

        // The password alone gives no session now.
        val phone = session()
        val first = phone.login("ana", "ana-password-1")
        val challenge = json(first.bodyAsText()).get("challenge").asString
        assertNull(first.headers["Set-Cookie"])
        assertEquals(HttpStatusCode.Unauthorized, phone.get("/api/library").status)
        assertEquals(HttpStatusCode.Unauthorized, phone.post("/auth/2fa") { setBody("""{"challenge":"$challenge","code":"111111"}""") }.status)
        assertEquals(HttpStatusCode.OK, phone.post("/auth/2fa") { setBody("""{"challenge":"$challenge","code":"${recovery[0]}"}""") }.status)
        assertEquals(HttpStatusCode.OK, phone.get("/api/library").status)

        // A recovery code works once; a challenge works once.
        val again = json(session().login("ana", "ana-password-1").bodyAsText()).get("challenge").asString
        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/2fa") { setBody("""{"challenge":"$again","code":"${recovery[0]}"}""") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/2fa") { setBody("""{"challenge":"$challenge","code":"${recovery[1]}"}""") }.status)

        // Turning it off needs the password and a code.
        assertEquals(HttpStatusCode.BadRequest, ana.post("/api/2fa/disable") { setBody("""{"password":"mal","code":"${recovery[2]}"}""") }.status)
        assertEquals(HttpStatusCode.OK, ana.post("/api/2fa/disable") { setBody("""{"password":"ana-password-1","code":"${recovery[2]}"}""") }.status)
        assertEquals(HttpStatusCode.OK, session().login("ana", "ana-password-1").let { r -> assertTrue(r.headers["Set-Cookie"] != null); r.status })
    }

    @Test
    fun sessionsCanBeListedAndClosed() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val pc = session().also { it.login("admin", "admin-password-1") }
        val phone = session().also { it.login("admin", "admin-password-1") }
        val list = JsonParser.parseString(pc.get("/api/sessions").bodyAsText()).asJsonArray.map { it.asJsonObject }
        assertEquals(2, list.size)
        assertEquals(1, list.count { it.get("current").asBoolean })
        val other = list.first { !it.get("current").asBoolean }.get("id").asLong
        assertEquals(HttpStatusCode.OK, pc.request("/api/sessions/$other") { method = HttpMethod.Delete }.status)
        assertEquals("closed at once", HttpStatusCode.Unauthorized, phone.get("/api/library").status)
        assertEquals(HttpStatusCode.OK, pc.get("/api/library").status)
        // The database keeps only hashes of session tokens.
        val stored = s.db.tx { query("SELECT token_hash FROM user_sessions") { getString(1) } }
        assertTrue(stored.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun invitationsAreOneUseAndAdminOnly() = testApplication {
        val c = config()
        val s = Services(c)
        application { yomukai(c, s) }
        val admin = session().also { it.login("admin", "admin-password-1") }
        val invite = json(admin.post("/api/admin/invites") { setBody("""{"days":3,"note":"para Ana"}""") }.bodyAsText())
        val token = invite.get("token").asString
        assertEquals(HttpStatusCode.OK, client.get("/auth/invite/$token").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/auth/invite/$token") { setBody("""{"name":"ana","password":"corta"}""") }.status)
        val ana = session()
        assertEquals(HttpStatusCode.OK, ana.post("/auth/invite/$token") { setBody("""{"name":"ana","password":"ana-password-1"}""") }.status)
        assertEquals(HttpStatusCode.OK, ana.get("/api/library").status)
        assertEquals("spent", HttpStatusCode.BadRequest, client.post("/auth/invite/$token") { setBody("""{"name":"otro","password":"otro-password-1"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/auth/invite/$token").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/auth/invite/inventado-inventado-inventado").status)
        assertEquals(HttpStatusCode.Forbidden, ana.post("/api/admin/invites") { setBody("{}") }.status)

        // Admin actions are in the audit log, with who did them; users cannot read it.
        delay(200)
        val audit = admin.get("/api/admin/audit").bodyAsText()
        assertTrue(audit, audit.contains("POST /invites") && audit.contains("admin") && audit.contains("para Ana"))
        assertEquals(HttpStatusCode.Forbidden, ana.get("/api/admin/audit").status)
    }

    @Test
    fun firstRunNeedsTheSetupCode() = testApplication {
        val dir = Files.createTempDirectory("yksetup").toFile()
        val c = Config.fromEnv(mapOf("DATA_DIR" to dir.path, "BACKUPS_ENABLED" to "false"))
        val s = Services(c)
        application { yomukai(c, s) }
        client.get("/auth/me") // starts the application
        var token: String? = null
        repeat(50) { if (token == null) { token = File(dir, "setup-token").takeIf { it.isFile }?.readText()?.trim(); if (token == null) delay(100) } }
        assertNotNull(token)
        assertTrue(json(client.get("/auth/me").bodyAsText()).get("setupNeeded").asBoolean)
        assertEquals("no default admin", HttpStatusCode.Unauthorized, client.post("/auth/login") { setBody("""{"name":"admin","password":"admin"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/auth/setup") { setBody("""{"token":"otro","name":"yo","password":"mi-password-1"}""") }.status)
        val me = session()
        assertEquals(HttpStatusCode.OK, me.post("/auth/setup") { setBody("""{"token":"$token","name":"yo","password":"mi-password-1"}""") }.status)
        assertEquals(HttpStatusCode.OK, me.get("/api/admin/users").status)
        assertFalse(File(dir, "setup-token").exists())
        assertEquals(HttpStatusCode.BadRequest, client.post("/auth/setup") { setBody("""{"token":"$token","name":"otro","password":"mi-password-1"}""") }.status)
        assertFalse(json(client.get("/auth/me").bodyAsText()).get("setupNeeded").asBoolean)
    }

    @Test
    fun tooManyAuthRequestsFromOneAddressAreRefused() = testApplication {
        val c = config(mapOf("AUTH_RATE" to "5"))
        application { yomukai(c, Services(c)) }
        val codes = (1..8).map { client.post("/auth/login") { setBody("""{"name":"x","password":"y"}""") }.status }
        assertTrue(codes.toString(), codes.contains(HttpStatusCode.TooManyRequests))
        assertEquals("reads are not counted against the login budget", HttpStatusCode.Unauthorized, client.get("/auth/me").status)
    }
}
