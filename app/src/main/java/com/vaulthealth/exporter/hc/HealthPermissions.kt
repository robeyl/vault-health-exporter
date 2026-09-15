package com.vaulthealth.exporter.hc

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import com.vaulthealth.core.model.RecordType
import kotlin.reflect.KClass

/**
 * Bulk access to exercise GPS routes. Health Connect added this after connect-client 1.1.0,
 * so it is referenced by its raw platform string.
 */
private const val READ_EXERCISE_ROUTES_PERMISSION = "android.permission.health.READ_EXERCISE_ROUTES"

data class PermissionSlot(val recordType: RecordType, val permission: String)

/**
 * Every Health Connect record type this app can read, mapped to the permission that governs it.
 *
 * Accessibility notes: steps cadence is governed by READ_STEPS and cycling cadence by
 * READ_EXERCISE — Health Connect has no separate cadence permission.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object HealthPermissions {
    val recordKClasses: List<Pair<RecordType, KClass<out Record>>> = listOf(
        RecordType.STEPS to StepsRecord::class,
        RecordType.DISTANCE to DistanceRecord::class,
        RecordType.ACTIVE_CALORIES to ActiveCaloriesBurnedRecord::class,
        RecordType.TOTAL_CALORIES to TotalCaloriesBurnedRecord::class,
        RecordType.EXERCISE to ExerciseSessionRecord::class,
        RecordType.HEART_RATE to HeartRateRecord::class,
        RecordType.RESTING_HEART_RATE to RestingHeartRateRecord::class,
        RecordType.OXYGEN_SATURATION to OxygenSaturationRecord::class,
        RecordType.SLEEP to SleepSessionRecord::class,
        RecordType.SPEED to SpeedRecord::class,
        RecordType.STEPS_CADENCE to StepsCadenceRecord::class,
        RecordType.CYCLING_CADENCE to CyclingPedalingCadenceRecord::class,
        RecordType.WEIGHT to WeightRecord::class,
        RecordType.BASAL_BODY_TEMPERATURE to BasalBodyTemperatureRecord::class,
        RecordType.BASAL_METABOLIC_RATE to BasalMetabolicRateRecord::class,
        RecordType.BLOOD_GLUCOSE to BloodGlucoseRecord::class,
        RecordType.BLOOD_PRESSURE to BloodPressureRecord::class,
        RecordType.BODY_FAT to BodyFatRecord::class,
        RecordType.BODY_TEMPERATURE to BodyTemperatureRecord::class,
        RecordType.BODY_WATER_MASS to BodyWaterMassRecord::class,
        RecordType.BONE_MASS to BoneMassRecord::class,
        RecordType.CERVICAL_MUCUS to CervicalMucusRecord::class,
        RecordType.ELEVATION_GAINED to ElevationGainedRecord::class,
        RecordType.FLOORS_CLIMBED to FloorsClimbedRecord::class,
        RecordType.HRV_RMSSD to HeartRateVariabilityRmssdRecord::class,
        RecordType.HEIGHT to HeightRecord::class,
        RecordType.HYDRATION to HydrationRecord::class,
        RecordType.INTERMENSTRUAL_BLEEDING to IntermenstrualBleedingRecord::class,
        RecordType.LEAN_BODY_MASS to LeanBodyMassRecord::class,
        RecordType.MENSTRUATION_FLOW to MenstruationFlowRecord::class,
        RecordType.MENSTRUATION_PERIOD to MenstruationPeriodRecord::class,
        RecordType.MINDFULNESS_SESSION to MindfulnessSessionRecord::class,
        RecordType.NUTRITION to NutritionRecord::class,
        RecordType.OVULATION_TEST to OvulationTestRecord::class,
        RecordType.PLANNED_EXERCISE to PlannedExerciseSessionRecord::class,
        RecordType.POWER to PowerRecord::class,
        RecordType.RESPIRATORY_RATE to RespiratoryRateRecord::class,
        RecordType.SEXUAL_ACTIVITY to SexualActivityRecord::class,
        RecordType.SKIN_TEMPERATURE to SkinTemperatureRecord::class,
        RecordType.VO2_MAX to Vo2MaxRecord::class,
        RecordType.WHEELCHAIR_PUSHES to WheelchairPushesRecord::class,
    )

    /** Simple class name -> our record type, used by the reflective fallback mapper. */
    private val bySimpleName: Map<String, RecordType> =
        recordKClasses.associate { (type, klass) -> klass.java.simpleName to type }

    fun typeOf(record: Record): RecordType? = bySimpleName[record.javaClass.simpleName]

    val slots: List<PermissionSlot> = recordKClasses.mapNotNull { (type, klass) ->
        runCatching { PermissionSlot(type, HealthPermission.getReadPermission(klass)) }.getOrNull()
    }

    /**
     * Permissions requested in one go. READ_EXERCISE_ROUTES is intentionally NOT included:
     * connect-client 1.1.0's contract rejects that newer platform string, which makes Health
     * Connect close the whole consent screen. GPS is obtained per session via
     * [androidx.health.connect.client.contracts.ExerciseRouteRequestContract] instead.
     */
    val readPermissions: Set<String> = slots.map { it.permission }.toSet()

    val recordTypes: Set<KClass<out Record>> = recordKClasses.map { it.second }.toSet()

    const val READ_HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    const val READ_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    const val READ_EXERCISE_ROUTES: String = READ_EXERCISE_ROUTES_PERMISSION
}
