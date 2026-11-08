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
        },
        Migration(2, "Workers, cola de trabajos, resultados cacheados, flags de funcionalidad y tráfico mensual") {
            exec("CREATE TABLE IF NOT EXISTS feature_flags(name TEXT PRIMARY KEY, enabled INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            exec(
                """CREATE TABLE IF NOT EXISTS workers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
                   token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, revoked_at INTEGER, last_seen INTEGER,
                   capabilities TEXT NOT NULL DEFAULT '{}', version TEXT NOT NULL DEFAULT '', paused INTEGER NOT NULL DEFAULT 0)"""
            )
            exec(
                """CREATE TABLE IF NOT EXISTS jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, priority INTEGER NOT NULL,
                   payload TEXT NOT NULL, dedup_key TEXT, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                   max_attempts INTEGER NOT NULL DEFAULT 3, worker_id INTEGER REFERENCES workers(id) ON DELETE SET NULL,
                   requested_by INTEGER REFERENCES users(id) ON DELETE SET NULL, prefers_gpu INTEGER NOT NULL DEFAULT 0,
                   created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, not_before INTEGER NOT NULL DEFAULT 0,
                   expires_at INTEGER NOT NULL, lease_until INTEGER, error TEXT, result_key TEXT)"""
            )
            exec("CREATE INDEX IF NOT EXISTS jobs_pending ON jobs(status, priority DESC, created_at)")
            // At most one live job per piece of work: asking again joins the job already queued or running.
            exec("CREATE UNIQUE INDEX IF NOT EXISTS jobs_dedup ON jobs(dedup_key) WHERE status IN ('queued', 'running')")
            exec(
                """CREATE TABLE IF NOT EXISTS results(key TEXT PRIMARY KEY, file TEXT NOT NULL, size INTEGER NOT NULL,
                   content_type TEXT NOT NULL, created_at INTEGER NOT NULL, last_access INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS results_lru ON results(last_access)")
            exec(
                """CREATE TABLE IF NOT EXISTS bandwidth(month TEXT PRIMARY KEY, bytes INTEGER NOT NULL DEFAULT 0,
                   warned80 INTEGER NOT NULL DEFAULT 0, warned100 INTEGER NOT NULL DEFAULT 0)"""
            )
        },
        Migration(3, "Permiso de cada worker para llevarse copias de la base de datos") {
            addColumn("workers", "can_backup", "INTEGER NOT NULL DEFAULT 0")
        },
        Migration(4, "Sesiones guardadas como hash con dispositivo e IP, 2FA, invitaciones y registro de auditoría") {
            exec(
                """CREATE TABLE IF NOT EXISTS user_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, token_hash TEXT NOT NULL UNIQUE,
                   user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                   device TEXT NOT NULL DEFAULT '', ip TEXT NOT NULL DEFAULT '')"""
            )
            exec("CREATE INDEX IF NOT EXISTS user_sessions_user ON user_sessions(user_id)")
            // Existing sessions keep working: their tokens are hashed here, then the plain-text table goes.
            val hasOld = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sessions'") { getString(1) }.isNotEmpty()
            if (hasOld) {
                query("SELECT token, user_id, created_at, last_seen FROM sessions") { listOf(getString(1), getLong(2), getLong(3), getLong(4)) }.forEach { r ->
                    update(
                        "INSERT OR IGNORE INTO user_sessions(token_hash, user_id, created_at, last_seen) VALUES(?,?,?,?)",
                        sha256Hex(r[0] as String), r[1], r[2], r[3]
                    )
                }
                exec("DROP TABLE sessions")
            }
            addColumn("users", "totp_secret", "TEXT")
            addColumn("users", "totp_enabled", "INTEGER NOT NULL DEFAULT 0")
            addColumn("users", "totp_last_step", "INTEGER NOT NULL DEFAULT -1")
            exec(
                """CREATE TABLE IF NOT EXISTS recovery_codes(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   code_hash TEXT NOT NULL, used_at INTEGER, PRIMARY KEY(user_id, code_hash))"""
            )
            exec(
                """CREATE TABLE IF NOT EXISTS invites(id INTEGER PRIMARY KEY AUTOINCREMENT, token_hash TEXT NOT NULL UNIQUE,
                   created_by INTEGER REFERENCES users(id) ON DELETE SET NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
                   expires_at INTEGER NOT NULL, used_at INTEGER, used_by INTEGER REFERENCES users(id) ON DELETE SET NULL, revoked INTEGER NOT NULL DEFAULT 0)"""
            )
            exec(
                """CREATE TABLE IF NOT EXISTS audit_log(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor_id INTEGER,
                   actor_name TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '',
                   ip TEXT NOT NULL DEFAULT '')"""
            )
            exec("CREATE INDEX IF NOT EXISTS audit_at ON audit_log(at)")
        },
        Migration(5, "Salud de las fuentes según el canario") {
            exec(
                """CREATE TABLE IF NOT EXISTS source_health(source_id TEXT PRIMARY KEY, status TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '',
                   steps TEXT NOT NULL DEFAULT '', checked_at INTEGER NOT NULL, failing_since INTEGER, last_ok_at INTEGER)"""
            )
        },
        Migration(6, "Obras unificadas: obras, sus páginas en cada fuente, elecciones de capítulo, dónde sigo y relleno") {
            exec(
                """CREATE TABLE IF NOT EXISTS works(key TEXT PRIMARY KEY, anilist_id INTEGER, mal_id INTEGER, type TEXT NOT NULL, title TEXT NOT NULL,
                   alt_titles TEXT NOT NULL DEFAULT '[]', cover TEXT NOT NULL DEFAULT '', banner TEXT NOT NULL DEFAULT '', synopsis TEXT NOT NULL DEFAULT '',
                   genres TEXT NOT NULL DEFAULT '[]', score INTEGER, status TEXT NOT NULL DEFAULT '', episodes INTEGER, chapters INTEGER,
                   next_episode INTEGER, next_airing_at INTEGER, updated_at INTEGER NOT NULL)"""
            )
            exec(
                """CREATE TABLE IF NOT EXISTS work_sources(source_id TEXT NOT NULL, media_id TEXT NOT NULL, work_key TEXT NOT NULL,
                   confidence TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(source_id, media_id))"""
            )
            exec("CREATE INDEX IF NOT EXISTS work_sources_work ON work_sources(work_key)")
            // Library and history keep their (source, title) keys; the work is an extra column filled in the background.
            addColumn("library", "work_key", "TEXT")
            addColumn("history", "work_key", "TEXT")
            exec("CREATE INDEX IF NOT EXISTS library_work ON library(user_id, work_key)")
            exec(
                """CREATE TABLE IF NOT EXISTS work_choices(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, work_key TEXT NOT NULL,
                   number_key TEXT NOT NULL, source_id TEXT NOT NULL, PRIMARY KEY(user_id, work_key, number_key))"""
            )
            exec(
                """CREATE TABLE IF NOT EXISTS adaptation_points(anime_key TEXT NOT NULL, until_episode INTEGER NOT NULL, target_key TEXT NOT NULL,
                   chapter REAL, volume INTEGER, updated_by INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(anime_key, until_episode, target_key))"""
            )
            exec("CREATE INDEX IF NOT EXISTS adaptation_target ON adaptation_points(target_key)")
            exec(
                """CREATE TABLE IF NOT EXISTS adaptation_history(id INTEGER PRIMARY KEY AUTOINCREMENT, anime_key TEXT NOT NULL, until_episode INTEGER NOT NULL,
                   target_key TEXT NOT NULL, before TEXT NOT NULL, after TEXT NOT NULL, changed_by TEXT NOT NULL, changed_at INTEGER NOT NULL)"""
            )
            exec("CREATE TABLE IF NOT EXISTS fillers(slug TEXT PRIMARY KEY, data TEXT NOT NULL, fetched_at INTEGER NOT NULL)")
        },
        Migration(7, "Salas para ver juntos, su chat y el registro de actividad") {
            exec(
                """CREATE TABLE IF NOT EXISTS rooms(code TEXT PRIMARY KEY, owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   name TEXT NOT NULL, persistent INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, last_active INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS rooms_owner ON rooms(owner_id)")
            exec(
                """CREATE TABLE IF NOT EXISTS room_messages(id INTEGER PRIMARY KEY AUTOINCREMENT, room_code TEXT NOT NULL REFERENCES rooms(code) ON DELETE CASCADE,
                   user_id INTEGER, user_name TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, spoiler INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS room_messages_room ON room_messages(room_code, created_at)")
            exec(
                """CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   kind TEXT NOT NULL, data TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS events_user ON events(user_id, kind, created_at)")
            exec("CREATE INDEX IF NOT EXISTS events_time ON events(created_at)")
        },
        Migration(8, "Perfiles, valoraciones, recomendaciones y logros") {
            addColumn("users", "bio", "TEXT NOT NULL DEFAULT ''")
            addColumn("users", "accent", "TEXT NOT NULL DEFAULT ''")
            addColumn("users", "favorite", "TEXT NOT NULL DEFAULT ''")
            addColumn("users", "shown_title", "TEXT NOT NULL DEFAULT ''")
            addColumn("users", "hidden", "INTEGER NOT NULL DEFAULT 0")
            addColumn("users", "private_categories", "TEXT NOT NULL DEFAULT '[]'")
            addColumn("users", "avatar_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn("users", "banner_at", "INTEGER NOT NULL DEFAULT 0")
            exec(
                """CREATE TABLE IF NOT EXISTS ratings(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, work_key TEXT NOT NULL,
                   score INTEGER NOT NULL, review TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, PRIMARY KEY(user_id, work_key))"""
            )
            exec("CREATE INDEX IF NOT EXISTS ratings_work ON ratings(work_key)")
            exec(
                """CREATE TABLE IF NOT EXISTS recommendations(id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   to_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, source_id TEXT NOT NULL, media_id TEXT NOT NULL, work_key TEXT NOT NULL,
                   title TEXT NOT NULL, cover TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
                   seen INTEGER NOT NULL DEFAULT 0, added INTEGER NOT NULL DEFAULT 0)"""
            )
            exec("CREATE INDEX IF NOT EXISTS recommendations_to ON recommendations(to_id, created_at)")
            exec(
                """CREATE TABLE IF NOT EXISTS achievements(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, id TEXT NOT NULL,
                   unlocked_at INTEGER NOT NULL, notified INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, id))"""
            )
            exec("CREATE TABLE IF NOT EXISTS achievement_scans(user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, at INTEGER NOT NULL)")
        },
        Migration(9, "Foro, moderación y notificaciones") {
            exec(
                """CREATE TABLE IF NOT EXISTS forum_threads(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, work_key TEXT,
                   title TEXT NOT NULL, author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at INTEGER NOT NULL,
                   last_post_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, locked INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)"""
            )
            exec("CREATE INDEX IF NOT EXISTS forum_threads_list ON forum_threads(category, deleted, pinned, last_post_at)")
            exec("CREATE INDEX IF NOT EXISTS forum_threads_work ON forum_threads(work_key)")
            exec(
                """CREATE TABLE IF NOT EXISTS forum_posts(id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER NOT NULL REFERENCES forum_threads(id) ON DELETE CASCADE,
                   author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, body TEXT NOT NULL, spoiler_until REAL, quote_id INTEGER,
                   created_at INTEGER NOT NULL, edited_at INTEGER, deleted INTEGER NOT NULL DEFAULT 0)"""
            )
            exec("CREATE INDEX IF NOT EXISTS forum_posts_thread ON forum_posts(thread_id, id)")
            exec(
                """CREATE TABLE IF NOT EXISTS forum_reactions(post_id INTEGER NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
                   user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, emoji TEXT NOT NULL, PRIMARY KEY(post_id, user_id, emoji))"""
            )
            exec("CREATE TABLE IF NOT EXISTS forum_images(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at INTEGER NOT NULL)")
            exec(
                """CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0)"""
            )
            exec("CREATE INDEX IF NOT EXISTS notifications_user ON notifications(user_id, id)")
            addColumn("users", "muted_until", "INTEGER NOT NULL DEFAULT 0")
            addColumn("users", "muted_reason", "TEXT NOT NULL DEFAULT ''")
            addColumn("users", "ntfy_topic", "TEXT")
        },
        Migration(10, "Temas personalizados, su galería y el tema de cada usuario") {
            exec(
                """CREATE TABLE IF NOT EXISTS themes(id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   json TEXT NOT NULL, published INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS themes_owner ON themes(owner_id)")
            exec(
                """CREATE TABLE IF NOT EXISTS theme_uses(theme_id INTEGER NOT NULL REFERENCES themes(id) ON DELETE CASCADE,
                   user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, PRIMARY KEY(theme_id, user_id))"""
            )
            exec("CREATE TABLE IF NOT EXISTS theme_backgrounds(id INTEGER PRIMARY KEY AUTOINCREMENT, owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at INTEGER NOT NULL)")
            addColumn("users", "theme", "TEXT")
        },
        Migration(11, "Palabras guardadas para aprender idiomas") {
            exec(
                """CREATE TABLE IF NOT EXISTS words(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   lang TEXT NOT NULL, word TEXT NOT NULL, meaning TEXT NOT NULL DEFAULT '', context TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
                   card TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(user_id, lang, word))"""
            )
        },
        Migration(12, "Integraciones (Jellyfin, Kavita/Komga) guardadas cifradas, y obras seguidas para descargar") {
            exec("CREATE TABLE IF NOT EXISTS integrations(name TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            exec(
                """CREATE TABLE IF NOT EXISTS follows(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   source_id TEXT NOT NULL, media_id TEXT NOT NULL, title TEXT NOT NULL, rules TEXT NOT NULL DEFAULT '{}', last_number REAL NOT NULL DEFAULT 0,
                   created_at INTEGER NOT NULL, UNIQUE(source_id, media_id))"""
            )
        },
        Migration(13, "Usuario de Jellyfin vinculado a cada cuenta (progreso en las dos direcciones)") {
            addColumn("users", "jellyfin_user", "TEXT")
        },
        Migration(14, "Catálogo de fuentes: las que el admin quita para todos") {
            exec("CREATE TABLE IF NOT EXISTS source_settings(id TEXT PRIMARY KEY, hidden INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)")
        },
        Migration(15, "Importar bibliotecas de AniList, MyAnimeList y Mihon: cola de títulos por resolver") {
            exec(
                """CREATE TABLE IF NOT EXISTS imports(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   origin TEXT NOT NULL, data TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
            )
            exec("CREATE INDEX IF NOT EXISTS imports_pending ON imports(state, id)")
        },
        Migration(16, "Mazos de palabras compartidos entre los usuarios del servidor") {
            exec(
                """CREATE TABLE IF NOT EXISTS decks(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                   name TEXT NOT NULL, lang TEXT NOT NULL, words TEXT NOT NULL, size INTEGER NOT NULL, created_at INTEGER NOT NULL)"""
            )
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
