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
    val expiresAt: Long?,
    /** Set when the account was blocked for abusive use; only an admin lifts it. */
    val suspendedAt: Long? = null,
    val suspendedReason: String? = null
)

/*
 * Synced rows. Every change gets [updatedAt] (when it happened, by the clock of whoever made it) and a
 * server revision: devices ask for "everything after revision N", so a device whose clock is wrong
 * never misses a change. When two devices change the same row, the later [updatedAt] wins. Deleting
 * keeps the row with deleted = true, so the deletion reaches the other devices too.
 *
 * Gson fills these from JSON without running Kotlin defaults: a missing field arrives as null or 0,
 * hence [normalized] before anything is written.
 */

data class LibraryRow(
    val sourceId: String, val mediaId: String, val title: String, val cover: String, val type: String,
    val status: String, val author: String, val genres: String, val addedAt: Long, val readingStatus: String,
    /** Names of the user's categories this title belongs to. */
    val categories: List<String>? = emptyList(),
    val updatedAt: Long = 0, val deleted: Boolean = false,
    /** Set only in what the library page reads: the same work is already listed from another source. */
    val sameWorkShown: Boolean = false
) {
    fun normalized() = copy(
        sourceId = clip(sourceId, ID_MAX), mediaId = clip(mediaId, ID_MAX), title = clip(title, TEXT_MAX),
        cover = clip(cover, ID_MAX), type = clip(type, 20).ifEmpty { "MANGA" }, status = clip(status, 100),
        author = clip(author, TEXT_MAX), genres = clip(genres, 2_000), readingStatus = clip(readingStatus, 20).ifEmpty { "NONE" }.let { if (it == "PLAN") "PLAN_TO_READ" else it },
        categories = categories.orEmpty().map { clip(it, 100).trim() }.filter { it.isNotEmpty() }.distinct().take(50)
    )
}

data class ProgressRow(
    val sourceId: String, val chapterId: String, val mediaId: String, val read: Boolean,
    val page: Int, val total: Int, val fraction: Double, val updatedAt: Long
) {
    fun normalized() = copy(
        sourceId = clip(sourceId, ID_MAX), chapterId = clip(chapterId, ID_MAX), mediaId = clip(mediaId, ID_MAX),
        page = page.coerceAtLeast(0), total = total.coerceAtLeast(0),
        fraction = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
    )
}

data class HistoryRow(
    val sourceId: String, val mediaId: String, val chapterId: String, val chapterTitle: String,
    val title: String, val cover: String, val type: String, val readAt: Long,
    val updatedAt: Long = 0, val deleted: Boolean = false
) {
    fun normalized() = copy(
        sourceId = clip(sourceId, ID_MAX), mediaId = clip(mediaId, ID_MAX), chapterId = clip(chapterId, ID_MAX),
        chapterTitle = clip(chapterTitle, TEXT_MAX), title = clip(title, TEXT_MAX), cover = clip(cover, ID_MAX),
        type = clip(type, 20).ifEmpty { "MANGA" }, updatedAt = updatedAt.takeIf { it > 0 } ?: readAt
    )
}

/** A saved spot: page index for images, paragraph index for novels. */
data class BookmarkRow(
    val sourceId: String, val mediaId: String, val chapterId: String, val chapterTitle: String,
    val position: Int, val label: String, val createdAt: Long, val updatedAt: Long = 0, val deleted: Boolean = false
) {
    fun normalized() = copy(
        sourceId = clip(sourceId, ID_MAX), mediaId = clip(mediaId, ID_MAX), chapterId = clip(chapterId, ID_MAX),
        chapterTitle = clip(chapterTitle, TEXT_MAX), label = clip(label, 300), position = position.coerceAtLeast(0),
        updatedAt = updatedAt.takeIf { it > 0 } ?: createdAt
    )
}

