package com.nelson.yomukai.data.image

/**
 * Larger versions of the thumbnails sites put in their lists. Only patterns whose original is known to exist next to
 * the thumbnail; anything else stays as it is. Used by the server's image proxy and by the app (which loads covers
 * straight from the sites), each falling back to the original address if the larger one fails.
 */
object HighResCovers {
    private val MANGADEX = Regex("""^(https://uploads\.mangadex\.org/covers/[^/?#]+/[^/?#]+\.(?:jpe?g|png|webp|gif))\.(?:256|512)\.jpg$""", RegexOption.IGNORE_CASE)
    private val WORDPRESS = Regex("""^(https?://[^?#]+/wp-content/uploads/[^?#]+?)-\d{2,4}x\d{2,4}(\.(?:jpe?g|png|webp))$""", RegexOption.IGNORE_CASE)
    private val ANILIST = Regex("""^(https://s4\.anilist\.co/file/anilistcdn/media/(?:anime|manga)/cover/)(?:small|medium|large)(/[^?#]+)$""")
    private val MAL_RESIZED = Regex("""^(https://cdn\.myanimelist\.net)/r/\d+x\d+(/images/(?:anime|manga)/[^?#]+?)(?:\?.*)?$""")
    private val MAL_THUMB = Regex("""^(https://cdn\.myanimelist\.net/images/(?:anime|manga)/\d+/\d+)[tvl](\.(?:jpe?g|webp))$""")

    fun upgrade(url: String): String =
        MANGADEX.matchEntire(url)?.groupValues?.get(1)
            ?: WORDPRESS.matchEntire(url)?.let { it.groupValues[1] + it.groupValues[2] }
            ?: ANILIST.matchEntire(url)?.let { it.groupValues[1] + "extraLarge" + it.groupValues[2] }
            ?: MAL_RESIZED.matchEntire(url)?.let { upgrade(it.groupValues[1] + it.groupValues[2]) }
            ?: MAL_THUMB.matchEntire(url)?.let { it.groupValues[1] + it.groupValues[2] }
            ?: url
}
