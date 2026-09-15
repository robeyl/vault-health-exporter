package com.vaulthealth.exporter.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vaulthealth.exporter.VaultHealthApp
import com.vaulthealth.exporter.domain.DeltaRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Scheduled incremental export. This is a background job: it never requests exercise-route
 * consent and never collects route points. Sessions with routes keep their
 * `consent_required`/`no_data` state and are surfaced for foreground consent.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultHealthApp).container
        val state = container.prefs.snapshot()
        val uri = state.treeUri?.let(Uri::parse) ?: return Result.success()
        val zone = ZoneId.systemDefault()

        return when (
            val run = withContext(Dispatchers.IO) {
                container.deltaEngine.run(uri, zone, allowRoutes = false)
            }
        ) {
            is DeltaRun.Completed, DeltaRun.NoChanges -> Result.success()

            is DeltaRun.NeedsSnapshot -> {
                container.prefs.setLastMessage("Scheduled export paused: ${run.prompt}")
                Result.success()
            }

            is DeltaRun.Failed -> if (runAttemptCount < 3) {
                Result.retry()
            } else {
                container.prefs.setLastMessage("Scheduled export failed: ${run.reason}")
                Result.failure()
            }
        }
    }
}
