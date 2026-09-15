package com.vaulthealth.core.naming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class FileNamesTest {
    @Test
    fun `snapshot name uses requested range`() {
        assertEquals(
            "health-connect-2026-04-16_to_2026-09-14.ndjson",
            FileNames.snapshot(LocalDate.parse("2026-04-16"), LocalDate.parse("2026-09-14")),
        )
    }

    @Test
    fun `never overwrite - disambiguator produces a new name`() {
        assertNotEquals(
            FileNames.snapshot(LocalDate.parse("2026-04-16"), LocalDate.parse("2026-09-14")),
            FileNames.snapshot(LocalDate.parse("2026-04-16"), LocalDate.parse("2026-09-14"), disambiguator = 2),
        )
    }

    @Test
    fun `delta name is a compact utc timestamp`() {
        assertEquals(
            "health-connect-delta-2026-09-15T020000Z.ndjson",
            FileNames.delta(Instant.parse("2026-09-15T02:00:00Z")),
        )
    }

    @Test
    fun `summary and checksum names`() {
        assertEquals("2026-09-14.md", FileNames.summary(LocalDate.parse("2026-09-14")))
        assertEquals("a.ndjson.sha256", FileNames.checksum("a.ndjson"))
    }

    @Test
    fun `vault layout is relative to the chosen folder`() {
        assertEquals("snapshots", VaultPaths.SNAPSHOTS)
        assertEquals("deltas", VaultPaths.DELTAS)
        assertEquals("Summaries", VaultPaths.SUMMARIES)
    }
}
