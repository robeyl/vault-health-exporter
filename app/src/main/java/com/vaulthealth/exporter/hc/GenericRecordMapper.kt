package com.vaulthealth.exporter.hc

import androidx.health.connect.client.records.Record
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.Sample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneOffset

/**
 * Fallback mapper for record types that [RecordMapper] does not hand-map.
 *
 * It reads every public, no-argument getter and converts Health Connect's unit value classes
 * (Length, Mass, Energy, Volume, Temperature, Power, Velocity, Percentage, BloodGlucose) into
 * plain numbers with an explicit unit suffix, so no record type is silently dropped and newly
 * added Health Connect types still export.
 */
object GenericRecordMapper {

    private val unitAccessors: Map<String, List<Pair<String, String>>> = mapOf(
        "Length" to listOf("inMeters" to "Meters"),
        "Mass" to listOf("inKilograms" to "Kilograms"),
        "Energy" to listOf("inKilocalories" to "Kilocalories"),
        "Volume" to listOf("inLiters" to "Liters"),
        "Temperature" to listOf("inCelsius" to "Celsius"),
        "TemperatureDelta" to listOf("inCelsius" to "Celsius"),
        "Power" to listOf("inWatts" to "Watts", "inKilocaloriesPerDay" to "KilocaloriesPerDay"),
        "Velocity" to listOf("inMetersPerSecond" to "MetersPerSecond"),
        "Percentage" to listOf("value" to "Percent"),
        "BloodGlucose" to listOf("inMillimolesPerLiter" to "MillimolesPerLiter"),
    )

    private val structuralGetters = setOf(
        "getClass", "getMetadata", "getStartTime", "getEndTime", "getStartZoneOffset",
        "getEndZoneOffset", "getTime", "getZoneOffset", "getSamples", "getStages", "getDeltas",
        "getRoute", "getBlocks", "getLaps", "getSegments",
    )

    fun map(record: Record, type: RecordType): HealthRecord? {
        val start = invoke(record, "getStartTime") as? Instant
        val end = invoke(record, "getEndTime") as? Instant
        val time = invoke(record, "getTime") as? Instant
        val zone = (invoke(record, "getStartZoneOffset") ?: invoke(record, "getZoneOffset")) as? ZoneOffset
        val anchor = start ?: time ?: return null

        val values = sortedMapOf<String, JsonElement>()
        for (method in record.javaClass.methods) {
            if (method.parameterCount != 0) continue
            if (Modifier.isStatic(method.modifiers)) continue
            if (!method.name.startsWith("get")) continue
            if (method.name in structuralGetters) continue
            val value = runCatching { method.invoke(record) }.getOrNull() ?: continue
            val (key, element) = convert(method.name, value) ?: continue
            values[key] = element
        }

        val samples = (invoke(record, "getSamples") as? List<*>)
            ?.mapNotNull { sample -> mapSample(sample) }
            .orEmpty()

        return HealthRecord(
            id = record.metadata.id,
            type = type,
            sourcePackage = record.metadata.dataOrigin.packageName,
            startTime = anchor.toString(),
            endTime = end?.toString(),
            zoneOffset = zone?.toString(),
            values = values,
            samples = samples,
        )
    }

    private fun mapSample(sample: Any?): Sample? {
        if (sample == null) return null
        val time = invoke(sample, "getTime") as? Instant ?: return null
        val raw = sample.javaClass.methods
            .firstOrNull {
                it.parameterCount == 0 &&
                    !Modifier.isStatic(it.modifiers) &&
                    it.name.startsWith("get") &&
                    it.name != "getTime"
            }
            ?.let { runCatching { it.invoke(sample) }.getOrNull() }
        val number = toNumber(raw) ?: return null
        return Sample(time.toString(), JsonPrimitive(number))
    }

    /** Returns the JSON key and value, suffixing unit values (e.g. `heightMeters`). */
    private fun convert(getterName: String, value: Any): Pair<String, JsonElement>? {
        val baseKey = getterName.removePrefix("get").replaceFirstChar { it.lowercase() }
        val unit = unitNumber(value)
        if (unit != null) {
            return (baseKey + unit.second) to JsonPrimitive(unit.first)
        }
        return when (value) {
            is Boolean -> baseKey to JsonPrimitive(value)
            is Number -> baseKey to JsonPrimitive(value)
            is String -> if (value.isBlank()) null else baseKey to JsonPrimitive(value)
            is Enum<*> -> baseKey to JsonPrimitive(value.name)
            is Collection<*> -> {
                val numbers = value.mapNotNull { toNumber(it) }
                if (numbers.isEmpty()) null else baseKey to JsonArray(numbers.map { JsonPrimitive(it) })
            }

            else -> null
        }
    }

    private fun toNumber(value: Any?): Double? = when {
        value == null -> null
        value is Number -> value.toDouble()
        value is Boolean -> null
        else -> unitNumber(value)?.first
    }

    private fun unitNumber(value: Any): Pair<Double, String>? {
        val accessors = unitAccessors[value.javaClass.simpleName] ?: return null
        for ((method, suffix) in accessors) {
            val number = invoke(value, method) as? Number ?: continue
            return number.toDouble() to suffix
        }
        return null
    }

    private fun invoke(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(target)
    }.getOrNull()
}
