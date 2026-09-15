package com.vaulthealth.core.checksum

import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ContentHashTest {
    private fun record(id: String, count: Long = 1) = HealthRecord(
        id = id,
        type = RecordType.STEPS,
        sourcePackage = "pkg",
        startTime = "2026-09-14T00:00:00Z",
        endTime = "2026-09-14T01:00:00Z",
        values = mapOf("count" to JsonPrimitive(count)),
    )

    @Test
    fun `same records in a different order hash the same`() {
        val a = ContentHash.ofRecords(listOf(record("a"), record("b")))
        val b = ContentHash.ofRecords(listOf(record("b"), record("a")))
        assertEquals(a, b)
    }

    @Test
    fun `different values hash differently`() {
        assertNotEquals(
            ContentHash.ofRecords(listOf(record("a", 1))),
            ContentHash.ofRecords(listOf(record("a", 2))),
        )
    }

    @Test
    fun `an extra record changes the hash`() {
        assertNotEquals(
            ContentHash.ofRecords(listOf(record("a"))),
            ContentHash.ofRecords(listOf(record("a"), record("b"))),
        )
    }

    @Test
    fun `delta hash covers tombstones and ignores their order`() {
        val u = listOf(record("a"))
        assertEquals(
            ContentHash.ofDelta(u, listOf("x", "y")),
            ContentHash.ofDelta(u, listOf("y", "x")),
        )
        assertNotEquals(
            ContentHash.ofDelta(u, listOf("x")),
            ContentHash.ofDelta(u, listOf("x", "y")),
        )
    }
}
