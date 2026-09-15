package com.vaulthealth.exporter

import android.content.Context
import com.vaulthealth.exporter.export.DeltaEngine
import com.vaulthealth.exporter.export.SnapshotEngine
import com.vaulthealth.exporter.export.SummaryEngine
import com.vaulthealth.exporter.hc.HealthConnectGateway
import com.vaulthealth.exporter.storage.VaultPrefs
import com.vaulthealth.exporter.storage.VaultWriter
import com.vaulthealth.exporter.storage.db.VaultDatabase

/** Minimal manual DI. Everything is app-scoped and private. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = VaultDatabase.build(appContext)

    val prefs = VaultPrefs(appContext)
    val history = database.exports()
    val gateway = HealthConnectGateway(appContext)
    val writer = VaultWriter(appContext)
    val summaries = SummaryEngine(gateway, writer, prefs)
    val snapshotEngine = SnapshotEngine(gateway, prefs, writer, history, summaries)
    val deltaEngine = DeltaEngine(gateway, prefs, writer, history, summaries)
}
