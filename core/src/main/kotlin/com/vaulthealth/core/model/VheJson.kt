package com.vaulthealth.core.model

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration used for every persisted artifact. Changing this changes
 * the on-disk format, so it is versioned through [NdjsonSchema].
 */
object VheJson {
    val codec: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        ignoreUnknownKeys = true
    }
}

object NdjsonSchema {
    const val NAME = "vault-health-exporter/ndjson"
    const val VERSION = 1
}
