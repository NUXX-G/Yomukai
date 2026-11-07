package com.nelson.yomukai.domain.model

import com.nelson.yomukai.i18n.tr

/** Normalizes the free-form status strings sources return into a small set the UI can badge. */
enum class PublicationStatus(val displayName: String) {
    ONGOING(tr("En emisión")),
    COMPLETED(tr("Finalizado")),
    HIATUS(tr("En pausa")),
    CANCELLED(tr("Cancelado")),
    UNKNOWN(tr("Desconocido"));

    companion object {
        fun parse(raw: String): PublicationStatus {
            val s = raw.lowercase()
            return when {
                s.isBlank() -> UNKNOWN
                listOf("complet", "finaliz", "finish", "terminad", "ended").any { it in s } -> COMPLETED
                listOf("hiatus", "pausa", "paused").any { it in s } -> HIATUS
                listOf("cancel", "dropped", "abandon").any { it in s } -> CANCELLED
                listOf("ongoing", "emisi", "active", "activ", "publish", "curso", "reciente").any { it in s } -> ONGOING
                else -> UNKNOWN
            }
        }
    }
}
