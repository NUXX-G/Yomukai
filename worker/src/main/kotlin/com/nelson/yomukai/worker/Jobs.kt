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
class JobContext(val workDir: File, val http: okhttp3.OkHttpClient) {
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

/** The handlers this build knows; later phases add theirs here. */
fun allHandlers(config: WorkerConfig): List<JobHandler> = listOf<JobHandler>(EchoHandler())
    .filter { config.onlyEngines.isEmpty() || it.type in config.onlyEngines || it.type == "echo" }
    .filter { it.available() }
