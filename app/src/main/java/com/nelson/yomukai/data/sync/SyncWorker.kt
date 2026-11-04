package com.nelson.yomukai.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nelson.yomukai.YomukaiApplication
import java.util.concurrent.TimeUnit

/** Runs the server sync in the background, when there is a connection. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as YomukaiApplication).container.serverSync
        if (!sync.state.value.connected) return Result.success()
        val result = sync.sync()
        // A dropped connection is retried later; a refused session waits for the user.
        return when {
            result.isSuccess -> Result.success()
            result.exceptionOrNull()?.message?.contains("sesión") == true -> Result.success()
            runAttemptCount < 5 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "server_sync"
        private const val SOON = "server_sync_soon"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every hour while connected; cancelled when the user disconnects. */
        fun schedule(context: Context, connected: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!connected) {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(SOON)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * After a local change: one sync a little later, so a whole reading session (many page turns)
         * becomes one request. Changes made while it waits ride along.
         */
        fun soon(context: Context, delaySeconds: Long = 20) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
        }
    }
}
