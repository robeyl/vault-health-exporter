package com.vaulthealth.exporter.export

import android.net.Uri
import com.vaulthealth.core.dedup.Dedup
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.NdjsonSchema
import com.vaulthealth.core.naming.FileNames
import com.vaulthealth.core.naming.VaultPaths
import com.vaulthealth.core.ndjson.AppInfo
import com.vaulthealth.core.ndjson.DeltaLine
import com.vaulthealth.core.ndjson.DeltaOp
import com.vaulthealth.core.ndjson.ExportHeader
import com.vaulthealth.core.ndjson.ExportKind
import com.vaulthealth.core.ndjson.NdjsonCodec
import com.vaulthealth.core.token.ExportPlan
import com.vaulthealth.core.token.TokenErrorKind
import com.vaulthealth.core.token.TokenIssue
import com.vaulthealth.core.token.TokenPolicy
import com.vaulthealth.exporter.BuildConfig
import com.vaulthealth.exporter.domain.DeltaRun
import com.vaulthealth.exporter.domain.WriteOutcome
import com.vaulthealth.exporter.hc.ChangesOutcome
import com.vaulthealth.exporter.hc.HealthConnectGateway
import com.vaulthealth.exporter.hc.RecordMapper
import com.vaulthealth.exporter.storage.VaultPrefs
import com.vaulthealth.exporter.storage.VaultWriter
import com.vaulthealth.exporter.storage.db.ExportHistoryDao
import com.vaulthealth.exporter.storage.db.ExportRecordEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Incremental export driven by Health Connect change tokens.
 *
 * The new token is persisted ONLY after the delta file is durably written, so a crash can never
 * skip changes. An expired/invalid token forces the user back to a fresh snapshot — never a guess.
 */
