package com.vaulthealth.exporter.hc

import android.content.Context
import androidx.health.connect.client.ExperimentalDeduplicationApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

enum class SdkAvailability { AVAILABLE, UPDATE_REQUIRED, NOT_SUPPORTED }

/** Health Connect's `ReadRecordsRequest.DEDUPLICATION_STRATEGY_DISABLED`. */
private const val DEDUPLICATION_DISABLED = 0

sealed interface ChangesOutcome {
    data class Ok(
        val upserts: List<Record>,
        val deletedIds: List<String>,
        val nextToken: String,
    ) : ChangesOutcome

    data object TokenExpired : ChangesOutcome

    data class Failed(val reason: String) : ChangesOutcome
}

/**
 * Thin coroutine wrapper around [HealthConnectClient]. No policy here — just IO and pagination.
 */
class HealthConnectGateway(private val context: Context) {

    fun availability(): SdkAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> SdkAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SdkAvailability.UPDATE_REQUIRED
        else -> SdkAvailability.NOT_SUPPORTED
    }

    fun clientOrNull(): HealthConnectClient? =
        if (availability() == SdkAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    private fun requireClient(): HealthConnectClient =
        clientOrNull() ?: error("Health Connect is not available on this device")

    suspend fun grantedPermissions(): Set<String> = requireClient().permissionController.getGrantedPermissions()

    /** Reads every page of a record type inside [range]. Deduplication is disabled to keep raw data. */
    @OptIn(ExperimentalDeduplicationApi::class)
    suspend fun <T : Record> readAll(klass: KClass<T>, start: Instant, end: Instant): List<T> {
        val client = requireClient()
        val range = TimeRangeFilter.between(start, end)
        val out = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    klass,
                    range,
                    emptySet(),
                    true,
                    1000,
                    pageToken,
                    // Health Connect's ReadRecordsRequest.DEDUPLICATION_STRATEGY_DISABLED (0);
                    // referenced numerically because its companion is internal to the library.
                    DEDUPLICATION_DISABLED,
                ),
            )
            out.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    /**
     * Like [readAll] but never fails the caller: a missing/ungranted permission for one record
     * type must not abort a whole export.
     */
    suspend fun <T : Record> readAllOrEmpty(klass: KClass<T>, start: Instant, end: Instant): List<T> =
        runCatching { readAll(klass, start, end) }.getOrDefault(emptyList())

    suspend fun <T : Record> readOne(klass: KClass<T>, recordId: String): T? =
        runCatching { requireClient().readRecord(klass, recordId).record }.getOrNull()

    suspend fun newChangesToken(types: Set<KClass<out Record>>): String =
        requireClient().getChangesToken(ChangesTokenRequest(types, emptySet()))

    /** Drains every page of changes reachable from [token]. */
    suspend fun collectChanges(token: String): ChangesOutcome {
        return try {
            val client = requireClient()
            val upserts = ArrayList<Record>()
            val deletions = ArrayList<String>()
            var cursor = token
            while (true) {
                val response = client.getChanges(cursor)
                if (response.changesTokenExpired) return ChangesOutcome.TokenExpired
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> upserts.add(change.record)
                        is DeletionChange -> deletions.add(change.recordId)
                    }
                }
                cursor = response.nextChangesToken
                if (!response.hasMore) break
            }
            ChangesOutcome.Ok(upserts, deletions, cursor)
        } catch (t: Throwable) {
            ChangesOutcome.Failed(t.message ?: t::class.java.simpleName)
        }
    }
}
