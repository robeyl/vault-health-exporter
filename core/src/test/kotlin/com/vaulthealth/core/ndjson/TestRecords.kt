package com.vaulthealth.core.ndjson

import com.vaulthealth.core.model.HealthRecord
import com.vaulthealth.core.model.RecordType
import com.vaulthealth.core.model.Route
import com.vaulthealth.core.model.Sample
import kotlinx.serialization.json.JsonPrimitive

object TestRecords {
    fun steps(id: String, count: Int, pkg: String = "com.google.android.apps.healthdata") = HealthRecord(
        id = id,
        type = RecordType.STEPS,
        sourcePackage = pkg,
        startTime = "2026-09-14T00:05:00Z",
        endTime = "2026-09-14T00:30:00Z",
        zoneOffset = "+12:00",
        values = mapOf("count" to JsonPrimitive(count)),
    )

    fun heartRate(id: String, bpms: List<Int>) = HealthRecord(
        id = id,
        type = RecordType.HEART_RATE,
        sourcePackage = "com.xiaomi.wearable",
        startTime = "2026-09-14T00:00:00Z",
        endTime = "2026-09-14T00:10:00Z",
        samples = bpms.mapIndexed { i, bpm ->
            Sample("2026-09-14T00:0$i:00Z", JsonPrimitive(bpm))
        },
    )

    fun exercise(id: String, route: Route?) = HealthRecord(
        id = id,
        type = RecordType.EXERCISE,
        sourcePackage = "com.xiaomi.wearable",
        startTime = "2026-09-14T06:30:00Z",
        endTime = "2026-09-14T07:15:00Z",
        values = mapOf("exerciseType" to JsonPrimitive("RUNNING")),
        route = route,
    )
}
