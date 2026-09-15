package com.vaulthealth.exporter.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vaulthealth.exporter.VaultHealthApp
import com.vaulthealth.exporter.watch.WatcherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-schedules after reboot or app update; WorkManager does not always survive both. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        WorkScheduler.enqueueCatchUp(context)

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = (appContext as VaultHealthApp).container.prefs.snapshot()
                if (prefs.watcherSeconds > 0) {
                    // Starting a foreground service from boot may be refused; ignore and let the
                    // next app open restart it.
                    runCatching { WatcherService.start(appContext) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
