package com.vaulthealth.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenPolicyTest {
    @Test
    fun `no token forces a snapshot`() {
        val plan = TokenPolicy.plan(null, TokenErrorKind.NONE)
        assertTrue(plan is ExportPlan.Resnapshot)
        assertEquals(TokenIssue.MISSING, (plan as ExportPlan.Resnapshot).issue)
    }

    @Test
    fun `blank token forces a snapshot`() {
        val plan = TokenPolicy.plan("   ", null)
        assertTrue(plan is ExportPlan.Resnapshot)
    }

    @Test
    fun `valid token proceeds incrementally`() {
        val plan = TokenPolicy.plan("tok-123", TokenErrorKind.NONE)
        assertEquals(ExportPlan.Incremental("tok-123"), plan)
    }

    @Test
    fun `expired token forces a fresh snapshot and is never guessed around`() {
        val plan = TokenPolicy.plan("tok-123", TokenErrorKind.EXPIRED)
        assertEquals(ExportPlan.Resnapshot(TokenIssue.EXPIRED), plan)
    }

    @Test
    fun `invalid token is reported as rejected`() {
        assertEquals(ExportPlan.Resnapshot(TokenIssue.REJECTED), TokenPolicy.plan("tok", TokenErrorKind.INVALID))
    }

    @Test
    fun `classifies health connect errors`() {
        assertEquals(TokenErrorKind.EXPIRED, TokenPolicy.classify("Change token expired"))
        assertEquals(TokenErrorKind.INVALID, TokenPolicy.classify("invalid token supplied"))
        assertEquals(TokenErrorKind.INVALID, TokenPolicy.classify("malformed token"))
        assertEquals(TokenErrorKind.NONE, TokenPolicy.classify(null))
        assertEquals(TokenErrorKind.OTHER, TokenPolicy.classify("disk on fire"))
    }

    @Test
    fun `every non-clean issue has a remediation prompt`() {
        TokenIssue.entries.filter { it != TokenIssue.NONE }.forEach {
            assertTrue(TokenPolicy.promptFor(it)?.isNotBlank() == true, "missing prompt for $it")
        }
    }
}
