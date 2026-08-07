package dev.reedd.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.reedd.ReeddApp
import java.util.concurrent.TimeUnit

/**
 * Watches conversions while the app is closed.
 *
 * Fifteen minutes is the platform's floor for periodic work, and it is coarse --
 * but this only has to notice that a conversion finished, not animate a progress
 * bar. While a screen is open the ViewModel polls every few seconds through the
 * same [dev.reedd.domain.ConversionWatcher], writing to the same rows, so the
 * user sees fine-grained progress exactly when they are looking at it.
 *
 * The worker **cancels itself** once nothing is pending. Leaving a periodic job
 * registered forever would wake the device every quarter of an hour for a library
 * where every book is already converted; [enqueuePeriodic] is called again the
 * next time something is uploaded.
 */
class PollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ReeddApp).container
        val remaining = try {
            container.watcher.pollAll()
        } catch (e: Throwable) {
            // pollAll already isolates per-book failures, so anything arriving
            // here is unexpected. Retry rather than silently stop watching.
            return if (container.watcher.isTransient(e)) Result.retry() else Result.failure()
        }

        if (remaining == 0) {
            cancelPeriodic(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "poll-jobs-periodic"
        const val ONCE_NAME = "poll-jobs-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Register the background watcher. Safe to call repeatedly: KEEP means an
         * already-scheduled watcher is left alone rather than restarted, which
         * would push its next run a full period into the future.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** A single immediate sweep, for app start or a pull-to-refresh. */
        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<PollWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
