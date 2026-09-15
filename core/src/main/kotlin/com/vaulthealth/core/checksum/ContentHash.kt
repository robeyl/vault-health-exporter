package com.vaulthealth.core.checksum

import com.vaulthealth.core.model.HealthRecord

/**
 * Stable identity of an export's *content*, independent of headers, timestamps and filenames.
 *
 * Two runs that would produce the same records hash to the same value, which is how the app
 * refuses to write the same data twice.
 */
object ContentHash {
    fun ofRecords(records: List<HealthRecord>): String {
        val builder = StringBuilder()
        records.sortedBy { it.canonicalKey }.forEach { record ->
            builder.append(record.canonicalKey).append('|')
            builder.append(record.startTime).append('|')
            builder.append(record.endTime).append('|')
            builder.append(record.values.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
            builder.append('|')
            builder.append(record.samples.joinToString(",") { "${it.t}=${it.value}" })
            builder.append('|')
            builder.append(record.stages.joinToString(",") { "${it.stage}:${it.start}:${it.end}" })
            builder.append('|')
            builder.append(
                record.route?.let { route ->
                    route.state.name + route.points.joinToString(",") { "${it.t},${it.lat},${it.lon},${it.alt}" }
                } ?: "",
            )
            builder.append('\n')
        }
        return Sha256.hex(builder.toString())
    }

    fun ofTombstones(ids: List<String>): String = Sha256.hex(ids.sorted().joinToString("\n"))

    fun ofDelta(upserts: List<HealthRecord>, deletedIds: List<String>): String =
        Sha256.hex(ofRecords(upserts) + "|" + ofTombstones(deletedIds))
}
