package com.vaulthealth.exporter.hc

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
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
import com.vaulthealth.core.model.RecordType
import kotlin.reflect.KClass

data class PermissionSlot(val recordType: RecordType, val permission: String)

/**
 * The exact Health Connect read permissions this app asks for, mapped to user-facing record types.
 *
 * Cadence has no dedicated permission in Health Connect 1.1.0: steps cadence is governed by
 * READ_STEPS and cycling cadence by READ_EXERCISE. We still export the cadence record types,
 * we just do not request a permission that does not exist.
 */
object HealthPermissions {
    private val recordKClasses: List<Pair<RecordType, KClass<out Record>>> = listOf(
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
    )

    val slots: List<PermissionSlot> = recordKClasses.map { (type, klass) ->
        PermissionSlot(type, HealthPermission.getReadPermission(klass))
    }

    /** Deduplicated: cadence shares permissions with steps/exercise. */
    val readPermissions: Set<String> = slots.map { it.permission }.toSet()

    val recordTypes: Set<KClass<out Record>> = recordKClasses.map { it.second }.toSet()

    const val READ_HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    const val READ_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun labelFor(permission: String): String =
        slots.firstOrNull { it.permission == permission }?.recordType?.wireName
            ?: when (permission) {
                READ_HISTORY -> "Read health data history"
                READ_BACKGROUND -> "Read health data in background"
                else -> permission.substringAfterLast('.')
            }
}
