package com.vaulthealth.core.summary

import com.vaulthealth.core.model.RouteState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailySummaryTest {
    private val zone: ZoneId = ZoneId.of("Pacific/Auckland")

    private fun aggregate() = DailyAggregate(
        date = LocalDate.parse("2026-09-14"),
        zone = ZoneId.of("UTC"),
        generatedAt = Instant.parse("2026-09-15T02:00:00Z"),
        steps = 8432,
        distanceMeters = 6210.0,
        exerciseMinutes = 47,
        exerciseSessions = 2,
        activeCalories = 512.4,
        totalCalories = 2140.0,
        sleepMinutes = 432,
        sleepStages = linkedMapOf("DEEP" to 80L, "REM" to 65L),
        heartRateAvg = 62.4,
        heartRateMin = 48.0,
        heartRateMax = 141.0,
        restingHeartRate = 54.0,
        spo2Avg = 97.2,
        spo2Min = 95.0,
        spo2Max = 99.0,
        weightKg = 74.23,
        workouts = listOf(
            WorkoutRef(
                id = "hc:ex:123",
                start = "2026-09-14T06:30:00Z",
                end = "2026-09-14T07:15:00Z",
                title = "Run",
                minutes = 45,
                routeState = RouteState.AVAILABLE,
            ),
        ),
    )

    @Test
    fun `renders a concise deterministic note`() {
        val expected = """
            ---
            date: 2026-09-14
            generated_by: vault-health-exporter
            source: health-connect
            generated_at: 2026-09-15T02:00:00Z
            ---
            # Health Summary — 2026-09-14

            - Steps: 8432
            - Distance: 6210.00 km
            - Exercise: 47 min (2 sessions)
            - Active calories: 512 kcal
            - Total calories: 2140 kcal
            - Sleep: 7h 12m (deep 1h 20m, REM 1h 05m)
            - Heart rate: avg 62 bpm (min 48, max 141)
            - Resting heart rate: 54 bpm
            - SpO2: 97% (min 95, max 99)
            - Weight: 74.2 kg

            ## Workouts
            - 2026-09-14 06:30–07:15 Run 45 min `id=hc:ex:123` `route=available`
        """.trimIndent() + "\n"

        assertEquals(expected, DailySummaryGenerator.render(aggregate()))
    }

    @Test
    fun `same input always renders identical output`() {
        val a = DailySummaryGenerator.render(aggregate())
        val b = DailySummaryGenerator.render(aggregate())
        assertEquals(a, b)
    }

    @Test
    fun `missing data degrades gracefully`() {
        val empty = DailyAggregate(
            date = LocalDate.parse("2026-09-14"),
            zone = zone,
            generatedAt = Instant.parse("2026-09-15T02:00:00Z"),
        )
        val markdown = DailySummaryGenerator.render(empty)
        assertTrue(markdown.contains("- Steps: (no data)"))
        assertTrue(markdown.contains("- Exercise: 0 min (0 sessions)"))
        assertTrue(markdown.contains("## Workouts\n- (none)"))
    }

    @Test
    fun `never embeds raw payloads`() {
        val markdown = DailySummaryGenerator.render(aggregate())
        assertFalse(markdown.contains("samples"))
        assertFalse(markdown.contains("{"))
        assertFalse(markdown.contains("source_package"))
    }

    @Test
    fun `workout times are rendered in the aggregate zone`() {
        val aotearoa = aggregate().copy(zone = zone)
        val markdown = DailySummaryGenerator.render(aotearoa)
        // 06:30 UTC is 18:30 in Auckland on 2026-09-14 (NZST, +12).
        assertTrue(markdown.contains("2026-09-14 18:30–19:15 Run 45 min"), markdown)
    }
}
