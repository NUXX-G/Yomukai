package com.nelson.yomukai.worker

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.UnknownHostException

/** The worker never reaches the home network from an address a site gave, not even after a redirect or a DNS change. */
class PublicOnlyTest {
    @Test
    fun homeAddressesAreNeverResolvedNorFollowed() {
        assertThrows(UnknownHostException::class.java) { PublicOnly.dns.lookup("localhost") }
        assertThrows(PermanentJobFailure::class.java) { PublicOnly.check("http://127.0.0.1/admin") }
        assertThrows(PermanentJobFailure::class.java) { PublicOnly.check("file:///etc/passwd") }
        val client = PublicOnly.client(okhttp3.OkHttpClient())
        assert(!client.followRedirects && !client.followSslRedirects)
    }
}
