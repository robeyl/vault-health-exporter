package com.vaulthealth.core.daterange

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DateRangeTest {
    private val auckland: ZoneId = ZoneId.of("Pacific/Auckland")

    @Test
    fun `local day range spans first to last millisecond inclusive`() {
        val range = DateRange.ofLocalDates(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"), auckland)
        assertEquals("2026-09-13T12:00:00Z", range.start.toString())
        assertEquals("2026-09-14T11:59:59.999Z", range.end.toString())
        assertEquals(LocalDate.parse("2026-09-14"), range.startLocalDate(auckland))
        assertEquals(LocalDate.parse("2026-09-14"), range.endLocalDate(auckland))
    }

    @Test
    fun `lastDays returns inclusive window containing today`() {
        val range = DateRange.lastDays(7, LocalDate.parse("2026-09-14"), auckland)
        assertEquals(LocalDate.parse("2026-09-08"), range.startLocalDate(auckland))
        assertEquals(LocalDate.parse("2026-09-14"), range.endLocalDate(auckland))
    }

    @Test
    fun `chunking is contiguous and covers the whole range`() {
        val range = DateRange.instantRange(
            Instant.parse("2026-04-16T00:00:00Z"),
            Instant.parse("2026-09-14T23:59:59.999Z"),
        )
        val chunks = range.chunked(maxSpanDays = 30)
        assertTrue(chunks.size >= 6)
        assertEquals(range.start, chunks.first().start)
        assertEquals(range.end, chunks.last().end)
        chunks.zipWithNext().forEach { (a, b) ->
            assertEquals(a.end.plusMillis(1), b.start)
            assertTrue(!a.end.isBefore(a.start))
        }
    }

    @Test
    fun `rejects inverted ranges`() {
        assertThrows<IllegalArgumentException> {
            DateRange.instantRange(Instant.parse("2026-09-15T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"))
        }
    }

    @Test
    fun `requested range records the zone`() {
        val range = DateRange.ofLocalDates(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"), auckland)
        val requested = range.asRequestedRange(auckland)
        assertEquals("Pacific/Auckland", requested.timezone)
        assertEquals("2026-09-13T12:00:00Z", requested.start)
    }
}
