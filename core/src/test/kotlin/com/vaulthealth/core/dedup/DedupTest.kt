package com.vaulthealth.core.dedup

import com.vaulthealth.core.ndjson.DeltaLine
import com.vaulthealth.core.ndjson.DeltaOp
import com.vaulthealth.core.ndjson.TestRecords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DedupTest {
    @Test
    fun `removes duplicate canonical keys keeping first occurrence`() {
        val first = TestRecords.steps("same", 100)
        val duplicate = TestRecords.steps("same", 999)
        val other = TestRecords.steps("other", 5)

        val result = Dedup.dedupe(listOf(first, duplicate, other))

        assertEquals(2, result.records.size)
        assertEquals(1, result.duplicatesRemoved)
        assertEquals(100, result.records.first().values["count"].toString().toInt())
    }

    @Test
    fun `same id from different source packages is not a duplicate`() {
        val a = TestRecords.steps("id1", 1, pkg = "source.a")
        val b = TestRecords.steps("id1", 2, pkg = "source.b")
        assertEquals(2, Dedup.dedupe(listOf(a, b)).records.size)
    }

    @Test
    fun `applyDelta replaces upserts and removes tombstones`() {
        val existing = listOf(
            TestRecords.steps("s1", 100),
            TestRecords.steps("s2", 200),
        )
        val upserts = listOf(TestRecords.steps("s1", 150))
        val tombstones = listOf(
            DeltaLine(op = DeltaOp.DELETE, id = "s2", type = "Steps", sourcePackage = "com.google.android.apps.healthdata"),
        )

        val merged = Dedup.applyDelta(existing, upserts, tombstones)

        assertEquals(1, merged.records.size)
        assertEquals(150, merged.records.first().values["count"].toString().toInt())
    }
}
