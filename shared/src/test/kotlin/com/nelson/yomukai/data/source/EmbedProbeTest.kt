package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.source.anime.EmbedProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2/F3: dead or sandbox-refusing hosts are not offered; packed players give their stream. */
class EmbedProbeTest {
    @Test fun `dead and sandbox-refusing pages are recognized`() {
        assertTrue(EmbedProbe.isDead("<html><body><h1>WE ARE SORRY</h1><p>We can't find the video you are looking for.</p></body></html>"))
        assertTrue(EmbedProbe.refusesSandbox("<script>if(sandboxed()){document.body.innerHTML='This video is not available due to sandboxed iframe!'}</script>"))
        assertFalse(EmbedProbe.isDead("<html><body><video src='a.mp4'></video></body></html>"))
    }

    @Test fun `a packed player gives its hls master`() {
        // Packed the way StreamWish/Filemoon/VidHide pages do: words replaced by base-36 indexes.
        val packed = "<script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}" +
            "('0.1({2:[{3:\"4://5.6/7/8.9\"}]});',36,10,'jwplayer|setup|sources|file|https|cdn|example|hls|master|m3u8'.split('|'),0,{}))</script>"
        assertEquals("https://cdn.example/hls/master.m3u8", EmbedProbe.findStream(packed))
    }

    @Test fun `streamtape link is put together like its page does`() {
        // As served by streamtape.com/e/… on 2026-11-11.
        val html = "document.getElementById('robotlink').innerHTML = '//streamtape.c'+ " +
            "('xcdom/get_video?id=zQxrqv3kXBCYRKa&expires=1794499327&ip=F0uWKRSPKxSHDN&token=PnhT6dlSifCK').substring(2).substring(1);"
        assertEquals(
            "https://streamtape.com/get_video?id=zQxrqv3kXBCYRKa&expires=1794499327&ip=F0uWKRSPKxSHDN&token=PnhT6dlSifCK",
            EmbedProbe.findStream(html)
        )
    }

    @Test fun `player pages are not streams, lockers are not players, streamwish goes to its mirror`() {
        assertNull(EmbedProbe.findStream("<a href='https://streamtape.com/e/zQx/kikkkaaaannns_01.mp4'>x</a>"))
        assertTrue(EmbedProbe.isDownloadOnly("https://1fichier.com/?h2ngfq8vc7gs0q8dav5v"))
        assertFalse(EmbedProbe.isDownloadOnly("https://voe.sx/e/abc"))
        assertEquals("https://swdyu.com/e/d99ip6zwtm5y", EmbedProbe.mirrorFor("https://hgcloud.to/e/d99ip6zwtm5y"))
        assertEquals("https://voe.sx/e/abc", EmbedProbe.mirrorFor("https://voe.sx/e/abc"))
    }

    @Test fun `dood gives its pass path, token and stream like its page`() {
        // As served by playmogo.com (Dood) on 2026-11-11.
        val html = "\$.get('/pass_md5/283447961-188-26-1794432152-836ff9011ead2286f6eb446753c5e13b/y195pgz5x78kos4lw15ex3yo', function(data) {" +
            "function makePlay() { return a + \"?token=y195pgz5x78kos4lw15ex3yo&expiry=\" + Date.now(); }"
        val (path, token) = EmbedProbe.doodPass(html)!!
        assertEquals("/pass_md5/283447961-188-26-1794432152-836ff9011ead2286f6eb446753c5e13b/y195pgz5x78kos4lw15ex3yo", path)
        assertEquals("y195pgz5x78kos4lw15ex3yo", token)
        val stream = EmbedProbe.doodStream("https://we1047lp.cloudatacdn.com/u5kj/12nojyvapq~", token, now = 1L)!!
        assertTrue(stream.matches(Regex("""https://we1047lp\.cloudatacdn\.com/u5kj/12nojyvapq~[A-Za-z0-9]{10}\?token=y195pgz5x78kos4lw15ex3yo&expiry=1""")))
        assertTrue(EmbedProbe.isDood("https://dsvplay.com/e/fubd0g4i52pi"))
        assertNull(EmbedProbe.doodStream("http://evil/", token))
    }

    @Test fun `a crafted packed script cannot blow up`() {
        val huge = "x".repeat(10_000)
        val words = (0 until 100).joinToString("|") { huge }
        val payload = (0 until 1_000).joinToString(" ") { (it % 100).toString(36) }
        assertNull(EmbedProbe.unpack("}('$payload',36,100,'$words'.split('|')"))
    }

    @Test fun `plain players and escaped slashes`() {
        assertEquals("https://v.host/x/index.m3u8?t=1", EmbedProbe.findStream("""sources: [{file:"https:\/\/v.host\/x\/index.m3u8?t=1"}]"""))
        assertNull(EmbedProbe.findStream("<img src='https://v.host/thumb.mp4'>"))
    }
}
