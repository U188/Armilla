package io.github.mangi.eta.agent.voice.doubao

import org.junit.Assert.*
import org.junit.Test

class DoubaoDiagnosticsTest {
    @Test fun redactsCredentialsUrlsAndLongTokensButKeepsUsefulReason() {
        val result = DoubaoDiagnostics.sanitize("permission denied for secret-value https://example.com/audio?token=abc", listOf("secret-value"))
        assertTrue(result.contains("permission denied"))
        assertFalse(result.contains("secret-value"))
        assertFalse(result.contains("example.com"))
        assertFalse(DoubaoDiagnostics.sanitize("api_key=abc123; denied").contains("abc123"))
        assertFalse(DoubaoDiagnostics.sanitize("transcript=private words; denied").contains("private words"))
    }
    @Test fun outputIsBoundedAndSingleLine() {
        val result = DoubaoDiagnostics.sanitize("拒绝\n".repeat(2000))
        assertTrue(result.length <= 500)
        assertFalse(result.contains('\n'))
    }
}
