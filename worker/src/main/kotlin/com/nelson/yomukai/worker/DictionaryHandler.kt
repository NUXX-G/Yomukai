package com.nelson.yomukai.worker

import com.nelson.yomukai.data.learning.Dictionary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * Builds a language's offline dictionary: streams kaikki.org's Wiktionary dump (gigabytes, never kept on disk) and
 * writes the few fields worth keeping into a small SQLite file ([Dictionary.SCHEMA]) that the server serves.
 */
class DictionaryHandler(private val dumpUrl: (String) -> String? = Dictionary::dumpUrl) : JobHandler {
    override val type = "dictionary"

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val lang = job.payload.get("lang")?.asString.orEmpty()
        val url = dumpUrl(lang) ?: throw PermanentJobFailure("Idioma sin diccionario")
        val out = context.newFile(".sqlite")
        out.delete()
        val client = context.http.newBuilder().readTimeout(5, TimeUnit.MINUTES).callTimeout(0, TimeUnit.MILLISECONDS).build()
        DriverManager.getConnection("jdbc:sqlite:${out.absolutePath}").use { db ->
            db.createStatement().use { it.execute("PRAGMA journal_mode=OFF"); it.execute("PRAGMA synchronous=OFF"); it.execute(Dictionary.SCHEMA) }
            db.autoCommit = false
            val insert = db.prepareStatement("INSERT INTO entries(key, word, pos, pron, glosses, es) VALUES(?,?,?,?,?,?)")
            var count = 0
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("kaikki.org respondió ${r.code}")
                val reader = (r.body ?: error("Respuesta vacía")).byteStream().reader(Charsets.UTF_8).buffered(BUFFER)
                for (line in reader.lineSequence()) {
                    if (line.length > MAX_LINE) continue
                    val e = Dictionary.parse(line) ?: continue
                    insert.setString(1, Dictionary.key(e.word)); insert.setString(2, e.word); insert.setString(3, e.pos)
                    insert.setString(4, e.pron); insert.setString(5, e.glosses.joinToString(Dictionary.SEPARATOR))
                    insert.setString(6, e.es.joinToString(Dictionary.SEPARATOR))
                    insert.addBatch()
                    if (++count % BATCH == 0) { insert.executeBatch(); db.commit(); currentCoroutineContext().ensureActive() }
                    if (count >= MAX_ENTRIES) break
                }
            }
            insert.executeBatch()
            db.commit()
            if (count == 0) throw PermanentJobFailure("El diccionario salió vacío")
            db.autoCommit = true
            db.createStatement().use { it.execute(Dictionary.INDEX); it.execute("VACUUM") }
        }
        return JobOutput(out, "application/octet-stream")
    }

    private companion object {
        const val BUFFER = 1 shl 16
        const val BATCH = 5_000
        const val MAX_LINE = 2_000_000
        const val MAX_ENTRIES = 3_000_000
    }
}
