package com.nelson.yomukai.worker

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * High-quality voices on the home computer. By default Pocket TTS (Kyutai, CPU-friendly) through its command line,
 * multilingual since April 2026: Spanish by default (its own Spanish voice «lola», and its other voices speaking
 * Spanish with --language spanish), English, French, German, Portuguese and Italian. Older Pocket TTS versions
 * without --language keep the English voices only. Every result is levelled to the same loudness (ffmpeg loudnorm)
 * so pieces of a chapter never jump in volume (F6.1).
 *
 * As before:
 * any other engine can be plugged in with WORKER_TTS_COMMAND, a template whose placeholders {text}, {voice} and {out}
 * are replaced inside each argument (no shell: the text can never become a command). The voices offered are
 * WORKER_TTS_VOICES ("id:lang:gender:Nombre,..."); the speed is applied afterwards with ffmpeg's atempo when asked.
 * Runs at low priority like every worker process.
 */
class TtsHandler(
    private val command: List<String>? = template(System.getenv("WORKER_TTS_COMMAND")),
    private val configuredVoices: List<Voice>? = parseVoices(System.getenv("WORKER_TTS_VOICES")),
    /** Whether the installed Pocket TTS understands --language (null: ask it once). */
    private val multilingual: Boolean? = null
) : JobHandler {
    /** [engineVoice]: the name the engine knows it by (Pocket's «alba» for «es_alba»). */
    data class Voice(val id: String, val lang: String, val gender: String, val label: String, val engineVoice: String = id)

    override val type = "tts"

    private val pocketLanguages: Boolean by lazy { multilingual ?: Capabilities.helpMentions("pocket-tts", "--language", "generate", "--help") }

    private val effectiveCommand: List<String>? by lazy {
        command ?: (if (pocketLanguages) POCKET_MULTI else POCKET).takeIf { multilingual != null || Capabilities.installed("pocket-tts", "--help") }
    }

    /** What is offered: the configured voices, or Pocket's (Spanish first when this Pocket TTS speaks it). */
    val voices: List<Voice> get() = configuredVoices ?: if (command == null && pocketLanguages) POCKET_MULTI_VOICES else POCKET_VOICES

    override fun available(): Boolean = effectiveCommand != null

    /** The voices this worker offers, for the server's voice list. */
    fun info(): JsonArray = JsonArray().apply {
        voices.forEach { v -> add(JsonObject().apply { addProperty("id", v.id); addProperty("lang", v.lang); addProperty("gender", v.gender); addProperty("label", v.label) }) }
    }

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val text = job.payload.get("text")?.asString.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text.length > MAX_CHARS) throw PermanentJobFailure("Texto vacío o demasiado largo")
        val requested = job.payload.get("voice")?.asString.orEmpty()
        // "clone:<id>": a voice per character, cloned from the server's sample (fetched once, kept in the work folder).
        val voice = if (requested.startsWith(CLONE)) {
            if (!canClone) throw PermanentJobFailure("Este worker no puede clonar voces")
            val id = requested.removePrefix(CLONE).toLongOrNull() ?: throw PermanentJobFailure("Muestra no válida")
            (context.voiceSample(id) ?: throw PermanentJobFailure("No se pudo descargar la muestra de voz")).absolutePath
        } else {
            if (voices.none { it.id == requested }) throw PermanentJobFailure("Voz desconocida en este worker")
            requested
        }
        val rate = (job.payload.get("rate")?.asDouble ?: 1.0).coerceIn(0.5, 2.0)
        val raw = context.newFile(".wav")
        // «es_alba» is Pocket's voice «alba» speaking Spanish; the language comes from the voice (or the job, for clones).
        val known = voices.firstOrNull { it.id == requested }
        val lang = known?.lang ?: job.payload.get("lang")?.asString?.take(2)?.lowercase() ?: "es"
        val engineVoice = known?.engineVoice ?: voice
        val args = (effectiveCommand ?: throw PermanentJobFailure("No hay motor de voz")).map { a ->
            a.replace("{text}", text).replace("{voice}", engineVoice).replace("{out}", raw.absolutePath).replace("{language}", LANGUAGES[lang] ?: "english")
        }
        val result = Processes.run(args, timeoutMs = 5 * 60_000L)
        if (result.exitCode != 0 || raw.length() < 1_000) error("El motor de voz falló: ${result.output.takeLast(300)}")
        if (!Capabilities.installed("ffmpeg", "-version")) return JobOutput(raw, "audio/wav")
        val out = context.newFile(".wav")
        val ff = Processes.run(listOf("ffmpeg", "-y", "-v", "error", "-i", raw.absolutePath, "-filter:a", filters(rate), out.absolutePath), timeoutMs = 120_000)
        return if (ff.exitCode == 0 && out.length() > 1_000) JobOutput(out, "audio/wav") else JobOutput(raw, "audio/wav")
    }

    /** Pocket TTS clones a voice from a short WAV given as --voice; other engines plugged in may not. */
    val canClone: Boolean get() = command == null && effectiveCommand != null

    companion object {
        const val MAX_CHARS = 2_000
        const val CLONE = "clone:"

        /** Pocket TTS's command line (pip install pocket-tts). If its flags change, set WORKER_TTS_COMMAND. */
        val POCKET = listOf("pocket-tts", "generate", "--voice", "{voice}", "--text", "{text}", "--output-path", "{out}")

        /** Pocket TTS with a language (pip install -U pocket-tts, from April 2026). */
        val POCKET_MULTI = listOf("pocket-tts", "generate", "--language", "{language}", "--voice", "{voice}", "--text", "{text}", "--output-path", "{out}")

        /** The --language names of Pocket TTS. */
        val LANGUAGES = mapOf("es" to "spanish", "en" to "english", "fr" to "french", "de" to "german", "pt" to "portuguese", "it" to "italian", "nl" to "dutch")

        /**
         * Pocket TTS speaking Spanish first (the default for Spanish texts): its native Spanish voice, then some of its
         * clearest voices in Spanish, then the other languages and English. scripts/tts-compare.sh makes samples of each.
         */
        val POCKET_MULTI_VOICES = listOf(
            Voice("lola", "es", "f", "Lola (nativa)"), Voice("es_alba", "es", "f", "Alba", "alba"), Voice("es_marius", "es", "m", "Marius", "marius"),
            Voice("es_jean", "es", "m", "Jean", "jean"), Voice("es_fantine", "es", "f", "Fantine", "fantine"), Voice("es_george", "es", "m", "George", "george"),
            Voice("es_vera", "es", "f", "Vera", "vera"),
            Voice("estelle", "fr", "f", "Estelle"), Voice("juergen", "de", "m", "Jürgen"), Voice("rafael", "pt", "m", "Rafael"), Voice("giovanni", "it", "m", "Giovanni")
        ) + listOf(
            "alba" to "f", "marius" to "m", "javert" to "m", "jean" to "m", "fantine" to "f", "cosette" to "f", "eponine" to "f", "azelma" to "f"
        ).map { (id, g) -> Voice(id, "en", g, id.replaceFirstChar { it.uppercase() }) }

        /** Speed (when asked) and the same loudness for every piece: -16 LUFS, peaks under -1.5 dB. */
        fun filters(rate: Double): String = buildList {
            if (kotlin.math.abs(rate - 1.0) >= 0.01) add("atempo=$rate")
            add("loudnorm=I=-16:TP=-1.5:LRA=11")
        }.joinToString(",")

        /** Pocket TTS's built-in voices (English), for versions without --language. */
        val POCKET_VOICES = listOf(
            Voice("alba", "en", "f", "Alba"), Voice("marius", "en", "m", "Marius"), Voice("javert", "en", "m", "Javert"),
            Voice("jean", "en", "m", "Jean"), Voice("fantine", "en", "f", "Fantine"), Voice("cosette", "en", "f", "Cosette"),
            Voice("eponine", "en", "f", "Eponine"), Voice("azelma", "en", "f", "Azelma")
        )

        /** "a b {text}" -> ["a", "b", "{text}"]; null when not set. */
        fun template(raw: String?): List<String>? = raw?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))

        private val VOICE_ID = Regex("[a-z0-9_-]{1,40}")

        /** "id:lang:gender:Nombre,..." -> voices; null when not set or nothing valid. */
        fun parseVoices(raw: String?): List<Voice>? = raw?.split(',')?.mapNotNull { item ->
            val p = item.trim().split(':')
            val id = p.getOrNull(0)?.lowercase()?.takeIf { VOICE_ID.matches(it) } ?: return@mapNotNull null
            Voice(id, p.getOrNull(1)?.take(2)?.lowercase() ?: "es", p.getOrNull(2)?.take(1)?.lowercase().orEmpty(), p.getOrNull(3)?.take(60) ?: id)
        }?.takeIf { it.isNotEmpty() }
    }
}
