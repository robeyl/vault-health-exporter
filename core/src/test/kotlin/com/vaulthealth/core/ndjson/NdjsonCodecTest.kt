package com.vaulthealth.core.ndjson

import com.vaulthealth.core.checksum.Sha256
import com.vaulthealth.core.model.NdjsonSchema
import com.vaulthealth.core.model.Route
import com.vaulthealth.core.model.RouteState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NdjsonCodecTest {
    private fun snapshotHeader() = ExportHeader(
        schema = NdjsonSchema.NAME,
        version = NdjsonSchema.VERSION,
        kind = ExportKind.SNAPSHOT,
        exportedAt = "2026-09-14T10:00:00Z",
        requestedRange = RequestedRange(
            start = "2026-04-16T00:00:00Z",
            end = "2026-09-14T23:59:59.999Z",
            timezone = "Pacific/Auckland",
        ),
        app = AppInfo(version = "1.0.0"),
    )

    private fun deltaHeader() = ExportHeader(
        schema = NdjsonSchema.NAME,
        version = NdjsonSchema.VERSION,
        kind = ExportKind.DELTA,
        exportedAt = "2026-09-15T02:00:00Z",
        changeTokenBefore = "tok-1",
        changeTokenAfter = "tok-2",
        app = AppInfo(version = "1.0.0"),
    )

    private val records = listOf(
        TestRecords.steps("s1", 842),
        TestRecords.steps("s2", 0),
        TestRecords.heartRate("h1", listOf(61, 62)),
        TestRecords.exercise("e1", Route(RouteState.CONSENT_REQUIRED)),
        TestRecords.exercise("e2", null),
    )

    @Test
    fun `snapshot is header + one record per line + manifest`() {
        val built = NdjsonCodec.buildSnapshot(snapshotHeader(), records)
        val lines = built.fileText.trimEnd('\n').split('\n')

        assertEquals(records.size + 2, lines.size)
        assertTrue(NdjsonReader.isKnownSchema(built.fileText))
        assertEquals(
            "vault-health-exporter/ndjson",
            NdjsonReader.schemaNameOf(built.fileText),
        )
        assertTrue(built.fileText.endsWith("\n"))
    }

    @Test
    fun `manifest summarises counts and issues deterministically`() {
        val built = NdjsonCodec.buildSnapshot(snapshotHeader(), records)
        val m = built.manifest

        assertEquals(5, m.recordTotal)
        assertEquals(mapOf("ExerciseSession" to 2, "HeartRate" to 1, "Steps" to 2), m.recordCounts)
        assertEquals(1, m.issueCounts["zero_value"])
        assertEquals(1, m.issueCounts["route_consent_required"])
        assertEquals(1, m.issueCounts["route_missing_opt_in"])
        assertEquals("manifest", m.kind)
        assertEquals(NdjsonCodec.SCOPE_PAYLOAD, m.checksum.scope)
        assertEquals(Sha256.hex(built.payload), m.checksum.value)
    }

    @Test
    fun `round trips through the reader`() {
        val built = NdjsonCodec.buildSnapshot(snapshotHeader(), records)
        val parsed = NdjsonReader.parse(built.fileText)

        assertEquals(ExportKind.SNAPSHOT, parsed.header.kind)
        assertEquals(records, parsed.records)
        assertTrue(NdjsonReader.verifyPayloadChecksum(built.fileText))
        assertEquals(Sha256.hex(built.fileText), built.fileSha256)
    }

    @Test
    fun `checksum detects tampering`() {
        val built = NdjsonCodec.buildSnapshot(snapshotHeader(), records)
        val tampered = built.fileText.replace("\"count\":842", "\"count\":999")
        assertFalse(NdjsonReader.verifyPayloadChecksum(tampered))
    }

    @Test
    fun `delta carries upserts and tombstones with token chain`() {
        val tombstone = DeltaLine(
            op = DeltaOp.DELETE,
            id = "gone-1",
            type = "Steps",
            sourcePackage = "com.xiaomi.wearable",
            deletedAt = "2026-09-15T01:59:00Z",
        )
        val built = NdjsonCodec.buildDelta(
            header = deltaHeader(),
            upserts = listOf(TestRecords.steps("s3", 431)),
            tombstones = listOf(tombstone),
        )
        val parsed = NdjsonReader.parse(built.fileText)

        assertEquals(ExportKind.DELTA, parsed.header.kind)
        assertEquals("tok-1", parsed.header.changeTokenBefore)
        assertEquals("tok-2", parsed.header.changeTokenAfter)
        assertEquals(1, parsed.records.size)
        assertEquals(2, parsed.deltas.size)
        assertEquals(1, built.manifest.upserts)
        assertEquals(1, built.manifest.deletions)
        assertEquals(DeltaOp.DELETE, parsed.deltas[1].op)
        assertEquals("gone-1", parsed.deltas[1].id)
        assertTrue(NdjsonReader.verifyPayloadChecksum(built.fileText))
    }

    @Test
    fun `record lines do not inline the record inside a delta wrapper`() {
        val built = NdjsonCodec.buildDelta(deltaHeader(), listOf(TestRecords.steps("s3", 431)), emptyList())
        val recordLine = built.fileText.split('\n')[1]
        assertFalse(recordLine.contains("\"record\""))
        assertTrue(recordLine.contains("\"type\":\"Steps\""))
    }
}
