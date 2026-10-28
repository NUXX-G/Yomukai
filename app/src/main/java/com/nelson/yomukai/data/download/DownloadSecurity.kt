package com.nelson.yomukai.data.download

import java.io.File
import java.net.URI

/** Rules every download must satisfy before a byte is written to disk. Pure, so they are unit tested. */
object DownloadSecurity {
    const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
    const val MAX_TEXT_BYTES = 5L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 4L * 1024 * 1024 * 1024

    class InsecureDownloadException(message: String) : java.io.IOException(message)

    /**
     * Only HTTPS is accepted. Plain HTTP is allowed solely for `.onion` hosts, where Tor itself
     * provides the encryption end to end.
     */
    fun requireSecureUrl(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw InsecureDownloadException("Dirección de descarga no válida")
        }
        val host = uri.host?.lowercase() ?: throw InsecureDownloadException("Dirección de descarga sin servidor")
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme == "https" -> url.trim()
            scheme == "http" && host.endsWith(".onion") -> url.trim()
            else -> throw InsecureDownloadException("Descarga bloqueada: solo se permite HTTPS ($host)")
        }
    }

    /** True when the response content type may be saved as a page image. */
    fun isImageType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("image/") && type != "image/svg+xml"
    }

    fun isVideoType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("video/") || type == "application/octet-stream" || type == "application/mp4" || type == "binary/octet-stream"
    }

    fun imageExtension(contentType: String?): String = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> "jpg"
    }

    /** Rejects a declared size over [max]; unknown sizes (-1) pass and are limited while streaming. */
    fun checkDeclaredSize(contentLength: Long, max: Long) {
        if (contentLength > max) throw InsecureDownloadException("El archivo es demasiado grande (${contentLength / (1024 * 1024)} MB)")
    }

    /** A file name safe to create inside the app directory: no separators, no dots-only names, bounded length. */
    fun sanitizeFileName(name: String, fallback: String = "file"): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(100)
        return if (cleaned.isEmpty() || cleaned.all { it == '.' || it == '_' }) fallback else cleaned
    }

    /** True when [file] resolves inside [root] (after symlinks and `..`), guarding against path traversal. */
    fun isInside(root: File, file: File): Boolean = try {
        val base = root.canonicalFile
        val target = file.canonicalFile
        target == base || target.path.startsWith(base.path + File.separator)
    } catch (e: java.io.IOException) {
        false
    }

    /** Magic-number check for an MP4/MOV container: bytes 4..8 spell `ftyp` (or `moov`/`mdat` for fragmented files). */
    fun looksLikeMp4(header: ByteArray): Boolean {
        if (header.size < 12) return false
        val tag = String(header, 4, 4, Charsets.ISO_8859_1)
        return tag == "ftyp" || tag == "moov" || tag == "mdat" || tag == "free" || tag == "wide"
    }

    /** A finished file is complete when the server's declared length (if any) matches what was written. */
    fun isComplete(expectedBytes: Long, actualBytes: Long): Boolean =
        actualBytes > 0 && (expectedBytes < 0 || expectedBytes == actualBytes)
}
