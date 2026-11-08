package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.speech.SpeechPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Voices prepared overnight: between [NIGHT_START] and [NIGHT_END] (server time), once a night, the chapter after the
 * last one read of each novel someone is reading is queued for the home worker with the voices they chose, cut
 * exactly as their reader will ask for it (SpeechPlan, the same cut as the web), so the morning's listening is ready
 * and shared by whoever reads the same text. Only worker voices ("w:"); low priority; behind the "tts_nightly" switch.
 */
class NightlyVoices(private val s: Services, private val zone: ZoneId = ZoneId.systemDefault(), private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var lastNight: LocalDate? = null

    /** Called every few minutes: runs at most once a night. Returns how many pieces were queued. */
    suspend fun tick(): Int {
        if (!s.features.isOn("tts_nightly") || !s.features.isOn("worker_jobs")) return 0
        val now = Instant.ofEpochMilli(clock()).atZone(zone)
        if (now.hour !in NIGHT_START until NIGHT_END || lastNight == now.toLocalDate()) return 0
        lastNight = now.toLocalDate()
        return s.db.users().filter { !it.isGuest && it.suspendedAt == null }.sumOf { user ->
            runCatching { forUser(user) }.getOrDefault(0)
        }
    }

    suspend fun forUser(user: User): Int {
        val novel = s.db.settings(user.id)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            ?.getAsJsonObject("novel") ?: return 0
        val voices = novel.getAsJsonObject("voices") ?: return 0
        val characters = novel.get("ttsCharacters")?.takeIf { it.isJsonPrimitive }?.asBoolean != false
        val rate = novel.get("speechRate")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f
        // The novels read most recently, one entry each.
        val reading = s.db.history(user.id).filter { it.type == "NOVEL" }.distinctBy { it.sourceId to it.mediaId }.take(MAX_WORKS)
        return reading.sumOf { h -> forWork(user, h, voices, characters, rate) }
    }

    private suspend fun forWork(user: User, h: HistoryRow, voices: JsonObject, characters: Boolean, rate: Float): Int {
        val lang = s.sources.get(h.sourceId)?.language?.lowercase()?.take(2) ?: return 0
        val roles = voices.getAsJsonObject(lang) ?: return 0
        if (roles.entrySet().none { it.value.isJsonPrimitive && it.value.asString.startsWith("w:") }) return 0
        val next = nextChapter(h) ?: return 0
        val text = s.catalog.text(h.sourceId, next).getOrNull() ?: return 0
        return queue(user, text, roles, characters, rate)
    }

    /** The chapter after the one last read, by number (lists come in either order). */
    private suspend fun nextChapter(h: HistoryRow): String? {
        val list = s.catalog.chapters(h.sourceId, h.mediaId, null).getOrNull()?.chapters ?: return null
        val current = list.firstOrNull { it.id == h.chapterId } ?: return null
        return list.filter { it.number > current.number }.minByOrNull { it.number }?.url
    }

    private suspend fun queue(user: User, text: String, roles: JsonObject, characters: Boolean, rate: Float): Int =
        plan(text, roles, characters).entries.sumOf { (v, texts) ->
            s.voices.prefetch(user, texts, v, rate, priority = JobPriority.BACKGROUND, limit = MAX_PIECES, maxPending = MAX_PIECES, ttlMs = TTL_MS)
        }

    /** The pieces of [text] to prepare, grouped by the worker voice that will read them. */
    internal fun plan(text: String, roles: JsonObject, characters: Boolean): Map<String, List<String>> {
        fun voice(role: String) = roles.get(role)?.takeIf { it.isJsonPrimitive }?.asString
        val byVoice = LinkedHashMap<String, MutableList<String>>()
        for (paragraph in SpeechPlan.paragraphs(text)) {
            for (piece in SpeechPlan.roles(paragraph, characters)) {
                // A role without a voice of its own uses the reader's default (a light VPS voice): nothing to prepare.
                val v = voice(piece.role) ?: continue
                if (v.startsWith("w:")) byVoice.getOrPut(v) { mutableListOf() } += piece.text
            }
        }
        return byVoice
    }

    companion object {
        const val NIGHT_START = 3
        const val NIGHT_END = 6
        private const val MAX_WORKS = 5
        private const val MAX_PIECES = 300
        private const val TTL_MS = 12 * 3_600_000L
    }
}
