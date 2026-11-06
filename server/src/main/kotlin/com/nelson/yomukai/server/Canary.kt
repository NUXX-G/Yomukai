package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.DiagnosticStep
import com.nelson.yomukai.data.source.SourceDiagnostics
import com.nelson.yomukai.data.source.SourceHealth
import com.nelson.yomukai.data.source.SourceVerdict
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

data class SourceHealthRow(
    val sourceId: String,
    val status: SourceHealth,
    val summary: String,
    val checkedAt: Long,
    /** When it last went from fine to broken; null while fine. */
    val failingSince: Long?,
    val lastOkAt: Long?
)

/** What the canary last found for each source, in SQLite so the server and the cron command share it. */
class SourceHealthStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun all(): List<SourceHealthRow> = db.tx { query("SELECT * FROM source_health") { row() } }

    suspend fun get(id: String): SourceHealthRow? = db.tx { queryOne("SELECT * FROM source_health WHERE source_id = ?", id) { row() } }

    /** Saves a new result; returns the previous one, to tell transitions apart. */
    suspend fun record(id: String, status: SourceHealth, summary: String, steps: String): SourceHealthRow? {
        val now = clock()
        return db.tx {
            val previous = queryOne("SELECT * FROM source_health WHERE source_id = ?", id) { row() }
            val failingSince = when {
                status == SourceHealth.DEGRADED -> previous?.failingSince ?: now
                else -> null
            }
            val lastOk = if (status != SourceHealth.DEGRADED) now else previous?.lastOkAt
            update(
                """INSERT INTO source_health(source_id, status, summary, steps, checked_at, failing_since, last_ok_at) VALUES(?,?,?,?,?,?,?)
                   ON CONFLICT(source_id) DO UPDATE SET status = excluded.status, summary = excluded.summary, steps = excluded.steps,
                   checked_at = excluded.checked_at, failing_since = excluded.failing_since, last_ok_at = excluded.last_ok_at""",
                id, status.name, summary.take(1_000), steps.take(8_000), now, failingSince, lastOk
            )
            previous
        }
    }

    suspend fun steps(id: String): String? = db.tx { queryOne("SELECT steps FROM source_health WHERE source_id = ?", id) { getString(1) } }

    private fun java.sql.ResultSet.row() = SourceHealthRow(
        getString("source_id"), runCatching { SourceHealth.valueOf(getString("status")) }.getOrDefault(SourceHealth.OK),
        getString("summary"), getLong("checked_at"), longOrNull("failing_since"), longOrNull("last_ok_at")
    )
}

/**
 * Walks every source through the same steps a reader takes (latest, search, details, chapters, content) with
 * [SourceDiagnostics], decides with [SourceVerdict], stores the result and warns the admin when a source breaks or
 * comes back. Gentle on the sites: one source at a time, with a pause between them.
 */
class Canary(
    private val store: SourceHealthStore,
    private val notifier: Notifier,
    private val pauseMs: Long = 20_000,
    private val onBroken: suspend (CatalogueSource, List<DiagnosticStep>) -> Unit = { _, _ -> },
    private val diagnose: suspend (CatalogueSource) -> List<DiagnosticStep> = { SourceDiagnostics.run(it) }
) {
    private val log = LoggerFactory.getLogger("Canary")

    data class Outcome(val source: String, val status: SourceHealth, val summary: String, val changed: Boolean)

    suspend fun check(source: CatalogueSource): Outcome {
        val steps = runCatching { diagnose(source) }.getOrElse { listOf(DiagnosticStep("Prueba", false, it.message ?: "Error")) }
        val status = SourceVerdict.of(steps)
        val summary = SourceVerdict.summary(steps)
        val previous = store.record(source.id, status, summary, steps.joinToString("\n") { "${if (it.ok) "OK" else "FALLO"} ${it.name}: ${it.detail}" })
        val wasBroken = previous?.status == SourceHealth.DEGRADED
        val broken = status == SourceHealth.DEGRADED
        when {
            broken && !wasBroken -> {
                log.warn("Fuente {} rota: {}", source.id, summary)
                notifier.admin("canary-${source.id}-down", "Fuente caída: ${source.name}", "$summary\nSe oculta en la web y la app hasta que vuelva a funcionar.", urgent = true)
                runCatching { onBroken(source, steps) }.onFailure { log.warn("No se pudo pedir la reparación de {}", source.id, it) }
            }
            !broken && wasBroken -> {
                log.info("Fuente {} recuperada", source.id)
                notifier.admin("canary-${source.id}-up", "Fuente recuperada: ${source.name}", "Vuelve a funcionar y se muestra de nuevo.")
            }
        }
        return Outcome(source.id, status, summary, broken != wasBroken)
    }

    suspend fun checkAll(sources: List<CatalogueSource>): List<Outcome> = sources.mapIndexed { i, source ->
        if (i > 0) delay(pauseMs)
        check(source).also { log.info("Canario {}: {} — {}", it.source, it.status, it.summary) }
    }
}

/** The set of sources to hide, read from the store once a minute by the running server. */
suspend fun refreshDegraded(store: SourceHealthStore, sources: com.nelson.yomukai.data.source.SourceManager) {
    sources.degraded = store.all().filter { it.status == SourceHealth.DEGRADED }.associate { it.sourceId to it.summary }
}
