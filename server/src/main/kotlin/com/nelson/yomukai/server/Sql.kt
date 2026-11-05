package com.nelson.yomukai.server

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** Small JDBC helpers for the newer tables; every value is bound, never pasted into the SQL. */
internal fun PreparedStatement.bind(args: Array<out Any?>) {
    args.forEachIndexed { i, v ->
        val n = i + 1
        when (v) {
            null -> setNull(n, Types.NULL)
            is String -> setString(n, v)
            is Int -> setInt(n, v)
            is Long -> setLong(n, v)
            is Boolean -> setInt(n, if (v) 1 else 0)
            is Double -> setDouble(n, v)
            else -> setString(n, v.toString())
        }
    }
}

internal fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { it.bind(args); it.executeUpdate() }

internal fun <T> Connection.query(sql: String, vararg args: Any?, row: ResultSet.() -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.row()) } }
    }

internal fun <T> Connection.queryOne(sql: String, vararg args: Any?, row: ResultSet.() -> T): T? = query(sql, *args, row = row).firstOrNull()

internal fun Connection.lastId(): Long = query("SELECT last_insert_rowid()") { getLong(1) }.first()

internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
