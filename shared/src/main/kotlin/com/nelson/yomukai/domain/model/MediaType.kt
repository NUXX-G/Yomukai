package com.nelson.yomukai.domain.model

enum class MediaType(val displayName: String) {
    MANGA("Manga"),
    MANHWA("Manhwa"),
    MANHUA("Manhua"),
    COMIC("Cómic"),
    NOVEL("Novela"),
    ANIME("Anime");

    val isReadableAsImages: Boolean get() = this != NOVEL && this != ANIME

    companion object {
        fun fromName(name: String?): MediaType = entries.firstOrNull { it.name == name } ?: MANGA
    }
}
