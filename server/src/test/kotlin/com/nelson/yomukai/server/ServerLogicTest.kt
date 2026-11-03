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
        db.replaceAll(a.id, listOf(LibraryRow("s", "3", "Tres", "", "MANGA", "", "", "", 1, "NONE")), emptyList(), emptyList(), emptyList(), emptyList(), "{}")
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
        assertEquals("20010db800010002/64", LoginThrottle.addressKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertEquals("shortened forms are the same network", LoginThrottle.addressKey("2001:db8::1"), LoginThrottle.addressKey("2001:0db8:0000:0000::9"))
        assertEquals("1.2.3.4", LoginThrottle.addressKey("::ffff:1.2.3.4"))
        assertEquals(LoginThrottle.addressKey("2001:db8:1:2::1"), LoginThrottle.addressKey("2001:db8:1:2:ffff::9"))
        assertEquals("1.2.3.4", LoginThrottle.addressKey("1.2.3.4"))
        assertEquals("names are never looked up", "other:example.com", LoginThrottle.addressKey("example.com"))
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

    private fun lib(id: String, at: Long, status: String = "READING", deleted: Boolean = false, cats: List<String> = emptyList()) =
        LibraryRow("s", id, "T$id", "", "MANGA", "", "", "", 1, status, cats, at, deleted)

    @Test
    fun syncKeepsTheNewestChangeAndOnlySendsWhatIsNew() = runBlocking {
        val db = tempDb()
        val u = db.createUser("sync", null, admin = false, guest = false, expiresAt = null)
        // Phone pushes two titles.
        val first = db.sync(u.id, 0, listOf(lib("1", 100), lib("2", 100)), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertEquals(2, first.library.size)
        // An older change from another device loses; a newer one wins.
        val second = db.sync(u.id, first.rev, listOf(lib("1", 50, "DROPPED"), lib("2", 200, "COMPLETED")), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertEquals(listOf("2"), second.library.map { it.mediaId })
        val now = db.library(u.id).associateBy { it.mediaId }
        assertEquals("READING", now["1"]!!.readingStatus)
        assertEquals("COMPLETED", now["2"]!!.readingStatus)
        // Nothing new: nothing comes back.
        val idle = db.sync(u.id, second.rev, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertTrue(idle.library.isEmpty() && idle.progress.isEmpty())
        // A deletion travels as a deleted row and leaves the library.
        val gone = db.sync(u.id, idle.rev, listOf(lib("1", 300, deleted = true)), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertTrue(gone.library.single().deleted)
        assertEquals(listOf("2"), db.library(u.id).map { it.mediaId })
    }

    @Test
    fun webDeletionsAndCategoryRenamesReachOtherDevices() = runBlocking {
        val db = tempDb()
        val u = db.createUser("cats", null, admin = false, guest = false, expiresAt = null)
        val start = db.sync(u.id, 0, listOf(lib("1", 10, cats = listOf("Favoritas"))), listOf(CategoryRow("Favoritas", 0, 10)),
            emptyList(), listOf(HistoryRow("s", "1", "c1", "Cap. 1", "T1", "", "MANGA", 10)), emptyList(), null)
        db.editCategory(u.id, "Favoritas", "Top")
        db.removeHistory(u.id, "s", "1")
        val changes = db.sync(u.id, start.rev, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertEquals(setOf("Favoritas" to true, "Top" to false), changes.categories.map { it.name to it.deleted }.toSet())
        assertEquals(listOf("Top"), changes.library.single().categories)
        assertTrue(changes.history.single().deleted)
        assertTrue(db.history(u.id).isEmpty())
    }

    @Test
    fun sharedSettingsSyncOnlyWhenNewer() = runBlocking {
        val db = tempDb()
        val u = db.createUser("prefs", null, admin = false, guest = false, expiresAt = null)
        db.putSettings(u.id, """{"novel":{"size":20},"exclusions":{"global":["Gore"],"perSource":{}}}""", 1_000)
        val phone = com.google.gson.JsonParser.parseString("""{"exclusions":{"global":["BL"],"perSource":{}},"updatedAt":2000}""").asJsonObject
        val result = db.sync(u.id, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), phone)
        val stored = com.google.gson.JsonParser.parseString(db.settings(u.id)).asJsonObject
        assertEquals("the web's own settings stay", 20, stored.getAsJsonObject("novel").get("size").asInt)
        assertEquals("BL", stored.getAsJsonObject("exclusions").getAsJsonArray("global")[0].asString)
        assertEquals(2000L, result.settings!!.get("updatedAt").asLong)
        val stale = com.google.gson.JsonParser.parseString("""{"exclusions":{"global":[],"perSource":{}},"updatedAt":1500}""").asJsonObject
        db.sync(u.id, result.rev, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), stale)
        assertTrue(db.settings(u.id)!!.contains("BL"))
    }

    @Test
    fun syncRowsAreCleanedBeforeStoring() = runBlocking {
        val db = tempDb()
        val u = db.createUser("clean", null, admin = false, guest = false, expiresAt = null)
        val huge = "x".repeat(10_000)
        db.sync(u.id, 0, listOf(lib("1", 5).copy(title = huge, categories = null)), emptyList(),
            listOf(ProgressRow("s", "c", "1", false, -3, 10, Double.NaN, 5)), emptyList(), emptyList(), null)
        val row = db.library(u.id).single()
        assertEquals(500, row.title.length)
        assertTrue(row.categories!!.isEmpty())
        val p = db.allProgress(u.id).single()
        assertEquals(0, p.page)
        assertEquals(0.0, p.fraction, 0.0)
    }

    @Test
    fun hlsPlansFollowKeysAndRefuseWhatCannotBeJoined() {
        val text = """#EXTM3U
            |#EXT-X-MEDIA-SEQUENCE:7
            |#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            |#EXTINF:4,
            |seg0.ts
            |#EXT-X-KEY:METHOD=NONE
            |#EXTINF:4,
            |https://cdn.example.com/seg1.ts
            |#EXT-X-ENDLIST""".trimMargin()
        val plan = HlsPlan.parse(text, "https://v.example.com/hls/index.m3u8")!!
        assertEquals(listOf("https://v.example.com/hls/seg0.ts", "https://cdn.example.com/seg1.ts"), plan.segments.map { it.url })
        assertEquals("https://v.example.com/hls/key.bin", plan.segments[0].keyUrl)
        assertEquals("IV is the sequence number", 7, plan.segments[0].iv.last().toInt())
        assertNull(plan.segments[1].keyUrl)
        assertNull(HlsPlan.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\nseg.ts", "https://v.example.com/a.m3u8"))
        assertNull(HlsPlan.parse("#EXTM3U\n#EXT-X-BYTERANGE:100@0\nseg.ts", "https://v.example.com/a.m3u8"))
    }

    @Test
    fun downloadNamesAndXmlAreSafe() {
        assertEquals("Solo Leveling Cap. 1 x", Downloader.safeName("Solo Leveling / Cap. 1: x"))
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", Downloader.xml("a <b> & \"c\"\u0001"))
    }
}
