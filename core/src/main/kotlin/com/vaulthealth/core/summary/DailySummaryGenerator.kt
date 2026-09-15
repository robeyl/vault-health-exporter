package com.vaulthealth.core.summary

import com.vaulthealth.core.model.RouteState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WorkoutRef(
    val id: String,
    /** ISO-8601 UTC instant. */
    val start: String,
    val end: String?,
    val title: String,
    val minutes: Long,
    val routeState: RouteState,
)

/**
 * Fully materialised daily totals. Nothing here is raw Health Connect data — the caller
 * aggregates records before handing them over, so no raw payload can leak into the note.
 */
data class DailyAggregate(
    val date: LocalDate,
    val zone: ZoneId,
    val generatedAt: Instant,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val exerciseMinutes: Long? = null,
    val exerciseSessions: Int = 0,
    val activeCalories: Double? = null,
    val totalCalories: Double? = null,
    val sleepMinutes: Long? = null,
    val sleepStages: Map<String, Long> = emptyMap(),
    val heartRateAvg: Double? = null,
    val heartRateMin: Double? = null,
    val heartRateMax: Double? = null,
    val restingHeartRate: Double? = null,
    val spo2Avg: Double? = null,
    val spo2Min: Double? = null,
    val spo2Max: Double? = null,
    val weightKg: Double? = null,
    val workouts: List<WorkoutRef> = emptyList(),
)

/**
 * Deterministic Markdown renderer. Same input -> byte-identical output. No clock reads,
 * no randomness, no locale dependence, no LLM.
 */
object DailySummaryGenerator {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val NO_DATA = "(no data)"
    private val STAGE_ORDER = listOf("DEEP" to "deep", "REM" to "REM", "LIGHT" to "light", "AWAKE" to "awake")

    fun render(a: DailyAggregate): String = buildString {
        append("---\n")
        append("date: ${a.date}\n")
        append("generated_by: vault-health-exporter\n")
        append("source: health-connect\n")
        append("generated_at: ${a.generatedAt}\n")
        append("---\n")
        append("# Health Summary — ${a.date}\n\n")

        append("- Steps: ${a.steps ?: NO_DATA}\n")
        append("- Distance: ${a.distanceMeters?.let { "${num(it, 2)} km" } ?: NO_DATA}\n")
        append("- Exercise: ${a.exerciseMinutes?.let { "$it min" } ?: "0 min"} (${a.exerciseSessions} sessions)\n")
        append("- Active calories: ${a.activeCalories?.let { "${num(it, 0)} kcal" } ?: NO_DATA}\n")
        append("- Total calories: ${a.totalCalories?.let { "${num(it, 0)} kcal" } ?: NO_DATA}\n")
        append("- Sleep: ${sleepLine(a) }\n")
        append("- Heart rate: ${heartRateLine(a)}\n")
        append("- Resting heart rate: ${a.restingHeartRate?.let { "${num(it, 0)} bpm" } ?: NO_DATA}\n")
        append("- SpO2: ${spo2Line(a)}\n")
        append("- Weight: ${a.weightKg?.let { "${num(it, 1)} kg" } ?: NO_DATA}\n")
        append("\n## Workouts\n")
        if (a.workouts.isEmpty()) {
            append("- (none)\n")
        } else {
            a.workouts.forEach { w ->
                val start = runCatching { Instant.parse(w.start).atZone(a.zone).format(TIME) }.getOrElse { "?" }
                val end = w.end?.let { runCatching { Instant.parse(it).atZone(a.zone).format(TIME) }.getOrNull() }
                val range = if (end != null) "$start–$end" else start
                val title = w.title.replace('\n', ' ').replace('\r', ' ').trim().ifEmpty { "Workout" }
                append("- ${a.date} $range $title ${w.minutes} min `id=${w.id}` `route=${w.routeState.name.lowercase()}`\n")
            }
        }
    }

    private fun sleepLine(a: DailyAggregate): String {
        val base = a.sleepMinutes?.let { duration(it) } ?: NO_DATA
        if (a.sleepStages.isEmpty()) return base
        val parts = STAGE_ORDER.mapNotNull { (key, label) ->
            a.sleepStages[key]?.let { "$label ${duration(it)}" }
        }.toMutableList()
        val known = STAGE_ORDER.map { it.first }.toSet()
        a.sleepStages.filterKeys { it !in known }.toSortedMap().forEach { (k, v) ->
            parts.add("${k.lowercase()} ${duration(v)}")
        }
        return if (parts.isEmpty()) base else "$base (${parts.joinToString(", ")})"
    }

    private fun heartRateLine(a: DailyAggregate): String {
        val parts = mutableListOf<String>()
        a.heartRateAvg?.let { parts.add("avg ${num(it, 0)} bpm") }
        if (a.heartRateMin != null && a.heartRateMax != null) {
            parts.add("min ${num(a.heartRateMin, 0)}, max ${num(a.heartRateMax, 0)}")
        } else {
            a.heartRateMin?.let { parts.add("min ${num(it, 0)}") }
            a.heartRateMax?.let { parts.add("max ${num(it, 0)}") }
        }
        if (parts.isEmpty()) return NO_DATA
        return if (parts.size == 1) parts.first() else "${parts[0]} (${parts.drop(1).joinToString(", ")})"
    }

    private fun spo2Line(a: DailyAggregate): String {
        val avg = a.spo2Avg?.let { "${num(it, 0)}%" } ?: return NO_DATA
        val bounds = if (a.spo2Min != null && a.spo2Max != null) {
            " (min ${num(a.spo2Min!!, 0)}, max ${num(a.spo2Max!!, 0)})"
        } else {
            ""
        }
        return avg + bounds
    }

    private fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
    }

    private fun num(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)
}
