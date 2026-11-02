package com.nelson.yomukai.data.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.OutputStream

/**
 * Writes finished downloads to `Download/Yomukai` on shared storage, so they can be opened from any
 * file manager or player without the app. Files already there are left alone.
 */
class PublicExporter(private val context: Context) {
    /** [folder] is relative to `Download/Yomukai`; [write] only runs when the file does not exist yet. */
    fun save(folder: String, name: String, mime: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(folder, name, mime, write)
        else saveAsFile(folder, name, write)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(folder: String, name: String, mime: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$folder/"
        resolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(path, name), null
        )?.use { if (it.moveToFirst()) return }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("No se pudo crear $name")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("No se pudo escribir $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Android 9 and older: plain files, which need the storage permission. */
    private fun saveAsFile(folder: String, name: String, write: (OutputStream) -> Unit) {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT/$folder")
        val target = File(dir, name)
        if (target.exists()) return
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use(write)
        check(tmp.renameTo(target)) { "No se pudo guardar $name" }
    }

    companion object {
        const val ROOT = "Yomukai"
        const val BINARY = "application/octet-stream" // keeps .cbz/.nfo/.epub names: Android renames known types
    }
}

/** Names and metadata of exported files. Pure, so it is unit tested. */
object ExportNaming {
    private val SEASON = listOf(
        Regex("""(?i)\b(?:season|temporada)\s*(\d+)"""),
        Regex("""(?i)\b(\d+)(?:st|nd|rd|th|ª|º)?\s*(?:season|temporada)"""),
        Regex("""(?i)\bS(\d+)$""")
    )

    fun season(title: String): Int = SEASON.firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() } ?: 1

    /** The series folder: without the season, so every season lands under one show. */
    fun seriesName(title: String): String =
        SEASON.fold(title) { text, pattern -> text.replace(pattern, "") }.trim(' ', '-', ':', '–').ifBlank { title }

    /** 5 -> "05", 12.5 -> "12.5". */
    fun number(value: Float): String =
        if (value % 1f == 0f) "%02d".format(value.toInt()) else value.toString()

    fun xml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
