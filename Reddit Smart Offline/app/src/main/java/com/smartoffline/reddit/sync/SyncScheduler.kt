package com.smartoffline.reddit.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.smartoffline.reddit.data.AppPrefs
import com.smartoffline.reddit.data.Repository
import com.smartoffline.reddit.data.SubredditSettings
import java.util.concurrent.TimeUnit

/**
 * One periodic WorkManager job per subreddit with automatic sync enabled, using that subreddit's own
 * interval. Android decides the exact run time (Doze, battery saver), so times are approximate.
 */
class SyncScheduler(private val context: Context, private val repo: Repository) {
    private val wm get() = WorkManager.getInstance(context)

    companion object {
        const val MANUAL = "manual-sync"
        fun periodicName(sub: String) = "auto-sync-" + sub.lowercase()
        const val TAG_PERIODIC = "auto-sync"
    }

    private fun constraints(p: AppPrefs, background: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(background && p.batteryNotLow)
        .setRequiresCharging(background && p.requireCharging)
        .setRequiresStorageNotLow(background)
        .build()

    fun schedule(s: SubredditSettings, p: AppPrefs, replace: Boolean) {
        if (!p.autoDownloads || !s.autoSync) { cancel(s.subreddit); return }
        val hours = s.intervalHours.coerceAtLeast(1).toLong()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS, (hours * 60 / 4).coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints(p, true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag(TAG_PERIODIC)
            .setInputData(workDataOf(
                SyncWorker.KEY_SUBS to arrayOf(s.subreddit),
                SyncWorker.KEY_TRIGGER to "Scheduled",
                SyncWorker.KEY_SCHEDULED to true,
            ))
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(s.subreddit),
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(sub: String) { wm.cancelUniqueWork(periodicName(sub)) }

    /** Reconciles WorkManager with the configured subreddits. */
    fun scheduleAll(p: AppPrefs, replace: Boolean) {
        val subs = repo.subreddits()
        if (!p.autoDownloads) { wm.cancelAllWorkByTag(TAG_PERIODIC); return }
        subs.forEach { schedule(repo.settings(it.name), p, replace) }
    }

    /** Starts a manual synchronization now (or as soon as the network constraint is met). */
    fun syncNow(p: AppPrefs, subs: List<String>?, mediaOnly: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(p, false))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                SyncWorker.KEY_SUBS to subs?.toTypedArray(),
                SyncWorker.KEY_TRIGGER to "Manual",
                SyncWorker.KEY_MEDIA_ONLY to mediaOnly,
            ))
            .build()
        wm.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun manualWork() = wm.getWorkInfosForUniqueWorkFlow(MANUAL)
    fun periodicWork() = wm.getWorkInfosByTagFlow(TAG_PERIODIC)
}
