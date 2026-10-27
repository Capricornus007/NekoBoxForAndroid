package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun removesAnsiColorsFromOldLogsAndExports() {
        val line = "\u001b[37mDEBUG\u001b[0m[0010] [\u001b[38;5;84m42\u001b[0m 1ms] router: ready"
        val plain = "DEBUG[0010] [42 1ms] router: ready"
        assertEquals(plain, stripAnsiColors(line))
        assertEquals(plain, redactDiagnostic(line))
        assertFalse(redactDiagnostic("\u001b[31mpassword\u001b[0m=fixture-secret").contains("fixture-secret"))
    }

    @Test fun previewKeepsValidJsonAndHidesNestedSecrets() {
        val source = """{"outbounds":[{"type":"http","password":"secret phrase","tls":{"private_key":["private-fixture"]},"transport":{"headers":{"Example-Api-Key":"header-fixture"}}}],"url":"https://example.test/account-fixture"}"""
        val preview = redactConfigJson(source)
        val parsed = JsonParser.parseString(preview)
        assertEquals("http", parsed.asJsonObject["outbounds"].asJsonArray[0].asJsonObject["type"].asString)
        for (secret in listOf("secret phrase", "private-fixture", "header-fixture", "account-fixture")) assertFalse(preview.contains(secret))
    }

    @Test fun hidesEscapedHeaderValues() {
        val result = redactDiagnostic("""{"customHeaders":"Example-Key: a\"quoted-secret","password":"escaped\\secret"}""")
        assertFalse(result.contains("quoted-secret"))
        assertFalse(result.contains("escaped"))
    }

    @Test fun hidesCredentialsInUris() {
        assertFalse(redactDiagnostic("failure vless://my-uuid@host:443?token=secret").contains("my-uuid"))
    }

    @Test fun hidesQuotedAndWhitespaceDelimitedSecrets() {
        val result = redactDiagnostic("\"password\": \"secret phrase\", Authorization=Bearer abcdef\nprivate_key=key-value")
        listOf("secret phrase", "abcdef", "key-value").forEach { assertFalse(result.contains(it)) }
    }

    @Test fun hidesPemBlocksButKeepsUsefulErrors() {
        val result = redactDiagnostic("timeout\nprivate_key=-----BEGIN PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----")
        assertTrue(result.contains("timeout"))
        assertFalse(result.contains("private-data"))
    }

    @org.junit.Test fun hidesAwg31HeaderProtectionKeysInLogsAndPreviews() {
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        for (name in listOf("HeaderProtectionKey", "header_protection_key")) {
            org.junit.Assert.assertFalse(redactDiagnostic("$name=$key").contains(key))
            org.junit.Assert.assertFalse(redactConfigJson("{\"$name\":\"$key\"}").contains(key))
        }
    }
}
