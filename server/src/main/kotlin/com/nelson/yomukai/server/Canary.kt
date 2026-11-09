package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.DiagnosticStep
import com.nelson.yomukai.data.source.SourceDiagnostics
import com.nelson.yomukai.data.source.SourceHealth
import com.nelson.yomukai.data.source.SourceVerdict
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val diagnose: suspend (CatalogueSource) -> List<DiagnosticStep> = { SourceDiagnostics.run(it) },
    /** Where the source's home page ends up after redirects (null = could not tell); JSON sources follow by themselves. */
    private val landing: suspend (CatalogueSource) -> String? = { null },
    /** Moving a source to a new domain and back (see [DomainAliases]); null = never moved by itself. */
    private val domains: DomainMover? = null
) {
    /** What the canary needs to try a new domain for a source: set and undo the move, and visit an address. */
    interface DomainMover {
        suspend fun isMoved(host: String): Boolean
        suspend fun move(from: String, to: String)
        suspend fun undo(from: String)

        /** A guessed domain where the source works: kept for the admin to confirm, never applied by itself. */
        suspend fun suggest(from: String, to: String)

        /** The address a visit to [url] ends at, or null when nothing answers there. */
        suspend fun visit(url: String): String?
    }

    /** When each source last had its other domains tried (once a day at most). */
    private val guessedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val log = LoggerFactory.getLogger("Canary")

    data class Outcome(val source: String, val status: SourceHealth, val summary: String, val changed: Boolean)

    suspend fun check(source: CatalogueSource): Outcome {
        val moved = movedTo(source)
        var steps = runCatching { diagnose(source) }.getOrElse { listOf(DiagnosticStep("Prueba", false, it.message ?: "Error")) }
        if (SourceVerdict.of(steps) == SourceHealth.DEGRADED || moved != null) {
            // Nothing found here may stop the check of this source or the next ones.
            runCatching { newDomain(source, moved) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                ?.let { steps = it }
        }
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

    /**
     * The site moved. Where its own domain now sends visitors ([redirected]) is trusted as the site's word: if the whole
     * source works there it is used at once (and the passing steps returned). If its domain answers nothing, the same
     * name with other usual endings is tried, but a guess is only suggested to the admin, never applied (anyone may
     * own that name). A redirect to a forum or a parked page never passes.
     */
    private suspend fun newDomain(source: CatalogueSource, redirected: String?): List<DiagnosticStep>? {
        val mover = domains ?: return null
        if (source is com.nelson.yomukai.data.source.declarative.DeclarativeSource) return null
        val base = source.baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
        if (mover.isMoved(base)) return null
        if (redirected != null) {
            val host = runCatching { DomainAliases.host(redirected) }.getOrNull()
            val steps = host?.let { works(mover, source, base, it, keep = true) }
            if (host != null && steps != null) {
                notifier.admin("domain-${source.id}-$host", "${source.name} se ha mudado a $host",
                    "Su web redirige a $host y la fuente funciona ahí (listas, ficha, capítulos y páginas): ya se usa ese dominio.")
                return steps
            }
            notifier.admin("domain-${source.id}-$redirected", "${source.name} redirige a $redirected",
                "Su web redirige a $redirected, pero la fuente no funciona ahí. Si su web se mudó, pon el dominio nuevo en Administración.")
            return null
        }
        if (System.currentTimeMillis() - (guessedAt[source.id] ?: 0L) < GUESS_EVERY_MS || mover.visit(source.baseUrl) != null) return null
        guessedAt[source.id] = System.currentTimeMillis()
        val answering = DomainGuesses.of(base).filter { candidate ->
            mover.visit("https://$candidate/")?.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.") == candidate.removePrefix("www.")
        }
        answering.take(MAX_CANDIDATES).firstOrNull { works(mover, source, base, it, keep = false) != null }?.let { host ->
            mover.suggest(base, host)
            notifier.admin("domain-guess-${source.id}-$host", "¿${source.name} está ahora en $host?",
                "Su dominio ya no responde y la fuente funciona en $host. Confírmalo en Administración › Webs que se han mudado de dominio.")
        }
        return null
    }

    /**
     * The source's steps with its requests sent to [host] for a moment; the move is undone afterwards whatever happens
     * (also when the check is cancelled) unless [keep] and every step that matters passed. Null when it does not work.
     */
    private suspend fun works(mover: DomainMover, source: CatalogueSource, base: String, host: String, keep: Boolean): List<DiagnosticStep>? {
        mover.move(base, host)
        var kept = false
        try {
            val steps = runCatching { diagnose(source) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            val ok = steps != null && SourceVerdict.of(steps) != SourceHealth.DEGRADED
            kept = ok && keep
            return steps.takeIf { ok }
        } finally {
            if (!kept) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mover.undo(base) }
        }
    }

    /** The new host when the source's site now sends visitors to another domain (www. and paths aside). */
    internal suspend fun movedTo(source: CatalogueSource): String? {
        if (source is com.nelson.yomukai.data.source.declarative.DeclarativeSource) return null
        val base = source.baseUrl.toHttpUrlOrNull() ?: return null
        val final = runCatching { landing(source) }.getOrNull()?.toHttpUrlOrNull() ?: return null
        fun root(h: String) = h.lowercase().removePrefix("www.")
        return root(final.host).takeIf { it != root(base.host) && !it.endsWith("." + root(base.host)) }
    }

    private companion object {
        const val GUESS_EVERY_MS = 24 * 60 * 60_000L
        const val MAX_CANDIDATES = 4
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
