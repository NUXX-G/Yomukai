package com.nelson.yomukai.server

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * One numbered step of the database schema. [apply] must be idempotent: it may meet a database where part of
 * it already exists (a crash halfway, or a database made by the code from before versioned migrations).
 * Every new table, column or index goes here as a new entry at the end of [Migrations.ALL]; old entries never change.
 */
class Migration(val version: Int, val description: String, val apply: Connection.() -> Unit)

object Migrations {
    private val log = LoggerFactory.getLogger("Migrations")

    val ALL: List<Migration> = listOf(
        Migration(1, "Esquema inicial (usuarios, sesiones, biblioteca, progreso, historial, ajustes, categorías, marcadores)") {
            BASELINE.forEach(::exec)
            // Columns added to the first tables before migrations were versioned.
            addColumn("library", "categories", "TEXT NOT NULL DEFAULT '[]'")
            addColumn("library", "updated_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn("library", "deleted", "INTEGER NOT NULL DEFAULT 0")
            addColumn("library", "rev", "INTEGER NOT NULL DEFAULT 0")
            addColumn("progress", "rev", "INTEGER NOT NULL DEFAULT 0")
            addColumn("history", "updated_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn("history", "deleted", "INTEGER NOT NULL DEFAULT 0")
            addColumn("history", "rev", "INTEGER NOT NULL DEFAULT 0")
            addColumn("settings", "shared_updated_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn("settings", "rev", "INTEGER NOT NULL DEFAULT 0")
            addColumn("users", "suspended_at", "INTEGER")
            addColumn("users", "suspended_reason", "TEXT")
            listOf("library", "progress", "history", "bookmarks", "categories").forEach { t ->
                exec("CREATE INDEX IF NOT EXISTS ${t}_rev ON $t(user_id, rev)")
            }
            // Rows from before revisions existed: give them one so the first sync of a device sees them.
            listOf("library", "progress", "history", "settings").forEach { t -> exec("UPDATE $t SET rev = 1 WHERE rev = 0") }
            exec("UPDATE library SET updated_at = added_at WHERE updated_at = 0")
            // The web used "PLAN" before it shared the app's names.
            exec("UPDATE library SET reading_status = 'PLAN_TO_READ' WHERE reading_status = 'PLAN'")
            exec("UPDATE history SET updated_at = read_at WHERE updated_at = 0")
        }
    )

    val LATEST: Int get() = ALL.last().version

    /** Applies the steps this database has not seen, each in its own transaction. Returns the versions applied. */
    fun run(connection: Connection, migrations: List<Migration> = ALL): List<Int> {
        require(migrations.map { it.version } == (1..migrations.size).toList()) { "Las migraciones deben ir numeradas 1, 2, 3… sin huecos" }
        connection.exec(
            "CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at INTEGER NOT NULL)"
        )
        val current = currentVersion(connection)
        check(current <= migrations.size) {
            "La base de datos es de una versión más nueva de Yomukai (esquema $current, este programa conoce hasta ${migrations.size}). " +
                "Actualiza Yomukai o restaura una copia de seguridad."
        }
        val applied = mutableListOf<Int>()
        for (m in migrations.drop(current)) {
            val auto = connection.autoCommit
            connection.autoCommit = false
            try {
                m.apply(connection)
                connection.prepareStatement("INSERT INTO schema_version(version, description, applied_at) VALUES(?,?,?)").use {
                    it.setInt(1, m.version); it.setString(2, m.description); it.setLong(3, System.currentTimeMillis())
                    it.executeUpdate()
                }
                connection.commit()
            } catch (e: Throwable) {
                connection.rollback()
                throw IllegalStateException("Falló la migración ${m.version} (${m.description})", e)
            } finally {
                connection.autoCommit = auto
            }
            log.info("Migración {} aplicada: {}", m.version, m.description)
            applied += m.version
        }
        return applied
    }

    fun currentVersion(connection: Connection): Int = connection.createStatement().use { s ->
        s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { it.next(); it.getInt(1) }
    }

    /** The tables as the first server created them; later changes are columns added above or new migrations. */
    private val BASELINE = listOf(
        """CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
           pass_hash TEXT, is_admin INTEGER NOT NULL DEFAULT 0, is_guest INTEGER NOT NULL DEFAULT 0,
           created_at INTEGER NOT NULL, expires_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS users_expiry ON users(is_guest, expires_at)",
        """CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
           created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS library(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, source_id TEXT NOT NULL,
           media_id TEXT NOT NULL, title TEXT NOT NULL, cover TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL DEFAULT '',
           author TEXT NOT NULL DEFAULT '', genres TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL,
           reading_status TEXT NOT NULL DEFAULT 'NONE', PRIMARY KEY(user_id, source_id, media_id))""",
        """CREATE TABLE IF NOT EXISTS progress(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, source_id TEXT NOT NULL,
           chapter_id TEXT NOT NULL, media_id TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0, page INTEGER NOT NULL DEFAULT 0,
           total INTEGER NOT NULL DEFAULT 0, fraction REAL NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
           PRIMARY KEY(user_id, source_id, chapter_id))""",
        "CREATE INDEX IF NOT EXISTS progress_media ON progress(user_id, source_id, media_id)",
        """CREATE TABLE IF NOT EXISTS history(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, source_id TEXT NOT NULL,
           media_id TEXT NOT NULL, chapter_id TEXT NOT NULL, chapter_title TEXT NOT NULL, title TEXT NOT NULL, cover TEXT NOT NULL,
           type TEXT NOT NULL, read_at INTEGER NOT NULL, PRIMARY KEY(user_id, source_id, media_id))""",
        "CREATE INDEX IF NOT EXISTS history_recent ON history(user_id, read_at)",
        "CREATE TABLE IF NOT EXISTS settings(user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, json TEXT NOT NULL)",
        """CREATE TABLE IF NOT EXISTS categories(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, name TEXT NOT NULL,
           sort_order INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
           rev INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, name))""",
        """CREATE TABLE IF NOT EXISTS bookmarks(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, source_id TEXT NOT NULL,
           chapter_id TEXT NOT NULL, position INTEGER NOT NULL, media_id TEXT NOT NULL, chapter_title TEXT NOT NULL,
           label TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
           rev INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, source_id, chapter_id, position))""",
        "CREATE INDEX IF NOT EXISTS bookmarks_media ON bookmarks(user_id, source_id, media_id)",
        "CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY, value INTEGER NOT NULL)",
        "INSERT OR IGNORE INTO counters(name, value) VALUES('rev', 1)"
    )
}

internal fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun Connection.columnsOf(table: String): Set<String> = createStatement().use { s ->
    s.executeQuery("PRAGMA table_info($table)").use { rs ->
        buildSet { while (rs.next()) add(rs.getString("name")) }
    }
}

/** SQLite has no ADD COLUMN IF NOT EXISTS. */
internal fun Connection.addColumn(table: String, column: String, definition: String) {
    if (column !in columnsOf(table)) exec("ALTER TABLE $table ADD COLUMN $column $definition")
}
