package com.nelson.yomukai.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

class HighResCoversTest {
    @Test
    fun thumbnailsBecomeTheirOriginals() {
        assertEquals(
            "https://uploads.mangadex.org/covers/abc-123/f00.jpg",
            HighResCovers.upgrade("https://uploads.mangadex.org/covers/abc-123/f00.jpg.512.jpg")
        )
        assertEquals(
            "https://marmota.me/wp-content/uploads/2024/05/portada.webp",
            HighResCovers.upgrade("https://marmota.me/wp-content/uploads/2024/05/portada-193x278.webp")
        )
        assertEquals(
            "https://s4.anilist.co/file/anilistcdn/media/manga/cover/extraLarge/bx30013-abc.jpg",
            HighResCovers.upgrade("https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/bx30013-abc.jpg")
        )
        assertEquals(
            "https://cdn.myanimelist.net/images/anime/1244/138851.jpg",
            HighResCovers.upgrade("https://cdn.myanimelist.net/r/50x70/images/anime/1244/138851t.jpg?s=abc")
        )
        assertEquals(
            "https://cdn.myanimelist.net/images/manga/2/253146.jpg",
            HighResCovers.upgrade("https://cdn.myanimelist.net/images/manga/2/253146v.jpg")
        )
        val other = "https://cdn.example.com/covers/thumb-200x300.jpg"
        assertEquals("anything else stays", other, HighResCovers.upgrade(other))
        val original = "https://cdn.myanimelist.net/images/anime/1244/138851.jpg"
        assertEquals(original, HighResCovers.upgrade(original))
    }
}
