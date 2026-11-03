package com.nelson.yomukai.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

data class User(
    val id: Long,
    val name: String,
    val isAdmin: Boolean,
    val isGuest: Boolean,
    val createdAt: Long,
    /** Guests only: when their account and data are deleted. */
    val expiresAt: Long?
)

data class LibraryRow(
    val sourceId: String, val mediaId: String, val title: String, val cover: String, val type: String,
    val status: String, val author: String, val genres: String, val addedAt: Long, val readingStatus: String
)

data class ProgressRow(
    val sourceId: String, val chapterId: String, val mediaId: String, val read: Boolean,
    val page: Int, val total: Int, val fraction: Double, val updatedAt: Long
)

data class HistoryRow(
    val sourceId: String, val mediaId: String, val chapterId: String, val chapterTitle: String,
    val title: String, val cover: String, val type: String, val readAt: Long
)

/**
 * SQLite, one file in the data folder. A single connection used one statement at a time is plenty
 * for a few hundred people and keeps SQLite free of lock errors; WAL keeps reads fast while writing.
 */
class Database(file: File) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val lock = Mutex()

    init {
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=NORMAL")
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            SCHEMA.forEach(s::execute)
        }
    }

    /** Runs [block] on the IO threads with the connection to itself. */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val auto = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = auto
            }
        }
    }

    // Users and sessions

    suspend fun createUser(name: String, passHash: String?, admin: Boolean, guest: Boolean, expiresAt: Long?): User = tx {
        val now = System.currentTimeMillis()
        prepare("INSERT INTO users(name, pass_hash, is_admin, is_guest, created_at, expires_at) VALUES(?,?,?,?,?,?)") {
            setString(1, name); setString(2, passHash); setInt(3, admin.int); setInt(4, guest.int); setLong(5, now)
            if (expiresAt != null) setLong(6, expiresAt) else setNull(6, java.sql.Types.INTEGER)
            executeUpdate()
        }
        val id = prepare("SELECT last_insert_rowid()") { executeQuery().use { it.next(); it.getLong(1) } }
        User(id, name, admin, guest, now, expiresAt)
    }

    suspend fun userByName(name: String): Pair<User, String?>? = tx {
        prepare("SELECT * FROM users WHERE name = ? COLLATE NOCASE") {
            setString(1, name)
            executeQuery().use { if (it.next()) it.user() to it.getString("pass_hash") else null }
        }
    }

    suspend fun userBySession(token: String): User? = tx {
        prepare("SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ?") {
            setString(1, token)
            executeQuery().use { if (it.next()) it.user() else null }
        }
    }

    suspend fun users(): List<User> = tx {
        prepare("SELECT * FROM users WHERE is_guest = 0 ORDER BY name") { executeQuery().use { rs -> rs.all { user() } } }
    }

    suspend fun guestCount(): Int = tx {
        prepare("SELECT COUNT(*) FROM users WHERE is_guest = 1") { executeQuery().use { it.next(); it.getInt(1) } }
    }

    suspend fun setPassword(userId: Long, passHash: String) = tx {
        prepare("UPDATE users SET pass_hash = ? WHERE id = ?") { setString(1, passHash); setLong(2, userId); executeUpdate() }
        // Changing the password closes every other session.
        prepare("DELETE FROM sessions WHERE user_id = ?") { setLong(1, userId); executeUpdate() }
    }

    suspend fun deleteUser(userId: Long) = tx {
        prepare("DELETE FROM users WHERE id = ?") { setLong(1, userId); executeUpdate() }
    }

    suspend fun createSession(token: String, userId: Long) = tx {
        val now = System.currentTimeMillis()
        prepare("INSERT INTO sessions(token, user_id, created_at, last_seen) VALUES(?,?,?,?)") {
            setString(1, token); setLong(2, userId); setLong(3, now); setLong(4, now); executeUpdate()
        }
    }

    suspend fun deleteSession(token: String) = tx {
        prepare("DELETE FROM sessions WHERE token = ?") { setString(1, token); executeUpdate() }
    }

    /** Deletes guests whose week is over; every row of theirs goes with them (ON DELETE CASCADE). */
    suspend fun deleteExpiredGuests(now: Long = System.currentTimeMillis()): Int = tx {
        prepare("DELETE FROM users WHERE is_guest = 1 AND expires_at IS NOT NULL AND expires_at < ?") {
            setLong(1, now); executeUpdate()
        }
    }

    // Library

    suspend fun library(userId: Long): List<LibraryRow> = tx {
        prepare("SELECT * FROM library WHERE user_id = ? ORDER BY added_at DESC") {
            setLong(1, userId)
            executeQuery().use { rs -> rs.all { libraryRow() } }
        }
    }

    suspend fun putLibrary(userId: Long, row: LibraryRow) = tx { upsertLibrary(userId, row) }

    fun Connection.upsertLibrary(userId: Long, row: LibraryRow) {
        prepare(
            "INSERT INTO library(user_id, source_id, media_id, title, cover, type, status, author, genres, added_at, reading_status) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id, source_id, media_id) DO UPDATE SET " +
                "title=excluded.title, cover=excluded.cover, type=excluded.type, status=excluded.status, author=excluded.author, " +
                "genres=excluded.genres, reading_status=excluded.reading_status"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.mediaId); setString(4, row.title)
            setString(5, row.cover); setString(6, row.type); setString(7, row.status); setString(8, row.author)
            setString(9, row.genres); setLong(10, row.addedAt); setString(11, row.readingStatus)
            executeUpdate()
        }
    }

    suspend fun removeLibrary(userId: Long, sourceId: String, mediaId: String) = tx {
        prepare("DELETE FROM library WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, userId); setString(2, sourceId); setString(3, mediaId); executeUpdate()
        }
    }

    // Reading progress and history

    suspend fun progress(userId: Long, sourceId: String, mediaId: String): List<ProgressRow> = tx {
        prepare("SELECT * FROM progress WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, userId); setString(2, sourceId); setString(3, mediaId)
            executeQuery().use { rs -> rs.all { progressRow() } }
        }
    }

    suspend fun allProgress(userId: Long): List<ProgressRow> = tx {
        prepare("SELECT * FROM progress WHERE user_id = ?") { setLong(1, userId); executeQuery().use { rs -> rs.all { progressRow() } } }
    }

    suspend fun putProgress(userId: Long, rows: List<ProgressRow>) = tx { rows.forEach { upsertProgress(userId, it) } }

    fun Connection.upsertProgress(userId: Long, row: ProgressRow) {
        prepare(
            "INSERT INTO progress(user_id, source_id, chapter_id, media_id, read, page, total, fraction, updated_at) VALUES(?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(user_id, source_id, chapter_id) DO UPDATE SET read=excluded.read, page=excluded.page, " +
                "total=excluded.total, fraction=excluded.fraction, updated_at=excluded.updated_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.chapterId); setString(4, row.mediaId)
            setInt(5, row.read.int); setInt(6, row.page); setInt(7, row.total); setDouble(8, row.fraction); setLong(9, row.updatedAt)
            executeUpdate()
        }
    }

    suspend fun history(userId: Long, limit: Int = 200): List<HistoryRow> = tx {
        prepare("SELECT * FROM history WHERE user_id = ? ORDER BY read_at DESC LIMIT ?") {
            setLong(1, userId); setInt(2, limit)
            executeQuery().use { rs -> rs.all { historyRow() } }
        }
    }

    suspend fun putHistory(userId: Long, row: HistoryRow) = tx { upsertHistory(userId, row) }

    fun Connection.upsertHistory(userId: Long, row: HistoryRow) {
        prepare(
            "INSERT INTO history(user_id, source_id, media_id, chapter_id, chapter_title, title, cover, type, read_at) VALUES(?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(user_id, source_id, media_id) DO UPDATE SET chapter_id=excluded.chapter_id, chapter_title=excluded.chapter_title, " +
                "title=excluded.title, cover=excluded.cover, type=excluded.type, read_at=excluded.read_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.mediaId); setString(4, row.chapterId)
            setString(5, row.chapterTitle); setString(6, row.title); setString(7, row.cover); setString(8, row.type); setLong(9, row.readAt)
            executeUpdate()
        }
    }

    suspend fun removeHistory(userId: Long, sourceId: String, mediaId: String) = tx {
        prepare("DELETE FROM history WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, userId); setString(2, sourceId); setString(3, mediaId); executeUpdate()
        }
    }

    // Settings: one JSON document per user, owned by the web app.

    suspend fun settings(userId: Long): String? = tx {
        prepare("SELECT json FROM settings WHERE user_id = ?") { setLong(1, userId); executeQuery().use { if (it.next()) it.getString(1) else null } }
    }

    suspend fun putSettings(userId: Long, json: String) = tx { upsertSettings(userId, json) }

    fun Connection.upsertSettings(userId: Long, json: String) {
        prepare("INSERT INTO settings(user_id, json) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET json=excluded.json") {
            setLong(1, userId); setString(2, json); executeUpdate()
        }
    }

    /** Replaces everything a user has; used by import. */
    suspend fun replaceAll(userId: Long, library: List<LibraryRow>, progress: List<ProgressRow>, history: List<HistoryRow>, settings: String?) = tx {
        listOf("library", "progress", "history").forEach { table ->
            prepare("DELETE FROM $table WHERE user_id = ?") { setLong(1, userId); executeUpdate() }
        }
        library.forEach { upsertLibrary(userId, it) }
        progress.forEach { upsertProgress(userId, it) }
        history.forEach { upsertHistory(userId, it) }
        if (settings != null) upsertSettings(userId, settings)
    }

    companion object {
        private val SCHEMA = listOf(
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
            "CREATE TABLE IF NOT EXISTS settings(user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, json TEXT NOT NULL)"
        )
    }
}

