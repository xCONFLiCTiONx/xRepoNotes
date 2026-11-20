package com.xconflictionx.xreponotes.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class NotesSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        val legacyPrefs = applicationContext.getSharedPreferences("xtexteditor_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(NotesSyncEngine.PREF_KEY_SERVER_URL, legacyPrefs.getString(NotesSyncEngine.PREF_KEY_SERVER_URL, NotesSyncEngine.DEFAULT_SERVER_URL))
            ?: NotesSyncEngine.DEFAULT_SERVER_URL
        val apiKey = ApiKeyStore.read(applicationContext).orEmpty()
        if (apiKey.isBlank()) return Result.success()

        return when (val result = NotebookSyncEngine.sync(applicationContext, serverUrl, apiKey)) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Failure -> Result.retry()
        }
    }
}

object NotesSyncScheduler {
    private const val PERIODIC_WORK = "xreponotes-periodic-sync"
    private const val IMMEDIATE_WORK = "xreponotes-retry-sync"

    private fun connectedConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<NotesSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<NotesSyncWorker>()
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
