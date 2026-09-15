package com.vaulthealth.core.naming

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Vault-relative locations. All writes are confined to these directories. */
object VaultPaths {
    const val RAW_ROOT = "90 Private/Health/Raw/health-connect"
    const val SNAPSHOTS = "$RAW_ROOT/snapshots"
    const val DELTAS = "$RAW_ROOT/deltas"
    const val SUMMARIES = "90 Private/Health/Summaries"
}

object FileNames {
    private val COMPACT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'")

    fun snapshot(start: LocalDate, end: LocalDate, disambiguator: Int? = null): String {
        val base = "health-connect-${start}_to_${end}"
        return if (disambiguator == null) "$base.ndjson" else "$base-$disambiguator.ndjson"
    }

    fun delta(endedAt: Instant, disambiguator: Int? = null): String {
        val base = "health-connect-delta-${compactUtc(endedAt)}"
        return if (disambiguator == null) "$base.ndjson" else "$base-$disambiguator.ndjson"
    }

    fun summary(date: LocalDate): String = "$date.md"

    fun checksum(fileName: String): String = "$fileName.sha256"

    fun compactUtc(instant: Instant): String = COMPACT.format(instant.atZone(ZoneOffset.UTC))
}
