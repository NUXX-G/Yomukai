package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import java.io.File

/** A job handed out by the VPS. The payload comes from the VPS, but the worker still checks every field it uses. */
data class ClaimedJob(val id: Long, val type: String, val payload: JsonObject, val attempt: Int)

/** What a job produced: a file to upload (deleted afterwards) with its type. */
data class JobOutput(val file: File, val contentType: String)

/** The job cannot succeed however often it is retried (bad input, unsupported content...). */
class PermanentJobFailure(message: String) : Exception(message)

/** One kind of heavy work. [available] is checked at start-up: only installed engines are announced to the VPS. */
interface JobHandler {
    val type: String
    fun available(): Boolean = true
    suspend fun run(job: ClaimedJob, context: JobContext): JobOutput

    /** Frees memory held between jobs; called every minute. */
    fun unloadIfIdle() {}
}

/** Per-job helpers: a private folder and the shared HTTP client for fetching inputs. */
class JobContext(
    val workDir: File,
    val http: okhttp3.OkHttpClient,
    /** Asks the VPS for a fresh, direct video of an episode (addresses expire while a job waits). */
    val freshVideo: (sourceId: String, episodeUrl: String, preferredServer: String) -> JsonObject? = { _, _, _ -> null },
    /** A voice sample of the server (voices per character), downloaded once into the work folder; null if it failed. */
    val voiceSample: (id: Long) -> File? = { null }
) {
    fun newFile(suffix: String): File = File.createTempFile("job-", suffix, workDir)
}

/** Sends the payload back: lets the admin check the whole round trip with the "probar worker" button. */
class EchoHandler : JobHandler {
    override val type = "echo"
    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val file = context.newFile(".json")
        file.writeText(job.payload.toString())
        return JobOutput(file, "application/json")
    }
}

/**
 * Proposes a fix for a broken source as a pull request, with scripts/auto-repair.sh in a local clone of the repository
 * (WORKER_REPAIR_REPO). Needs git, gh and Claude Code on this machine; never merges anything.
 */
class SourceRepairHandler(private val repo: java.io.File?) : JobHandler {
    override val type = "source_repair"
    override fun available(): Boolean = repo != null && java.io.File(repo, "scripts/auto-repair.sh").isFile &&
        listOf("git", "gh", "claude").all { Capabilities.installed(it) }

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val id = job.payload.get("sourceId")?.asString.orEmpty()
        if (!Regex("[a-z0-9_]{2,40}").matches(id)) throw PermanentJobFailure("Id de fuente no válido")
        val report = context.newFile(".txt").apply { writeText(job.payload.get("report")?.asString.orEmpty().take(8_000)) }
        try {
            val result = Processes.run(
                listOf("bash", "scripts/auto-repair.sh", id, report.absolutePath), timeoutMs = 45 * 60_000L, dir = repo,
                env = mapOf("WORKER_REPAIR_REPO" to repo!!.absolutePath)
            )
            val json = result.output.lineSequence().lastOrNull { it.trim().startsWith("{\"pr\"") }
                ?: throw PermanentJobFailure("La reparación no abrió ningún PR: " + result.output.takeLast(500))
            val out = context.newFile(".json")
            out.writeText(json.trim())
            return JobOutput(out, "application/json")
        } finally {
            report.delete()
        }
    }
}

/** The handlers this build knows; later phases add theirs here. */
fun allHandlers(config: WorkerConfig): List<JobHandler> = listOf(
    EchoHandler(), SourceRepairHandler(config.repairRepo), FingerprintHandler(), TtsHandler(), OcrHandler(), WhisperHandler(), TranslateHandler(), DictionaryHandler(),
    JellyfinDownloadHandler(), CbzExportHandler()
)
    .filter { config.onlyEngines.isEmpty() || it.type in config.onlyEngines || it.type == "echo" }
    .filter { it.available() }
