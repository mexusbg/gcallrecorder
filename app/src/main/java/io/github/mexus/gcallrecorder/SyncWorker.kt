package io.github.mexus.gcallrecorder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync. The in-activity ContactsObserver only fires while the app is open;
 * this WorkManager job is the persistent trigger so contact changes made with the app closed still
 * reach the dialer's always-record list. Survives reboot on its own (WorkManager re-schedules).
 * Android's minimum periodic interval is 15 minutes, so a change can lag up to that long.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!Syncer.autoSyncEnabled(applicationContext)) return Result.success()
        // syncNow is diff-skipping and in-call-deferring already; a failed write should be retried.
        return runCatching {
            when (Syncer.syncNow(applicationContext)) {
                is SyncOutcome.Wrote -> Result.success()
                else -> Result.success() // Skipped/NoCurrent/Deferred: nothing to retry
            }
        }.getOrElse { Result.retry() }
    }
}

object SyncScheduler {
    private const val WORK = "gcallrecorder_periodic"

    /** (Re)schedule or cancel the periodic job to match the stored interval. Interval 0 = Never. */
    fun apply(ctx: Context) {
        val minutes = Syncer.syncIntervalMinutes(ctx)
        if (minutes <= 0) {
            cancel(ctx)
            return
        }
        // WorkManager floors periodic intervals at 15 min; all offered options are >= 15.
        val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        // UPDATE (not KEEP) so a changed interval actually replaces the existing schedule.
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
}
