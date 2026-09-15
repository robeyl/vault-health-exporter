package com.vaulthealth.exporter.export

import android.net.Uri
import com.vaulthealth.core.daterange.DateRange
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.naming.FileNames
import com.vaulthealth.core.naming.VaultPaths
import com.vaulthealth.core.summary.DailySummaryGenerator
import com.vaulthealth.core.summary.SummaryAggregator
import com.vaulthealth.exporter.domain.WriteOutcome
import com.vaulthealth.exporter.hc.HealthConnectGateway
import com.vaulthealth.exporter.hc.HealthPermissions
import com.vaulthealth.exporter.hc.RecordMapper
import com.vaulthealth.exporter.storage.VaultPrefs
import com.vaulthealth.exporter.storage.VaultWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes deterministic, LLM-free daily Markdown notes. Summaries are derived artifacts and may
 * be regenerated/overwritten; raw record data is never embedded, only aggregates.
 */
class SummaryEngine(
    private val gateway: HealthConnectGateway,
    private val writer: VaultWriter,
    private val prefs: VaultPrefs,
) {
    /** Uses records already fetched (snapshot path) — no extra Health Connect reads. */
    suspend fun writeFromRecords(
        treeUri: Uri,
        records: List<HealthRecord>,
        days: List<LocalDate>,
        zone: ZoneId,
        generatedAt: Instant,
    ): List<WriteOutcome> = days.map { day -> writeDay(treeUri, day, zone, generatedAt, records) }

    /** Fetches exactly the affected days (delta path). Records are read per day. */
    suspend fun writeByFetch(
        treeUri: Uri,
        days: List<LocalDate>,
        zone: ZoneId,
        generatedAt: Instant = Instant.now(),
    ): List<WriteOutcome> = days.map { day ->
        val range = DateRange.ofLocalDates(day, day, zone)
        val records = HealthPermissions.recordTypes
            .flatMap { gateway.readAll(it, range.start, range.end) }
            .mapNotNull { RecordMapper.map(it, allowRoutes = false) }
        writeDay(treeUri, day, zone, generatedAt, records)
    }

    private fun writeDay(
        treeUri: Uri,
        day: LocalDate,
        zone: ZoneId,
        generatedAt: Instant,
        records: List<HealthRecord>,
    ): WriteOutcome {
        val aggregate = SummaryAggregator.aggregate(day, zone, generatedAt, records)
        val markdown = DailySummaryGenerator.render(aggregate)
        return writer.writeDerived(treeUri, VaultPaths.SUMMARIES, FileNames.summary(day), markdown)
    }
}
