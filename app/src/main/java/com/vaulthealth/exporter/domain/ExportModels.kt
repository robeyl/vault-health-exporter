package com.vaulthealth.exporter.domain

import com.vaulthealth.core.token.TokenIssue
import kotlinx.serialization.Serializable

enum class ScheduleCadence { NONE, DAILY, WEEKLY }

@Serializable
data class PendingRouteConsent(
    val sessionId: String,
    val title: String,
    val startTime: String,
)

sealed interface WriteOutcome {
    data class Written(
        val fileName: String,
        val sha256: String,
        val bytes: Int,
        val verified: Boolean,
    ) : WriteOutcome

    data class AlreadyExists(val fileName: String) : WriteOutcome
    data class Failed(val reason: String) : WriteOutcome
}

data class ExportRunResult(
    val kind: String,
    val outcomes: List<WriteOutcome>,
    val recordTotal: Int,
    val message: String,
)

sealed interface DeltaRun {
    data class Completed(
        val fileName: String,
        val upserts: Int,
        val deletions: Int,
        val summaries: Int,
    ) : DeltaRun

    data class NeedsSnapshot(val issue: TokenIssue, val prompt: String) : DeltaRun
    data class Failed(val reason: String) : DeltaRun
    data object NoChanges : DeltaRun
}
