package com.vaulthealth.core.dedup

import com.vaulthealth.core.model.HealthRecord

data class DedupResult(
    val records: List<HealthRecord>,
    val duplicatesRemoved: Int,
)

/**
 * Removes records that share a canonical key (type + source package + stable id).
 * Input order is preserved and the first occurrence wins, so results are deterministic
 * regardless of how the caller paginated the source.
 */
object Dedup {
    fun dedupe(records: List<HealthRecord>): DedupResult {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<HealthRecord>(records.size)
        var removed = 0
        for (record in records) {
            if (seen.add(record.canonicalKey)) {
                out.add(record)
            } else {
                removed++
            }
        }
        return DedupResult(out, removed)
    }

    /** Merges an existing immutable export with a newer delta: upserts replace, tombstones remove. */
    fun applyDelta(
        existing: List<HealthRecord>,
        upserts: List<HealthRecord>,
        tombstones: List<com.vaulthealth.core.ndjson.DeltaLine>,
    ): DedupResult {
        val byKey = LinkedHashMap<String, HealthRecord>()
        existing.forEach { byKey[it.canonicalKey] = it }
        var changed = 0
        upserts.forEach { upsert ->
            byKey[upsert.canonicalKey] = upsert
            changed++
        }
        // A deletion change only carries the stable Health Connect record id.
        tombstones.forEach { tombstone ->
            val id = tombstone.id ?: return@forEach
            val removed = byKey.entries.removeIf { it.value.id == id }
            if (removed) changed++
        }
        return DedupResult(byKey.values.toList(), changed)
    }
}
