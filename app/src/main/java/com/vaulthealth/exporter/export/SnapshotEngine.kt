package com.vaulthealth.exporter.export

import android.net.Uri
import com.vaulthealth.core.checksum.Sha256
import com.vaulthealth.core.dedup.Dedup
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.NdjsonSchema
import com.vaulthealth.core.model.VheJson
import com.vaulthealth.core.model.RouteState
import com.vaulthealth.core.naming.FileNames
import com.vaulthealth.core.naming.VaultPaths
import com.vaulthealth.core.ndjson.AppInfo
import com.vaulthealth.core.ndjson.ExportHeader
import com.vaulthealth.core.ndjson.ExportKind
import com.vaulthealth.core.ndjson.NdjsonCodec
import com.vaulthealth.core.ndjson.RequestedRange
import com.vaulthealth.core.summary.SummaryAggregator
import com.vaulthealth.core.token.TokenErrorKind
import com.vaulthealth.exporter.BuildConfig
import com.vaulthealth.exporter.domain.ExportRunResult
import com.vaulthealth.exporter.domain.PendingRouteConsent
import com.vaulthealth.exporter.domain.WriteOutcome
import com.vaulthealth.exporter.hc.HealthPermissions
import com.vaulthealth.exporter.hc.HealthConnectGateway
import com.vaulthealth.exporter.hc.RecordMapper
import com.vaulthealth.exporter.storage.VaultPrefs
import com.vaulthealth.exporter.storage.VaultWriter
import com.vaulthealth.exporter.storage.db.ExportHistoryDao
import com.vaulthealth.exporter.storage.db.ExportRecordEntity
import com.vaulthealth.core.daterange.DateRange
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One-time historical snapshot: reads a date range, writes a single immutable NDJSON file,
 * a sibling `.sha256`, then seeds the change token so incremental deltas can follow.
 */
class SnapshotEngine(
    private val gateway: HealthConnectGateway,
    private val prefs: VaultPrefs,
    private val writer: VaultWriter,
    private val history: ExportHistoryDao,
    private val summaries: SummaryEngine,
) {
    suspend fun run(
        treeUri: Uri,
        range: DateRange,
        zone: ZoneId,
        allowRoutes: Boolean,
        onProgress: (String) -> Unit,
    ): ExportRunResult {
        val exportedAt = Instant.now()
        onProgress("Requesting a change token…")
        // Taken BEFORE reading so no changes can slip between snapshot and first delta.
        val tokenBefore = gateway.newChangesToken(HealthPermissions.recordTypes)

        onProgress("Reading Health Connect records…")
        val native = HealthPermissions.recordTypes.flatMap { klass ->
            gateway.readAll(klass, range.start, range.end)
        }
        val mapped = native.mapNotNull { RecordMapper.map(it, allowRoutes) }
        val deduped = Dedup.dedupe(mapped)
        onProgress("${deduped.records.size} records (${deduped.duplicatesRemoved} duplicates removed)")

        val header = ExportHeader(
            schema = NdjsonSchema.NAME,
            version = NdjsonSchema.VERSION,
            kind = ExportKind.SNAPSHOT,
            exportedAt = exportedAt.toString(),
            requestedRange = range.asRequestedRange(zone),
            changeTokenBefore = tokenBefore,
            app = AppInfo(version = BuildConfig.VERSION_NAME),
        )
        val built = NdjsonCodec.buildSnapshot(header, deduped.records)

        val baseName = FileNames.snapshot(range.startLocalDate(zone), range.endLocalDate(zone))
        val fileName = uniqueName(treeUri, VaultPaths.SNAPSHOTS, baseName)
        onProgress("Writing $fileName…")
        val outcomes = mutableListOf<WriteOutcome>()
        val fileOutcome = writer.writeImmutable(treeUri, VaultPaths.SNAPSHOTS, fileName, built.fileText)
        outcomes += fileOutcome

        var summariesWritten = 0
        if (fileOutcome is WriteOutcome.Written) {
            outcomes += writer.writeChecksum(treeUri, VaultPaths.SNAPSHOTS, fileName, built.fileSha256)
            prefs.setChangeToken(tokenBefore)
            prefs.setTokenError(TokenErrorKind.NONE)
            prefs.recordSnapshot(exportedAt.toString(), fileName)
            prefs.setLastMessage("Snapshot $fileName written (${deduped.records.size} records)")

            val pending = deduped.records
                .filter { it.type == com.vaulthealth.core.model.RecordType.EXERCISE }
                .filter { it.route?.state == RouteState.CONSENT_REQUIRED }
                .map { PendingRouteConsent(it.id, it.titleOrId(), it.startTime) }
            prefs.setPendingRoutes(pending)

            onProgress("Writing daily summaries…")
            val dayOutcomes = summaries.writeFromRecords(
                treeUri = treeUri,
                records = deduped.records,
                days = daysIn(range, zone),
                zone = zone,
                generatedAt = exportedAt,
            )
            outcomes += dayOutcomes
            summariesWritten = dayOutcomes.count { it is WriteOutcome.Written }

            history.insert(
                ExportRecordEntity(
                    fileName = fileName,
                    kind = "snapshot",
                    dirPath = VaultPaths.SNAPSHOTS,
                    recordCount = deduped.records.size,
                    upserts = 0,
                    deletions = 0,
                    summaries = summariesWritten,
                    sha256 = built.fileSha256,
                    verified = fileOutcome.verified,
                    createdAt = exportedAt.toString(),
                    status = "written",
                ),
            )
        } else {
            history.insert(
                ExportRecordEntity(
                    fileName = fileName,
                    kind = "snapshot",
                    dirPath = VaultPaths.SNAPSHOTS,
                    recordCount = deduped.records.size,
                    upserts = 0,
                    deletions = 0,
                    summaries = 0,
                    sha256 = built.fileSha256,
                    verified = false,
                    createdAt = exportedAt.toString(),
                    status = fileOutcome::class.java.simpleName,
                ),
            )
        }

        return ExportRunResult(
            kind = "snapshot",
            outcomes = outcomes,
            recordTotal = deduped.records.size,
            message = messageFor(fileOutcome, fileName),
        )
    }

    private fun messageFor(outcome: WriteOutcome, fileName: String): String = when (outcome) {
        is WriteOutcome.Written -> "Wrote $fileName and $fileName.sha256"
        is WriteOutcome.AlreadyExists -> "$fileName already exists; re-run to create a disambiguated copy"
        is WriteOutcome.Failed -> "Snapshot failed: ${outcome.reason}"
    }

    private fun uniqueName(treeUri: Uri, dirPath: String, baseName: String): String {
        if (!writer.exists(treeUri, dirPath, baseName)) return baseName
        val stem = baseName.removeSuffix(".ndjson")
        var index = 2
        while (index < 100) {
            val candidate = "$stem-$index.ndjson"
            if (!writer.exists(treeUri, dirPath, candidate)) return candidate
            index++
        }
        return baseName
    }

    private fun daysIn(range: DateRange, zone: ZoneId): List<LocalDate> {
        val start = range.startLocalDate(zone)
        val end = range.endLocalDate(zone)
        val total = ChronoUnit.DAYS.between(start, end)
        if (total < 0) return emptyList()
        return (0..total).map { start.plusDays(it) }
    }
}

private fun HealthRecord.titleOrId(): String =
    (values[SummaryAggregator.KEY_TITLE] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: (values[SummaryAggregator.KEY_EXERCISE_TYPE] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: "Workout"
