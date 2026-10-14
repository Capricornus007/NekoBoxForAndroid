package io.nekohasekai.sagernet.ktx

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun hidesCredentialsInUris() {
        assertFalse(redactDiagnostic("failure vless://my-uuid@host:443?token=secret").contains("my-uuid"))
    }
    @Test fun hidesQuotedAndWhitespaceDelimitedSecrets() {
        val result = redactDiagnostic("\"password\": \"secret phrase\", Authorization=Bearer abcdef\nprivate_key=key-value")
        listOf("secret phrase", "abcdef", "key-value").forEach { assertFalse(result.contains(it)) }
    }
    @Test fun hidesPemBlocksButKeepsUsefulErrors() {
        val result = redactDiagnostic("timeout\n-----BEGIN PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----")
        assertTrue(result.contains("timeout")); assertFalse(result.contains("private-data"))
    }
}
