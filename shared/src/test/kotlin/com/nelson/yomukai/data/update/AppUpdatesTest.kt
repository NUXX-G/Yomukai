package com.nelson.yomukai.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun versionsCompareByNumbers() {
        assertTrue(AppUpdates.isNewer("v2.0.0", "1.13.0"))
        assertTrue(AppUpdates.isNewer("1.13.1", "1.13"))
        assertFalse(AppUpdates.isNewer("1.13.0", "1.13.0"))
        assertFalse(AppUpdates.isNewer("1.9.9", "1.13.0"))
        assertFalse("pre-release suffix ignored", AppUpdates.isNewer("1.13.0-beta", "1.13.0"))
    }

    @Test
    fun readsGithubsLatestRelease() {
        val json = """{"tag_name":"v2.0.0","html_url":"https://github.com/NUXX-G/Yomukai/releases/tag/v2.0.0","draft":false,"prerelease":false,
            "assets":[{"name":"Yomukai-v2.0.0.apk.sha256","browser_download_url":"https://github.com/x.sha256"},
                      {"name":"Yomukai-v2.0.0.apk","browser_download_url":"https://github.com/NUXX-G/Yomukai/releases/download/v2.0.0/Yomukai-v2.0.0.apk"}]}"""
        val update = AppUpdates.parse(json, "1.13.0")!!
        assertEquals("2.0.0", update.version)
        assertTrue(update.apkUrl!!.endsWith(".apk"))
        assertNull("already up to date", AppUpdates.parse(json, "2.0.0"))
        assertNull("links elsewhere are refused", AppUpdates.parse(json.replace("https://github.com/NUXX-G/Yomukai/releases/tag", "https://evil.example"), "1.0"))
    }

    @Test
    fun eachPhoneGetsItsOwnApk() {
        val json = """{"tag_name":"v2.1.0","html_url":"https://github.com/NUXX-G/Yomukai/releases/tag/v2.1.0","draft":false,"prerelease":false,
            "assets":[{"name":"Yomukai-v2.1.0-armeabi-v7a.apk","browser_download_url":"https://github.com/a/v7.apk"},
            {"name":"Yomukai-v2.1.0.apk","browser_download_url":"https://github.com/a/universal.apk"},
            {"name":"Yomukai-v2.1.0-arm64-v8a.apk","browser_download_url":"https://github.com/a/arm64.apk"}]}"""
        assertEquals("https://github.com/a/arm64.apk", AppUpdates.parse(json, "2.0.0", listOf("arm64-v8a", "armeabi-v7a"))!!.apkUrl)
        assertEquals("https://github.com/a/universal.apk", AppUpdates.parse(json, "2.0.0", listOf("riscv64"))!!.apkUrl)
        assertEquals("https://github.com/a/universal.apk", AppUpdates.parse(json, "2.0.0")!!.apkUrl)
    }
}
