package com.vaulthealth.core.summary

import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.Route
import com.vaulthealth.core.model.RouteState
import com.vaulthealth.core.model.Sample
import com.vaulthealth.core.model.Stage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SummaryAggregatorTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun record(
        id: String,
        type: RecordType,
        start: String,
        end: String? = null,
        values: Map<String, JsonPrimitive> = emptyMap(),
        samples: List<Sample> = emptyList(),
        stages: List<Stage> = emptyList(),
        route: Route? = null,
    ) = HealthRecord(
        id = id,
        type = type,
        sourcePackage = "com.xiaomi.wearable",
        startTime = start,
        endTime = end,
        values = values,
        samples = samples,
        stages = stages,
        route = route,
    )

    @Test
    fun `aggregates totals for a single day`() {
        val day = LocalDate.parse("2026-09-14")
        val records = listOf(
            record("s1", RecordType.STEPS, "2026-09-14T01:00:00Z", values = mapOf("count" to JsonPrimitive(4000))),
            record("s2", RecordType.STEPS, "2026-09-14T10:00:00Z", values = mapOf("count" to JsonPrimitive(4432))),
            record("d1", RecordType.DISTANCE, "2026-09-14T01:00:00Z", values = mapOf("meters" to JsonPrimitive(6210.0))),
            record(
                "c1",
                RecordType.ACTIVE_CALORIES,
                "2026-09-14T02:00:00Z",
                values = mapOf("kilocalories" to JsonPrimitive(512.4)),
            ),
            record(
                "e1",
                RecordType.EXERCISE,
                "2026-09-14T06:30:00Z",
                "2026-09-14T07:15:00Z",
                values = mapOf(
                    "exerciseType" to JsonPrimitive("RUNNING"),
                    "title" to JsonPrimitive("Run"),
                ),
                route = Route(RouteState.CONSENT_REQUIRED),
            ),
            record(
                "sl1",
                RecordType.SLEEP,
                "2026-09-13T22:00:00Z",
                "2026-09-14T05:12:00Z",
                stages = listOf(
                    Stage("DEEP", "2026-09-13T22:00:00Z", "2026-09-13T23:20:00Z"),
                    Stage("REM", "2026-09-13T23:30:00Z", "2026-09-14T00:35:00Z"),
                ),
            ),
            record(
                "h1",
                RecordType.HEART_RATE,
                "2026-09-14T00:00:00Z",
                samples = listOf(
                    Sample("2026-09-14T00:00:00Z", JsonPrimitive(48)),
                    Sample("2026-09-14T00:05:00Z", JsonPrimitive(141)),
                ),
            ),
            record(
                "o1",
                RecordType.OXYGEN_SATURATION,
                "2026-09-14T03:00:00Z",
                values = mapOf("percent" to JsonPrimitive(97.2)),
            ),
            record(
                "w1",
                RecordType.WEIGHT,
                "2026-09-14T07:00:00Z",
                values = mapOf("kilograms" to JsonPrimitive(74.23)),
            ),
            // Outside the day and must be ignored.
            record("s3", RecordType.STEPS, "2026-09-15T01:00:00Z", values = mapOf("count" to JsonPrimitive(999))),
        )

        val aggregate = SummaryAggregator.aggregate(
            date = day,
            zone = zone,
            generatedAt = Instant.parse("2026-09-15T02:00:00Z"),
            records = records,
        )

        assertEquals(8432L, aggregate.steps)
        assertEquals(6210.0, aggregate.distanceMeters)
        assertEquals(512.4, aggregate.activeCalories)
        assertEquals(45L, aggregate.exerciseMinutes)
        assertEquals(1, aggregate.exerciseSessions)
        assertEquals(RouteState.CONSENT_REQUIRED, aggregate.workouts.single().routeState)
        // Sleep session starts the night before and is attributed to the wake date (2026-09-14).
        assertEquals(432L, aggregate.sleepMinutes)
        assertEquals(80L, aggregate.sleepStages["DEEP"])
        assertEquals(65L, aggregate.sleepStages["REM"])
        assertEquals(94.5, aggregate.heartRateAvg)
        assertEquals(48.0, aggregate.heartRateMin)
        assertEquals(141.0, aggregate.heartRateMax)
        assertEquals(97.2, aggregate.spo2Avg)
        assertEquals(74.23, aggregate.weightKg)
    }

    @Test
    fun `weight uses the last reading of the day`() {
        val day = LocalDate.parse("2026-09-14")
        val records = listOf(
            record("w1", RecordType.WEIGHT, "2026-09-14T07:00:00Z", values = mapOf("kilograms" to JsonPrimitive(75.0))),
            record("w2", RecordType.WEIGHT, "2026-09-14T21:00:00Z", values = mapOf("kilograms" to JsonPrimitive(74.4))),
        )
        val aggregate = SummaryAggregator.aggregate(day, zone, Instant.EPOCH, records)
        assertEquals(74.4, aggregate.weightKg)
    }

    @Test
    fun `empty input yields an empty aggregate`() {
        val aggregate = SummaryAggregator.aggregate(LocalDate.parse("2026-09-14"), zone, Instant.EPOCH, emptyList())
        assertEquals(null, aggregate.steps)
        assertEquals(0, aggregate.exerciseSessions)
        assertEquals(emptyMap<String, Long>(), aggregate.sleepStages)
    }
}
