package com.vaulthealth.core.daterange

import com.vaulthealth.core.ndjson.RequestedRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A closed instant range. All arithmetic is UTC; localisation happens only for naming/display. */
data class DateRange(val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "end ($end) must not be before start ($start)" }
    }

    val spanDays: Long
        get() = ChronoUnit.DAYS.between(start, end)

    /** Splits into contiguous chunks of at most [maxSpanDays]. */
    fun chunked(maxSpanDays: Long): List<DateRange> {
        require(maxSpanDays > 0) { "maxSpanDays must be positive" }
        val chunks = ArrayList<DateRange>()
        var cursor = start
        while (cursor <= end) {
            val candidate = cursor.plus(maxSpanDays, ChronoUnit.DAYS)
            val chunkEnd = if (candidate > end) end else candidate.minusMillis(1)
            chunks.add(DateRange(cursor, chunkEnd))
            cursor = chunkEnd.plusMillis(1)
        }
        return chunks
    }

    fun startLocalDate(zone: ZoneId): LocalDate = start.atZone(zone).toLocalDate()
    fun endLocalDate(zone: ZoneId): LocalDate = end.atZone(zone).toLocalDate()

    fun asRequestedRange(zone: ZoneId): RequestedRange = RequestedRange(
        start = start.toString(),
        end = end.toString(),
        timezone = zone.id,
    )

    companion object {
        /** Inclusive local days, expanded to the first/last millisecond of those days. */
        fun ofLocalDates(startDay: LocalDate, endDay: LocalDate, zone: ZoneId): DateRange {
            require(!endDay.isBefore(startDay)) { "end day must not be before start day" }
            val start = startDay.atStartOfDay(zone).toInstant()
            val end = endDay.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
            return DateRange(start, end)
        }

        fun lastDays(days: Long, today: LocalDate, zone: ZoneId): DateRange {
            require(days > 0) { "days must be positive" }
            return ofLocalDates(today.minusDays(days - 1), today, zone)
        }

        fun instantRange(start: Instant, end: Instant): DateRange = DateRange(start, end)
    }
}
