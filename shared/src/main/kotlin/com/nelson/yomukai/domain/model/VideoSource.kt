package com.nelson.yomukai.domain.model

/**
 * A server for one episode. [isDirect] streams (HLS/MP4) play in the built-in player;
 * the rest are web embeds that can only be opened in a browser.
 */
data class VideoSource(
    val quality: String,
    val videoUrl: String,
    val serverName: String,
    val isDirect: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)