data class CategoryRow(val name: String, val sortOrder: Int, val updatedAt: Long, val deleted: Boolean = false) {
    fun normalized() = copy(name = clip(name, 100).trim())
}

/** What a device has not seen yet, from [Database.changesSince]. */
data class SyncChanges(
    val rev: Long,
    val library: List<LibraryRow>,
    val categories: List<CategoryRow>,
    val progress: List<ProgressRow>,
    val history: List<HistoryRow>,
    val bookmarks: List<BookmarkRow>,
    /** The shared part of the settings, with its updatedAt, when it changed. */
    val settings: com.google.gson.JsonObject?
)

private val gson = com.google.gson.Gson()
private const val ID_MAX = 2_000
private const val TEXT_MAX = 500
private fun clip(value: String?, max: Int) = value.orEmpty().take(max)

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
        }
        Migrations.run(connection)
    }

    /** The schema step this database is at; see [Migrations]. */
    fun schemaVersion(): Int = Migrations.currentVersion(connection)

    fun close() = connection.close()

    /**
     * A consistent copy of the whole database into [target] (which must not exist) while the server keeps running:
     * SQLite's VACUUM INTO reads one snapshot, so writes made meanwhile are either fully in the copy or not at all.
     */
    suspend fun backupTo(target: File) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.createStatement().use { it.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'") }
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

    /** The next server revision; every write takes one. */
    private fun Connection.nextRev(): Long {
        prepare("UPDATE counters SET value = value + 1 WHERE name = 'rev'") { executeUpdate() }
        return prepare("SELECT value FROM counters WHERE name = 'rev'") { executeQuery().use { it.next(); it.getLong(1) } }
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

    suspend fun userById(id: Long): User? = tx { queryOne("SELECT * FROM users WHERE id = ?", id) { user() } }

    suspend fun userCount(admins: Boolean = false): Int = tx {
        queryOne(if (admins) "SELECT COUNT(*) FROM users WHERE is_admin = 1" else "SELECT COUNT(*) FROM users WHERE is_guest = 0") { getInt(1) } ?: 0
    }

    suspend fun userByName(name: String): Pair<User, String?>? = tx {
        prepare("SELECT * FROM users WHERE name = ? COLLATE NOCASE") {
            setString(1, name)
            executeQuery().use { if (it.next()) it.user() to it.getString("pass_hash") else null }
        }
    }

    suspend fun userBySession(token: String): User? = tx {
        prepare("SELECT u.* FROM user_sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?") {
            setString(1, sha256Hex(token))
            executeQuery().use { if (it.next()) it.user() else null }
        }
    }

    suspend fun users(): List<User> = tx {
        prepare("SELECT * FROM users WHERE is_guest = 0 OR suspended_at IS NOT NULL ORDER BY is_guest, name") { executeQuery().use { rs -> rs.all { user() } } }
    }

    suspend fun guestCount(): Int = tx {
        prepare("SELECT COUNT(*) FROM users WHERE is_guest = 1") { executeQuery().use { it.next(); it.getInt(1) } }
    }

    suspend fun setPassword(userId: Long, passHash: String) = tx {
        prepare("UPDATE users SET pass_hash = ? WHERE id = ?") { setString(1, passHash); setLong(2, userId); executeUpdate() }
        // Changing the password closes every other session.
        prepare("DELETE FROM user_sessions WHERE user_id = ?") { setLong(1, userId); executeUpdate() }
    }

    suspend fun suspend(userId: Long, reason: String) = tx {
        prepare("UPDATE users SET suspended_at = ?, suspended_reason = ? WHERE id = ? AND suspended_at IS NULL") {
            setLong(1, System.currentTimeMillis()); setString(2, reason.take(300)); setLong(3, userId); executeUpdate()
        }
    }

    suspend fun reactivate(userId: Long) = tx {
        prepare("UPDATE users SET suspended_at = NULL, suspended_reason = NULL WHERE id = ?") { setLong(1, userId); executeUpdate() }
    }

    suspend fun deleteUser(userId: Long) = tx {
        prepare("DELETE FROM users WHERE id = ?") { setLong(1, userId); executeUpdate() }
    }

    /** Only the SHA-256 of each session token is stored: neither the database nor a copy of it can log anyone in. */
    suspend fun createSession(token: String, userId: Long, device: String = "", ip: String = "") = tx {
        val now = System.currentTimeMillis()
        update(
            "INSERT INTO user_sessions(token_hash, user_id, created_at, last_seen, device, ip) VALUES(?,?,?,?,?,?)",
            sha256Hex(token), userId, now, now, device.take(200), ip.take(64)
        )
    }

    suspend fun deleteSession(token: String) = tx { update("DELETE FROM user_sessions WHERE token_hash = ?", sha256Hex(token)) }

    data class SessionRow(val id: Long, val createdAt: Long, val lastSeen: Long, val device: String, val ip: String, val tokenHash: String)

    suspend fun sessionsOf(userId: Long): List<SessionRow> = tx {
        query("SELECT id, created_at, last_seen, device, ip, token_hash FROM user_sessions WHERE user_id = ? ORDER BY last_seen DESC", userId) {
            SessionRow(getLong(1), getLong(2), getLong(3), getString(4), getString(5), getString(6))
        }
    }

    /** Deletes one of [userId]'s own sessions; returns its token hash (to drop it from caches) or null. */
    suspend fun deleteSessionById(userId: Long, id: Long): String? = tx {
        val hash = queryOne("SELECT token_hash FROM user_sessions WHERE id = ? AND user_id = ?", id, userId) { getString(1) }
        if (hash != null) update("DELETE FROM user_sessions WHERE id = ?", id)
        hash
    }

    suspend fun deleteOtherSessions(userId: Long, keepToken: String): Int = tx {
        update("DELETE FROM user_sessions WHERE user_id = ? AND token_hash <> ?", userId, sha256Hex(keepToken))
    }

    suspend fun touchSession(token: String, now: Long, ip: String) = tx {
        update("UPDATE user_sessions SET last_seen = ?, ip = ? WHERE token_hash = ?", now, ip.take(64), sha256Hex(token))
    }

    /** Deletes guests whose week is over; every row of theirs goes with them (ON DELETE CASCADE). */
    suspend fun deleteExpiredGuests(now: Long = System.currentTimeMillis()): Int = tx {
        prepare("DELETE FROM users WHERE is_guest = 1 AND expires_at IS NOT NULL AND expires_at < ?") {
            setLong(1, now); executeUpdate()
        }
    }

    // Library

    suspend fun library(userId: Long): List<LibraryRow> = tx {
        prepare("SELECT * FROM library WHERE user_id = ? AND deleted = 0 ORDER BY added_at DESC") {
            setLong(1, userId)
            executeQuery().use { rs -> rs.all { libraryRow() } }
        }
    }

    suspend fun libraryRow(userId: Long, sourceId: String, mediaId: String): LibraryRow? = tx {
        prepare("SELECT * FROM library WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, userId); setString(2, sourceId); setString(3, mediaId)
            executeQuery().use { if (it.next()) it.libraryRow() else null }
        }
    }

    suspend fun putLibrary(userId: Long, row: LibraryRow) = tx { upsertLibrary(userId, row) }

    /** Writes [row] unless the stored one changed later. Returns whether it was written. */
    fun Connection.upsertLibrary(userId: Long, input: LibraryRow): Boolean {
        val row = input.normalized()
        return prepare(
            "INSERT INTO library(user_id, source_id, media_id, title, cover, type, status, author, genres, added_at, reading_status, " +
                "categories, updated_at, deleted, rev) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id, source_id, media_id) DO UPDATE SET " +
                "title=excluded.title, cover=excluded.cover, type=excluded.type, status=excluded.status, author=excluded.author, " +
                "genres=excluded.genres, added_at=excluded.added_at, reading_status=excluded.reading_status, categories=excluded.categories, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted, rev=excluded.rev WHERE excluded.updated_at > library.updated_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.mediaId); setString(4, row.title)
            setString(5, row.cover); setString(6, row.type); setString(7, row.status); setString(8, row.author)
            setString(9, row.genres); setLong(10, row.addedAt); setString(11, row.readingStatus)
            setString(12, gson.toJson(row.categories)); setLong(13, row.updatedAt); setInt(14, row.deleted.int); setLong(15, nextRev())
            executeUpdate() > 0
        }
    }

    suspend fun removeLibrary(userId: Long, sourceId: String, mediaId: String) = tx {
        prepare("UPDATE library SET deleted = 1, updated_at = ?, rev = ? WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, System.currentTimeMillis()); setLong(2, nextRev()); setLong(3, userId); setString(4, sourceId); setString(5, mediaId)
            executeUpdate()
        }
    }

    // Categories

    suspend fun categories(userId: Long): List<CategoryRow> = tx {
        prepare("SELECT * FROM categories WHERE user_id = ? AND deleted = 0 ORDER BY sort_order, name COLLATE NOCASE") {
            setLong(1, userId); executeQuery().use { rs -> rs.all { categoryRow() } }
        }
    }

    fun Connection.upsertCategory(userId: Long, input: CategoryRow): Boolean {
        val row = input.normalized()
        if (row.name.isEmpty()) return false
        return prepare(
            "INSERT INTO categories(user_id, name, sort_order, updated_at, deleted, rev) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(user_id, name) DO UPDATE SET sort_order=excluded.sort_order, updated_at=excluded.updated_at, " +
                "deleted=excluded.deleted, rev=excluded.rev WHERE excluded.updated_at > categories.updated_at"
        ) {
            setLong(1, userId); setString(2, row.name); setInt(3, row.sortOrder); setLong(4, row.updatedAt)
            setInt(5, row.deleted.int); setLong(6, nextRev()); executeUpdate() > 0
        }
    }

    /** Creates, renames or deletes a category; titles in it follow. */
    suspend fun editCategory(userId: Long, oldName: String?, newName: String?, sortOrder: Int = 0) = tx {
        val now = System.currentTimeMillis()
        if (newName != null) upsertCategory(userId, CategoryRow(newName, sortOrder, now))
        if (oldName != null && oldName != newName) {
            upsertCategory(userId, CategoryRow(oldName, 0, now, deleted = true))
            // Titles that were in it move to the new name, or leave it when it is deleted.
            val rows = prepare("SELECT * FROM library WHERE user_id = ? AND deleted = 0") {
                setLong(1, userId); executeQuery().use { rs -> rs.all { libraryRow() } }
            }.filter { oldName in it.categories.orEmpty() }
            rows.forEach { r ->
                val names = r.categories.orEmpty().map { if (it == oldName) newName else it }.filterNotNull().distinct()
                upsertLibrary(userId, r.copy(categories = names, updatedAt = now))
            }
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

    fun Connection.upsertProgress(userId: Long, input: ProgressRow): Boolean {
        val row = input.normalized()
        return prepare(
            "INSERT INTO progress(user_id, source_id, chapter_id, media_id, read, page, total, fraction, updated_at, rev) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(user_id, source_id, chapter_id) DO UPDATE SET media_id=excluded.media_id, read=excluded.read, page=excluded.page, " +
                "total=excluded.total, fraction=excluded.fraction, updated_at=excluded.updated_at, rev=excluded.rev " +
                "WHERE excluded.updated_at > progress.updated_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.chapterId); setString(4, row.mediaId)
            setInt(5, row.read.int); setInt(6, row.page); setInt(7, row.total); setDouble(8, row.fraction); setLong(9, row.updatedAt)
            setLong(10, nextRev())
            executeUpdate() > 0
        }
    }

    suspend fun history(userId: Long, limit: Int = 200): List<HistoryRow> = tx {
        prepare("SELECT * FROM history WHERE user_id = ? AND deleted = 0 ORDER BY read_at DESC LIMIT ?") {
            setLong(1, userId); setInt(2, limit)
            executeQuery().use { rs -> rs.all { historyRow() } }
        }
    }

    suspend fun putHistory(userId: Long, row: HistoryRow) = tx { upsertHistory(userId, row) }

    fun Connection.upsertHistory(userId: Long, input: HistoryRow): Boolean {
        val row = input.normalized()
        return prepare(
            "INSERT INTO history(user_id, source_id, media_id, chapter_id, chapter_title, title, cover, type, read_at, updated_at, deleted, rev) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id, source_id, media_id) DO UPDATE SET chapter_id=excluded.chapter_id, " +
                "chapter_title=excluded.chapter_title, title=excluded.title, cover=excluded.cover, type=excluded.type, read_at=excluded.read_at, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted, rev=excluded.rev WHERE excluded.updated_at > history.updated_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.mediaId); setString(4, row.chapterId)
            setString(5, row.chapterTitle); setString(6, row.title); setString(7, row.cover); setString(8, row.type); setLong(9, row.readAt)
            setLong(10, row.updatedAt); setInt(11, row.deleted.int); setLong(12, nextRev())
            executeUpdate() > 0
        }
    }

    suspend fun removeHistory(userId: Long, sourceId: String, mediaId: String) = tx {
        prepare("UPDATE history SET deleted = 1, updated_at = ?, rev = ? WHERE user_id = ? AND source_id = ? AND media_id = ?") {
            setLong(1, System.currentTimeMillis()); setLong(2, nextRev()); setLong(3, userId); setString(4, sourceId); setString(5, mediaId)
            executeUpdate()
        }
    }

    // Bookmarks

    suspend fun bookmarks(userId: Long, sourceId: String, mediaId: String): List<BookmarkRow> = tx {
        prepare("SELECT * FROM bookmarks WHERE user_id = ? AND source_id = ? AND media_id = ? AND deleted = 0 ORDER BY created_at DESC") {
            setLong(1, userId); setString(2, sourceId); setString(3, mediaId); executeQuery().use { rs -> rs.all { bookmarkRow() } }
        }
    }

    suspend fun allBookmarks(userId: Long): List<BookmarkRow> = tx {
        prepare("SELECT * FROM bookmarks WHERE user_id = ? AND deleted = 0") { setLong(1, userId); executeQuery().use { rs -> rs.all { bookmarkRow() } } }
    }

    suspend fun putBookmark(userId: Long, row: BookmarkRow) = tx { upsertBookmark(userId, row) }

    fun Connection.upsertBookmark(userId: Long, input: BookmarkRow): Boolean {
        val row = input.normalized()
        return prepare(
            "INSERT INTO bookmarks(user_id, source_id, chapter_id, position, media_id, chapter_title, label, created_at, updated_at, deleted, rev) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id, source_id, chapter_id, position) DO UPDATE SET media_id=excluded.media_id, " +
                "chapter_title=excluded.chapter_title, label=excluded.label, created_at=excluded.created_at, updated_at=excluded.updated_at, " +
                "deleted=excluded.deleted, rev=excluded.rev WHERE excluded.updated_at > bookmarks.updated_at"
        ) {
            setLong(1, userId); setString(2, row.sourceId); setString(3, row.chapterId); setInt(4, row.position); setString(5, row.mediaId)
            setString(6, row.chapterTitle); setString(7, row.label); setLong(8, row.createdAt); setLong(9, row.updatedAt)
            setInt(10, row.deleted.int); setLong(11, nextRev())
            executeUpdate() > 0
        }
    }

    // Settings: one JSON document per user, owned by the web app. Its shared part (sources and excluded
    // genres) is also synced with the Android app.

    suspend fun settings(userId: Long): String? = tx { settingsJson(userId) }

    private fun Connection.settingsJson(userId: Long): String? =
        prepare("SELECT json FROM settings WHERE user_id = ?") { setLong(1, userId); executeQuery().use { if (it.next()) it.getString(1) else null } }

    suspend fun putSettings(userId: Long, json: String, sharedUpdatedAt: Long = System.currentTimeMillis()) =
        tx { upsertSettings(userId, json, sharedUpdatedAt) }

    fun Connection.upsertSettings(userId: Long, json: String, sharedUpdatedAt: Long = System.currentTimeMillis()) {
        prepare(
            "INSERT INTO settings(user_id, json, shared_updated_at, rev) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET " +
                "json=excluded.json, shared_updated_at=excluded.shared_updated_at, rev=excluded.rev"
        ) {
            setLong(1, userId); setString(2, json); setLong(3, sharedUpdatedAt); setLong(4, nextRev()); executeUpdate()
        }
    }

    suspend fun sharedUpdatedAtOf(userId: Long): Long = tx { sharedUpdatedAt(userId) }

    private fun Connection.sharedUpdatedAt(userId: Long): Long =
        prepare("SELECT shared_updated_at FROM settings WHERE user_id = ?") { setLong(1, userId); executeQuery().use { if (it.next()) it.getLong(1) else 0L } }

    // Sync

    /** One device's changes, applied where they are newer; then everything the device has not seen. */
    suspend fun sync(
        userId: Long, since: Long, library: List<LibraryRow>, categories: List<CategoryRow>, progress: List<ProgressRow>,
        history: List<HistoryRow>, bookmarks: List<BookmarkRow>, shared: com.google.gson.JsonObject?
    ): SyncChanges = tx {
        categories.forEach { upsertCategory(userId, it) }
        library.forEach { upsertLibrary(userId, it) }
        progress.forEach { upsertProgress(userId, it) }
        history.forEach { upsertHistory(userId, it) }
        bookmarks.forEach { upsertBookmark(userId, it) }
        val sharedAt = shared?.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        if (shared != null && sharedAt > sharedUpdatedAt(userId)) {
            val current = settingsJson(userId)?.let { runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?: com.google.gson.JsonObject()
            for (key in SHARED_SETTINGS) {
                if (shared.has(key)) current.add(key, shared.get(key)) else current.remove(key)
            }
            upsertSettings(userId, current.toString(), sharedAt)
        }
        changesSince(userId, since)
    }

    private fun Connection.changesSince(userId: Long, since: Long): SyncChanges {
        fun <T> rows(table: String, read: ResultSet.() -> T): List<T> =
            prepare("SELECT * FROM $table WHERE user_id = ? AND rev > ?") {
                setLong(1, userId); setLong(2, since); executeQuery().use { rs -> rs.all(read) }
            }
        val settingsRow = prepare("SELECT json, shared_updated_at FROM settings WHERE user_id = ? AND rev > ?") {
            setLong(1, userId); setLong(2, since)
            executeQuery().use { rs ->
                if (!rs.next()) null else {
                    val all = runCatching { com.google.gson.JsonParser.parseString(rs.getString(1)).asJsonObject }.getOrNull() ?: com.google.gson.JsonObject()
                    com.google.gson.JsonObject().apply {
                        SHARED_SETTINGS.forEach { key -> all.get(key)?.let { add(key, it) } }
                        addProperty("updatedAt", rs.getLong(2))
                    }
                }
            }
        }
        val rev = prepare("SELECT value FROM counters WHERE name = 'rev'") { executeQuery().use { it.next(); it.getLong(1) } }
        return SyncChanges(
            rev = rev,
            library = rows("library") { libraryRow() },
            categories = rows("categories") { categoryRow() },
            progress = rows("progress") { progressRow() },
            history = rows("history") { historyRow() },
            bookmarks = rows("bookmarks") { bookmarkRow() },
            settings = settingsRow
        )
    }

    /** Replaces everything a user has; used by import. */
    suspend fun replaceAll(
        userId: Long, library: List<LibraryRow>, progress: List<ProgressRow>, history: List<HistoryRow>,
        categories: List<CategoryRow>, bookmarks: List<BookmarkRow>, settings: String?
    ) = tx {
        // Everything already there is marked deleted (so synced devices drop it too), then the copy is written.
        val now = System.currentTimeMillis()
        listOf("library", "history", "bookmarks", "categories").forEach { table ->
            prepare("UPDATE $table SET deleted = 1, updated_at = ?, rev = ? WHERE user_id = ? AND deleted = 0") {
                setLong(1, now); setLong(2, nextRev()); setLong(3, userId); executeUpdate()
            }
        }
        prepare("DELETE FROM progress WHERE user_id = ?") { setLong(1, userId); executeUpdate() }
        categories.forEach { upsertCategory(userId, it.copy(updatedAt = now + 1)) }
        library.forEach { upsertLibrary(userId, it.copy(updatedAt = now + 1)) }
        progress.forEach { upsertProgress(userId, it) }
        history.forEach { upsertHistory(userId, it.copy(updatedAt = now + 1)) }
        bookmarks.forEach { upsertBookmark(userId, it.copy(updatedAt = now + 1)) }
        if (settings != null) upsertSettings(userId, settings, now + 1)
    }

    companion object {
        /** Settings both the web and the Android app understand. */
        val SHARED_SETTINGS = listOf("enabledSources", "exclusions", "showAdult")
    }
}

private val Boolean.int get() = if (this) 1 else 0

private inline fun <T> Connection.prepare(sql: String, block: PreparedStatement.() -> T): T = prepareStatement(sql).use(block)

private inline fun <T> ResultSet.all(row: ResultSet.() -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out += row()
    return out
}

private val stringList = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type

private fun ResultSet.user() = User(
    id = getLong("id"), name = getString("name"), isAdmin = getInt("is_admin") == 1, isGuest = getInt("is_guest") == 1,
    createdAt = getLong("created_at"), expiresAt = getLong("expires_at").takeIf { !wasNull() },
    suspendedAt = getLong("suspended_at").takeIf { !wasNull() }, suspendedReason = getString("suspended_reason")
)

private fun ResultSet.libraryRow() = LibraryRow(
    getString("source_id"), getString("media_id"), getString("title"), getString("cover"), getString("type"),
    getString("status"), getString("author"), getString("genres"), getLong("added_at"), getString("reading_status"),
    categories = runCatching { gson.fromJson<List<String>>(getString("categories"), stringList) }.getOrNull().orEmpty(),
    updatedAt = getLong("updated_at"), deleted = getInt("deleted") == 1
)

private fun ResultSet.progressRow() = ProgressRow(
    getString("source_id"), getString("chapter_id"), getString("media_id"), getInt("read") == 1,
    getInt("page"), getInt("total"), getDouble("fraction"), getLong("updated_at")
)

private fun ResultSet.historyRow() = HistoryRow(
    getString("source_id"), getString("media_id"), getString("chapter_id"), getString("chapter_title"),
    getString("title"), getString("cover"), getString("type"), getLong("read_at"),
    updatedAt = getLong("updated_at"), deleted = getInt("deleted") == 1
)

private fun ResultSet.bookmarkRow() = BookmarkRow(
    getString("source_id"), getString("media_id"), getString("chapter_id"), getString("chapter_title"),
    getInt("position"), getString("label"), getLong("created_at"), getLong("updated_at"), getInt("deleted") == 1
)

private fun ResultSet.categoryRow() = CategoryRow(getString("name"), getInt("sort_order"), getLong("updated_at"), getInt("deleted") == 1)
