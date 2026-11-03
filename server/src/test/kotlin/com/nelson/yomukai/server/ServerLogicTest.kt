package com.nelson.yomukai.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerLogicTest {
    private fun tempDb(): Database = Database(File(Files.createTempDirectory("yk").toFile(), "t.db"))

    @Test
    fun passwordsVerifyAndNeverRepeat() {
        val a = Passwords.hash("secreto")
        val b = Passwords.hash("secreto")
        assertNotEquals("salted", a, b)
        assertTrue(Passwords.verify("secreto", a))
        assertFalse(Passwords.verify("otra", a))
        assertFalse(Passwords.verify("secreto", null))
        assertTrue(Passwords.generate().matches(Regex("[a-z0-9]{4}(-[a-z0-9]{4}){3}")))
    }

    @Test
    fun rateLimitRefillsOverTime() {
        val limiter = RateLimiter()
        val start = 1_000_000L
        repeat(10) { assertTrue(limiter.allow(1, 10, start)) }
        assertFalse(limiter.allow(1, 10, start))
        assertTrue("one token back after 6 s at 10/min", limiter.allow(1, 10, start + 6_000))
        assertTrue("users do not share a budget", limiter.allow(2, 10, start))
    }

    @Test
    fun expiredGuestsGoAwayWithTheirData() = runBlocking {
        val db = tempDb()
        val now = System.currentTimeMillis()
        val old = db.createUser("invitado-viejo", null, admin = false, guest = true, expiresAt = now - 1)
        val fresh = db.createUser("invitado-nuevo", null, admin = false, guest = true, expiresAt = now + 86_400_000)
        val friend = db.createUser("amigo", Passwords.hash("x"), admin = false, guest = false, expiresAt = null)
        listOf(old, fresh, friend).forEach { u ->
            db.putLibrary(u.id, LibraryRow("s", "m", "T", "", "MANGA", "", "", "", now, "NONE"))
            db.createSession("tok-${u.id}", u.id)
        }
        assertEquals(1, db.deleteExpiredGuests(now))
        assertNull(db.userBySession("tok-${old.id}"))
        assertTrue(db.library(old.id).isEmpty())
        assertEquals(1, db.library(fresh.id).size)
        assertEquals(1, db.library(friend.id).size)
    }

    @Test
    fun importReplacesOnlyThatUser() = runBlocking {
        val db = tempDb()
        val a = db.createUser("a", null, false, false, null)
        val b = db.createUser("b", null, false, false, null)
        db.putLibrary(a.id, LibraryRow("s", "1", "Uno", "", "MANGA", "", "", "", 1, "NONE"))
        db.putLibrary(b.id, LibraryRow("s", "2", "Dos", "", "MANGA", "", "", "", 1, "NONE"))
        db.replaceAll(a.id, listOf(LibraryRow("s", "3", "Tres", "", "MANGA", "", "", "", 1, "NONE")), emptyList(), emptyList(), "{}")
        assertEquals(listOf("3"), db.library(a.id).map { it.mediaId })
        assertEquals(listOf("2"), db.library(b.id).map { it.mediaId })
    }

    @Test
    fun settingsDecideSourcesAndExclusions() {
        val prefs = UserPrefs.parse("""{"enabledSources":["mangadex"],"exclusions":{"global":["BL"],"perSource":{"asura":["Gore"]}}}""")
        assertEquals(setOf("mangadex"), prefs.enabledSources)
        assertEquals(setOf("BL", "Gore"), prefs.excludedFor("asura"))
        assertNull("no list means the defaults", UserPrefs.parse("""{"theme":"dark"}""").enabledSources)
        assertEquals(UserPrefs.DEFAULT, UserPrefs.parse("no es json"))
    }

    @Test
    fun internalAddressesAreNeverReached() {
        fun ip(text: String) = java.net.InetAddress.getByName(text)
        listOf("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1", "0.0.0.0")
            .forEach { assertFalse(it, PublicAddresses.isPublic(ip(it))) }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { assertTrue(it, PublicAddresses.isPublic(ip(it))) }
    }

    @Test
    fun proxyNeverServesPagesAsImages() {
        assertEquals("image/png", MediaProxy.safeImageType("image/png"))
        assertEquals("image/webp", MediaProxy.safeImageType("IMAGE/WEBP; charset=x"))
        assertEquals("image/jpeg", MediaProxy.safeImageType("text/html"))
        assertEquals("image/jpeg", MediaProxy.safeImageType("image/svg+xml"))
        assertEquals("image/jpeg", MediaProxy.safeImageType(""))
    }

    @Test
    fun repeatedWrongPasswordsLockTheKey() {
        val throttle = LoginThrottle()
        val keys = listOf("ip:1.2.3.4", "user:amigo")
        repeat(LoginThrottle.MAX_FAILURES - 1) { throttle.failed(keys, 0) }
        assertEquals(0L, throttle.waitFor(keys, 0))
        throttle.failed(keys, 0)
        assertTrue(throttle.waitFor(keys, 0) > 0)
        assertEquals("unlocked after the lock time", 0L, throttle.waitFor(keys, LoginThrottle.LOCK_MS + 1))
        assertTrue("another address may still try that account", throttle.begin(listOf("ip:9.9.9.9", "pair:9.9.9.9|amigo"), 0))
    }

    @Test
    fun attemptsSentAtOnceCannotSlipUnderTheLimit() {
        val throttle = LoginThrottle()
        val keys = listOf("ip:5.5.5.5", "pair:5.5.5.5|amigo")
        repeat(LoginThrottle.MAX_FAILURES) { assertTrue(throttle.begin(keys, 0)) }
        assertFalse("still checking five passwords: a sixth waits", throttle.begin(keys, 0))
        throttle.finish(keys, success = true, now = 0)
        repeat(LoginThrottle.MAX_FAILURES - 1) { throttle.finish(keys, success = false, now = 0) }
        assertTrue(throttle.begin(keys, 0))
    }

    @Test
    fun accountsSlowDownWithoutLockingTheOwner() {
        val throttle = LoginThrottle()
        repeat(LoginThrottle.SLOW_AFTER) { throttle.accountFailed("amigo", 0) }
        assertEquals(0L, throttle.accountDelay("amigo", 0))
        repeat(100) { throttle.accountFailed("amigo", 0) }
        assertEquals(LoginThrottle.MAX_DELAY_MS, throttle.accountDelay("amigo", 0))
        assertEquals("the brake ends with the window", 0L, throttle.accountDelay("amigo", LoginThrottle.WINDOW_MS + 1))
    }

    @Test
    fun oneRightPasswordDoesNotResetTheAddress() {
        val throttle = LoginThrottle()
        repeat(LoginThrottle.MAX_FAILURES - 1) { throttle.failed(listOf("ip:7.7.7.7", "pair:7.7.7.7|victima"), 0) }
        assertTrue(throttle.begin(listOf("ip:7.7.7.7", "pair:7.7.7.7|propia"), 0))
        throttle.finish(listOf("ip:7.7.7.7", "pair:7.7.7.7|propia"), success = true, now = 0)
        throttle.failed(listOf("ip:7.7.7.7", "pair:7.7.7.7|victima"), 0)
        assertTrue("the address hit its limit despite the success in between", throttle.waitFor(listOf("ip:7.7.7.7"), 0) > 0)
    }

    @Test
    fun ipv6AddressesGroupByTheirNetwork() {
        assertEquals("2001:db8:1:2::/64", LoginThrottle.addressKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertEquals(LoginThrottle.addressKey("2001:db8:1:2::1"), LoginThrottle.addressKey("2001:db8:1:2:ffff::9"))
        assertEquals("1.2.3.4", LoginThrottle.addressKey("1.2.3.4"))
    }

    @Test
    fun proxyLinksAreSignedAndPlaylistsRewritten() {
        val proxy = MediaProxy(ServerHttp(null), ByteArray(32) { 7 }, Files.createTempDirectory("yk").toFile(), 2)
        val link = proxy.image("https://cdn.example.com/a.jpg", mapOf("Referer" to "https://example.com/"))
        assertTrue(link.startsWith("/api/img?u=https%3A%2F%2Fcdn.example.com%2Fa.jpg&h="))
        assertNotEquals(link, proxy.image("https://cdn.example.com/b.jpg", mapOf("Referer" to "https://example.com/")))
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10,\nseg/0.ts\n"
        val rewritten = proxy.rewritePlaylist(playlist, "https://v.example.com/hls/index.m3u8", emptyMap())
        assertTrue(rewritten.contains("URI=\"/api/stream?u=https%3A%2F%2Fv.example.com%2Fhls%2Fkey.bin"))
        assertTrue(rewritten.lines().any { it.startsWith("/api/stream?u=https%3A%2F%2Fv.example.com%2Fhls%2Fseg%2F0.ts") })
        assertTrue(rewritten.startsWith("#EXTM3U"))
    }
}
