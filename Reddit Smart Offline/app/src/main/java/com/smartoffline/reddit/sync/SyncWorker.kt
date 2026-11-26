package com.smartoffline.reddit.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.smartoffline.reddit.App
import com.smartoffline.reddit.util.Notifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Runs a synchronization: one subreddit (scheduled), a manual set, or retries of unfinished downloads. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing synchronization", 0, 0, true)

    private fun foreground(text: String, progress: Int, max: Int, indeterminate: Boolean): ForegroundInfo {
        val n = App.get().notifier.syncNotification("Reddit Smart Offline", text, progress, max, indeterminate)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifier.ID_SYNC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifier.ID_SYNC, n)
    }

    override suspend fun doWork(): Result {
        val app = App.get()
        if (!app.prefs.value.autoDownloads && inputData.getBoolean(KEY_SCHEDULED, false)) return Result.success()
        val subs = inputData.getStringArray(KEY_SUBS)?.toList()
        val trigger = inputData.getString(KEY_TRIGGER) ?: "Manual"
        val mediaOnly = inputData.getBoolean(KEY_MEDIA_ONLY, false)
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)

        // Long downloads run as a foreground service; Android may refuse this while the app is in the
        // background, in which case the work simply continues within WorkManager's normal limits.
        try { setForeground(getForegroundInfo()) } catch (_: Exception) { }

        return coroutineScope {
            val updater: Job = launch {
                app.engine.progress.collectLatest { p ->
                    if (!p.running) return@collectLatest
                    val text = buildString {
                        if (p.paused) append("Paused · ")
                        append(p.currentSubreddit?.let { "r/$it · " } ?: "")
                        append(p.operation)
                    }
                    app.notifier.update(Notifier.ID_SYNC, app.notifier.syncNotification(
                        "Reddit Smart Offline", text, p.mediaDone, p.mediaTotal, p.mediaTotal == 0))
                    kotlinx.coroutines.delay(1000)
                }
            }
            val outcome = app.engine.run(subs, trigger, mediaOnly, scheduled)
            updater.cancel()
            app.notifier.cancel(Notifier.ID_SYNC)
            when (outcome) {
                SyncOutcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.failure()
                SyncOutcome.FAILED -> Result.failure()
                else -> Result.success()
            }
        }
    }

    companion object {
        const val KEY_SUBS = "subs"
        const val KEY_TRIGGER = "trigger"
        const val KEY_MEDIA_ONLY = "mediaOnly"
        const val KEY_SCHEDULED = "scheduled"
    }
}
