package com.vaulthealth.core.token

enum class TokenIssue {
    NONE,
    MISSING,
    EXPIRED,
    REJECTED,
    UNKNOWN,
}

enum class TokenErrorKind {
    NONE,
    EXPIRED,
    INVALID,
    OTHER,
}

sealed interface ExportPlan {
    /** Continue incrementally from a persisted token. */
    data class Incremental(val token: String) : ExportPlan

    /** No usable token: the user must run a fresh snapshot. Never guess. */
    data class Resnapshot(val issue: TokenIssue) : ExportPlan
}

/**
 * Decides whether an incremental export can proceed. This only encodes policy; the caller owns
 * persistence and the actual Health Connect call.
 */
object TokenPolicy {
    fun plan(persistedToken: String?, lastError: TokenErrorKind?): ExportPlan {
        val issue = issueFor(lastError)
        return when {
            issue == TokenIssue.EXPIRED || issue == TokenIssue.REJECTED || issue == TokenIssue.UNKNOWN ->
                ExportPlan.Resnapshot(issue)

            persistedToken.isNullOrBlank() -> ExportPlan.Resnapshot(TokenIssue.MISSING)
            else -> ExportPlan.Incremental(persistedToken)
        }
    }

    fun issueFor(lastError: TokenErrorKind?): TokenIssue = when (lastError) {
        null, TokenErrorKind.NONE -> TokenIssue.NONE
        TokenErrorKind.EXPIRED -> TokenIssue.EXPIRED
        TokenErrorKind.INVALID -> TokenIssue.REJECTED
        TokenErrorKind.OTHER -> TokenIssue.UNKNOWN
    }

    /** Maps a Health Connect error message to a coarse error kind. */
    fun classify(message: String?): TokenErrorKind {
        if (message.isNullOrBlank()) return TokenErrorKind.NONE
        val lower = message.lowercase()
        return when {
            "expired" in lower -> TokenErrorKind.EXPIRED
            "invalid" in lower || "malformed" in lower || "token" in lower -> TokenErrorKind.INVALID
            else -> TokenErrorKind.OTHER
        }
    }

    /** Human-readable remediation shown in the UI. */
    fun promptFor(issue: TokenIssue): String? = when (issue) {
        TokenIssue.NONE -> null
        TokenIssue.MISSING -> "No change token stored yet. Run a historical snapshot first."
        TokenIssue.EXPIRED -> "The change token expired. Run a fresh historical snapshot from the last known good date."
        TokenIssue.REJECTED -> "Health Connect rejected the stored change token. Run a fresh historical snapshot."
        TokenIssue.UNKNOWN -> "Could not continue from the stored change token. Run a fresh historical snapshot."
    }
}
