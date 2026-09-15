package com.vaulthealth.exporter.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-schedules after reboot or app update; WorkManager does not always survive both. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkScheduler.enqueueCatchUp(context)
    }
}
