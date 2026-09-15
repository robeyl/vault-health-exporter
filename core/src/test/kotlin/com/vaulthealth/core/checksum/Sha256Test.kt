package com.vaulthealth.core.checksum

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Sha256Test {
    @Test
    fun `matches known vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc"),
        )
    }

    @Test
    fun `checksum line is sha256sum compatible`() {
        val hash = Sha256.hex("abc")
        val line = Sha256.checksumLine(hash, "health-connect-2026-04-16_to_2026-09-14.ndjson")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  health-connect-2026-04-16_to_2026-09-14.ndjson\n",
            line,
        )
        val parsed = Sha256.parseChecksumLine(line)!!
        assertEquals(hash, parsed.hash)
        assertEquals("health-connect-2026-04-16_to_2026-09-14.ndjson", parsed.fileName)
    }

    @Test
    fun `parses binary marker form`() {
        val parsed = Sha256.parseChecksumLine(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad *file.ndjson",
        )!!
        assertEquals("file.ndjson", parsed.fileName)
    }

    @Test
    fun `rejects malformed lines`() {
        assertNull(Sha256.parseChecksumLine("not a checksum"))
        assertNull(Sha256.parseChecksumLine("abcd  file"))
        assertNull(Sha256.parseChecksumLine(""))
    }
}
