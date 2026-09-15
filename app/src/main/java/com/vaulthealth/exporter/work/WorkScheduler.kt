package com.vaulthealth.exporter.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vaulthealth.exporter.domain.ScheduleCadence
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val UNIQUE_PERIODIC_WORK = "vault-health-export-periodic"
    const val UNIQUE_CATCH_UP_WORK = "vault-health-export-catchup"
    const val KEY_TASK = "task"
    const val TASK_DELTA = "delta"

    fun apply(context: Context, cadence: ScheduleCadence) {
        val workManager = WorkManager.getInstance(context)
        if (cadence == ScheduleCadence.NONE) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val intervalDays = if (cadence == ScheduleCadence.WEEKLY) 7L else 1L
        val request = PeriodicWorkRequestBuilder<ExportWorker>(
            intervalDays,
            TimeUnit.DAYS,
            30,
            TimeUnit.MINUTES,
        )
            .setInputData(workDataOf(KEY_TASK to TASK_DELTA))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Fired on boot/app open. WorkManager persists periodic work, but a missed window or a
     * rebooted device can leave a gap; this one-shot closes it without duplicating work.
     */
    fun enqueueCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(workDataOf(KEY_TASK to TASK_DELTA))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_CATCH_UP_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
