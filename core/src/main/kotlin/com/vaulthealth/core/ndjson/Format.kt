package com.vaulthealth.core.ndjson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Snapshot or delta bundle. */
@Serializable
enum class ExportKind {
    @SerialName("snapshot") SNAPSHOT,
    @SerialName("delta") DELTA,
}

@Serializable
data class AppInfo(
    val name: String = "Vault Health Exporter",
    val version: String,
)

@Serializable
data class RequestedRange(
    /** ISO-8601 UTC instant, inclusive. */
    val start: String,
    /** ISO-8601 UTC instant, inclusive. */
    val end: String,
    /** IANA zone id the range was interpreted in, e.g. Pacific/Auckland. */
    val timezone: String,
)

/** First line of every export file. */
@Serializable
data class ExportHeader(
    val schema: String,
    val version: Int,
    val kind: ExportKind,
    @SerialName("exported_at") val exportedAt: String,
    val source: String = "health-connect",
    @SerialName("requested_range") val requestedRange: RequestedRange? = null,
    @SerialName("change_token_before") val changeTokenBefore: String? = null,
    @SerialName("change_token_after") val changeTokenAfter: String? = null,
    val app: AppInfo? = null,
)

@Serializable
data class Checksum(
    val algorithm: String = "sha256",
    /** Documents exactly what the value covers. */
    val scope: String,
    val value: String,
)

/** Final line of every export file. */
@Serializable
data class ExportManifest(
    val kind: String = "manifest",
    @SerialName("record_total") val recordTotal: Int,
    @SerialName("record_counts") val recordCounts: Map<String, Int> = emptyMap(),
    @SerialName("issue_counts") val issueCounts: Map<String, Int> = emptyMap(),
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("requested_range") val requestedRange: RequestedRange? = null,
    val upserts: Int? = null,
    val deletions: Int? = null,
    @SerialName("change_token_before") val changeTokenBefore: String? = null,
    @SerialName("change_token_after") val changeTokenAfter: String? = null,
    val checksum: Checksum,
)

@Serializable
enum class DeltaOp {
    @SerialName("upsert") UPSERT,
    @SerialName("delete") DELETE,
}

/**
 * A single delta line. Upsert lines carry a full [record]; delete lines carry only the
 * tombstone identity so the file stays a lossless change log.
 */
@Serializable
data class DeltaLine(
    val op: DeltaOp,
    val record: com.vaulthealth.core.model.HealthRecord? = null,
    val id: String? = null,
    val type: String? = null,
    @SerialName("source_package") val sourcePackage: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)
