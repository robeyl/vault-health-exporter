package com.vaulthealth.exporter.hc

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.Route
import com.vaulthealth.core.model.RoutePoint
import com.vaulthealth.core.model.RouteState
import com.vaulthealth.core.model.Sample
import com.vaulthealth.core.model.Stage
import com.vaulthealth.core.summary.SummaryAggregator
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/**
 * Converts a native Health Connect record into the normalised, lossless export model.
 *
 * [allowRoutes] is false for background work: route points are never collected there. If a
 * session does have route data, the state is recorded as CONSENT_REQUIRED so the foreground
 * app can offer the "Grant route access" action.
 */
object RecordMapper {

    fun map(record: Record, allowRoutes: Boolean): HealthRecord? = when (record) {
        is StepsRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.STEPS,
            values = mapOf(SummaryAggregator.KEY_STEPS_COUNT to JsonPrimitive(record.count)),
        )

        is DistanceRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.DISTANCE,
            values = mapOf(SummaryAggregator.KEY_METERS to JsonPrimitive(record.distance.inMeters)),
        )

        is ActiveCaloriesBurnedRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.ACTIVE_CALORIES,
            values = mapOf(SummaryAggregator.KEY_KILOCALORIES to JsonPrimitive(record.energy.inKilocalories)),
        )

        is TotalCaloriesBurnedRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.TOTAL_CALORIES,
            values = mapOf(SummaryAggregator.KEY_KILOCALORIES to JsonPrimitive(record.energy.inKilocalories)),
        )

        is ExerciseSessionRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.EXERCISE,
            values = exerciseValues(record),
            stages = record.segments.map {
                Stage(
                    stage = "SEGMENT_${it.segmentType}",
                    start = it.startTime.toString(),
                    end = it.endTime.toString(),
                )
            },
            route = mapRoute(record.exerciseRouteResult, allowRoutes),
        )

        is HeartRateRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.HEART_RATE,
            samples = record.samples.map { Sample(it.time.toString(), JsonPrimitive(it.beatsPerMinute)) },
        )

        is SpeedRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.SPEED,
            samples = record.samples.map { Sample(it.time.toString(), JsonPrimitive(it.speed.inMetersPerSecond)) },
        )

        is StepsCadenceRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.STEPS_CADENCE,
            samples = record.samples.map { Sample(it.time.toString(), JsonPrimitive(it.rate)) },
        )

        is CyclingPedalingCadenceRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.CYCLING_CADENCE,
            samples = record.samples.map { Sample(it.time.toString(), JsonPrimitive(it.revolutionsPerMinute)) },
        )

        is SleepSessionRecord -> interval(
            metadata = record.metadata,
            startTime = record.startTime,
            endTime = record.endTime,
            zoneOffset = record.startZoneOffset,
            type = RecordType.SLEEP,
            values = buildMap {
                record.title?.let { put(SummaryAggregator.KEY_TITLE, JsonPrimitive(it)) }
            },
            stages = record.stages.map {
                Stage(
                    stage = SleepSessionRecord.STAGE_TYPE_INT_TO_STRING_MAP[it.stage] ?: "UNKNOWN",
                    start = it.startTime.toString(),
                    end = it.endTime.toString(),
                )
            },
        )

        is RestingHeartRateRecord -> instant(
            metadata = record.metadata,
            time = record.time,
            zoneOffset = record.zoneOffset,
            type = RecordType.RESTING_HEART_RATE,
            values = mapOf(SummaryAggregator.KEY_BPM to JsonPrimitive(record.beatsPerMinute)),
        )

        is OxygenSaturationRecord -> instant(
            metadata = record.metadata,
            time = record.time,
            zoneOffset = record.zoneOffset,
            type = RecordType.OXYGEN_SATURATION,
            // Health Connect reports saturation as a 0..1 fraction; export percentage points.
            values = mapOf(SummaryAggregator.KEY_PERCENT to JsonPrimitive(record.percentage.value * 100.0)),
        )

        is WeightRecord -> instant(
            metadata = record.metadata,
            time = record.time,
            zoneOffset = record.zoneOffset,
            type = RecordType.WEIGHT,
            values = mapOf(SummaryAggregator.KEY_KILOGRAMS to JsonPrimitive(record.weight.inKilograms)),
        )

        // Everything else (blood pressure, nutrition, HRV, VO2 max, …) goes through the generic
        // reflective mapper so no Health Connect record type is dropped.
        else -> HealthPermissions.typeOf(record)?.let { GenericRecordMapper.map(record, it) }
    }

    private fun exerciseValues(record: ExerciseSessionRecord): Map<String, JsonPrimitive> = buildMap {
        put(
            SummaryAggregator.KEY_EXERCISE_TYPE,
            JsonPrimitive(
                ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[record.exerciseType]
                    ?: record.exerciseType.toString(),
            ),
        )
        record.title?.let { put(SummaryAggregator.KEY_TITLE, JsonPrimitive(it)) }
    }

    private fun interval(
        metadata: Metadata,
        startTime: Instant,
        endTime: Instant,
        zoneOffset: ZoneOffset?,
        type: RecordType,
        values: Map<String, JsonPrimitive> = emptyMap(),
        samples: List<Sample> = emptyList(),
        stages: List<Stage> = emptyList(),
        route: Route? = null,
    ): HealthRecord = HealthRecord(
        id = metadata.id,
        type = type,
        sourcePackage = metadata.dataOrigin.packageName,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        zoneOffset = zoneOffset?.toString(),
        values = values,
        samples = samples,
        stages = stages,
        route = route,
    )

    private fun instant(
        metadata: Metadata,
        time: Instant,
        zoneOffset: ZoneOffset?,
        type: RecordType,
        values: Map<String, JsonPrimitive> = emptyMap(),
    ): HealthRecord = HealthRecord(
        id = metadata.id,
        type = type,
        sourcePackage = metadata.dataOrigin.packageName,
        startTime = time.toString(),
        endTime = null,
        zoneOffset = zoneOffset?.toString(),
        values = values,
    )

    private fun mapRoute(result: ExerciseRouteResult?, allowRoutes: Boolean): Route? = when (result) {
        null -> null
        is ExerciseRouteResult.ConsentRequired -> Route(RouteState.CONSENT_REQUIRED)
        is ExerciseRouteResult.NoData -> Route(RouteState.NO_DATA)
        is ExerciseRouteResult.Data -> if (allowRoutes) {
            Route(
                state = RouteState.AVAILABLE,
                points = result.exerciseRoute.route.map { location ->
                    RoutePoint(
                        t = location.time.toString(),
                        lat = location.latitude,
                        lon = location.longitude,
                        alt = location.altitude?.inMeters,
                    )
                },
            )
        } else {
            // Route exists but we are in the background: defer collection to a foreground grant.
            Route(RouteState.CONSENT_REQUIRED)
        }

        else -> Route(RouteState.UNAVAILABLE)
    }
}
