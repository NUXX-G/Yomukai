package com.nelson.yomukai.data.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nelson.yomukai.MainActivity
import com.nelson.yomukai.R
import com.nelson.yomukai.YomukaiApplication
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.prefs.AppSettings
import com.nelson.yomukai.data.source.AnimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refreshes the chapter list of every tracked library title and posts a notification for each
 * title that gained chapters/episodes. Titles seen for the first time are only synced (no
 * notification), so adding something to the library never floods the user.
 */
class LibraryUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as YomukaiApplication).container
        val repo = container.libraryRepository
        val tracked = repo.getTrackedLibrary()
        Log.d(TAG, "Comprobando ${tracked.size} títulos")
        val limiter = Semaphore(3)
        val failures = AtomicInteger(0)

        val updates: List<Pair<MediaEntity, List<ChapterEntity>>> = coroutineScope {
            tracked.map { media ->
                async {
                    limiter.withPermit {
                        val before = repo.getChapters(media.sourceId, media.mediaId).map { it.chapterId }.toSet()
                        val result = repo.refreshChapters(media.sourceId, media.mediaId)
                        if (result.isFailure) {
                            failures.incrementAndGet()
                            return@withPermit null
                        }
                        if (before.isEmpty()) return@withPermit null
                        val fresh = repo.getChapters(media.sourceId, media.mediaId).filter { it.chapterId !in before }
                        if (fresh.isEmpty()) null else media to fresh
                    }
                }
            }.awaitAll().filterNotNull()
        }

        updates.forEach { (media, chapters) ->
            val isAnime = container.sourceManager.get(media.sourceId) is AnimeSource
            notifyNewChapters(applicationContext, media, chapters, isAnime)
        }
        Log.d(TAG, "Actualizados: ${updates.size}, fallos: ${failures.get()}")
        // Only ask WorkManager to retry when every source failed (likely no connectivity).
        return if (tracked.isNotEmpty() && failures.get() == tracked.size) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "YomukaiUpdates"
        private const val CHANNEL_ID = "library_updates"
        private const val PERIODIC_WORK = "library_updates_periodic"
        private const val MANUAL_WORK = "library_updates_now"
        const val EXTRA_SOURCE_ID = "yomukai.sourceId"
        const val EXTRA_MEDIA_ID = "yomukai.mediaId"

        /** (Re)schedules or cancels the periodic check to match the user's settings. */
        fun schedule(context: Context, settings: AppSettings) {
            val workManager = WorkManager.getInstance(context)
            if (!settings.updatesEnabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(
                settings.updateIntervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS
            )
                .setConstraints(constraints(settings.updatesWifiOnly))
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setConstraints(constraints(wifiOnly = false))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Nuevos capítulos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Avisos cuando un título de tu biblioteca tiene capítulos o episodios nuevos"
                    }
                )
            }
        }

        private fun notifyNewChapters(context: Context, media: MediaEntity, chapters: List<ChapterEntity>, isAnime: Boolean) {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
            ensureChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SOURCE_ID, media.sourceId)
                putExtra(EXTRA_MEDIA_ID, media.mediaId)
            }
            val requestCode = (media.sourceId + media.mediaId).hashCode()
            val pending = PendingIntent.getActivity(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val noun = if (isAnime) {
                if (chapters.size == 1) "episodio nuevo" else "episodios nuevos"
            } else {
                if (chapters.size == 1) "capítulo nuevo" else "capítulos nuevos"
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_book)
                .setContentTitle(media.title)
                .setContentText("${chapters.size} $noun: ${chapters.maxBy { it.sortIndex }.title}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(chapters.sortedBy { it.sortIndex }.joinToString("\n") { it.title }))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setGroup("yomukai_updates")
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(requestCode, notification) }
        }
    }
}
