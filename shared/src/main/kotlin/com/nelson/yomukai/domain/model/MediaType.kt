package com.nelson.yomukai.domain.model

import com.nelson.yomukai.i18n.tr

enum class MediaType(val displayName: String) {
    MANGA(tr("Manga")),
    MANHWA(tr("Manhwa")),
    MANHUA(tr("Manhua")),
    COMIC(tr("Cómic")),
    NOVEL(tr("Novela")),
    ANIME(tr("Anime"));

    val isReadableAsImages: Boolean get() = this != NOVEL && this != ANIME

    companion object {
        fun fromName(name: String?): MediaType = entries.firstOrNull { it.name == name } ?: MANGA
    }
}
