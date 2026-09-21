package com.vaulthealth.exporter.export

import android.net.Uri
import com.vaulthealth.core.checksum.ContentHash
import com.vaulthealth.core.checksum.Sha256
import com.vaulthealth.core.dedup.Dedup
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.NdjsonSchema
import com.vaulthealth.core.model.RecordType
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
import androidx.health.connect.client.permission.HealthPermission
import com.vaulthealth.exporter.hc.HealthConnectGateway
import com.vaulthealth.exporter.hc.RecordMapper
import com.vaulthealth.exporter.storage.VaultPrefs
import com.vaulthealth.exporter.storage.VaultWriter
import com.vaulthealth.exporter.storage.db.ExportHistoryDao
import com.vaulthealth.exporter.storage.db.ExportRecordEntity
import com.vaulthealth.core.daterange.DateRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
        // Health Connect refuses a change token that includes even one record type we lack
        // read access to, so only ask about the types that are actually granted.
        val grantedPermissions = gateway.grantedPermissions()
        val readableTypes = HealthPermissions.recordTypes.filter { klass ->
            val permission = runCatching { HealthPermission.getReadPermission(klass) }.getOrNull()
            permission != null && permission in grantedPermissions
        }.toSet()
        val tokenBefore = gateway.newChangesToken(readableTypes)

        onProgress("Reading Health Connect records…")
        val native = readableTypes.flatMap { klass ->
            gateway.readAllOrEmpty(klass, range.start, range.end)
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

        // Refuse to write the same data twice, whatever the filename would have been.
        val contentId = ContentHash.ofRecords(deduped.records)
        if (history.countByContent("snapshot", contentId) > 0) {
            return ExportRunResult(
                kind = "snapshot",
                outcomes = listOf(WriteOutcome.Skipped("(duplicate)", "identical snapshot already exported")),
                recordTotal = deduped.records.size,
                message = "Identical data was already exported; nothing written",
            )
        }

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
                days = summaryDays(deduped.records, zone),
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
                    contentId = contentId,
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
        is WriteOutcome.Skipped -> "Nothing to export: ${outcome.reason}"
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

    /**
     * Summary days are derived from the records that actually exist, not from the requested
     * range. An "all time" snapshot therefore does not create a note for every day since 2000.
     * Capped to the most recent [MAX_SUMMARY_DAYS] days with data.
     */
    private fun summaryDays(records: List<HealthRecord>, zone: ZoneId): List<LocalDate> {
        val days = sortedSetOf<LocalDate>()
        records.forEach { record ->
            val anchor = if (record.type == RecordType.SLEEP && record.endTime != null) {
                record.endTime
            } else {
                record.startTime
            }
            runCatching { Instant.parse(anchor).atZone(zone).toLocalDate() }
                .getOrNull()
                ?.let(days::add)
        }
        return days.toList().takeLast(MAX_SUMMARY_DAYS)
    }

    private companion object {
        const val MAX_SUMMARY_DAYS = 400
    }
}

private fun HealthRecord.titleOrId(): String =
    (values[SummaryAggregator.KEY_TITLE] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: (values[SummaryAggregator.KEY_EXERCISE_TYPE] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: "Workout"
