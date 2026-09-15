package com.vaulthealth.core.summary

import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.RouteState
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns a set of normalised records into a [DailyAggregate]. Deterministic: records are
 * sorted before reduction so floating point sums and "last value" picks never vary.
 */
object SummaryAggregator {
    const val KEY_STEPS_COUNT = "count"
    const val KEY_METERS = "meters"
    const val KEY_KILOCALORIES = "kilocalories"
    const val KEY_BPM = "bpm"
    const val KEY_PERCENT = "percent"
    const val KEY_KILOGRAMS = "kilograms"
    const val KEY_TITLE = "title"
    const val KEY_EXERCISE_TYPE = "exerciseType"

    fun aggregate(
        date: LocalDate,
        zone: ZoneId,
        generatedAt: Instant,
        records: List<HealthRecord>,
    ): DailyAggregate {
        val dayRecords = records
            .filter { it.attributionDate(zone) == date }
            .sortedWith(compareBy({ it.startTime }, { it.id }))

        var steps: Long? = null
        var distance: Double? = null
        var active: Double? = null
        var total: Double? = null
        var exerciseMinutes: Long? = null
        var exerciseSessions = 0
        var sleepMinutes: Long? = null
        val sleepStages = LinkedHashMap<String, Long>()
        val workouts = mutableListOf<WorkoutRef>()
        val heartRates = mutableListOf<Double>()
        val restingHeartRates = mutableListOf<Double>()
        val spo2 = mutableListOf<Double>()
        var weight: Double? = null

        for (record in dayRecords) {
            when (record.type) {
                RecordType.STEPS -> steps = (steps ?: 0L) + (record.longValue(KEY_STEPS_COUNT) ?: 0L)
                RecordType.DISTANCE -> distance = (distance ?: 0.0) + (record.doubleValue(KEY_METERS) ?: 0.0)
                RecordType.ACTIVE_CALORIES ->
                    active = (active ?: 0.0) + (record.doubleValue(KEY_KILOCALORIES) ?: 0.0)

                RecordType.TOTAL_CALORIES ->
                    total = (total ?: 0.0) + (record.doubleValue(KEY_KILOCALORIES) ?: 0.0)

                RecordType.EXERCISE -> {
                    val minutes = record.durationMinutes()
                    exerciseMinutes = (exerciseMinutes ?: 0L) + minutes
                    exerciseSessions++
                    workouts += WorkoutRef(
                        id = record.id,
                        start = record.startTime,
                        end = record.endTime,
                        title = record.stringValue(KEY_TITLE)
                            ?: record.stringValue(KEY_EXERCISE_TYPE)
                            ?: "Workout",
                        minutes = minutes,
                        routeState = record.route?.state ?: RouteState.UNAVAILABLE,
                    )
                }

                RecordType.SLEEP -> {
                    val minutes = record.durationMinutes()
                    sleepMinutes = (sleepMinutes ?: 0L) + minutes
                    for (stage in record.stages) {
                        val stageMinutes = minutesBetween(stage.start, stage.end)
                        sleepStages[stage.stage] = (sleepStages[stage.stage] ?: 0L) + stageMinutes
                    }
                }

                RecordType.HEART_RATE ->
                    record.samples.forEach { sample ->
                        (sample.value as? JsonPrimitive)?.content?.toDoubleOrNull()?.let(heartRates::add)
                    }

                RecordType.RESTING_HEART_RATE ->
                    record.doubleValue(KEY_BPM)?.let(restingHeartRates::add)

                RecordType.OXYGEN_SATURATION ->
                    record.doubleValue(KEY_PERCENT)?.let(spo2::add)

                RecordType.WEIGHT -> record.doubleValue(KEY_KILOGRAMS)?.let { weight = it }

                // Speed, cadence, blood pressure, nutrition, etc. are exported but not aggregated
                // into the daily note.
                else -> Unit
            }
        }

        return DailyAggregate(
            date = date,
            zone = zone,
            generatedAt = generatedAt,
            steps = steps,
            distanceMeters = distance,
            exerciseMinutes = exerciseMinutes,
            exerciseSessions = exerciseSessions,
            activeCalories = active,
            totalCalories = total,
            sleepMinutes = sleepMinutes,
            sleepStages = sleepStages,
            heartRateAvg = heartRates.averageOrNull(),
            heartRateMin = heartRates.minOrNull(),
            heartRateMax = heartRates.maxOrNull(),
            restingHeartRate = restingHeartRates.averageOrNull(),
            spo2Avg = spo2.averageOrNull(),
            spo2Min = spo2.minOrNull(),
            spo2Max = spo2.maxOrNull(),
            weightKg = weight,
            workouts = workouts,
        )
    }

    /**
     * Which day a record belongs to. Sleep is attributed to the day you woke up, which is how
     * people read "last night's sleep"; everything else uses its start instant.
     */
    private fun HealthRecord.attributionDate(zone: ZoneId): LocalDate? = runCatching {
        val anchor = if (type == RecordType.SLEEP && endTime != null) endTime else startTime
        Instant.parse(anchor).atZone(zone).toLocalDate()
    }.getOrNull()

    private fun HealthRecord.durationMinutes(): Long =
        if (endTime == null) 0L else minutesBetween(startTime, endTime)

    private fun minutesBetween(start: String, end: String?): Long {
        if (end == null) return 0L
        return runCatching {
            Duration.between(Instant.parse(start), Instant.parse(end)).toMinutes().coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    private fun HealthRecord.doubleValue(key: String): Double? =
        (values[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun HealthRecord.longValue(key: String): Long? =
        (values[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun HealthRecord.stringValue(key: String): String? =
        (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