class DeltaEngine(
    private val gateway: HealthConnectGateway,
    private val prefs: VaultPrefs,
    private val writer: VaultWriter,
    private val history: ExportHistoryDao,
    private val summaries: SummaryEngine,
) {
    suspend fun run(
        treeUri: Uri,
        zone: ZoneId,
        allowRoutes: Boolean,
        onProgress: (String) -> Unit = {},
    ): DeltaRun {
        val state = prefs.snapshot()
        val plan = TokenPolicy.plan(state.changeToken, state.tokenError)
        if (plan is ExportPlan.Resnapshot) {
            return DeltaRun.NeedsSnapshot(
                issue = plan.issue,
                prompt = TokenPolicy.promptFor(plan.issue) ?: "Run a fresh historical snapshot.",
            )
        }

        val token = (plan as ExportPlan.Incremental).token
        onProgress("Reading changes since token…")
        when (val outcome = gateway.collectChanges(token)) {
            is ChangesOutcome.TokenExpired -> {
                prefs.setTokenError(TokenErrorKind.EXPIRED)
                return DeltaRun.NeedsSnapshot(
                    issue = TokenIssue.EXPIRED,
                    prompt = TokenPolicy.promptFor(TokenIssue.EXPIRED)!!,
                )
            }

            is ChangesOutcome.Failed -> {
                prefs.setTokenError(TokenPolicy.classify(outcome.reason))
                return DeltaRun.Failed(outcome.reason)
            }

            is ChangesOutcome.Ok -> {
                val exportedAt = Instant.now()
                val upserts = Dedup.dedupe(
                    outcome.upserts.mapNotNull { RecordMapper.map(it, allowRoutes) },
                ).records
                val tombstones = outcome.deletedIds.map { id ->
                    DeltaLine(op = DeltaOp.DELETE, id = id, deletedAt = exportedAt.toString())
                }

                if (upserts.isEmpty() && tombstones.isEmpty()) {
                    prefs.setChangeToken(outcome.nextToken)
                    prefs.setTokenError(TokenErrorKind.NONE)
                    return DeltaRun.NoChanges
                }

                val header = ExportHeader(
                    schema = NdjsonSchema.NAME,
                    version = NdjsonSchema.VERSION,
                    kind = ExportKind.DELTA,
                    exportedAt = exportedAt.toString(),
                    changeTokenBefore = token,
                    changeTokenAfter = outcome.nextToken,
                    app = AppInfo(version = BuildConfig.VERSION_NAME),
                )
                val built = NdjsonCodec.buildDelta(header, upserts, tombstones)
                val fileName = uniqueDeltaName(treeUri, exportedAt)
                onProgress("Writing $fileName…")
                val fileOutcome = writer.writeImmutable(treeUri, VaultPaths.DELTAS, fileName, built.fileText)
                if (fileOutcome !is WriteOutcome.Written) {
                    return DeltaRun.Failed(
                        (fileOutcome as? WriteOutcome.Failed)?.reason
                            ?: "$fileName already exists; nothing was changed",
                    )
                }

                writer.writeChecksum(treeUri, VaultPaths.DELTAS, fileName, built.fileSha256)
                // Only now is it safe to advance the token.
                prefs.setChangeToken(outcome.nextToken)
                prefs.setTokenError(TokenErrorKind.NONE)
                prefs.recordDelta(exportedAt.toString(), fileName)
                prefs.setLastMessage(
                    "Delta $fileName: ${upserts.size} upserts, ${tombstones.size} deletions",
                )

                val summaryOutcomes = summaries.writeByFetch(
                    treeUri = treeUri,
                    days = affectedDays(upserts, zone, exportedAt),
                    zone = zone,
                    generatedAt = exportedAt,
                )

                history.insert(
                    ExportRecordEntity(
                        fileName = fileName,
                        kind = "delta",
                        dirPath = VaultPaths.DELTAS,
                        recordCount = upserts.size + tombstones.size,
                        upserts = upserts.size,
                        deletions = tombstones.size,
                        summaries = summaryOutcomes.count { it is WriteOutcome.Written },
                        sha256 = built.fileSha256,
                        verified = fileOutcome.verified,
                        createdAt = exportedAt.toString(),
                        status = "written",
                    ),
                )

                return DeltaRun.Completed(
                    fileName = fileName,
                    upserts = upserts.size,
                    deletions = tombstones.size,
                    summaries = summaryOutcomes.count { it is WriteOutcome.Written },
                )
            }
        }
    }

    /**
     * Foreground-only route patch. Written after the user grants route access for a session via
     * [androidx.health.connect.client.contracts.ExerciseRouteRequestContract]; never called from
     * background work. Immutable, like every other export.
     */
    suspend fun writeRoutePatch(treeUri: Uri, record: HealthRecord): DeltaRun {
        val exportedAt = Instant.now()
        val header = ExportHeader(
            schema = NdjsonSchema.NAME,
            version = NdjsonSchema.VERSION,
            kind = ExportKind.DELTA,
            exportedAt = exportedAt.toString(),
            app = AppInfo(version = BuildConfig.VERSION_NAME),
        )
        val built = NdjsonCodec.buildDelta(header, listOf(record), emptyList())
        val fileName = uniqueName(
            treeUri,
            VaultPaths.DELTAS,
            "health-connect-route-${FileNames.compactUtc(exportedAt)}.ndjson",
        )
        val fileOutcome = writer.writeImmutable(treeUri, VaultPaths.DELTAS, fileName, built.fileText)
        if (fileOutcome !is WriteOutcome.Written) {
            return DeltaRun.Failed("Could not write route patch $fileName")
        }
        writer.writeChecksum(treeUri, VaultPaths.DELTAS, fileName, built.fileSha256)
        history.insert(
            ExportRecordEntity(
                fileName = fileName,
                kind = "route",
                dirPath = VaultPaths.DELTAS,
                recordCount = 1,
                upserts = 1,
                deletions = 0,
                summaries = 0,
                sha256 = built.fileSha256,
                verified = fileOutcome.verified,
                createdAt = exportedAt.toString(),
                status = "written",
            ),
        )
        return DeltaRun.Completed(fileName, 1, 0, 0)
    }

    private fun affectedDays(upserts: List<HealthRecord>, zone: ZoneId, now: Instant): List<LocalDate> {
        val days = upserts.mapNotNull { record ->
            runCatching { Instant.parse(record.startTime).atZone(zone).toLocalDate() }.getOrNull()
        }.toSortedSet()
        days.add(now.atZone(zone).toLocalDate())
        return days.toList().takeLast(14)
    }

    private fun uniqueDeltaName(treeUri: Uri, exportedAt: Instant): String {
        val base = FileNames.delta(exportedAt)
        return uniqueName(treeUri, VaultPaths.DELTAS, base)
    }

    private fun uniqueName(treeUri: Uri, dirPath: String, baseName: String): String {
        if (!writer.exists(treeUri, dirPath, baseName)) return baseName
        val stem = baseName.removeSuffix(".ndjson")
        var index = 2
        while (index < 1000) {
            val candidate = "$stem-$index.ndjson"
            if (!writer.exists(treeUri, dirPath, candidate)) return candidate
            index++
        }
        return baseName
    }
}
