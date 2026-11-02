package com.nelson.yomukai.data.download

import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.MediaEntity
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the files saved to shared storage: CBZ, EPUB and the metadata beside them. */
object ComicArchive {
    private fun x(text: String?) = ExportNaming.xml(text.orEmpty())

    /** ComicInfo.xml, the metadata comic readers look for inside a CBZ. */
    fun comicInfo(series: String, chapterTitle: String, number: String, media: MediaEntity?, pages: Int): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo>
          <Title>${x(chapterTitle)}</Title>
          <Series>${x(series)}</Series>
          <Number>${x(number.trimStart('0').ifEmpty { "0" })}</Number>
          <Summary>${x(media?.description)}</Summary>
          <Writer>${x(media?.author)}</Writer>
          <Genre>${x(media?.genres?.replace("|", ", "))}</Genre>
          <PageCount>$pages</PageCount>
          <Manga>${if (media?.type == "MANGA") "YesAndRightToLeft" else "No"}</Manga>
        </ComicInfo>
    """.trimIndent()

    /** Series description in the NFO format of Kodi, Jellyfin and similar. */
    fun seriesInfo(title: String, media: MediaEntity?, row: DownloadEntity): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""")
        appendLine("<tvshow>")
        appendLine("  <title>${x(title)}</title>")
        appendLine("  <plot>${x(media?.description)}</plot>")
        appendLine("  <status>${x(media?.status)}</status>")
        appendLine("  <credits>${x(media?.author)}</credits>")
        media?.genres?.split('|')?.filter { it.isNotBlank() }?.forEach { appendLine("  <genre>${x(it)}</genre>") }
        media?.altTitles?.split('|')?.filter { it.isNotBlank() }?.forEach { appendLine("  <originaltitle>${x(it)}</originaltitle>") }
        appendLine("  <source>${x(row.sourceId)}</source>")
        appendLine("</tvshow>")
    }

    /** Pages are already compressed, so they are stored as they are: faster, and the file gains nothing otherwise. */
    fun cbz(out: OutputStream, pages: List<File>, info: String) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(info.toByteArray())
            zip.closeEntry()
            pages.forEach { page -> stored(zip, page.name, page.readBytes()) }
        }
    }

    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Minimal valid EPUB 3 holding one chapter; `mimetype` must be first and uncompressed. */
    fun epub(out: OutputStream, title: String, author: String, chapterTitle: String, text: String) {
        val body = text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.joinToString("\n") { "<p>${x(it.trim())}</p>" }
        ZipOutputStream(out).use { zip ->
            stored(zip, "mimetype", "application/epub+zip".toByteArray())
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            add(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
            )
            add(
                "book.opf",
                """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">${x(title)}</dc:identifier><dc:title>${x(title)}</dc:title><dc:creator>${x(author)}</dc:creator><dc:language>es</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
            )
            add(
                "nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${x(title)}</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">${x(chapterTitle)}</a></li></ol></nav></body></html>"""
            )
            add(
                "chapter.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${x(chapterTitle)}</title></head><body><h1>${x(chapterTitle)}</h1>$body</body></html>"""
            )
        }
    }
}
