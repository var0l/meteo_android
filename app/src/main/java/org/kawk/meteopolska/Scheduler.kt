package org.kawk.meteopolska

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {

    private fun networkConstraint() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Ensure the 30-minute periodic capture is scheduled. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<MeteoCaptureWorker>(
            Constants.REFRESH_INTERVAL_MIN, TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraint())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            Constants.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Trigger an immediate one-off capture (manual refresh button). */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MeteoCaptureWorker>()
            .setConstraints(networkConstraint())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            Constants.ONESHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(Constants.PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(Constants.ONESHOT_WORK)
    }
}
