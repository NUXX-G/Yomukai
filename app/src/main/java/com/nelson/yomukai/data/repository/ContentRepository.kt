package com.nelson.yomukai.data.repository

import com.nelson.yomukai.data.download.DownloadManager
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager

/** A page to display: either a remote URL or a downloaded file. */
data class PageData(val model: Any, val headers: Map<String, String>)

/** Resolves chapter content, preferring offline copies over the network. */
class ContentRepository(
    private val sources: SourceManager,
    private val downloads: DownloadManager
) {
    suspend fun mangaPages(chapter: ChapterEntity): Result<List<PageData>> {
        downloads.localPages(chapter.sourceId, chapter.chapterId)?.let { files ->
            return Result.success(files.map { PageData(it, emptyMap()) })
        }
        val source = sources.get(chapter.sourceId) as? MangaSource
            ?: return Result.failure(IllegalStateException("Esta fuente no ofrece imágenes"))
        return source.fetchPageList(chapter.url).map { urls -> urls.map { PageData(it, source.imageHeaders) } }
    }

    suspend fun novelText(chapter: ChapterEntity): Result<String> {
        downloads.localText(chapter.sourceId, chapter.chapterId)?.let { return Result.success(it) }
        val source = sources.get(chapter.sourceId) as? NovelSource
            ?: return Result.failure(IllegalStateException("Esta fuente no ofrece texto"))
        return source.fetchChapterText(chapter.url)
    }
}