private val Boolean.int get() = if (this) 1 else 0

private inline fun <T> Connection.prepare(sql: String, block: PreparedStatement.() -> T): T = prepareStatement(sql).use(block)

private inline fun <T> ResultSet.all(row: ResultSet.() -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += row()
    return out
}

private fun ResultSet.user() = User(
    id = getLong("id"), name = getString("name"), isAdmin = getInt("is_admin") == 1, isGuest = getInt("is_guest") == 1,
    createdAt = getLong("created_at"), expiresAt = getLong("expires_at").takeIf { !wasNull() }
)

private fun ResultSet.libraryRow() = LibraryRow(
    getString("source_id"), getString("media_id"), getString("title"), getString("cover"), getString("type"),
    getString("status"), getString("author"), getString("genres"), getLong("added_at"), getString("reading_status")
)

private fun ResultSet.progressRow() = ProgressRow(
    getString("source_id"), getString("chapter_id"), getString("media_id"), getInt("read") == 1,
    getInt("page"), getInt("total"), getDouble("fraction"), getLong("updated_at")
)

private fun ResultSet.historyRow() = HistoryRow(
    getString("source_id"), getString("media_id"), getString("chapter_id"), getString("chapter_title"),
    getString("title"), getString("cover"), getString("type"), getLong("read_at")
)
