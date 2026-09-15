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
    @SerialName("BasalBodyTemperature") BASAL_BODY_TEMPERATURE("BasalBodyTemperature"),
    @SerialName("BasalMetabolicRate") BASAL_METABOLIC_RATE("BasalMetabolicRate"),
    @SerialName("BloodGlucose") BLOOD_GLUCOSE("BloodGlucose"),
    @SerialName("BloodPressure") BLOOD_PRESSURE("BloodPressure"),
    @SerialName("BodyFat") BODY_FAT("BodyFat"),
    @SerialName("BodyTemperature") BODY_TEMPERATURE("BodyTemperature"),
    @SerialName("BodyWaterMass") BODY_WATER_MASS("BodyWaterMass"),
    @SerialName("BoneMass") BONE_MASS("BoneMass"),
    @SerialName("CervicalMucus") CERVICAL_MUCUS("CervicalMucus"),
    @SerialName("ElevationGained") ELEVATION_GAINED("ElevationGained"),
    @SerialName("FloorsClimbed") FLOORS_CLIMBED("FloorsClimbed"),
    @SerialName("HeartRateVariabilityRmssd") HRV_RMSSD("HeartRateVariabilityRmssd"),
    @SerialName("Height") HEIGHT("Height"),
    @SerialName("Hydration") HYDRATION("Hydration"),
    @SerialName("IntermenstrualBleeding") INTERMENSTRUAL_BLEEDING("IntermenstrualBleeding"),
    @SerialName("LeanBodyMass") LEAN_BODY_MASS("LeanBodyMass"),
    @SerialName("MenstruationFlow") MENSTRUATION_FLOW("MenstruationFlow"),
    @SerialName("MenstruationPeriod") MENSTRUATION_PERIOD("MenstruationPeriod"),
    @SerialName("MindfulnessSession") MINDFULNESS_SESSION("MindfulnessSession"),
    @SerialName("Nutrition") NUTRITION("Nutrition"),
    @SerialName("OvulationTest") OVULATION_TEST("OvulationTest"),
    @SerialName("PlannedExerciseSession") PLANNED_EXERCISE("PlannedExerciseSession"),
    @SerialName("Power") POWER("Power"),
    @SerialName("RespiratoryRate") RESPIRATORY_RATE("RespiratoryRate"),
    @SerialName("SexualActivity") SEXUAL_ACTIVITY("SexualActivity"),
    @SerialName("SkinTemperature") SKIN_TEMPERATURE("SkinTemperature"),
    @SerialName("Vo2Max") VO2_MAX("Vo2Max"),
    @SerialName("WheelchairPushes") WHEELCHAIR_PUSHES("WheelchairPushes"),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWireName(name: String): RecordType? = byWireName[name]
    }
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
