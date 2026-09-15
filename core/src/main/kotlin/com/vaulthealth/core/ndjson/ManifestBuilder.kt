package com.vaulthealth.core.ndjson

import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.RouteState
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic manifest counters. Keys are always emitted in sorted order. */
object ManifestBuilder {
    const val ZERO_VALUE = "zero_value"
    const val EMPTY_SAMPLES = "empty_samples"
    const val ROUTE_CONSENT_REQUIRED = "route_consent_required"
    const val ROUTE_NO_DATA = "route_no_data"
    const val ROUTE_UNAVAILABLE = "route_unavailable"
    const val ROUTE_MISSING = "route_missing_opt_in"

    private val SAMPLE_TYPES = setOf(
        RecordType.HEART_RATE,
        RecordType.SPEED,
        RecordType.STEPS_CADENCE,
        RecordType.CYCLING_CADENCE,
        RecordType.POWER,
    )

    fun recordCounts(records: List<HealthRecord>): Map<String, Int> =
        records.groupingBy { it.type.wireName }
            .eachCount()
            .toSortedMap()

    fun issueCounts(records: List<HealthRecord>): Map<String, Int> {
        val issues = sortedMapOf<String, Int>()
        fun bump(key: String) {
            issues[key] = (issues[key] ?: 0) + 1
        }
        for (record in records) {
            if (isAllZero(record)) bump(ZERO_VALUE)
            if (record.type in SAMPLE_TYPES && record.samples.isEmpty()) bump(EMPTY_SAMPLES)
            if (record.type == RecordType.EXERCISE) {
                when (record.route?.state) {
                    null -> bump(ROUTE_MISSING)
                    RouteState.CONSENT_REQUIRED -> bump(ROUTE_CONSENT_REQUIRED)
                    RouteState.NO_DATA -> bump(ROUTE_NO_DATA)
                    RouteState.UNAVAILABLE -> bump(ROUTE_UNAVAILABLE)
                    RouteState.AVAILABLE -> Unit
                }
            }
        }
        return issues
    }

    private fun isAllZero(record: HealthRecord): Boolean {
        if (record.values.isEmpty()) return false
        val primitives = record.values.values.filterIsInstance<JsonPrimitive>()
        if (primitives.isEmpty() || primitives.size != record.values.size) return false
        return primitives.all { primitive ->
            if (primitive.isString) primitive.content == "0" else primitive.content.toDoubleOrNull() == 0.0
        }
    }
}
