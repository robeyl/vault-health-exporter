package com.vaulthealth.core.checksum

import java.security.MessageDigest

/** SHA-256 helpers. Everything here is deterministic and side-effect free. */
object Sha256 {
    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    /** `sha256sum` compatible line: `<hex>  <filename>` (two spaces). */
    fun checksumLine(hash: String, fileName: String): String = "$hash  $fileName\n"

    /** Parses a `sha256sum` line. Returns null when the line is not well formed. */
    fun parseChecksumLine(line: String): ChecksumLine? {
        val trimmed = line.trim('\n', '\r', ' ')
        if (trimmed.isEmpty()) return null
        val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(trimmed) ?: return null
        val hash = match.groupValues[1].lowercase()
        val name = match.groupValues[2].trim()
        if (name.isEmpty()) return null
        return ChecksumLine(hash, name)
    }
}

data class ChecksumLine(val hash: String, val fileName: String)
