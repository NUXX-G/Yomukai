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

    @Test fun `plain players and escaped slashes`() {
        assertEquals("https://v.host/x/index.m3u8?t=1", EmbedProbe.findStream("""sources: [{file:"https:\/\/v.host\/x\/index.m3u8?t=1"}]"""))
        assertNull(EmbedProbe.findStream("<img src='https://v.host/thumb.mp4'>"))
    }
}
