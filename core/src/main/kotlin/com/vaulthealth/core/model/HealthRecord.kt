package com.vaulthealth.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Canonical record types this app exports. The wire name intentionally mirrors the
 * Health Connect record class name so downstream consumers can map without a lookup table.
 */
@Serializable
enum class RecordType(val wireName: String) {
    @SerialName("Steps") STEPS("Steps"),
    @SerialName("Distance") DISTANCE("Distance"),
    @SerialName("ActiveCaloriesBurned") ACTIVE_CALORIES("ActiveCaloriesBurned"),
    @SerialName("TotalCaloriesBurned") TOTAL_CALORIES("TotalCaloriesBurned"),
    @SerialName("ExerciseSession") EXERCISE("ExerciseSession"),
    @SerialName("HeartRate") HEART_RATE("HeartRate"),
    @SerialName("RestingHeartRate") RESTING_HEART_RATE("RestingHeartRate"),
    @SerialName("OxygenSaturation") OXYGEN_SATURATION("OxygenSaturation"),
    @SerialName("SleepSession") SLEEP("SleepSession"),
    @SerialName("Speed") SPEED("Speed"),
    @SerialName("StepsCadence") STEPS_CADENCE("StepsCadence"),
    @SerialName("CyclingPedalingCadence") CYCLING_CADENCE("CyclingPedalingCadence"),
    @SerialName("Weight") WEIGHT("Weight"),
}

/** Whether an exercise route was exported, was absent, or needs foreground consent. */
@Serializable
enum class RouteState {
    @SerialName("available") AVAILABLE,
    @SerialName("no_data") NO_DATA,
    @SerialName("consent_required") CONSENT_REQUIRED,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class Sample(
    /** ISO-8601 UTC instant of the sample. */
    val t: String,
    /** Raw scalar value, kept as JSON so numbers stay lossless. */
    val value: JsonElement,
)

@Serializable
data class Stage(
    /** Health Connect sleep stage name, e.g. DEEP, REM, LIGHT, AWAKE, UNKNOWN. */
    val stage: String,
    val start: String,
    val end: String,
)

@Serializable
data class RoutePoint(
    val t: String,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
)

@Serializable
data class Route(
    val state: RouteState,
    val points: List<RoutePoint> = emptyList(),
)

/**
 * A single native Health Connect record, normalised but otherwise unmodified.
 *
 * `id` is the Health Connect stable record id (metadata.id). It is the dedupe key together
 * with the type and source package, and must never be regenerated.
 */
@Serializable
data class HealthRecord(
    val id: String,
    val type: RecordType,
    @SerialName("source_package") val sourcePackage: String,
    val origin: String? = null,
    /** ISO-8601 UTC instant. */
    @SerialName("start_time") val startTime: String,
    /** ISO-8601 UTC instant, absent for instantaneous records. */
    @SerialName("end_time") val endTime: String? = null,
    /** Local zone offset at record time, e.g. +12:00. */
    @SerialName("zone_offset") val zoneOffset: String? = null,
    val values: Map<String, JsonElement> = emptyMap(),
    val samples: List<Sample> = emptyList(),
    val stages: List<Stage> = emptyList(),
    val route: Route? = null,
) {
    val canonicalKey: String
        get() = "${type.wireName}|$sourcePackage|$id"
}
