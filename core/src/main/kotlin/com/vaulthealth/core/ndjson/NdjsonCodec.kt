package com.vaulthealth.core.ndjson

import com.vaulthealth.core.checksum.Sha256
import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.VheJson
import com.vaulthealth.core.model.NdjsonSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val OP_FIELD = "op"

/** The bytes that will be written, plus the checksums that describe them. */
data class BuiltExport(
    /** Header + records/deltas, newline terminated. This is what the manifest checksum covers. */
    val payload: String,
    val manifest: ExportManifest,
    /** Complete file: payload + manifest line. This is what the sibling `.sha256` covers. */
    val fileText: String,
    val fileSha256: String,
)

object NdjsonCodec {
    /** Checksum scope for [ExportManifest.checksum] — the manifest cannot hash itself. */
    const val SCOPE_PAYLOAD = "payload_excluding_manifest"

    fun encodeHeader(header: ExportHeader): String =
        VheJson.codec.encodeToString(ExportHeader.serializer(), header)

    fun encodeRecord(record: HealthRecord): String =
        VheJson.codec.encodeToString(HealthRecord.serializer(), record)

    fun encodeDelta(line: DeltaLine): String =
        VheJson.codec.encodeToString(DeltaLine.serializer(), line)

    fun encodeManifest(manifest: ExportManifest): String =
        VheJson.codec.encodeToString(ExportManifest.serializer(), manifest)

    /** Builds an immutable snapshot file from already-normalised records. */
    fun buildSnapshot(header: ExportHeader, records: List<HealthRecord>): BuiltExport {
        require(header.kind == ExportKind.SNAPSHOT) { "expected SNAPSHOT header, got ${header.kind}" }
        val payload = buildString {
            append(encodeHeader(header)).append('\n')
            records.forEach { append(encodeRecord(it)).append('\n') }
        }
        val manifest = ExportManifest(
            recordTotal = records.size,
            recordCounts = ManifestBuilder.recordCounts(records),
            issueCounts = ManifestBuilder.issueCounts(records),
            exportedAt = header.exportedAt,
            requestedRange = header.requestedRange,
            changeTokenBefore = header.changeTokenBefore,
            changeTokenAfter = header.changeTokenAfter,
            checksum = Checksum(scope = SCOPE_PAYLOAD, value = Sha256.hex(payload)),
        )
        return finalize(header, payload, manifest)
    }

    /** Builds an immutable delta file: upserts + deletion tombstones. */
    fun buildDelta(
        header: ExportHeader,
        upserts: List<HealthRecord>,
        tombstones: List<DeltaLine>,
    ): BuiltExport {
        require(header.kind == ExportKind.DELTA) { "expected DELTA header, got ${header.kind}" }
        require(tombstones.all { it.op == DeltaOp.DELETE }) { "tombstones must be delete lines" }
        val payload = buildString {
            append(encodeHeader(header)).append('\n')
            upserts.forEach { append(encodeRecord(it)).append('\n') }
            tombstones.forEach { append(encodeDelta(it)).append('\n') }
        }
        val manifest = ExportManifest(
            recordTotal = upserts.size + tombstones.size,
            recordCounts = ManifestBuilder.recordCounts(upserts),
            issueCounts = ManifestBuilder.issueCounts(upserts),
            exportedAt = header.exportedAt,
            changeTokenBefore = header.changeTokenBefore,
            changeTokenAfter = header.changeTokenAfter,
            upserts = upserts.size,
            deletions = tombstones.size,
            checksum = Checksum(scope = SCOPE_PAYLOAD, value = Sha256.hex(payload)),
        )
        return finalize(header, payload, manifest)
    }

    private fun finalize(header: ExportHeader, payload: String, manifest: ExportManifest): BuiltExport {
        val fileText = payload + encodeManifest(manifest) + "\n"
        return BuiltExport(
            payload = payload,
            manifest = manifest,
            fileText = fileText,
            fileSha256 = Sha256.hex(fileText),
        )
    }
}

data class ParsedExport(
    val header: ExportHeader,
    val records: List<HealthRecord>,
    val deltas: List<DeltaLine>,
    val manifest: ExportManifest,
)

object NdjsonReader {
    /** Parses a previously written export. Throws on malformed input. */
    fun parse(fileText: String): ParsedExport {
        val lines = fileText.split('\n').filter { it.isNotBlank() }
        require(lines.size >= 2) { "export must contain at least a header and a manifest" }
        val header = VheJson.codec.decodeFromString(ExportHeader.serializer(), lines.first())
        val manifest = VheJson.codec.decodeFromString(ExportManifest.serializer(), lines.last())
        val body = lines.subList(1, lines.size - 1)
        return when (header.kind) {
            ExportKind.SNAPSHOT -> ParsedExport(
                header = header,
                records = body.map { VheJson.codec.decodeFromString(HealthRecord.serializer(), it) },
                deltas = emptyList(),
                manifest = manifest,
            )

            ExportKind.DELTA -> {
                // Upserts are written as bare native records (no `op`), tombstones carry `op`.
                // This keeps the snapshot and delta record lines byte-compatible.
                val deltas = body.map { line ->
                    val obj = VheJson.codec.parseToJsonElement(line) as? JsonObject
                    if (obj != null && obj.containsKey(OP_FIELD)) {
                        VheJson.codec.decodeFromString(DeltaLine.serializer(), line)
                    } else {
                        DeltaLine(
                            op = DeltaOp.UPSERT,
                            record = VheJson.codec.decodeFromString(HealthRecord.serializer(), line),
                        )
                    }
                }
                ParsedExport(
                    header = header,
                    records = deltas.mapNotNull { it.record },
                    deltas = deltas,
                    manifest = manifest,
                )
            }
        }
    }

    /** Recomputes the payload checksum and compares it to the manifest. */
    fun verifyPayloadChecksum(fileText: String): Boolean {
        val lines = fileText.split('\n').filter { it.isNotBlank() }
        if (lines.size < 2) return false
        val manifest = VheJson.codec.decodeFromString(ExportManifest.serializer(), lines.last())
        val payload = lines.dropLast(1).joinToString(separator = "\n", postfix = "\n")
        return Sha256.hex(payload) == manifest.checksum.value
    }

    fun schemaNameOf(fileText: String): String? {
        val first = fileText.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val obj = VheJson.codec.parseToJsonElement(first) as? JsonObject ?: return null
        return obj["schema"]?.jsonPrimitive?.content
    }

    fun isKnownSchema(fileText: String): Boolean {
        val first = fileText.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val obj = VheJson.codec.parseToJsonElement(first) as? JsonObject ?: return false
        return obj["schema"]?.jsonPrimitive?.content == NdjsonSchema.NAME
    }
}
